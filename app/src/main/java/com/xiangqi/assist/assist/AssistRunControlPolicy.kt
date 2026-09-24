package com.xiangqi.assist.assist

/**
 * 运行控制的纯逻辑契约。
 *
     * “已准备”表示本次悬浮窗会话仍然存在；“正在取帧”表示Surface已接到ImageReader，
     * 与MediaProjection授权对象、VirtualDisplay会话分开管理。
 */
object AssistRunControlPolicy {
    /** 准备环境后仍保持暂停，必须由用户在悬浮窗内明确开始。 */
    const val PREPARED_PAUSED = true

    enum class SessionState {
        CLOSED,
        PREPARED_PAUSED,
        RUNNING,
        FOREGROUND_SUSPENDED,
    }

    /** 暂停时保留投影授权会话，但通过断开 Surface 停止向应用输出图像。 */
    const val PAUSED_RETAIN_PROJECTION = true

    /** 控制页唯一按钮：只要会话已准备，就提供“一键关闭”。 */
    fun primaryLabel(prepared: Boolean, @Suppress("UNUSED_PARAMETER") running: Boolean): String =
        if (prepared) "一键关闭" else "一键准备"

    /** 暂停必须保留投影授权对象，但此状态不表示帧输出管线仍在运行。 */
    fun shouldHoldProjection(state: SessionState, retainWhilePaused: Boolean): Boolean = when (state) {
        SessionState.RUNNING, SessionState.FOREGROUND_SUSPENDED -> true
        SessionState.PREPARED_PAUSED -> retainWhilePaused
        SessionState.CLOSED -> false
    }

    /** 哪些状态必须维持输出Surface与帧读取器。 */
    fun shouldRunCapturePipeline(state: SessionState): Boolean = state == SessionState.RUNNING

    /** 已准备时关闭按钮为红色，即使处于暂停态或正等待无障碍关闭核验。 */
    fun primaryButtonIsClose(preparedOrClosePending: Boolean): Boolean = preparedOrClosePending

    /** 关闭时是否需要先完成本应用无障碍组件撤销或用户设置收尾。 */
    fun accessibilityShutdownRequired(closeRequested: Boolean): Boolean = closeRequested

    /** 关闭前是否仍需要保留前台应用观察能力。 */
    fun shouldWatchForeground(state: SessionState): Boolean = state != SessionState.CLOSED

    /** 用户是否仍有一个未关闭的运行意图（前台保护暂停也算）。 */
    fun hasRunningIntent(
        prepared: Boolean,
        paused: Boolean,
        foregroundWasRunning: Boolean,
    ): Boolean = prepared && (!paused || foregroundWasRunning)

    /** 已准备的暂停会话仍显示“一键关闭”，即使当前没有帧输出。 */
    fun canStart(
        prepared: Boolean,
        paused: Boolean,
        foregroundWasRunning: Boolean,
    ): Boolean = prepared && paused && !foregroundWasRunning

    /** 一键关闭针对整个已准备会话，而不只是当前是否正在取帧。 */
    fun canStop(
        prepared: Boolean,
        @Suppress("UNUSED_PARAMETER") paused: Boolean,
        @Suppress("UNUSED_PARAMETER") foregroundWasRunning: Boolean,
    ): Boolean = prepared
}
