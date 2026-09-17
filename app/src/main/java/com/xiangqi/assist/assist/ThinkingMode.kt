package com.xiangqi.assist.assist

/**
 * 三种互斥思考预算：固定深度、整次 MultiPV 总时、每候选时间。
 * “每候选时间”仍只启动一次 MultiPV 搜索；实际 `movetime = 单候选时间 × 候选数`，
 * 因而界面含义和发给引擎的命令可以直接核对。
 */
enum class ThinkingMode {
    DEPTH,
    TOTAL_TIME,
    PER_CANDIDATE_TIME;

    fun next(): ThinkingMode = when (this) {
        DEPTH -> TOTAL_TIME
        TOTAL_TIME -> PER_CANDIDATE_TIME
        PER_CANDIDATE_TIME -> DEPTH
    }

    companion object {
        /** 兼容旧版本写入 SharedPreferences 的 `TIME`。 */
        fun fromStored(value: String?): ThinkingMode = when (value) {
            DEPTH.name -> DEPTH
            PER_CANDIDATE_TIME.name -> PER_CANDIDATE_TIME
            TOTAL_TIME.name, "TIME" -> TOTAL_TIME
            else -> ThinkingOptions.DEFAULT_MODE
        }
    }
}

object ThinkingOptions {
    /** 新安装默认使用总时模式；多条候选共享同一份总预算。 */
    val DEFAULT_MODE: ThinkingMode = ThinkingMode.TOTAL_TIME
    /** 候选主变数量的用户档位：默认1，允许1..6；所有工作模式统一遵从。 */
    const val MIN_CANDIDATE_COUNT = 1
    const val MAX_CANDIDATE_COUNT = 6
    const val DEFAULT_CANDIDATE_COUNT = 1

    /** 单个可见时间档位最高 240 秒；不再提供 360 秒档位。 */
    const val MAX_TIME_MS = 240_000
    const val MIN_TIME_MS = 100
    /** 每候选模式的物理总时上限 = 240秒 × 6候选。 */
    const val MAX_EFFECTIVE_TIME_MS = MAX_TIME_MS * MAX_CANDIDATE_COUNT

    val TIME_LEVELS_MS = listOf(
        100L, 1_000L, 3_000L, 5_000L, 8_000L, 16_000L,
        20_000L, 30_000L, 60_000L, 90_000L, 120_000L,
        240_000L,
    )

    const val DEFAULT_TIME_MS = 3_000
    const val DEFAULT_PER_CANDIDATE_TIME_MS = 3_000

    /** 默认搜索深度 20（不是 201，也不是旧值 36）。 */
    const val DEFAULT_DEPTH = 20

    fun effectivePerCandidateTotal(perCandidateMs: Int, candidates: Int): Int {
        val each = perCandidateMs.coerceIn(MIN_TIME_MS, MAX_TIME_MS)
        val count = candidates.coerceIn(MIN_CANDIDATE_COUNT, MAX_CANDIDATE_COUNT)
        return (each.toLong() * count.toLong())
            .coerceAtMost(MAX_EFFECTIVE_TIME_MS.toLong())
            .toInt()
    }

    fun formatTime(ms: Long): String = when {
        ms < 1_000L -> "${ms / 1000.0}s"
        ms % 1_000L == 0L -> "${ms / 1_000L}s"
        else -> "${"%.1f".format(ms / 1000.0)}s"
    }
}
