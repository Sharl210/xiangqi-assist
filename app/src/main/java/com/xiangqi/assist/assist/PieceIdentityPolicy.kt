package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece

/** 棋子类别（车/马/象/兵/卒等）的时序一致性规则。 */
object PieceIdentityPolicy {
    /** 同一格类别变化必须重新得到完整稳定窗口，不能用错误类别连续多帧污染已确认棋面。 */
    const val CLASS_CHANGE_CONFIRM_FRAMES = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES

    /** 两个盘面中“格子仍有子，但类别或阵营变了”的数量。 */
    fun replacementCount(a: Array<IntArray>, b: Array<IntArray>): Int {
        var count = 0
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val old = a[y][x]
            val now = b[y][x]
            if (old != Piece.EMPTY && now != Piece.EMPTY && old != now) count++
        }
        return count
    }

    /** 只有类别变化、占用格完全没变；这不可能是一步真实走子。 */
    fun isClassOnlyChange(a: Array<IntArray>, b: Array<IntArray>): Boolean {
        var replacements = 0
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val old = a[y][x]
            val now = b[y][x]
            if ((old == Piece.EMPTY) != (now == Piece.EMPTY)) return false
            if (old != Piece.EMPTY && old != now) replacements++
        }
        return replacements > 0
    }

    /** 容差可吸收漏检/高亮，但绝不能把不同汉字类别当作同一候选。 */
    fun compatibleWithinTolerance(
        a: Array<IntArray>,
        b: Array<IntArray>,
        tolerance: Int,
    ): Boolean = AssistBoard.diffCount(a, b) <= tolerance && replacementCount(a, b) == 0

    fun requiredFrames(legalMove: Boolean, classOnlyChange: Boolean, normal: Int): Int = when {
        legalMove -> minOf(normal, BoardTracker.FAST_CONFIRM_FRAMES)
        classOnlyChange -> maxOf(normal, CLASS_CHANGE_CONFIRM_FRAMES)
        else -> normal
    }

    /**
     * 修复“真实走子但终点被识别成另一种同色棋子”。
     *
     * 真实走子会留下唯一空起点和唯一非空终点；如果终点类别错了，先用起点的原类别
     * 还原，再交给象棋规则验证。静止棋子的同格换字没有起点/终点，绝不会被这里放行。
     */
    fun repairMovedPiece(
        previous: Array<IntArray>?,
        observed: Array<IntArray>,
    ): Array<IntArray>? {
        if (previous == null) return null
        var fromX = -1
        var fromY = -1
        var toX = -1
        var toY = -1
        var fromCount = 0
        var toCount = 0
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val old = previous[y][x]
            val now = observed[y][x]
            if (old == now) continue
            if (old != Piece.EMPTY && now == Piece.EMPTY) {
                fromX = x
                fromY = y
                fromCount++
            } else if (now != Piece.EMPTY) {
                toX = x
                toY = y
                toCount++
            }
        }
        if (fromCount != 1 || toCount != 1) return null

        val movingPiece = previous[fromY][fromX]
        val observedTarget = observed[toY][toX]
        if (movingPiece == Piece.EMPTY || observedTarget == Piece.EMPTY) return null
        if (observedTarget == movingPiece) return null
        if (Piece.isRed(observedTarget) != Piece.isRed(movingPiece)) return null

        val repaired = AssistBoard.clone(observed)
        repaired[toY][toX] = movingPiece
        return if (AssistBoard.isLegalSingleMove(previous, repaired)) repaired else null
    }
}
