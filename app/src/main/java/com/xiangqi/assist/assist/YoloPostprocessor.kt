package com.xiangqi.assist.assist

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv5 输出后处理（纯 JVM，便于单测）：
 * 解码 [1,25200,20] 张量 -> 置信度过滤 -> 类别感知 NMS -> 比例/尺寸一致性过滤。
 *
     * 过滤策略与 VinXiangQi 的 GetBoardFromPrediction 一致并加强：
 * - 棋子框宽高比须合理；
 * - 类别第一名与第二名只要求有最小可辨差距，不能因轻微置信度波动直接丢掉整枚棋子；
 * - 有棋盘框时按棋盘格尺寸做几何过滤；没有棋盘框时才使用较宽的相对尺寸兜底。
 *
 * 识别质量门在映射/结构校验层继续负责拒绝坏盘面；本层不使用上一帧猜类别，也不以
 * “低分”作为把真实棋子静默删除的理由。
 */
object YoloPostprocessor {

    const val MODEL_INPUT = 640
    const val ANCHORS = 25200
    const val DIMS = 20

    /** letterbox 参数：帧 -> 640 输入的缩放与居中留白 */
    data class Letterbox(val scale: Double, val padX: Double, val padY: Double) {
        companion object {
            fun forFrame(frameW: Int, frameH: Int): Letterbox {
                val s = min(MODEL_INPUT.toDouble() / frameW, MODEL_INPUT.toDouble() / frameH)
                val nw = (frameW * s).roundToInt()
                val nh = (frameH * s).roundToInt()
                return Letterbox(s, (MODEL_INPUT - nw) / 2.0, (MODEL_INPUT - nh) / 2.0)
            }
        }
    }

