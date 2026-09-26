package com.xiangqi.assist.assist

/**
 * 蓝色候选箭头的“起点预选”、以及绿色最终箭头的落子方式（纯 JVM，可单测）。
 *
 * 产品行为：
 * - 引擎每输出一条新的候选主变（蓝色箭头），就异步点按该着法的起点一次，
 *   让棋盘进入选中状态；
 * - 当最终结果确定（绿色箭头），若起点已经被正确预选并且手势已经完成，
 *   就只补一下终点，避免再点一次起点把选中取消；
 * - 否则回退到完整的“起点 → 终点”手势。
 *
 * 硬规则（任何一条不满足都不产生触摸副作用）：
 * 1. 自动走子关闭、无障碍通道未连接、手动/指导模式 → [PreviewDecision.NO_OP]；
 * 2. 落子事务进行中 → 不预选；
 * 3. 同一棋面 + 同一分析会话 + 同一 UCCI 起点不重复预选；
 * 4. 棋面或分析会话变化 → 旧预选状态立即失效；
 * 5. 预选手势尚未完成时，最终落子先等它结束，绝不叠加两个手势。
 */
object CandidatePreviewPolicy {

    /**
     * 起点点击切换到另一个预选目标时，两次点击之间的随机间隔范围。
     * 首次预选没有前一点击，不等待；每次实际派发后抽取下一次切换的间隔。
     */
    const val MIN_PREVIEW_SWITCH_GAP_MS = 1_250L
    const val MAX_PREVIEW_SWITCH_GAP_MS = 2_500L

    fun randomizedPreviewSwitchGapMs(random: java.util.Random): Long {
        val choices = (MAX_PREVIEW_SWITCH_GAP_MS - MIN_PREVIEW_SWITCH_GAP_MS + 1L).toInt()
        return MIN_PREVIEW_SWITCH_GAP_MS + random.nextInt(choices).toLong()
    }

    /**
     * 距上一次真正派发选中点击是否已满足该次抽取的切换间隔。
     * @param lastDispatchAt 上次派发时刻；Long.MIN_VALUE 表示还没有前一点击。
     * @param requiredGapMs 上一次派发后抽取的 1250–2500ms 间隔。
     */
    fun gapSatisfied(
        now: Long,
        lastDispatchAt: Long,
        requiredGapMs: Long = MIN_PREVIEW_SWITCH_GAP_MS,
    ): Boolean = lastDispatchAt == Long.MIN_VALUE ||
        now - lastDispatchAt >= requiredGapMs.coerceIn(
            MIN_PREVIEW_SWITCH_GAP_MS,
            MAX_PREVIEW_SWITCH_GAP_MS,
        )

    enum class PreviewDecision {
        /** 不产生任何触摸动作 */
        NO_OP,
        /** 需要预选该 UCCI 的起点 */
        DISPATCH_ORIGIN_PREVIEW,
        /** 该起点已经预选过，去重后不再重复点击 */
        ALREADY_SELECTED,
    }

    enum class FinalDecision {
        /** 走完整的“起点 → 终点”手势 */
        FULL_MOVE,
        /** 起点已预选完成，只需补一下终点 */
        REUSE_SELECTED_ORIGIN,
        /** 预选手势还在进行，等它结束再决定 */
        WAIT_SELECTION,
    }

    data class PreviewInput(
        /** `WorkModes.autoCanRun(...)`：自动走子开着、通道连着、模式允许 */
        val autoCanRun: Boolean,
        val landingInProgress: Boolean,
        val currentFen: String,
        val analysisId: Long,
        val ucci: String?,
        /** 起点能否换算成屏幕坐标、并且确实是我方棋子 */
        val originPointValid: Boolean,
        val selectedFen: String?,
        val selectedAnalysisId: Long,
        val selectedOrigin: String?,
        val selectionGestureCompleted: Boolean,
        val selectionAttempted: Boolean = false,
    )

    data class FinalInput(
        val currentFen: String,
        val analysisId: Long,
        val finalUcci: String,
        val selectedFen: String?,
        val selectedAnalysisId: Long,
        val selectedOrigin: String?,
        val selectionGestureCompleted: Boolean,
        val selectionAttempted: Boolean = false,
    )

