package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece

/**
 * 识别结果的缺子保护（纯 JVM）。
 *
 * 这不是用上一局面补棋子，也不把旧类别写回当前结果；它只在已经有可靠基线时
 * 检查当前画面是否出现了“无解释地消失的棋子”。真实的一步走子（含吃子）由规则
 * 引擎确认后放行；明显是既有盘面的残缺子集或局部抖动时拒绝，等待下一帧。
 * 大幅变化不在这里冒充“合法走子”，交给显式重建/用户刷新路径处理。
 * 首次建立棋盘、半自动用户主动刷新和手动摆子不使用这条历史完整性门。
 */
object BoardCompletenessPolicy {
    /** 返回 null 表示可接受；返回人类可读原因表示必须拒绝当前关键帧。 */
    fun rejectionReason(
        previous: Array<IntArray>?,
        current: Array<IntArray>,
    ): String? {
        if (previous == null || AssistBoard.equal(previous, current)) return null

        // 合法的一步（普通移动或吃子）是唯一允许的占用变化。
        if (AssistBoard.isLegalSingleMove(previous, current)) return null

        val previousCount = countPieces(previous)
        val currentCount = countPieces(current)
        val disappeared = mutableListOf<Pair<Int, Int>>()
        val appeared = mutableListOf<Pair<Int, Int>>()
        val replaced = mutableListOf<Pair<Int, Int>>()
        var unchangedOccupied = 0
        for (y in 0 until AssistBoard.H) for (x in 0 until AssistBoard.W) {
            val before = previous[y][x]
            val after = current[y][x]
            when {
                before != Piece.EMPTY && after == Piece.EMPTY -> disappeared += x to y
                before == Piece.EMPTY && after != Piece.EMPTY -> appeared += x to y
                before != Piece.EMPTY && after != Piece.EMPTY && before != after -> replaced += x to y
                before != Piece.EMPTY && before == after -> unchangedOccupied++
            }
        }

        // 只有当当前结果明显是上一盘面的“残缺子集”时，数量下降才表示漏检。
        // 全新局面/用户换盘可能合法地少很多子，不能因为总数下降就锁死同步。
        val mostlyRetained = currentCount > 0 &&
            unchangedOccupied.toDouble() / currentCount.toDouble() >= MOSTLY_RETAINED_FRACTION
        val severeCollapse = previousCount >= COLLAPSE_BASELINE_MIN &&
            currentCount <= previousCount - COLLAPSE_MIN_LOSS &&
            currentCount.toDouble() / previousCount.toDouble() <= COLLAPSE_RETAINED_RATIO &&
            mostlyRetained
        return when {
            severeCollapse ->
                "识别疑似大量漏子：当前仅识别 $currentCount/$previousCount 子，拒绝当前盘面"
            currentCount < previousCount && disappeared.isNotEmpty() && mostlyRetained ->
                "识别疑似漏子：当前少识别 ${previousCount - currentCount} 子，拒绝当前盘面"
            // 少量格位变化却没有规则合法的起点/终点，通常是动画残影或类别抖动；
            // 大幅变化保留给换盘/重建基线逻辑处理。
            currentCount == previousCount &&
                AssistBoard.diffCount(previous, current) <= LOCAL_CHANGE_MAX_CELLS &&
                (replaced.isNotEmpty() || (disappeared.isNotEmpty() && appeared.isNotEmpty())) ->
                "识别结果局部棋位不一致，拒绝当前盘面"
            disappeared.isNotEmpty() && appeared.isEmpty() && replaced.isEmpty() && mostlyRetained ->
                "识别疑似漏子：已有棋子无故消失，拒绝当前盘面"
            else -> null
        }
    }

    private const val MOSTLY_RETAINED_FRACTION = 0.50
    private const val COLLAPSE_BASELINE_MIN = 8
    private const val COLLAPSE_MIN_LOSS = 4
    private const val COLLAPSE_RETAINED_RATIO = 0.70
    private const val LOCAL_CHANGE_MAX_CELLS = 4

    private fun countPieces(board: Array<IntArray>): Int =
        board.sumOf { row -> row.count { it != Piece.EMPTY } }
}
