package com.xiangqi.assist.assist

/**
 * 工作模式（自动 / 半自动 / 手动）的**互斥**规则（纯 JVM，便于单元测试）。
 *
 * 两条独立的线，必须分清楚：
 * 1. **模式**：只决定棋盘怎么被识别——自动=持续扫盘；半自动=点「更新棋谱」才扫；
 *    手动=完全不扫，自己摆子。三者互斥，任何时刻至多一个生效。
 * 2. **自动落子**：独立开关，与模式相乘才是"全自动"。手动模式不参与自动落子。
 *
 * 早先三个模式各有一个布尔开关、各写各的，结果出现"两个模式同时亮着"，
 * 因此模式统一成一个值，两个开关全部由它派生，不允许外部各自设置。
 */
object WorkModes {

    /** 由模式值派生的开关；构造后保证至多一个为 true */
    class Flags(val manual: Boolean, val semi: Boolean) {
        override fun toString(): String = "manual=$manual semi=$semi"
    }

    /** 模式值 -> 开关（互斥的落点就在这里） */
    fun flags(mode: Int): Flags = Flags(
        manual = mode == AssistConfig.MODE_MANUAL,
        semi = mode == AssistConfig.MODE_SEMI,
    )

    /**
     * 当前模式下是否具备自动落子条件。
     *
     * 「模式」只决定**怎么识别棋盘**（自动 / 半自动 / 手动）；
     * 「自动落子」是独立开关，两者相乘才是真的全自动：
     * - 自动 + 自动落子  → 全自动（识别、计算、落子全包）
     * - 半自动 + 自动落子 → 点「更新棋谱」确认局面后，机器自动落子
     * - 手动：机器不知道你什么时候摆完，因此**不参与自动落子**
     */
    fun autoCanRun(mode: Int, autoPlayOn: Boolean, accessibilityConnected: Boolean): Boolean =
        autoPlayOn && accessibilityConnected &&
            (mode == AssistConfig.MODE_AUTO || mode == AssistConfig.MODE_SEMI)

    /** 模式名（状态栏/按钮文案统一用这个） */
    fun name(mode: Int): String = when (mode) {
        AssistConfig.MODE_MANUAL -> "手动"
        AssistConfig.MODE_SEMI -> "半自动"
        AssistConfig.MODE_AUTO -> "自动"
        else -> "指导"
    }

    /**
     * 模式轮换顺序（一个按钮不断点击）：自动 → 半自动 → 手动 → 自动。
     */
    fun nextInCycle(current: Int): Int = when (current) {
        AssistConfig.MODE_AUTO -> AssistConfig.MODE_SEMI
        AssistConfig.MODE_SEMI -> AssistConfig.MODE_MANUAL
        AssistConfig.MODE_MANUAL -> AssistConfig.MODE_AUTO
        else -> AssistConfig.MODE_AUTO
    }
}
