package com.xiangqi.assist.assist

import kotlin.math.ceil

/**
 * 自动点击式落子的时序策略（纯 JVM）。
 *
 * 起点点击后到终点点击的间隔不是固定值：以当前基准间隔为上限，
 * 每手在 0.3～1.0 倍之间随机取值。这样只改变人的反应感，不改变着法、
 * 坐标、回执和失败重试语义。
 */
object MoveTimingPolicy {
    const val MIN_FACTOR = 0.30
    const val MAX_FACTOR = 1.00
    const val DEFAULT_TAP_GAP_MS = 220L

    fun randomizedTapGapMs(
        baseMs: Long = DEFAULT_TAP_GAP_MS,
        random: java.util.Random,
    ): Long {
        val base = baseMs.coerceAtLeast(1L)
        val minimum = ceil(base * MIN_FACTOR).toLong().coerceIn(1L, base)
        val span = base - minimum
        if (span == 0L) return minimum
        // nextDouble 的上界为1.0，因此 +1 后可以稳定覆盖 minimum..base 两端。
        return minimum + (random.nextDouble() * (span + 1L).toDouble()).toLong()
            .coerceAtMost(base)
    }

    fun factorOf(gapMs: Long, baseMs: Long = DEFAULT_TAP_GAP_MS): Double {
        val base = baseMs.coerceAtLeast(1L)
        return gapMs.coerceIn(1L, base).toDouble() / base.toDouble()
    }
}
