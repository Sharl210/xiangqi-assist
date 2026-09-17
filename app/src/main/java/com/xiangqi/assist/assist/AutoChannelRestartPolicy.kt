package com.xiangqi.assist.assist

/**
 * 自动落子通道保险的纯逻辑规则。
 *
 * - 待落子状态持续超过两秒时，每个 READY 阶段只触发一次内部恢复；
 * - 真正派发一手棋前，按 FEN+UCCI 事务键只触发一次内部恢复；
 * - 规则本身不改变用户开关，也不产生界面文案。
 */
object AutoChannelRestartPolicy {
    const val READY_RESTART_TIMEOUT_MS = 2_000L
    const val PENDING_MOVE_TIMEOUT_MS = 6_000L

    fun shouldRestartReadyChannel(
        phase: AssistPhase.Phase,
        now: Long,
        phaseSinceAt: Long,
        restartedForPhaseAt: Long,
    ): Boolean {
        if (phase != AssistPhase.Phase.READY) return false
        if (phaseSinceAt <= 0L || now < phaseSinceAt) return false
        if (now - phaseSinceAt <= READY_RESTART_TIMEOUT_MS) return false
        return restartedForPhaseAt != phaseSinceAt
    }

    fun shouldReleasePendingMove(now: Long, createdAt: Long): Boolean {
        if (createdAt <= 0L || now < createdAt) return false
        return now - createdAt > PENDING_MOVE_TIMEOUT_MS
    }

    /** 同一局面同一着法只准备一次；落子失败后由调用方清除该键，下一次尝试重新准备。 */
    fun moveTransactionKey(fen: String, ucci: String): String = "$fen\u0000$ucci"
}