    /** UCCI 前两位＝起点格，例如 "h2e2" → "h2" */
    fun originOf(ucci: String): String = ucci.take(2)

    fun decidePreview(input: PreviewInput): PreviewDecision {
        if (!input.autoCanRun) return PreviewDecision.NO_OP
        if (input.landingInProgress) return PreviewDecision.NO_OP
        if (input.currentFen.isEmpty()) return PreviewDecision.NO_OP
        val ucci = input.ucci ?: return PreviewDecision.NO_OP
        if (ucci.length < 4) return PreviewDecision.NO_OP
        if (!input.originPointValid) return PreviewDecision.NO_OP
        val sameContext = input.selectedFen == input.currentFen &&
            input.selectedAnalysisId == input.analysisId
        if (sameContext && input.selectedOrigin != null) {
            // 同一预选起点不重复点；候选切换到另一枚棋子时允许发起选中目标切换，
            // 两次实际点击的随机间隔由服务层限流。
            if (input.selectedOrigin == originOf(ucci)) return PreviewDecision.ALREADY_SELECTED
        }
        return PreviewDecision.DISPATCH_ORIGIN_PREVIEW
    }

    fun decideFinal(input: FinalInput): FinalDecision {
        if (input.finalUcci.length < 4) return FinalDecision.FULL_MOVE
        val sameContext = input.selectedFen == input.currentFen &&
            input.selectedAnalysisId == input.analysisId &&
            input.selectedOrigin == originOf(input.finalUcci)
        if (!sameContext) return FinalDecision.FULL_MOVE
        return if (input.selectionGestureCompleted) {
            FinalDecision.REUSE_SELECTED_ORIGIN
        } else if (input.selectionAttempted) {
            FinalDecision.FULL_MOVE
        } else {
            FinalDecision.WAIT_SELECTION
        }
    }

    /** 只有匹配的已完成预选、仍是原无障碍实例且存在快照时，才可只点终点。 */
    fun shouldReuseSelectedOrigin(
        decision: FinalDecision,
        requireAutoSwitch: Boolean,
        snapshotMatches: Boolean,
        sameAccessibilityInstance: Boolean,
    ): Boolean = requireAutoSwitch &&
        decision == FinalDecision.REUSE_SELECTED_ORIGIN &&
        snapshotMatches &&
        sameAccessibilityInstance


}

/**
 * 预选状态（纯 JVM）。只有服务层会写它；渲染函数只读。
 *
 * [requestToken] 用来让迟到的无障碍手势回调失效：旧 token 的完成回调不能把
 * 新一轮的预选标记成“已完成”。
 */
class CandidatePreviewState {

    var fen: String = ""
        private set
    var analysisId: Long = -1L
        private set
    var origin: String? = null
        private set
    var gestureCompleted: Boolean = false
        private set
    var requestToken: Long = 0L
        private set
    var selectionAttempted: Boolean = false
        private set

    /** 棋面或分析会话变化时清空全部预选状态。 */
    fun onContext(fen: String, analysisId: Long) {
        if (fen == this.fen && analysisId == this.analysisId) return
        this.fen = fen
        this.analysisId = analysisId
        origin = null
        gestureCompleted = false
        selectionAttempted = false
        requestToken++
    }

    /** 记录已派发一次起点预选，返回本次手势的 token。 */
    fun markDispatched(origin: String): Long {
        this.origin = origin
        gestureCompleted = false
        selectionAttempted = true
        return ++requestToken
    }

    /** 无障碍手势回调：只有当前 token 才能改变完成状态。 */
    fun markCompleted(token: Long, completed: Boolean) {
        if (token != requestToken) return
        gestureCompleted = completed
        if (!completed) origin = null
    }

    /** 落子事务开始 / 模式切换 / 暂停 / 重置：彻底作废。 */
    fun clear() {
        origin = null
        gestureCompleted = false
        selectionAttempted = false
        requestToken++
    }

    fun snapshot(): Snapshot = Snapshot(fen, analysisId, origin, gestureCompleted, selectionAttempted, requestToken)

    data class Snapshot(
        val fen: String,
        val analysisId: Long,
        val origin: String?,
        val gestureCompleted: Boolean,
        val selectionAttempted: Boolean,
        val requestToken: Long,
    )
}