    /**
     * @param output 解释器原始输出 [1,25200,20]（已乘 sigmoid 的解码值），行序即 anchor 序
     * @param confThreshold 棋子/棋盘统一置信度阈值；低置信框仍须通过类别边际、几何和最终盘面结构门
     */
    fun decode(
        output: Array<FloatArray>,
        lb: Letterbox,
        frameW: Int,
        frameH: Int,
        confThreshold: Double = 0.45,
        iouThreshold: Double = 0.45,
        aspectMin: Double = 0.50,
        aspectMax: Double = 1.60,
        sizeMinFactor: Double = 0.40,
        sizeMaxFactor: Double = 2.20,
        /** 类别第一名相对第二名的最小置信度差；仅过滤近乎不可分辨的框。 */
        classMarginMin: Double = 0.05,
    ): List<YoloDetection> {
        data class Raw(
            val labelId: Int,
            val score: Double,
            val x1: Double,
            val y1: Double,
            val x2: Double,
            val y2: Double,
            val alternatives: List<Pair<Int, Double>>,
        )

        val raws = ArrayList<Raw>(256)
        for (i in 0 until ANCHORS) {
            val row = output[i]
            val obj = row[4].toDouble()
            if (obj < confThreshold) continue
            var bestCls = -1
            var bestScore = 0.0
            val classScores = ArrayList<Pair<Int, Double>>(DIMS - 5)
            for (c in 0 until DIMS - 5) {
                val s = obj * row[5 + c].toDouble()
                classScores += c to s
                if (s > bestScore) { bestScore = s; bestCls = c }
            }
            if (bestCls < 0 || bestScore < confThreshold) continue
            val alternatives = classScores
                .sortedByDescending { it.second }
                .take(3)
            val secondScore = classScores
                .asSequence()
                .filter { it.first != bestCls }
                .maxOfOrNull { it.second } ?: 0.0
            if (bestScore - secondScore < classMarginMin) continue
            val cx = row[0].toDouble()
            val cy = row[1].toDouble()
            val w = row[2].toDouble()
            val h = row[3].toDouble()
            raws.add(Raw(
                bestCls, bestScore, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2,
                alternatives,
            ))
        }
        if (raws.isEmpty()) return emptyList()

        // 类别感知 NMS
        raws.sortByDescending { it.score }
        val keep = ArrayList<Raw>()
        for (r in raws) {
            var overlapped = false
            for (k in keep) {
                if (k.labelId != r.labelId) continue
                if (iou(r.x1, r.y1, r.x2, r.y2, k.x1, k.y1, k.x2, k.y2) > iouThreshold) { overlapped = true; break }
            }
            if (!overlapped) keep.add(r)
        }

        // 640 输入域 -> 原始帧域
        fun toFrame(v: Double, pad: Double) = (v - pad) / lb.scale
        val dets = ArrayList<YoloDetection>(keep.size)
        for (r in keep) {
            val x1 = toFrame(r.x1, lb.padX); val y1 = toFrame(r.y1, lb.padY)
            val x2 = toFrame(r.x2, lb.padX); val y2 = toFrame(r.y2, lb.padY)
            dets.add(YoloDetection(r.labelId, r.score,
                (x1 + x2) / 2, (y1 + y2) / 2, (x2 - x1), (y2 - y1),
                r.alternatives))
        }

        // 棋子比例过滤 + 尺寸一致性过滤（board 框不参与）。
        // 有可靠棋盘框时优先按“每格尺寸 + 框内位置”判断，避免一个低置信但真实的
        // 边缘棋子因为全局中位数偏差被静默删除；没有棋盘框时才使用宽松中位数兜底。
        val pieces = dets.filter { !it.isBoard && it.w > 0 && it.h > 0 && aspectOk(it, aspectMin, aspectMax) }
        if (pieces.isEmpty()) return dets.filter { it.isBoard }
        val medianW = median(pieces.map { it.w })
        val medianH = median(pieces.map { it.h })
        val board = dets.filter { it.isBoard }.maxByOrNull { it.score }
        val boardGeometry = board?.let { b ->
            val ratio = b.w / b.h
            if (b.w > 0 && b.h > 0 && ratio in 0.70..1.30 &&
                b.w >= medianW * 7.0 && b.h >= medianH * 8.0) {
                val cellW = b.w / 8.0
                val cellH = b.h / 9.0
                val x0 = b.cx - b.w / 2.0
                val y0 = b.cy - b.h / 2.0
                val x1 = b.cx + b.w / 2.0
                val y1 = b.cy + b.h / 2.0
                doubleArrayOf(x0, y0, x1, y1, cellW, cellH)
            } else null
        }
        fun boardGeometryOk(d: YoloDetection, g: DoubleArray): Boolean {
            val cellW = g[4]
            val cellH = g[5]
            val marginX = cellW * 0.60
            val marginY = cellH * 0.60
            val inBoard = d.cx >= g[0] - marginX && d.cx <= g[2] + marginX &&
                d.cy >= g[1] - marginY && d.cy <= g[3] + marginY
            val sizeOk = d.w in (cellW * 0.25)..(cellW * 2.20) &&
                d.h in (cellH * 0.25)..(cellH * 2.20)
            return inBoard && sizeOk
        }
        val out = ArrayList<YoloDetection>()
        // 没有棋盘框且检测数量很少时，包围盒本身不可靠；保留较严格的
        // 小框下限以挡住按钮/标记误检。检测数量足够时使用调用方给出的宽松下限。
        val fallbackMinFactor = if (pieces.size < 8) maxOf(sizeMinFactor, 0.55) else sizeMinFactor
        for (d in dets) {
            when {
                d.isBoard -> out.add(d)
                !aspectOk(d, aspectMin, aspectMax) -> Unit
                boardGeometry != null && boardGeometryOk(d, boardGeometry) -> out.add(d)
                boardGeometry == null &&
                    d.w >= medianW * fallbackMinFactor && d.w <= medianW * sizeMaxFactor &&
                    d.h >= medianH * fallbackMinFactor && d.h <= medianH * sizeMaxFactor -> out.add(d)
                // 几何明显不可能的检测框才丢弃；不按类别置信度回填或猜测。
            }
        }
        return out
    }

    private fun aspectOk(d: YoloDetection, min: Double, max: Double): Boolean {
        val ratio = d.w / d.h
        return ratio in min..max
    }

    private fun iou(ax1: Double, ay1: Double, ax2: Double, ay2: Double,
                    bx1: Double, by1: Double, bx2: Double, by2: Double): Double {
        val ix1 = max(ax1, bx1); val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)
        val iw = max(0.0, ix2 - ix1); val ih = max(0.0, iy2 - iy1)
        val inter = iw * ih
        if (inter <= 0) return 0.0
        val a = (ax2 - ax1) * (ay2 - ay1)
        val b = (bx2 - bx1) * (by2 - by1)
        return inter / (a + b - inter)
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
