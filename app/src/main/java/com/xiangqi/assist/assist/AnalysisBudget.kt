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
    /**
     * Temporary equalized budget used only by the one-candidate variation channel.
     * When set, [totalTimeMs] is the per-candidate budget multiplied by [candidateCount],
     * and the extended total-time ceiling is used instead of the normal single-search ceiling.
     */
    val equalizedCandidateTimeMs: Int? = null,
) {
    init {
        require(maxDepth != null || totalTimeMs > 0) { "depth or total time is required" }
        require(totalTimeMs >= 0) { "total time cannot be negative" }
        require(candidateCount > 0) {
            "candidate count must be positive"
        }
        require(perCandidateTimeMs == null || perCandidateTimeMs > 0) {
            "per-candidate time must be positive"
        }
        require(equalizedCandidateTimeMs == null || equalizedCandidateTimeMs > 0) {
            "equalized candidate time must be positive"
        }
        require(perCandidateTimeMs == null || equalizedCandidateTimeMs == null) {
            "per-candidate and equalized budgets are mutually exclusive"
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
        val equalizedEach = equalizedCandidateTimeMs?.coerceIn(
            ThinkingOptions.MIN_TIME_MS,
            ThinkingOptions.MAX_TIME_MS,
        )
        val effectiveTotal = when {
            each != null -> ThinkingOptions.effectivePerCandidateTotal(each, candidates)
            equalizedEach != null -> (equalizedEach.toLong() * candidates.toLong())
                .coerceAtMost(ThinkingOptions.MAX_EFFECTIVE_TIME_MS.toLong())
                .toInt()
            else -> totalTimeMs.coerceIn(0, ThinkingOptions.MAX_TIME_MS)
        }
        return copy(
            maxDepth = maxDepth?.coerceIn(1, AssistDepth.MAX),
            totalTimeMs = effectiveTotal,
            candidateCount = candidates,
            perCandidateTimeMs = each,
            equalizedCandidateTimeMs = equalizedEach,
        )
    }

    /** 生成唯一一条 UCI go 命令；多候选不会生成多条命令。 */
    fun goCommand(): String = buildString {
        append("go")
        maxDepth?.let { append(" depth ").append(it) }
        if (totalTimeMs > 0) append(" movetime ").append(totalTimeMs)
    }

    /** Create the visible label from this exact command budget; never reread mutable settings. */
    fun statusLabel(): String = when {
        maxDepth != null -> "深度 $maxDepth · ${candidateCount}条共享"
        equalizedCandidateTimeMs != null ->
            "${candidateCount}条 · 每候选 ${ThinkingOptions.formatTime(equalizedCandidateTimeMs.toLong())}" +
                "（总计 ${ThinkingOptions.formatTime(totalTimeMs.toLong())}）"
        perCandidateTimeMs != null ->
            "${candidateCount}条 · 每候选 ${ThinkingOptions.formatTime(perCandidateTimeMs.toLong())}" +
                "（总计 ${ThinkingOptions.formatTime(totalTimeMs.toLong())}）"
        else -> "${candidateCount}条共享总时 ${ThinkingOptions.formatTime(totalTimeMs.toLong())}"
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

        /**
         * Temporary variation path: each candidate receives the original single-candidate
         * time, so the one MultiPV search receives that time multiplied by the candidate count.
         */
        fun forEqualizedTotalTime(perCandidateMs: Int, candidates: Int = 2): AnalysisBudget {
            val each = perCandidateMs.coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS)
            val count = candidates.coerceIn(
                ThinkingOptions.MIN_CANDIDATE_COUNT,
                ThinkingOptions.MAX_CANDIDATE_COUNT,
            )
            return AnalysisBudget(
                maxDepth = null,
                totalTimeMs = (each.toLong() * count.toLong())
                    .coerceAtMost(ThinkingOptions.MAX_EFFECTIVE_TIME_MS.toLong())
                    .toInt(),
                candidateCount = count,
                equalizedCandidateTimeMs = each,
            )
        }

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
