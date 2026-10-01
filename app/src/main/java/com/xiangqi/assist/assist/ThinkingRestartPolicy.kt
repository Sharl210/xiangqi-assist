package com.xiangqi.assist.assist

/**
 * 兜底「计算中」干等时的处置策略（纯 JVM，可单测）。
 *
 * 背景：`THINKING` 在阶段表里既表示"引擎真的在搜"，又是规则表的兜底出口
 * （轮到我走、引擎没在搜、着法也没定）。兜底态只能靠"重新发起分析"爬出来。
 *
 * 2026-10-01 真机教训：初版每 400ms 无条件重发一次，13 秒内刷了 25 次，
 * 每次后面都跟着 `ANALYSIS_SCHEDULE`，却始终没有一条 `ANALYSIS_REQUEST`——
 * 因为真正的病根是"这份棋面本身就非法"（非走子方王被将军），重发多少次都没用。
 * 于是这里定成三步：**限速重试 → 用尽次数后放弃并作废局面回识盘**，
 * 绝不允许在同一个错误局面上无限空转。
 */
object ThinkingRestartPolicy {

    /** 兜底态干等超过这个时长才认为"分析这条路没走通"。 */
    const val STALL_TIMEOUT_MS = 3_000L

    /** 两次重发之间的最小间隔，避免看门狗每个心跳都重发一次。 */
    const val MIN_RETRY_GAP_MS = 1_500L

    /** 最多重发几次；用尽后不再重发，改为作废局面回识盘。 */
    const val MAX_RETRIES = 3

    enum class Action {
        /** 允许再重发一次分析。 */
        RETRY,

        /** 距上次重发太近：本次跳过，不计次也不动作。 */
        THROTTLED,

        /** 重发次数已用尽：作废当前局面，退回识盘重新识别。 */
        GIVE_UP,
    }

    fun decide(attempts: Int, sinceLastRetryMs: Long): Action = when {
        sinceLastRetryMs < MIN_RETRY_GAP_MS -> Action.THROTTLED
        attempts >= MAX_RETRIES -> Action.GIVE_UP
        else -> Action.RETRY
    }
}
