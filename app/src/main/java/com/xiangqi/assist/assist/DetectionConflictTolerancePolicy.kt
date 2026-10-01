package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece

/**
 * 同一棋格出现类别竞争时的“可容忍”判定。
 *
 * 背景：`DetectionBoardMapper.map` 解决同格冲突的方式是「高分候选占位、低分候选丢弃」，
 * 也就是说冲突帧的 `screenRaw` 已经反映了胜出候选。旧逻辑只要冲突列表非空就整帧否决，
 * 使「棋面其实已经由高分候选决定、只是同格另有一个低分候选」的帧被永久拒绝，
 * 真机日志里因此连续 116 秒、111 次重试全部同因失败。
 *
 * 本策略只在胜负足够明确、且最终棋面确实落在胜出候选上时才允许该帧继续参与
 * 既有的多帧稳定确认门；模糊僵局一律拒绝，不做猜测、不改写类别。
 */
object DetectionConflictTolerancePolicy {

    /**
     * 判定“胜负明确”所需的最小分差。
     *
     * 低于该值时两个候选几乎不可区分，容忍等于随机选类，因此一律拒绝。
     * 真机日志中的目标冲突分差约 0.024（0.708 对 0.684），高于该阈值；
     * 而早期不可分的帧分差约 0.024 以下时仍会被拒绝。
     */
    const val MIN_DECISIVE_GAP = 0.02

    /** 允许容忍时，胜出候选必须达到的最低置信度；过低说明该格本身就没有可靠检测。 */
    const val MIN_WINNER_SCORE = 0.50

    data class Verdict(
        /** 是否允许该冲突不再否决整帧 */
        val tolerable: Boolean,
        /** 最终占位候选的类别 */
        val winnerPiece: Int,
        val winnerScore: Double,
        val loserScore: Double,
        /** 胜者与败者的分差 */
        val gap: Double,
    )

    /**
     * 判定单个同格冲突是否可容忍。
     *
     * 三个条件必须同时满足：
     * 1. 分差达到 [MIN_DECISIVE_GAP]，即胜负明确；
     * 2. 胜出候选置信度不低于 [MIN_WINNER_SCORE]；
     * 3. `screenRaw` 该格最终确实落在胜出候选上（冲突没有把结果改写成别的类别）。
     *
     * 任一条不满足即返回不可容忍，调用方须保持原拒绝行为。
     */
    fun evaluate(
        conflict: DetectionBoardMapper.CellClassConflict,
        screenRaw: Array<IntArray>,
    ): Verdict {
        val winnerPiece: Int
        val winnerScore: Double
        val loserScore: Double
        if (conflict.firstScore >= conflict.secondScore) {
            winnerPiece = conflict.firstPiece
            winnerScore = conflict.firstScore
            loserScore = conflict.secondScore
        } else {
            winnerPiece = conflict.secondPiece
            winnerScore = conflict.secondScore
            loserScore = conflict.firstScore
        }
        val gap = winnerScore - loserScore
        val placedOnWinner = conflict.row in screenRaw.indices &&
            conflict.col in screenRaw[conflict.row].indices &&
            screenRaw[conflict.row][conflict.col] == winnerPiece
        val tolerable = winnerPiece != Piece.EMPTY &&
            placedOnWinner &&
            winnerScore >= MIN_WINNER_SCORE &&
            gap >= MIN_DECISIVE_GAP
        return Verdict(
            tolerable = tolerable,
            winnerPiece = winnerPiece,
            winnerScore = winnerScore,
            loserScore = loserScore,
            gap = gap,
        )
    }
}
