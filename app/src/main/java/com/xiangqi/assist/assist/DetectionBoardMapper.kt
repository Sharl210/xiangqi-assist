package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YOLO 检测结果 -> 9×10 棋盘映射（纯 JVM）。
 *
 * 网格锚点取值优先级（命中即用，是「识别不到棋盘」问题的关键修复点）：
 * 1. 模型的 board 框：形状合理时最可靠；
 * 2. **上一帧成功定位的网格锚点**（[AnchorHint]）：board 框偶发漏检时沿用，避免整帧作废；
 * 3. 棋子中心点包围盒兜底（需有足够棋子且间距一致；
 *    有锚点提示时可放宽子数下限，因为此时只需确认「棋子确实落在既有网格上」）。
 *
 * 随后：棋子中心 round 到最近交点，越界丢弃，同格冲突保留高分者；
 * 朝向由两个王的位置推断（帅在下=STANDARD）。
 */
object DetectionBoardMapper {

    /**
     * 外部传入的网格锚点（帧像素坐标）。用于 board 框漏检时沿用上一帧的定位结果。
     * 传入前应确保它来自「最近一次成功识别」，且棋盘没有发生大幅位移。
     */
    class AnchorHint(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    class MappedBoard(
        /** 屏幕布局 [gy][gx]（gy=0 为屏幕顶部），元素为 Piece 常量或 EMPTY */
        val screenRaw: Array<IntArray>,
        /** canonical 布局（红恒在 y=9） */
        val canonical: Array<IntArray>,
        val orientation: Orientation,
        /** 由棋盘框换算的归一化网格（供悬浮窗遮挡判断、自动走子落点换算等复用） */
        val grid: BoardGrid?,
        val pieceCount: Int,
        val avgScore: Double,
        /** 识别日志：被丢弃的越界/重复棋子数 */
        val dropped: Int,
        /** 本次使用的锚点来源（诊断用） */
        val anchorSource: AnchorSource,
        /** 自动修正（多认的棋子被剔除）的说明；空表示这一帧没被修正过 */
        val repairNotes: List<String> = emptyList(),
        /** 同一棋盘格被不同类别的检测框同时占用；该帧不得提交为可用棋面。 */
        val cellClassConflicts: List<CellClassConflict> = emptyList(),
    )

    data class CellClassConflict(
        val row: Int,
        val col: Int,
        val firstPiece: Int,
        val secondPiece: Int,
        val firstScore: Double,
        val secondScore: Double,
    )

    enum class AnchorSource {
        /** 模型检出的 board 框 */
        BOARD_BOX,

        /** 沿用上一帧网格 */
        PREVIOUS_GRID,

        /** 棋子包围盒 */
        PIECE_BBOX,
    }

    /**
     * 单帧最少"棋子检测数"：低于该值不值得映射（整屏无棋盘）。残局可低至 3 子
     * （帅仕 vs 将），故不能沿用 10——那会把稀疏残局永远拒之门外；
     * 结构合法性由 AssistBoard.validate、瞬时坏帧由 BoardTracker 的多帧确认把关。
     */
    const val MIN_PIECES = 3

    /** 无锚点提示时，纯包围盒兜底所需的最少棋子数（太少无法确定网格朝向与格距） */
    private const val BBOX_MIN_PIECES = 12

    /** 有锚点提示时，包围盒兜底只需确认棋子落在既有网格内，子数下限可放宽 */
    private const val BBOX_MIN_PIECES_WITH_HINT = 4

    /** 屏幕内重复检测按置信度解决；不会用历史棋面替换当前模型类别。 */

    fun map(
        dets: List<YoloDetection>,
        frameW: Int,
        frameH: Int,
        minPieces: Int = MIN_PIECES,
        anchor: AnchorHint? = null,
        /** 裁剪推理时优先使用调用方提供的全屏网格，避免裁剪边界重算出偏移。 */
        preferAnchor: Boolean = false,
    ): MappedBoard? {
        val pieces = dets.filter { !it.isBoard }
        if (pieces.size < minPieces) return null

        val boardDet = dets.filter { it.isBoard }.maxByOrNull { it.score }
        val avgW = pieces.map { it.w }.average()
        val avgH = pieces.map { it.h }.average()

        var bx0 = 0.0; var by0 = 0.0; var bx1 = 0.0; var by1 = 0.0
        var source = AnchorSource.PIECE_BBOX
        var anchored = false

        // 裁剪图仍然使用当前画面传入的全屏网格作为几何基准；只用当前模型输出的
        // 棋子类别和中心，不让裁剪边界造成第二套“棋盘位置猜测”。
        if (preferAnchor && anchor != null) {
            val refined = refineBoardGrid(anchor.x0, anchor.y0, anchor.x1, anchor.y1, pieces)
            if (refined != null) {
                bx0 = refined[0]; by0 = refined[1]
                bx1 = refined[2]; by1 = refined[3]
                anchored = true
                source = AnchorSource.PREVIOUS_GRID
            }
            if (!anchored) {
                val w = anchor.x1 - anchor.x0
                val h = anchor.y1 - anchor.y0
                val ratio = if (h > 0) w / h else 0.0
                if (ratio in 0.7..1.3 && w > 0 && h > 0) {
                    bx0 = anchor.x0; by0 = anchor.y0; bx1 = anchor.x1; by1 = anchor.y1
                    anchored = true
                    source = AnchorSource.PREVIOUS_GRID
                }
            }
        }

        if (!anchored && boardDet != null) {
            val x0 = boardDet.cx - boardDet.w / 2
            val y0 = boardDet.cy - boardDet.h / 2
            val x1 = boardDet.cx + boardDet.w / 2
            val y1 = boardDet.cy + boardDet.h / 2
            val ratio = (x1 - x0) / (y1 - y0)
            val refined = if (ratio in 0.7..1.3 &&
                (x1 - x0) >= avgW * 7 && (y1 - y0) >= avgH * 8
            ) {
                refineBoardGrid(x0, y0, x1, y1, pieces)
            } else null
            if (refined != null) {
                bx0 = refined[0]; by0 = refined[1]
                bx1 = refined[2]; by1 = refined[3]
                anchored = true
                source = AnchorSource.BOARD_BOX
            }
        }

        // board 框缺失/形状异常：沿用上一帧网格，只作为几何兜底，不替换棋子类别。
        if (!anchored && anchor != null) {
            val w = anchor.x1 - anchor.x0
            val h = anchor.y1 - anchor.y0
            val ratio = if (h > 0) w / h else 0.0
            if (ratio in 0.7..1.3 && w > 0 && h > 0) {
                bx0 = anchor.x0; by0 = anchor.y0; bx1 = anchor.x1; by1 = anchor.y1
                anchored = true
                source = AnchorSource.PREVIOUS_GRID
            }
        }

        if (!anchored) {
            // 兜底：棋子中心包围盒（子数足够才可信；有锚点提示时下限放宽）
            val need = if (anchor != null) BBOX_MIN_PIECES_WITH_HINT else BBOX_MIN_PIECES
            if (pieces.size < need) return null
            val minX = pieces.minOf { it.cx }; val maxX = pieces.maxOf { it.cx }
            val minY = pieces.minOf { it.cy }; val maxY = pieces.maxOf { it.cy }
            val w = maxX - minX; val h = maxY - minY
            if (w <= 0 || h <= 0) return null
            if (w / h !in 0.7..1.3) return null
            // 最小棋子间距应与包围盒跨度相容（近似 8 列 9 行的等距网格）
            var minDist = Double.MAX_VALUE
            for (i in pieces.indices) for (j in i + 1 until pieces.size) {
                val dx = pieces[i].cx - pieces[j].cx
                val dy = pieces[i].cy - pieces[j].cy
                val d = sqrt(dx * dx + dy * dy)
                if (d < minDist) minDist = d
            }
            if (h < minDist * 8.0 || w < minDist * 7.0) return null
            bx0 = minX; by0 = minY; bx1 = maxX; by1 = maxY
            source = AnchorSource.PIECE_BBOX
        }

        val gridW = (bx1 - bx0) / 8.0
        val gridH = (by1 - by0) / 9.0
        if (gridW <= 0 || gridH <= 0) return null

        val cells = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        val cellScore = Array(AssistBoard.H) { DoubleArray(AssistBoard.W) }
        val cellClassConflicts = LinkedHashMap<Pair<Int, Int>, CellClassConflict>()
        var dropped = 0
        var scoreSum = 0.0
        for (p in pieces) {
            val col = ((p.cx - bx0) / gridW).roundToInt()
            val row = ((p.cy - by0) / gridH).roundToInt()
            if (col !in 0 until AssistBoard.W || row !in 0 until AssistBoard.H) { dropped++; continue }
            val piece = p.piece
            if (piece == Piece.EMPTY) {
                dropped++
                continue
            }
            if (cells[row][col] != Piece.EMPTY && cells[row][col] != piece) {
                val previous = CellClassConflict(
                    row = row,
                    col = col,
                    firstPiece = cells[row][col],
                    secondPiece = piece,
                    firstScore = cellScore[row][col],
                    secondScore = p.score,
                )
                val key = row to col
                val existing = cellClassConflicts[key]
                if (existing == null || p.score > existing.secondScore) {
                    cellClassConflicts[key] = previous
                }
            }
            if (cells[row][col] != Piece.EMPTY && cellScore[row][col] >= p.score) { dropped++; continue }
            if (cells[row][col] != Piece.EMPTY) scoreSum -= cellScore[row][col]
            cells[row][col] = piece
            cellScore[row][col] = p.score
            scoreSum += p.score
        }
        // 先根据未修正的双王位置确定屏幕朝向，再按 canonical 坐标检查兵/卒合法行。
        // 不能直接把屏幕 raw 当 canonical：翻转棋盘时红兵在屏幕上半部本来就是合法的。
        val rawOrientation = detectOrientation(cells) ?: Orientation.STANDARD
        val repair = BoardSanitizer.sanitize(cells, cellScore, rawOrientation)
        val fixed = repair.board
        val pieceCount = fixed.sumOf { row -> row.count { it != Piece.EMPTY } }

        val orientation = detectOrientation(fixed) ?: Orientation.STANDARD
        val canonical = toCanonical(fixed, orientation)

        val grid = try {
            BoardGrid.fromPxCorners(
                bx0.coerceIn(0.0, frameW.toDouble()), by0.coerceIn(0.0, frameH.toDouble()),
                bx1.coerceIn(0.0, frameW.toDouble()), by1.coerceIn(0.0, frameH.toDouble()),
                frameW, frameH)
        } catch (e: Exception) { null }

        return MappedBoard(
            /* screenRaw = */ fixed,
            /* canonical = */ canonical,
            /* orientation = */ orientation,
            /* grid = */ grid,
            /* pieceCount = */ pieceCount,
            /* avgScore = */ if (pieceCount > 0) scoreSum / pieceCount else 0.0,
            /* dropped = */ dropped + repair.notes.size,
            /* anchorSource = */ source,
            /* repairNotes = */ repair.notes,
            /* cellClassConflicts = */ cellClassConflicts.values.toList(),
        )
    }

    private data class AxisGridFit(
        val origin: Double,
        val step: Double,
        val support: Int,
        val medianResidual: Double,
        val penalty: Double,
    )

    /**
     * 从 board 检测框和棋子中心拟合真实九列十行交点。
     *
     * 训练集里的 board 框可能包住棋盘背景边缘，而不是左上/右下两个交点。
     * 直接把背景框等分会在相邻棋子之间制造“同格不同类别”假冲突。这里不猜棋子类别，
     * 只在 board 框附近搜索网格原点和步长；要求多数当前检测中心同时落在同一套网格上，
     * 否则仍回退到原有包围盒与结构安全门。
     */
    private fun refineBoardGrid(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        pieces: List<YoloDetection>,
    ): DoubleArray? {
        val baseW = (x1 - x0) / 8.0
        val baseH = (y1 - y0) / 9.0
        if (baseW <= 0.0 || baseH <= 0.0 || pieces.size < 3) return null

        val fitPieces = pieces.filter { p ->
            p.cx in (x0 - baseW * 0.75)..(x1 + baseW * 0.75) &&
                p.cy in (y0 - baseH * 0.75)..(y1 + baseH * 0.75)
        }
        if (fitPieces.size < 3) return null
        val xFit = fitGridAxis(fitPieces.map { it.cx }, x0, x1, 8)
        val yFit = fitGridAxis(fitPieces.map { it.cy }, y0, y1, 9)
        if (xFit == null || yFit == null) return null

        val supportLimit = maxOf(3, ceil(pieces.size * 0.65).toInt())
        val residuals = fitPieces.mapNotNull { p ->
            val col = ((p.cx - xFit.origin) / xFit.step).roundToInt()
            val row = ((p.cy - yFit.origin) / yFit.step).roundToInt()
            if (col !in 0..8 || row !in 0..9) return@mapNotNull null
            val rx = abs(p.cx - (xFit.origin + col * xFit.step)) / xFit.step
            val ry = abs(p.cy - (yFit.origin + row * yFit.step)) / yFit.step
            maxOf(rx, ry)
        }
        val close = residuals.count { it <= 0.32 }
        val median = residuals.sorted().let { values ->
            if (values.isEmpty()) Double.POSITIVE_INFINITY else values[values.size / 2]
        }
        if (close < supportLimit || median > 0.32) return null

        // 不允许拟合结果漂移到 board 框之外太远；这能挡住把一块按钮/面板误当棋盘。
        if (xFit.origin > x1 + baseW * 0.75 || xFit.origin + 8 * xFit.step < x0 - baseW * 0.75 ||
            yFit.origin > y1 + baseH * 0.75 || yFit.origin + 9 * yFit.step < y0 - baseH * 0.75
        ) return null

        return doubleArrayOf(
            xFit.origin,
            yFit.origin,
            xFit.origin + 8 * xFit.step,
            yFit.origin + 9 * yFit.step,
        )
    }

    private fun fitGridAxis(
        values: List<Double>,
        board0: Double,
        board1: Double,
        cellCount: Int,
    ): AxisGridFit? {
        val base = (board1 - board0) / cellCount.toDouble()
        if (base <= 0.0 || values.size < 3) return null
        val minSupport = maxOf(3, ceil(values.size * 0.50).toInt())
        var best: AxisGridFit? = null

        fun consider(origin: Double, step: Double) {
            if (!origin.isFinite() || !step.isFinite() || step <= 0.0) return
            if (origin > board1 + base * 0.75 || origin + cellCount * step < board0 - base * 0.75) return
            val residuals = ArrayList<Double>(values.size)
            var support = 0
            for (value in values) {
                val index = ((value - origin) / step).roundToInt()
                if (index !in 0..cellCount) continue
                val residual = abs(value - (origin + index * step)) / step
                residuals += residual
                if (residual <= 0.32) support++
            }
            if (support < minSupport || residuals.isEmpty()) return
            val median = residuals.sorted()[residuals.size / 2]
            val penalty = abs(origin - board0) / base + abs(step - base) / base
            val current = best
            if (current == null || support > current.support ||
                (support == current.support &&
                    (median < current.medianResidual - 1e-6 ||
                        (abs(median - current.medianResidual) <= 1e-6 && penalty < current.penalty)))
            ) {
                best = AxisGridFit(origin, step, support, median, penalty)
            }
        }

        // board 框附近的步长/原点搜索，覆盖背景边缘和交点外接框两种标注方式。
        for (stepIndex in 0..40) {
            val step = base * (0.80 + stepIndex * 0.01)
            for (shiftIndex in 0..44) {
                val shift = -0.45 + shiftIndex * 0.025
                consider(board0 + shift * base, step)
            }
            // 检测中心本身也提供候选原点，避免固定步长网格错过真实交点。
            for (value in values) {
                for (index in 0..cellCount) {
                    consider(value - index * step, step)
                }
            }
        }
        return best
    }

    /** 双王位置判定屏幕朝向（帅在下=STANDARD，独立实现保持纯 JVM 无耦合） */
    fun detectOrientation(raw: Array<IntArray>): Orientation? {
        var redRow = -1
        var blackRow = -1
        for (gy in 0 until AssistBoard.H) for (gx in 0 until AssistBoard.W) {
            when (raw[gy][gx]) {
                Piece.WSHUAI -> redRow = gy
                Piece.BJIANG -> blackRow = gy
            }
        }
        if (redRow >= 0 && redRow > 4) return Orientation.STANDARD
        if (blackRow >= 0 && blackRow > 4) return Orientation.FLIPPED
        if (redRow >= 0 && redRow <= 4) return Orientation.FLIPPED
        if (blackRow >= 0 && blackRow <= 4) return Orientation.STANDARD
        return null
    }

    /** 屏幕 raw 布局 -> 内部 canonical（红恒在 y=9） */
    fun toCanonical(raw: Array<IntArray>, orientation: Orientation): Array<IntArray> {
        val out = Array(AssistBoard.H) { IntArray(AssistBoard.W) }
        for (gy in 0 until AssistBoard.H) for (gx in 0 until AssistBoard.W) {
            val p = raw[gy][gx]
            if (p == Piece.EMPTY) continue
            when (orientation) {
                Orientation.STANDARD -> out[gy][gx] = p
                Orientation.FLIPPED -> out[AssistBoard.H - 1 - gy][AssistBoard.W - 1 - gx] = p
            }
        }
        return out
    }
}
