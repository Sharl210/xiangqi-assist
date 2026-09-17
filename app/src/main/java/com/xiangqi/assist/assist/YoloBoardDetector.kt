package com.xiangqi.assist.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv5 棋子检测器（Android 侧，TFLite）。
 *
 * 模型：优先使用 `assets/yolov5m_xq_fp32.tflite`（YOLOv5 medium，FP32 激活，输入 NHWC float32，
 * 输出 [1,25200,20] float32）；如果设备的TFLite运行时无法分配该模型，才回退到
 * `assets/yolov5n_xq_fp16.tflite`。输入为 RGB（不是 BGR，BGR 会把红黑阵营互换）。
 * 纯推理封装：解码与棋盘映射在纯 JVM 的 YoloPostprocessor / DetectionBoardMapper 中，
 * 便于单元测试。
 *
 * 性能要点：
 * - 支持[cropHint]裁剪推理：已知棋盘位置时只取棋盘外扩区域，单次 drawBitmap 完成缩放；
 * - 像素 -> float 在 JVM 内一次性写入复用数组，再整块 put 进输入缓冲（避免百万次 putFloat JNI）；
 * - 解码直接消费解释器输出（无 2MB flat 拷贝）。
 */
class YoloBoardDetector(context: Context) {

    private data class RuntimeModel(val interpreter: Interpreter, val file: String)
    private val runtimeModel = createRuntimeModel(context)
    private val inferenceGate = InferenceLifecycleGate()
    @Volatile private var closed = false
    private val interpreter: Interpreter = runtimeModel.interpreter
    /** 实际选用的模型，日志/状态诊断用。 */
    val modelFile: String = runtimeModel.file
    private val input: ByteBuffer
    private val inputFloat: FloatArray
    private val inputFloatBuffer: FloatBuffer
    private val output: Array<Array<FloatArray>>
    private val workPx = IntArray(YoloPostprocessor.MODEL_INPUT * YoloPostprocessor.MODEL_INPUT)
    private val dstRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var square: Bitmap? = null
    private var cropBitmap: Bitmap? = null

    init {
        input = ByteBuffer.allocateDirect(4 * 3 * YoloPostprocessor.MODEL_INPUT * YoloPostprocessor.MODEL_INPUT)
            .order(ByteOrder.nativeOrder())
        inputFloatBuffer = input.asFloatBuffer()
        inputFloat = FloatArray(3 * YoloPostprocessor.MODEL_INPUT * YoloPostprocessor.MODEL_INPUT)
        output = Array(1) { Array(YoloPostprocessor.ANCHORS) { FloatArray(YoloPostprocessor.DIMS) } }
    }

