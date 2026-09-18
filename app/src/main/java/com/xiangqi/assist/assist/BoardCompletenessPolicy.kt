package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece

/**
 * 识别结果的缺子保护（纯 JVM）。
 *
 * 这不是用上一局面补棋子，也不把旧类别写回当前结果；它只在已经有可靠基线时
 * 检查当前画面是否出现了“无解释地消失的棋子”。真实的一步走子（含吃子）由规则
 * 引擎确认后放行；明显是既有盘面的残缺子集或局部抖动时拒绝，等待下一帧。
 * 同占用格的类别抖动交给 BoardTracker 的类别一致性窗口处理；这里不把它误判成缺子。
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
        // “少子”只有在消失数量明显压过新增/类别替换，且当前结果仍大量沿用旧格位时
        // 才可信；真实连续走子（含多次吃子）通常是一消失对应一新增/替换，不满足此门。
        val lossDominates = disappeared.size >=
            maxOf(1, (appeared.size + replaced.size) * 2)
        val retainedSubset = currentCount < previousCount && mostlyRetained && lossDominates
        return when {
            retainedSubset && previousCount >= COLLAPSE_BASELINE_MIN &&
                currentCount <= previousCount - COLLAPSE_MIN_LOSS &&
                currentCount.toDouble() / previousCount.toDouble() <= COLLAPSE_RETAINED_RATIO ->
                "识别疑似大量漏子：当前仅识别 $currentCount/$previousCount 子，拒绝当前盘面"
            retainedSubset ->
                "识别疑似漏子：当前少识别 ${previousCount - currentCount} 子，拒绝当前盘面"
            else -> null
        }
    }

    private const val MOSTLY_RETAINED_FRACTION = 0.50
    private const val COLLAPSE_BASELINE_MIN = 8
    private const val COLLAPSE_MIN_LOSS = 4
    private const val COLLAPSE_RETAINED_RATIO = 0.70

    private fun countPieces(board: Array<IntArray>): Int =
        board.sumOf { row -> row.count { it != Piece.EMPTY } }
}
