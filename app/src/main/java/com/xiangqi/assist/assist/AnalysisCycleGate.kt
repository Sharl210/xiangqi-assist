package com.xiangqi.assist.assist

/**
 * 一次“局面到落子/下一回合”的分析周期闸门（纯 JVM）。
 *
 * 同一个局面在本周期内只能启动一次搜索；候选主变变化、界面刷新、识别到同一稳定局面
 * 都只能复用这次搜索。只有局面真正变化、用户明确翻转/重置，或上一手已经完成后，才允许
 * 开启下一周期。
 */
class AnalysisCycleGate {
    @Volatile private var activePosition: String? = null

    @Synchronized
    fun beginOrReuse(position: String): Decision {
        val active = activePosition
        return if (active == position) {
            Decision.REUSE
        } else {
            activePosition = position
            Decision.START
        }
    }

    @Synchronized
    fun invalidate() {
        activePosition = null
    }

    @Synchronized
    fun activePosition(): String? = activePosition

    enum class Decision { START, REUSE }
}
