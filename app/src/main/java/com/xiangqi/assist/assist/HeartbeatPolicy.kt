package com.xiangqi.assist.assist

/** 等待对方落子阶段的扫描保底策略（纯 JVM，可单测）。 */
object HeartbeatPolicy {
    /** 用户要求：最多 4 秒没有成功扫描就强制处理一帧。 */
    const val WAITING_SCAN_TIMEOUT_MS = 4_000L

    /** 强制扫描后的冷却：没有成功画面时最多每4秒再强制一次，避免异常设备忙循环。 */
    const val FORCE_SCAN_COOLDOWN_MS = 4_000L

    data class State(
        val lastSuccessfulScanAt: Long,
        val lastForcedScanAt: Long = Long.MIN_VALUE,
    )

    /** 当前是否仍处于等待对方阶段。 */
    fun shouldForceWaitingScan(
        phase: AssistPhase.Phase,
        now: Long,
        state: State,
    ): Boolean {
        if (phase != AssistPhase.Phase.WAITING) return false
        if (state.lastSuccessfulScanAt < 0L) return true
        if (now - state.lastSuccessfulScanAt < WAITING_SCAN_TIMEOUT_MS) return false
        return state.lastForcedScanAt == Long.MIN_VALUE ||
            now - state.lastForcedScanAt >= FORCE_SCAN_COOLDOWN_MS
    }

    fun afterForce(now: Long, state: State): State = state.copy(lastForcedScanAt = now)
    fun afterSuccessfulScan(now: Long, state: State): State =
        state.copy(lastSuccessfulScanAt = now)
}
