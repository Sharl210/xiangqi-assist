package com.xiangqi.assist.assist

/**
 * 自动落子的事务状态（纯 JVM，可单测）。
 *
 * 一次落子只有两种进行态：
 * 1. [Stage.DISPATCHING]：准备并派发无障碍手势；
 * 2. [Stage.VERIFYING]：手势已被系统接受并结束，等待**之后取得的新画面**。
 *
 * 没有 [State] 就没有落子事务，因此绝不能显示“落子中/核对中”。每次事务都有唯一
 * [State.token]，旧回调即使迟到也不能修改新事务，从根上消除“没落子却直接核对”。
 */
object LandingFlow {
    enum class Stage { DISPATCHING, VERIFYING }

    data class State(
        val token: Long,
        val ucci: String,
        val fen: String,
        val preBoard: Array<IntArray>,
        val stage: Stage,
        val startedAt: Long,
        val preRedGo: Boolean = true,
        val expectedPostBoard: Array<IntArray>? = null,
        val completedAt: Long = 0L,
    )

    fun begin(
        token: Long,
        ucci: String,
        fen: String,
        preBoard: Array<IntArray>,
        now: Long,
        preRedGo: Boolean = true,
    ): State {
        val expectedFen = AssistBoard.applyUcci(fen, ucci)
        val expectedBoard = expectedFen.takeIf { it != fen }?.let { AssistBoard.piecesFromFen(it) }
        return State(
            token = token,
            ucci = ucci,
            fen = fen,
            preBoard = AssistBoard.clone(preBoard),
            stage = Stage.DISPATCHING,
            startedAt = now,
            preRedGo = preRedGo,
            expectedPostBoard = expectedBoard?.let { AssistBoard.clone(it) },
        )
    }

    /**
     * 只有同一事务的手势完成回调才能进入核对；旧 token 或重复完成均返回 null。
     */
    fun complete(state: State?, token: Long, now: Long): State? {
        if (state == null || state.token != token || state.stage != Stage.DISPATCHING) return null
        return state.copy(stage = Stage.VERIFYING, completedAt = now)
    }

    fun isCurrent(state: State?, token: Long): Boolean = state?.token == token

    /** 核对必须绑定真实事务、完成时刻和落子前快照。 */
    fun canVerify(state: State?): Boolean =
        state != null && state.stage == Stage.VERIFYING && state.completedAt > 0L

    /** 只接受手势完成以后拍到的画面；旧帧不能充当落子回执。 */
    fun isPostGestureFrame(state: State?, frameAt: Long): Boolean =
        canVerify(state) && frameAt > state!!.completedAt
}
