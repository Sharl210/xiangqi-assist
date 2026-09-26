package com.xiangqi.assist.assist

/**
 * Scroll restoration follows the button the user last reached, not the visible row number.
 * Rows are rebuilt when the panel changes height, so row numbers are not stable identifiers.
 */
object ButtonScrollAnchorPolicy {
    data class Anchor(val buttonIndex: Int, val offsetWithinButtonPx: Int)
    data class Range(val startInclusive: Int, val endExclusive: Int)

    fun rowRange(row: Int, itemCount: Int, rowCount: Int): Range {
        val count = itemCount.coerceAtLeast(0)
        val rows = rowCount.coerceAtLeast(1)
        val perRow = ((count + rows - 1) / rows).coerceAtLeast(1)
        val start = (row.coerceAtLeast(0) * perRow).coerceAtMost(count)
        val end = (start + perRow).coerceAtMost(count)
        return Range(start, end)
    }

    fun rowContainingAnchor(anchor: Anchor?, itemCount: Int, rowCount: Int): Int? {
        if (anchor == null || anchor.buttonIndex !in 0 until itemCount) return null
        for (row in 0 until rowCount.coerceAtLeast(1)) {
            val range = rowRange(row, itemCount, rowCount)
            if (anchor.buttonIndex in range.startInclusive until range.endExclusive) return row
        }
        return null
    }

    fun scrollX(childLeft: Int, offsetWithinButtonPx: Int): Int =
        (childLeft + offsetWithinButtonPx).coerceAtLeast(0)

    /**
     * 延迟恢复前的纯索引安全门。UI 层还会校验 parent 和重建代号，
     * 这里负责把按钮范围/子项范围规则固定下来供回归测试。
     */
    fun childIndexForAnchor(
        anchor: Anchor?,
        rowStart: Int,
        rowEndExclusive: Int,
        childCount: Int,
    ): Int? {
        if (anchor == null || rowStart < 0 || rowEndExclusive <= rowStart || childCount <= 0) return null
        if (anchor.buttonIndex !in rowStart until rowEndExclusive) return null
        val childIndex = anchor.buttonIndex - rowStart
        return childIndex.takeIf { it in 0 until childCount }
    }
}
