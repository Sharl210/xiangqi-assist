package com.xiangqi.assist.assist

/** 悬浮面板“开始/暂停/继续”按钮的展示契约。 */
object AssistRunButtonPolicy {
    /** 只有真实运行态才显示绿色“暂停”；前台安全暂停也显示白色“继续”。 */
    fun label(running: Boolean, hasRunSession: Boolean): String = when {
        running -> "暂停"
        hasRunSession -> "继续"
        else -> "开始"
    }

    fun isActive(running: Boolean): Boolean = running
}
