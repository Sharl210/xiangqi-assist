package com.xiangqi.assist.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
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
 * 模型：按用户记忆的档位尝试大型→中型→Lite；每个候选都必须通过资产读取、Interpreter 创建、张量分配、
 * 输入 NHWC float32 `[1,640,640,3]`、输出 `[1,25200,20]` 和一次受控推理。当前大型档是由 XQ Medium 与 universal/旋转鲁棒模型组成的逐锚点集成模型，
 * 文件约 54.26 MiB，保留相同后处理契约；中型和 Lite 分别使用现有 `yolov5m_xq_fp32.tflite` 与 `yolov5n_xq_fp16.tflite`。
 * 输入为 RGB（不是 BGR，BGR 会把红黑阵营互换）。
 * 纯推理封装：解码与棋盘映射在纯 JVM 的 YoloPostprocessor / DetectionBoardMapper 中，
 * 便于单元测试。
 *
 * 性能要点：
 * - 支持[cropHint]裁剪推理：已知棋盘位置时只取棋盘外扩区域，单次 drawBitmap 完成缩放；
 * - 像素 -> float 在 JVM 内一次性写入复用数组，再整块 put 进输入缓冲（避免百万次 putFloat JNI）；
 * - 解码直接消费解释器输出（无 2MB flat 拷贝）。
 */