    /**
     * 选择并验证模型：主模型和回退模型都必须是可分配的 NHWC/25200×20 张量；
     * 不接受“文件存在但输入输出布局不匹配”的模型。
     */
    private fun createRuntimeModel(context: Context): RuntimeModel {
        val cores = Runtime.getRuntime().availableProcessors()
        val options = Interpreter.Options().apply {
            numThreads = EngineTuningPolicy.recognitionThreads(cores)
        }
        var lastError: Throwable? = null
        for (file in MODEL_FILES) {
            var candidate: Interpreter? = null
            try {
                candidate = Interpreter(loadModel(context, file), options)
                // 提前分配真实张量：把不兼容的模型/算子在启动阶段淘汰，
                // 不让第一次实战推理才暴露 native prepare 错误。
                candidate.allocateTensors()
                val inputShape = candidate.getInputTensor(0).shape()
                val outputShape = candidate.getOutputTensor(0).shape()
                require(inputShape.contentEquals(intArrayOf(1, 640, 640, 3))) {
                    "$file input shape ${inputShape.contentToString()} is not NHWC 640"
                }
                require(outputShape.contentEquals(intArrayOf(1, 25200, 20))) {
                    "$file output shape ${outputShape.contentToString()} is not YOLO XQ"
                }
                Log.i(TAG, "yolo model ready: $file input=${inputShape.contentToString()} output=${outputShape.contentToString()}")
                return RuntimeModel(candidate, file)
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "yolo model unavailable: $file", t)
                runCatching { candidate?.close() }
            }
        }
        throw IllegalStateException("没有可用的象棋识别模型", lastError)
    }

    /**
     * 检测一帧并映射为 9×10 棋盘。无足够棋子/映射失败返回 null。
     *
     * @param cropHint 裁剪区域（帧像素坐标 x0,y0,x1,y1）；null 表示整帧
     * @param anchor   上一帧成功定位的网格锚点（board 框漏检时沿用，避免整帧作废）
     * @param exclude  需要排除的帧内矩形（x0,y0,x1,y1），例如悬浮窗面板自身：
     *                 落在该矩形内的检测框会被丢弃，避免把面板上的棋子图样当成真实棋局
     */
    @Synchronized
    fun detect(
        frame: Frame,
        cropHint: DoubleArray? = null,
        anchor: DetectionBoardMapper.AnchorHint? = null,
        exclude: IntArray? = null,
    ): DetectionBoardMapper.MappedBoard? = inferenceGate.runIfOpen {
        detectInternal(frame, cropHint, anchor, exclude)
    }

    /** 只有生命周期闸门持有一个在途计数时才进入这里。 */
    private fun detectInternal(
        frame: Frame,
        cropHint: DoubleArray?,
        anchor: DetectionBoardMapper.AnchorHint?,
        exclude: IntArray?,
    ): DetectionBoardMapper.MappedBoard? {
        val cx0: Int; val cy0: Int; val cw: Int; val ch: Int
        if (cropHint != null && cropHint[2] - cropHint[0] >= 64 && cropHint[3] - cropHint[1] >= 64) {
            cx0 = max(0, cropHint[0].toInt()); cy0 = max(0, cropHint[1].toInt())
            cw = min(frame.width, cropHint[2].toInt()) - cx0
            ch = min(frame.height, cropHint[3].toInt()) - cy0
        } else {
            cx0 = 0; cy0 = 0; cw = frame.width; ch = frame.height
        }
        val lb = renderInput(frame, cx0, cy0, cw, ch)
        val t0 = System.currentTimeMillis()
        input.rewind()
        interpreter.run(input, output)
        val inferMs = System.currentTimeMillis() - t0
        // 模型本身负责类别判定；锚点只用于几何兜底，不能放宽类别阈值或替换字形。
        // 类别边际不足时直接丢弃该框，交给下一次稳定窗口重新识别，禁止上下文猜测。
        val confThreshold = 0.60
        val dets = YoloPostprocessor.decode(
            output[0], lb, cw, ch,
            confThreshold = confThreshold,
            aspectMin = 0.55,
            aspectMax = 1.50,
            sizeMinFactor = 0.45,
            sizeMaxFactor = 1.80,
            classMarginMin = 0.18,
        )
        // 裁剪坐标 -> 帧坐标
        val shifted0 = if (cx0 == 0 && cy0 == 0) dets
        else dets.map { YoloDetection(it.labelId, it.score, it.cx + cx0, it.cy + cy0, it.w, it.h) }
        // 排除区（悬浮窗面板）：面板上的棋子图样不属于真实棋局，必须剔除，
        // 否则会凭空多出/错位若干子，让识别结果不可用
        val shifted = if (exclude == null) shifted0 else shifted0.filterNot { d ->
            val x0 = exclude[0].toDouble(); val y0 = exclude[1].toDouble()
            val x1 = exclude[2].toDouble(); val y1 = exclude[3].toDouble()
            d.cx >= x0 && d.cx <= x1 && d.cy >= y0 && d.cy <= y1
        }
        val mapped = DetectionBoardMapper.map(
            shifted, frame.width, frame.height, anchor = anchor,
            preferAnchor = cropHint != null && anchor != null,
        )
        lastInferMs = inferMs
        lastPieceDets = shifted.count { !it.isBoard }
        lastRawDets = shifted0.size
        Log.d(TAG, "yolo: infer=${inferMs}ms crop=${cw}x$ch dets=${dets.size} " +
            "pieces=${mapped?.pieceCount ?: 0} avg=${mapped?.avgScore?.let { "%.2f".format(it) }} " +
            "dropped=${mapped?.dropped ?: 0} anchor=${mapped?.anchorSource ?: "-"}")
        return mapped
    }

    /** 最近一次推理耗时（毫秒），诊断用 */
    @Volatile var lastInferMs: Long = 0
        private set

    /** 最近一次推理检出的棋子数（过滤后），诊断用 */
    @Volatile var lastPieceDets: Int = 0
        private set

    /** 最近一次推理的原始检测框总数，诊断用 */
    @Volatile var lastRawDets: Int = 0
        private set

    @Synchronized
    fun close() {
        if (closed) return
        inferenceGate.close {
            closed = true
            interpreter.close()
            square?.recycle()
            square = null
            cropBitmap?.recycle()
            cropBitmap = null
        }
    }

    /** 帧 -> 640×640 letterbox RGB float 输入，返回 letterbox 参数（相对裁剪区域） */
    private fun renderInput(frame: Frame, cx0: Int, cy0: Int, cw: Int, chh: Int): YoloPostprocessor.Letterbox {
        val lb = YoloPostprocessor.Letterbox.forFrame(cw, chh)
        val nw = (cw * lb.scale).roundToInt().coerceAtLeast(1)
        val nh = (chh * lb.scale).roundToInt().coerceAtLeast(1)
        // 直接从像素数组取裁剪区（一次数组拷贝，尺寸稳定时复用位图以减少 GC 压力）
        var crop = cropBitmap
        if (crop == null || crop.width != cw || crop.height != chh) {
            crop?.recycle()
            crop = Bitmap.createBitmap(cw, chh, Bitmap.Config.ARGB_8888)
            cropBitmap = crop
        }
        crop.setPixels(frame.argb, cy0 * frame.width + cx0, frame.width, 0, 0, cw, chh)
        val sq = square ?: Bitmap.createBitmap(
            YoloPostprocessor.MODEL_INPUT, YoloPostprocessor.MODEL_INPUT, Bitmap.Config.ARGB_8888)
            .also { square = it }
        val canvas = Canvas(sq)
        canvas.drawColor(Color.rgb(114, 114, 114))
        dstRect.set(lb.padX.toFloat(), lb.padY.toFloat(),
            lb.padX.toFloat() + nw, lb.padY.toFloat() + nh)
        canvas.drawBitmap(crop, null, dstRect, paint)
        sq.getPixels(workPx, 0, YoloPostprocessor.MODEL_INPUT, 0, 0,
            YoloPostprocessor.MODEL_INPUT, YoloPostprocessor.MODEL_INPUT)
        // ARGB -> RGB float（JVM 内一次循环），再整块写入输入缓冲
        val f = inputFloat
        var k = 0
        for (p in workPx) {
            f[k++] = ((p shr 16) and 0xFF) / 255f
            f[k++] = ((p shr 8) and 0xFF) / 255f
            f[k++] = (p and 0xFF) / 255f
        }
        input.rewind()
        inputFloatBuffer.rewind()
        inputFloatBuffer.put(f)
        return lb
    }

    private fun loadModel(context: Context, file: String): ByteBuffer {
        context.assets.open(file).use { ins ->
            val buf = ByteBuffer.allocateDirect(ins.available()).order(ByteOrder.nativeOrder())
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                buf.put(chunk, 0, n)
            }
            buf.rewind()
            return buf
        }
    }

    companion object {
        private const val TAG = "AssistService"
        /** 主模型：VinXiangQi v1.4.0 中模型转换得到的 FP32 激活 TFLite。 */
        const val MODEL_FILE = "yolov5m_xq_fp32.tflite"
        /** 设备无法分配主模型时的兼容回退模型。 */
        const val FALLBACK_MODEL_FILE = "yolov5n_xq_fp16.tflite"
        private val MODEL_FILES = listOf(MODEL_FILE, FALLBACK_MODEL_FILE)
    }
}
