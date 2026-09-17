package com.xiangqi.assist.assist

/**
 * 一次棋面分析的不可变预算。
 *
 * [totalTimeMs] 是发给引擎的单条 `go` 命令总时。总时模式中，所有 MultiPV 候选共享它；
 * 每候选时间模式由 [perCandidateTimeMs] 明确记录界面档位，并换算为
 * `totalTimeMs = perCandidateTimeMs × candidateCount`，仍然只运行一个引擎搜索。
 * 深度模式只设置 [maxDepth]，不偷偷附加时间上限。
 */
data class AnalysisBudget(
    val maxDepth: Int?,
    val totalTimeMs: Int,
    val candidateCount: Int = ThinkingOptions.DEFAULT_CANDIDATE_COUNT,
    val perCandidateTimeMs: Int? = null,
) {
    init {
        require(maxDepth != null || totalTimeMs > 0) { "depth or total time is required" }
        require(totalTimeMs >= 0) { "total time cannot be negative" }
        require(candidateCount in ThinkingOptions.MIN_CANDIDATE_COUNT..ThinkingOptions.MAX_CANDIDATE_COUNT) {
            "candidate count out of range"
        }
        require(perCandidateTimeMs == null || perCandidateTimeMs > 0) {
            "per-candidate time must be positive"
        }
    }

    fun normalized(): AnalysisBudget {
        val candidates = candidateCount.coerceIn(
            ThinkingOptions.MIN_CANDIDATE_COUNT,
            ThinkingOptions.MAX_CANDIDATE_COUNT,
        )
        val each = perCandidateTimeMs?.coerceIn(
            ThinkingOptions.MIN_TIME_MS,
            ThinkingOptions.MAX_TIME_MS,
        )
        val effectiveTotal = if (each != null) {
            ThinkingOptions.effectivePerCandidateTotal(each, candidates)
        } else {
            totalTimeMs.coerceIn(0, ThinkingOptions.MAX_TIME_MS)
        }
        return copy(
            maxDepth = maxDepth?.coerceIn(1, AssistDepth.MAX),
            totalTimeMs = effectiveTotal,
            candidateCount = candidates,
            perCandidateTimeMs = each,
        )
    }

    /** 生成唯一一条 UCI go 命令；多候选不会生成多条命令。 */
    fun goCommand(): String = buildString {
        append("go")
        maxDepth?.let { append(" depth ").append(it) }
        if (totalTimeMs > 0) append(" movetime ").append(totalTimeMs)
    }

    companion object {
        fun forDepth(depth: Int, candidates: Int = 3): AnalysisBudget =
            AnalysisBudget(AssistDepth.clamp(depth), 0, candidates)

        fun forTotalTime(ms: Int, candidates: Int = 3): AnalysisBudget =
            AnalysisBudget(
                maxDepth = null,
                totalTimeMs = ms.coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS),
                candidateCount = candidates,
            )

        fun forPerCandidateTime(ms: Int, candidates: Int = 3): AnalysisBudget {
            val each = ms.coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS)
            return AnalysisBudget(
                maxDepth = null,
                totalTimeMs = ThinkingOptions.effectivePerCandidateTotal(each, candidates),
                candidateCount = candidates,
                perCandidateTimeMs = each,
            )
        }
    }
}

/** 候选浏览到最后即停住；实际执行始终采用 MultiPV 排名第一的路线。 */
object CandidatePolicy {
    fun nextDisplayed(current: Int, available: Int): Int = when {
        available <= 1 -> 0
        else -> (current.coerceIn(0, available - 1) + 1).coerceAtMost(available - 1)
    }

    const val DEFAULT_COUNT = 3

    fun bestLine(lines: List<AnalysisLine>): AnalysisLine? =
        lines.minByOrNull { it.multiPv }
}
