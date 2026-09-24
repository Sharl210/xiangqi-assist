package com.xiangqi.assist.assist

/** Scan fallback policy for continuous reading phases. */
object HeartbeatPolicy {
    const val WAITING_SCAN_TIMEOUT_MS = 250L
    const val OTHER_SCAN_TIMEOUT_MS = 500L
    const val FORCE_SCAN_COOLDOWN_MS = 250L
    const val OTHER_SCAN_COOLDOWN_MS = 500L

    data class State(
        /** Most recent sample successfully consumed by the capture/stability path. */
        val lastSuccessfulScanAt: Long,
        val lastForcedScanAt: Long = Long.MIN_VALUE,
    )

    fun isScanPhase(phase: AssistPhase.Phase): Boolean = when (phase) {
        AssistPhase.Phase.FINDING, AssistPhase.Phase.READY,
        AssistPhase.Phase.WAITING, AssistPhase.Phase.VERIFYING -> true
        else -> false
    }

    fun shouldForceWaitingScan(
        phase: AssistPhase.Phase,
        now: Long,
        state: State,
    ): Boolean {
        if (!isScanPhase(phase)) return false
        val timeout = if (phase == AssistPhase.Phase.WAITING)
            WAITING_SCAN_TIMEOUT_MS else OTHER_SCAN_TIMEOUT_MS
        val cooldown = if (phase == AssistPhase.Phase.WAITING)
            FORCE_SCAN_COOLDOWN_MS else OTHER_SCAN_COOLDOWN_MS
        if (state.lastSuccessfulScanAt >= 0L && now - state.lastSuccessfulScanAt < timeout) return false
        return state.lastForcedScanAt == Long.MIN_VALUE || now - state.lastForcedScanAt >= cooldown
    }

    fun afterForce(now: Long, state: State): State = state.copy(lastForcedScanAt = now)
    fun afterSuccessfulScan(now: Long, state: State): State = state.copy(lastSuccessfulScanAt = now)
}
