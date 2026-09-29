package com.xiangqi.assist.assist

/**
 * 自动执子方检测的稳定性策略（纯 JVM）。
 *
 * 自动模式下每一帧都要重新检测我方执子方（连场对局换局会换边），但**换边**必须连续若干帧一致才生效：
 * 单帧抖动（某帧把某个王判到另一侧）不得立刻翻转整局的我方方向，否则回合、算棋和落子会全部站错边。
 *
 * 未检测出唯一王时（[detected] 为 null）不改动现有值，也不清空已有的连续计数：
 * 已确认过的执子方沿用旧值继续识别，避免整盘被单帧拒收。
 */
object SideDetectionStabilityPolicy {

    /** 换边需要的连续一致帧数。 */
    const val REQUIRED_CONSECUTIVE_FRAMES = 3

    data class State(val streak: Int = 0)

    data class Decision(
        /** 本帧应采用的执子方；null 表示本帧未检测到，沿用调用方现有值。 */
        val red: Boolean?,
        val changed: Boolean,
        val state: State,
        /** 本帧是否读出了唯一王（无论是否与当前一致）。 */
        val observed: Boolean,
    )

    fun observe(
        state: State,
        detected: Boolean?,
        current: Boolean,
        required: Int = REQUIRED_CONSECUTIVE_FRAMES,
    ): Decision {
        if (detected == null) return Decision(null, false, state, false)
        if (detected == current) return Decision(current, false, State(0), true)
        val streak = state.streak + 1
        return if (streak >= required) {
            Decision(detected, true, State(0), true)
        } else {
            Decision(current, false, State(streak), true)
        }
    }
}
