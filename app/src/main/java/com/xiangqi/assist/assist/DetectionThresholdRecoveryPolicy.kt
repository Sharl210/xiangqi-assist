package com.xiangqi.assist.assist

import kotlin.math.pow

/**
 * 有界的识别失败恢复策略：连续每十次拒绝才降低一次阈值，成功后回到严格基线。
 * 阈值下降只影响检测候选的置信度筛选，双王、棋盘几何、合法点位、同格冲突和稳定窗仍必须通过。
 */
object DetectionThresholdRecoveryPolicy {
    /** 连续失败多少次降一档（用户要求：每三次降一档，指数下降，成功即复位）。 */
    const val MISSES_PER_STEP = 3
    const val BASE_THRESHOLD = 0.45
    const val MIN_THRESHOLD = 0.16
    const val STEP_FACTOR = 0.72
    const val MAX_LEVEL = 4

    data class State(val misses: Int = 0, val level: Int = 0)

    fun onRejected(state: State): State {
        val misses = state.misses + 1
        val level = minOf(MAX_LEVEL, misses / MISSES_PER_STEP)
        return State(misses, level)
    }

    fun onAccepted(): State = State()

    fun threshold(state: State, base: Double = BASE_THRESHOLD): Double =
        maxOf(MIN_THRESHOLD, base * STEP_FACTOR.pow(state.level.toDouble()))

    fun margin(state: State, base: Double = 0.05): Double =
        maxOf(0.01, base * STEP_FACTOR.pow(state.level.toDouble()))
}
