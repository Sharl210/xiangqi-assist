package com.xiangqi.assist.assist

/**
 * 同一棋格出现同阵营类别竞争时的有界消解条件。
 * 只决定是否允许尝试删除弱候选，最终是否接受仍由棋面安全门决定。
 */
object DetectionConflictPolicy {
    const val MIN_STRONG_SCORE = 0.70
    const val MAX_WEAK_SCORE = 0.60
    const val MIN_SCORE_GAP = 0.18

    fun shouldTryDominantResolution(
        firstPiece: Int,
        secondPiece: Int,
        firstScore: Double,
        secondScore: Double,
    ): Boolean {
        if (com.xiangqi.assist.gamelogic.Piece.isRed(firstPiece) !=
            com.xiangqi.assist.gamelogic.Piece.isRed(secondPiece)
        ) return false
        val strong = maxOf(firstScore, secondScore)
        val weak = minOf(firstScore, secondScore)
        return strong >= MIN_STRONG_SCORE &&
            weak <= MAX_WEAK_SCORE &&
            strong - weak >= MIN_SCORE_GAP
    }
}
