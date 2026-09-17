package com.xiangqi.assist.assist

/**
 * 引擎分析结果模型。
 *
 * **视角约定（重要）**：
 * - UCI 协议里 `score` 给的是**当前走子方**视角的分数：轮到黑走时，红方占优会报成负数。
 * - 本模块在解析时统一换算成**红方视角**（红方恒为正），下游展示层只需再按"我方执红/执黑"
 *   翻转一次，就能始终得到**我方**的胜率，不会因为轮到对手走而翻到对面去。
 */
class AnalysisLine(
    val multiPv: Int = 1,
    val depth: Int = 0,
    /** 红方视角分数（centipawn），正数红方有利 */
    val redScoreCp: Int = 0,
    /** 红方视角杀棋：正=红方将杀，负=黑方将杀；null=非杀棋 */
    val mateIn: Int? = null,
    /** 变着（UCCI 4 字母） */
    val pv: List<String> = emptyList(),
    /**
     * 引擎自带的胜负和期望（千分比，**红方视角**），来自 UCI_ShowWDL。
     * 三个值相加为 1000；引擎没给（老版本或未开启）时为 null。
     * 这是引擎 NNUE 直接给出的胜率数据，不是我们换算出来的。
     */
    val redWinPermille: Int? = null,
    val drawPermille: Int? = null,
    val redLossPermille: Int? = null,
) {
    /** 是否有引擎自带的胜负和期望 */
    val hasWdl: Boolean
        get() = redWinPermille != null && drawPermille != null && redLossPermille != null

    /** 显示的分数文本，如 "+2.60"、"M3"、"-M2" */
    fun scoreText(): String {
        val m = mateIn
        if (m != null) return if (m > 0) "M$m" else "-M${-m}"
        return if (redScoreCp >= 0) "+%.2f".format(redScoreCp / 100.0)
        else "%.2f".format(redScoreCp / 100.0)
    }
}

class AnalysisResult(
    val fen: String,
    val redGo: Boolean,
    val bestMove: String?,
    val lines: List<AnalysisLine>,
    /** 关联不可复用的搜索会话；同 FEN 的旧回调不能覆盖新会话。 */
    val analysisId: Long = 0L,
) {
    val bestLine: AnalysisLine? get() = CandidatePolicy.bestLine(lines)

    /** 首个走法优先取同深度候选集合的第一名；仅无 PV 时退回 bestmove。 */
    val bestUcci: String? get() = bestLine?.pv?.firstOrNull() ?: bestMove
}
