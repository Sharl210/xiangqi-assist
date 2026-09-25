package com.xiangqi.assist.assist

/**
 * 落子核对超时后的首张稳定棋面如何重新确定走子方。
 *
 * 仅当新画面与落子前局面、预期落子后局面完全一致，或能证明是预期局面的一个合法
 * 对手应手时，才允许重建基线。无法建立关系的观测必须拒绝，不能只凭“棋面本身看起来合法”
 * 就把漏识别/漂移结果提升为可信局面。
 */
object LandingRebasePolicy {
    enum class Relation {
        PRE_MOVE,
        EXPECTED_POST_MOVE,
        OPPONENT_REPLY,
        UNRELATED,
    }

    data class Resolution(
        val relation: Relation,
        /** 红方是否走；UNRELATED 时为 null，表示不得提交该棋面。 */
        val redGo: Boolean?,
    ) {
        val accepted: Boolean get() = redGo != null
    }

    /**
     * 根据事务快照判定重建棋面关系。
     *
     * 我方落子未生效时保持 [preRedGo]；预期落子后轮到另一方；预期局面之后仅接受
     * 轮到的对手完成的一步合法棋，其后恢复 [preRedGo]。跳帧、多次未知走子和残缺观测
     * 返回 UNRELATED，由调用方保持原基线并等待重新识别。
     */
    fun resolve(
        observed: Array<IntArray>,
        preBoard: Array<IntArray>?,
        expectedPostBoard: Array<IntArray>?,
        preRedGo: Boolean,
    ): Resolution {
        if (preBoard != null && AssistBoard.equal(observed, preBoard)) {
            return Resolution(Relation.PRE_MOVE, preRedGo)
        }
        if (expectedPostBoard != null && AssistBoard.equal(observed, expectedPostBoard)) {
            return Resolution(Relation.EXPECTED_POST_MOVE, !preRedGo)
        }
        if (expectedPostBoard != null && AssistBoard.isLegalSingleMove(expectedPostBoard, observed)) {
            val movedSide = AssistBoard.movedSide(expectedPostBoard, observed)
            val expectedMover = if (!preRedGo) 1 else 0
            if (movedSide == expectedMover) {
                return Resolution(Relation.OPPONENT_REPLY, preRedGo)
            }
        }
        return Resolution(Relation.UNRELATED, null)
    }
}
