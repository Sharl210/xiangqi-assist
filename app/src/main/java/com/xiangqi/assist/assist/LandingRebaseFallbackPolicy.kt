package com.xiangqi.assist.assist

/**
 * 落子核对超时后的有限回退。
 *
 * 事务快照优先级最高；但快照可能已经落后于真实棋局（例如对方连续走子、截图延迟
 * 或应用动画跨过了回执窗口）。此时不能无限期把所有合法棋面都拒绝，否则识别链会
 * 永久停在“重新确认棋面”。只有同一份当前棋面连续出现，并且通过双方轮次的引擎
 * 安全检查后，才允许把它作为新的观察基线。
 */
object LandingRebaseFallbackPolicy {
    data class State(
        val board: Array<IntArray>? = null,
        val streak: Int = 0,
    )

    data class Observation(
        val state: State,
        val accepted: Boolean,
    )

    fun observe(
        state: State,
        observed: Array<IntArray>,
        preRedGo: Boolean,
        requiredStreak: Int = 3,
    ): Observation {
        val nextStreak = if (state.board != null && AssistBoard.equal(state.board, observed)) {
            state.streak + 1
        } else {
            1
        }
        val next = State(AssistBoard.clone(observed), nextStreak)
        if (nextStreak < requiredStreak) return Observation(next, false)

        val before = AssistBoard.engineUnsafeReason(observed, preRedGo)
        val after = AssistBoard.engineUnsafeReason(observed, !preRedGo)
        return Observation(next, before == null || after == null)
    }
}
