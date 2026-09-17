package com.xiangqi.assist.assist

/**
 * 落子核对超时后的首张稳定棋面如何重新确定走子方。
 *
 * 这一步不能把旧的“当前棋面”直接当成事实：超时期间屏幕可能停留在落子前、
 * 已落子，或已经连对手的一手也走完。只使用落子事务保存的两份快照和新画面，
 * 不修改棋盘内容，也不把不确定结果伪装成已确认。
 */
object LandingRebasePolicy {
    /**
     * @return 首张新稳定画面对应的红方走子状态；true=红走，false=黑走。
     */
    fun redGoForObserved(
        observed: Array<IntArray>,
        preBoard: Array<IntArray>?,
        expectedPostBoard: Array<IntArray>?,
        preRedGo: Boolean,
    ): Boolean {
        // 画面仍是落子前：这手没有生效，保持原走子方。
        if (preBoard != null && AssistBoard.equal(observed, preBoard)) return preRedGo

        // 画面正好是预期落子后：只确认我方这手，轮到另一方。
        if (expectedPostBoard != null && AssistBoard.equal(observed, expectedPostBoard)) {
            return !preRedGo
        }

        // 期间又出现了下一手：若能从“预期落子后”看出对方移动，
        // 则轮次回到落子前的一方；若看出我方移动，则轮到另一方。
        // movedSide 返回 1=红刚走、0=黑刚走，下一轮就是相反颜色。
        val movedAfterExpected = expectedPostBoard?.let {
            AssistBoard.movedSide(it, observed)
        }
        if (movedAfterExpected != null) return movedAfterExpected == 0

        // 新画面与两份快照都无法建立可靠关系时，保留事务开始前的走子方；
        // 棋面本身仍由当前识别结果提交，后续真实变化会再次触发正常分析。
        return preRedGo
    }
}