class YoloBoardDetector(
    context: Context,
    requestedTier: YoloModelTier = YoloModelTier.LARGE,
) {

    private data class RuntimeModel(
        val interpreter: Interpreter,
        val tier: YoloModelTier,
        val file: String,
        val selectionResult: YoloModelSelectionResult,
    )
    private val runtimeModel = createRuntimeModel(context, requestedTier)
    private val inferenceGate = InferenceLifecycleGate()
    @Volatile private var closed = false
    private val interpreter: Interpreter = runtimeModel.interpreter
    /** 实际使用档位及这次探测的回退信息，供服务和设置页同步显示。 */
    val selectionResult: YoloModelSelectionResult = runtimeModel.selectionResult
    val modelTier: YoloModelTier = runtimeModel.tier
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
     * 按用户选择向下探测模型。每个候选都必须真实创建 Interpreter、分配张量、
     * 核对输入/输出契约并完成一次零输入受控推理；任何一步失败都只淘汰当前候选。
     * 负载、温度和功耗不参与这里的判定。
     */
    private fun createRuntimeModel(context: Context, requestedTier: YoloModelTier): RuntimeModel {
        val cores = Runtime.getRuntime().availableProcessors()
        val options = Interpreter.Options().apply {
            numThreads = EngineTuningPolicy.recognitionThreads(cores)
        }
        val failures = mutableListOf<String>()
        for (tier in requestedTier.fallbackOrder()) {
            var candidate: Interpreter? = null
            try {
                val model = loadModel(context, tier.fileName)
                candidate = Interpreter(model, options)
                // 提前分配真实张量：把不兼容的模型/算子在启动阶段淘汰，
                // 不让第一次实战推理才暴露 native prepare 错误。
                candidate.allocateTensors()
                val inputTensor = candidate.getInputTensor(0)
                val outputTensor = candidate.getOutputTensor(0)
                val inputShape = inputTensor.shape()
                val outputShape = outputTensor.shape()
                require(inputShape.contentEquals(MODEL_INPUT_SHAPE)) {
                    "输入形状 ${inputShape.contentToString()}，需要 ${MODEL_INPUT_SHAPE.contentToString()}"
                }
                require(outputShape.contentEquals(MODEL_OUTPUT_SHAPE)) {
                    "输出形状 ${outputShape.contentToString()}，需要 ${MODEL_OUTPUT_SHAPE.contentToString()}"
                }
                require(inputTensor.dataType() == DataType.FLOAT32) {
                    "输入类型 ${inputTensor.dataType()}，需要 FLOAT32"
                }
                require(outputTensor.dataType() == DataType.FLOAT32) {
                    "输出类型 ${outputTensor.dataType()}，需要 FLOAT32"
                }
                // 受控零输入探测：兼容性包含必要算子是否能真正执行，不能只看 allocateTensors。
                val probeInput = ByteBuffer.allocateDirect(MODEL_INPUT_BYTES)
                    .order(ByteOrder.nativeOrder())
                val probeOutput = Array(1) {
                    Array(YoloPostprocessor.ANCHORS) { FloatArray(YoloPostprocessor.DIMS) }
                }
                candidate.run(probeInput, probeOutput)
                Log.i(TAG, "yolo model ready: tier=${tier.name} file=${tier.fileName} " +
                    "input=${inputShape.contentToString()} output=${outputShape.contentToString()}")
                return RuntimeModel(
                    interpreter = candidate,
                    tier = tier,
                    file = tier.fileName,
                    selectionResult = YoloModelSelectionResult(
                        requested = requestedTier,
                        actual = tier,
                        modelFile = tier.fileName,
                        failureReasons = failures.toList(),
                    ),
                )
            } catch (t: Throwable) {
                val reason = describeCompatibilityFailure(t)
                failures += "${tier.displayName}：$reason"
                Log.w(TAG, "yolo model unavailable: tier=${tier.name} file=${tier.fileName}; $reason", t)
                runCatching { candidate?.close() }
            }
        }
        val selection = YoloModelSelectionResult(
            requested = requestedTier,
            actual = null,
            modelFile = null,
            failureReasons = failures,
            fatalReason = "已按${requestedTier.fallbackOrder().joinToString("→") { it.displayName }}顺序检查，但所有候选均未通过",
        )
        throw YoloModelUnavailableException(selection)
    }

    private fun describeCompatibilityFailure(t: Throwable): String {
        val message = t.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val name = t::class.java.simpleName.ifBlank { "运行时异常" }
        return if (message.isBlank()) name else "$name：${message.take(180)}"
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
        if (cropHint != null &&
            cropHint.size >= 4 &&
            cropHint[2] > cropHint[0] &&
            cropHint[3] > cropHint[1]
        ) {
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
        val confThreshold = 0.45
        val dets = YoloPostprocessor.decode(
            output[0], lb, cw, ch,
            confThreshold = confThreshold,
            aspectMin = 0.50,
            aspectMax = 1.60,
            sizeMinFactor = 0.40,
            sizeMaxFactor = 2.00,
            classMarginMin = 0.05,
        )
        // 裁剪坐标 -> 帧坐标
        val shifted0 = shiftToFrame(dets, cx0, cy0)
        val shifted = excludeDetections(shifted0, exclude)
        var mapped = DetectionBoardMapper.map(
            shifted, frame.width, frame.height, anchor = anchor,
            preferAnchor = cropHint != null && anchor != null,
        )
        var criticalRecoveryUsed = false

        // 皮肤或装饰差异可能让帅/将的置信度短暂跌过普通门槛。
        // 仅在严格结果缺王时，对同一模型输出做一次较宽松的重解码；
        // rescue 结果仍必须包含双王、通过基础结构校验，不能用旧棋面补子。
        if (mapped == null || !hasBothKings(mapped.canonical)) {
            val rescue = YoloPostprocessor.decode(
                output[0], lb, cw, ch,
                confThreshold = CRITICAL_RECOVERY_CONF_THRESHOLD,
                aspectMin = 0.50,
                aspectMax = 1.60,
                sizeMinFactor = 0.40,
                sizeMaxFactor = 2.00,
                classMarginMin = 0.02,
            )
            val rescueMapped = DetectionBoardMapper.map(
                excludeDetections(shiftToFrame(rescue, cx0, cy0), exclude),
                frame.width, frame.height, anchor = anchor,
                preferAnchor = cropHint != null && anchor != null,
            )
            val strictPieceCount = mapped?.pieceCount ?: -1
            if (isSafeRecoveredBoard(rescueMapped) &&
                rescueMapped != null && rescueMapped.pieceCount >= strictPieceCount
            ) {
                mapped = rescueMapped
                criticalRecoveryUsed = true
            }
        }

        lastInferMs = inferMs
        lastPieceDets = shifted.count { !it.isBoard }
        lastRawDets = shifted0.size
        Log.d(TAG, "yolo: infer=${inferMs}ms crop=${cw}x$ch dets=${dets.size} " +
            "pieces=${mapped?.pieceCount ?: 0} avg=${mapped?.avgScore?.let { "%.2f".format(it) }} " +
            "dropped=${mapped?.dropped ?: 0} anchor=${mapped?.anchorSource ?: "-"} " +
            "criticalRecovery=$criticalRecoveryUsed")
        return mapped
    }

    private fun shiftToFrame(dets: List<YoloDetection>, cx0: Int, cy0: Int): List<YoloDetection> =
        if (cx0 == 0 && cy0 == 0) dets else dets.map {
            YoloDetection(it.labelId, it.score, it.cx + cx0, it.cy + cy0, it.w, it.h, it.alternatives)
        }

    private fun excludeDetections(dets: List<YoloDetection>, exclude: IntArray?): List<YoloDetection> {
        if (exclude == null) return dets
        val x0 = exclude[0].toDouble(); val y0 = exclude[1].toDouble()
        val x1 = exclude[2].toDouble(); val y1 = exclude[3].toDouble()
        return dets.filterNot { d -> d.cx >= x0 && d.cx <= x1 && d.cy >= y0 && d.cy <= y1 }
    }

    private fun hasBothKings(board: Array<IntArray>): Boolean {
        var red = 0
        var black = 0
        for (row in board) for (piece in row) {
            if (piece == com.xiangqi.assist.gamelogic.Piece.WSHUAI) red++
            if (piece == com.xiangqi.assist.gamelogic.Piece.BJIANG) black++
        }
        return red == 1 && black == 1
    }

    private fun isSafeRecoveredBoard(mapped: DetectionBoardMapper.MappedBoard?): Boolean {
        if (mapped == null || !hasBothKings(mapped.canonical)) return false
        return AssistBoard.validate(mapped.canonical).isEmpty() &&
            AssistBoard.invalidPiecePlacement(mapped.canonical) == null
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
        /** 兼容性探测要求与生产推理输入/输出保持同一类型契约。 */
        private const val MODEL_INPUT_BYTES = 4 * 3 * YoloPostprocessor.MODEL_INPUT * YoloPostprocessor.MODEL_INPUT
        private val MODEL_INPUT_SHAPE = intArrayOf(1, 640, 640, 3)
        private val MODEL_OUTPUT_SHAPE = intArrayOf(1, 25200, 20)
        /** 关键棋子救援只降低置信度门槛，不改变正常帧的严格门槛。 */
        private const val CRITICAL_RECOVERY_CONF_THRESHOLD = 0.30
    }
}
