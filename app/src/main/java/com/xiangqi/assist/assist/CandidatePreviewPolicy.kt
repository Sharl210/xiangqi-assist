package com.xiangqi.assist.assist

/**
 * 蓝色候选箭头的“起点预选”、以及绿色最终箭头的落子方式（纯 JVM，可单测）。
 *
 * 产品行为：
 * - 引擎每输出一条新的候选主变（蓝色箭头），就**异步按住**该着法的起点一次，
 *   让棋盘出现“把棋子拿起来”的动效，更像真人思考；
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
        if (sameContext && input.selectedOrigin != null &&
            (input.selectionAttempted || input.selectedOrigin != null)) {
            // 一次分析会话只预选一次。迭代加深时 PV 可能改变起点，但那不是用户选择了
            // 新候选；继续换点会在棋盘上反复选不同棋子。若最终起点不同，正式落子流程
            // 会清理旧预选并回退完整起点→终点。
            return PreviewDecision.ALREADY_SELECTED
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

    fun snapshot(): Snapshot = Snapshot(fen, analysisId, origin, gestureCompleted, selectionAttempted)

    data class Snapshot(
        val fen: String,
        val analysisId: Long,
        val origin: String?,
        val gestureCompleted: Boolean,
        val selectionAttempted: Boolean,
    )
}
