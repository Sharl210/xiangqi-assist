package com.xiangqi.assist.assist

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO26/Ultralytics raw detection output decoder.
 *
 * The exported XQ candidate emits `[1,19,8400]`: four decoded xywh values
 * followed by 15 sigmoid class scores (14 red/black pieces plus board). It has
 * no objectness column and therefore must not be passed through the legacy
 * `[1,25200,20]` YOLOv5 decoder. Raw YOLO26 class IDs are normalized to the
 * legacy XQ label IDs before creating `YoloDetection`, so the existing piece
 * mapping and board mapper remain the single downstream contract. */
object Yolo26Postprocessor {
    const val MODEL_INPUT = 640
    const val ANCHORS = 8_400
    const val DIMS = 19
    const val CLASS_COUNT = 15
    const val BOARD_LABEL = 14

    /** YOLO26 class order -> the legacy XQ label order consumed by YoloDetection/Piece mapping. */
    private val rawToLegacyLabel = intArrayOf(
        2,  // Black_Advisor -> b_shi
        1,  // Black_Bishop  -> b_xiang
        5,  // Black_Cannon  -> b_pao
        3,  // Black_King    -> b_jiang
        0,  // Black_Knight  -> b_ma
        6,  // Black_Pawn    -> b_bing
        4,  // Black_Rook    -> b_che
        9,  // Red_Advisor   -> r_shi
        11, // Red_Bishop    -> r_xiang
        12, // Red_Cannon    -> r_pao
        10, // Red_King      -> r_jiang
        8,  // Red_Knight    -> r_ma
        13, // Red_Pawn      -> r_bing
        7,  // Red_Rook      -> r_che
        14, // board
    )

    private fun toLegacyLabel(rawLabel: Int): Int =
        rawToLegacyLabel.getOrElse(rawLabel) { -1 }

    fun decode(
        output: Array<FloatArray>,
        lb: YoloPostprocessor.Letterbox,
        frameW: Int,
        frameH: Int,
        confThreshold: Double = 0.25,
        iouThreshold: Double = 0.45,
        classMarginMin: Double = 0.03,
    ): List<YoloDetection> {
        require(output.size == DIMS) { "YOLO26 output channel count must be $DIMS" }
        require(output.all { it.size == ANCHORS }) {
            "YOLO26 output anchor count must be $ANCHORS"
        }

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
            var bestLabel = -1
            var bestScore = 0.0
            val scores = ArrayList<Pair<Int, Double>>(CLASS_COUNT)
            for (label in 0 until CLASS_COUNT) {
                val score = output[4 + label][i].toDouble()
                scores += label to score
                if (score > bestScore) {
                    bestScore = score
                    bestLabel = label
                }
            }
            if (bestLabel < 0 || bestScore < confThreshold) continue
            val secondScore = scores.asSequence()
                .filter { it.first != bestLabel }
                .maxOfOrNull { it.second } ?: 0.0
            if (bestLabel != BOARD_LABEL && bestScore - secondScore < classMarginMin) continue

            val legacyLabel = toLegacyLabel(bestLabel)
            if (legacyLabel < 0) continue
            val cx = output[0][i].toDouble()
            val cy = output[1][i].toDouble()
            val w = output[2][i].toDouble()
            val h = output[3][i].toDouble()
            if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() ||
                w <= 0.0 || h <= 0.0
            ) continue
            raws += Raw(
                labelId = legacyLabel,
                score = bestScore,
                x1 = (cx - w / 2.0 - lb.padX) / lb.scale,
                y1 = (cy - h / 2.0 - lb.padY) / lb.scale,
                x2 = (cx + w / 2.0 - lb.padX) / lb.scale,
                y2 = (cy + h / 2.0 - lb.padY) / lb.scale,
                alternatives = scores
                    .sortedByDescending { it.second }
                    .map { toLegacyLabel(it.first) to it.second }
                    .filter { it.first >= 0 }
                    .distinctBy { it.first }
                    .take(3),
            )
        }
        if (raws.isEmpty()) return emptyList()

        raws.sortByDescending { it.score }
        val keep = ArrayList<Raw>()
        for (candidate in raws) {
            // Raw YOLO26 exports contain many same-class end-to-end candidates.
            // Suppress duplicates before mapping to cells, but deliberately retain
            // different classes so the board mapper can reject a genuinely ambiguous cell.
            if (keep.any {
                    it.labelId == candidate.labelId &&
                        iou(it.x1, it.y1, it.x2, it.y2,
                            candidate.x1, candidate.y1, candidate.x2, candidate.y2) > iouThreshold
                }) continue
            keep += candidate
        }

        val all = keep.map {
            YoloDetection(
                labelId = it.labelId,
                score = it.score,
                cx = (it.x1 + it.x2) / 2.0,
                cy = (it.y1 + it.y2) / 2.0,
                w = it.x2 - it.x1,
                h = it.y2 - it.y1,
                alternatives = it.alternatives,
            )
        }
        val board = all.filter { it.isBoard }.maxByOrNull { it.score }
        val boardGeometry = board?.let { b ->
            val ratio = b.w / b.h
            if (ratio in 0.70..1.30 && b.w > 0 && b.h > 0) {
                doubleArrayOf(
                    b.cx - b.w / 2.0,
                    b.cy - b.h / 2.0,
                    b.cx + b.w / 2.0,
                    b.cy + b.h / 2.0,
                    b.w / 8.0,
                    b.h / 9.0,
                )
            } else null
        }
        return all.filter { d ->
            if (d.isBoard) return@filter true
            val withinFrame = d.cx + d.w / 2.0 >= 0.0 &&
                d.cy + d.h / 2.0 >= 0.0 &&
                d.cx - d.w / 2.0 <= frameW.toDouble() &&
                d.cy - d.h / 2.0 <= frameH.toDouble()
            if (!withinFrame) return@filter false
            val ratio = d.w / d.h
            if (ratio !in 0.45..1.80) return@filter false
            if (boardGeometry == null) return@filter true
            val g = boardGeometry
            // YOLO26 的 board 类通常包住棋盘背景而非交点外接矩形；这里仅做宽松的
            // 框内预筛，真实交点会在 DetectionBoardMapper 中重新拟合。过早用 0.60
            // 个格距裁掉边缘棋子，会把“检测到了但映射不到”伪装成模型漏检。
            val inBoard = d.cx in (g[0] - g[4] * 1.00)..(g[2] + g[4] * 1.00) &&
                d.cy in (g[1] - g[5] * 1.00)..(g[3] + g[5] * 1.00)
            val sizeOk = d.w in (g[4] * 0.18)..(g[4] * 2.80) &&
                d.h in (g[5] * 0.18)..(g[5] * 2.80)
            inBoard && sizeOk
        }
    }

    private fun iou(
        ax1: Double, ay1: Double, ax2: Double, ay2: Double,
        bx1: Double, by1: Double, bx2: Double, by2: Double,
    ): Double {
        val ix1 = max(ax1, bx1)
        val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2)
        val iy2 = min(ay2, by2)
        val iw = max(0.0, ix2 - ix1)
        val ih = max(0.0, iy2 - iy1)
        val intersection = iw * ih
        if (intersection <= 0.0) return 0.0
        val a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
        val b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
        return intersection / (a + b - intersection)
    }
}
