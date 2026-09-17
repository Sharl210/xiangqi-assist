package com.xiangqi.assist.assist

/** Pure run-control contract shared by the app control page and the foreground safety pause. */
object AssistRunControlPolicy {
    /** Preparing permissions and MediaProjection never implies starting recognition. */
    const val PREPARED_PAUSED = true

    fun hasRunningIntent(
        prepared: Boolean,
        paused: Boolean,
        foregroundWasRunning: Boolean,
    ): Boolean = prepared && (!paused || foregroundWasRunning)

    fun canStart(
        prepared: Boolean,
        paused: Boolean,
        foregroundWasRunning: Boolean,
    ): Boolean = prepared && paused && !foregroundWasRunning

    fun canStop(
        prepared: Boolean,
        paused: Boolean,
        foregroundWasRunning: Boolean,
    ): Boolean = prepared && (!paused || foregroundWasRunning)
}
