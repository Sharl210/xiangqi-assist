package com.xiangqi.assist.assist

/**
 * 落子回执阶段的唯一判定规则。
 *
 * 回执阶段不能把任意变化直接交给普通棋面跟踪器，否则一张“我方已走、对方也已走”的
 * 画面会被误当成普通新局面，覆盖落子事务并再次计算。这里先固定比较“落子前局面”和
 * “这手着法应产生的局面”，只有回执判定结束后才允许把画面交回主状态机。
 */
object LandingVerificationPolicy {
    enum class Verdict {
        /** 画面就是我方这手落子后的局面。 */
        LANDED,
        /** 我方这手落子后，对方已经紧接着走了一步。 */
        LANDED_AND_OPPONENT_MOVED,
        /** 画面包含预期落子，但后续变化无法可靠归类；先等待下一张新棋面。 */
        LANDED_WITH_UNCERTAIN_FOLLOWUP,
        /** 还不能证明这手已经落下，继续等回执或超时重试。 */
        WAITING,
    }

    data class Input(
        val preBoard: Array<IntArray>?,
        val expectedPostBoard: Array<IntArray>?,
        val observedBoard: Array<IntArray>?,
        val ucci: String,
        val preRedGo: Boolean,
        val tolerance: Int = 0,
    )

    fun evaluate(input: Input): Verdict {
        val pre = input.preBoard ?: return Verdict.WAITING
        val expected = input.expectedPostBoard ?: return Verdict.WAITING
        val observed = input.observedBoard ?: return Verdict.WAITING

        // 先验证本次着法的两个关键格：容差只能吸收其它识别抖动，不能把“起点仍有子、
        // 终点尚未落子”的落子前画面当成预期盘面。
        if (!containsExpectedMove(pre, expected, observed, input.ucci)) {
            return Verdict.WAITING
        }
        if (AssistBoard.diffCount(expected, observed) <= input.tolerance) {
            return Verdict.LANDED
        }

        // 如果预期局面之后恰好是对方的一步合法着法，说明两回合已经连续发生：
        // 我方落子必须确认，轮次随后回到我方，不能把这张图当成“我的第一步没落”。
        val movedSide = AssistBoard.movedSide(expected, observed)
        val opponentSide = if (input.preRedGo) 0 else 1
        if (movedSide == opponentSide && AssistBoard.isLegalSingleMove(expected, observed)) {
            return Verdict.LANDED_AND_OPPONENT_MOVED
        }

        // 走子关键格已确认，但后续变化无法可靠归类；先把我方这手视为已落，
        // 绝不重复派发同一手。下一张画面再交给普通跟踪器识别对手是否已走。
        return Verdict.LANDED_WITH_UNCERTAIN_FOLLOWUP
    }

    private fun containsExpectedMove(
        pre: Array<IntArray>,
        expected: Array<IntArray>,
        observed: Array<IntArray>,
        ucci: String,
    ): Boolean {
        if (ucci.length < 4) return false
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        val tx = ucci[2] - 'a'
        val ty = 9 - (ucci[3] - '0')
        if (fx !in 0..8 || fy !in 0..9 || tx !in 0..8 || ty !in 0..9) return false
        val moving = expected[ty][tx]
        if (moving == 0 || pre[fy][fx] != moving || observed[fy][fx] != 0) return false
        return observed[ty][tx] == moving
    }
}
