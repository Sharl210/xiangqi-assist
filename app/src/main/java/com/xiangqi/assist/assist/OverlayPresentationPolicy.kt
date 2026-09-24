package com.xiangqi.assist.assist

/** Presentation state shared by the foreground notification and the overlay windows. */
object OverlayPresentationPolicy {
    enum class Mode { PANEL, BALL, TEMPORARY_BALL_FOR_MOVE }

    fun isCollapsed(mode: Mode): Boolean = mode != Mode.PANEL

    fun notificationContent(
        mode: Mode,
        baseContent: String,
    ): String = when (mode) {
        Mode.PANEL -> baseContent
        Mode.BALL -> "悬浮球已显示；点悬浮球可展开面板。$baseContent"
        Mode.TEMPORARY_BALL_FOR_MOVE -> "悬浮球临时显示，落子完成后恢复面板。$baseContent"
    }
}
