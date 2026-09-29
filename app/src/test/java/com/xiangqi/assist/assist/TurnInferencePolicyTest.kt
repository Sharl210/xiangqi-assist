package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnInferencePolicyTest {

    private val start = AssistBoard.canonicalStart()

    /** 红马八进七：(x=1,y=9) -> (x=2,y=7)，一步合法着法。 */
    private fun afterRedKnightMove(): Array<IntArray> {
        val board = AssistBoard.clone(start)
        board[7][2] = board[9][1]
        board[9][1] = Piece.EMPTY
        return board
    }

    /** 黑马 8 进 7：(x=1,y=0) -> (x=2,y=2)，一步合法着法。 */
    private fun afterBlackKnightMove(): Array<IntArray> {
        val board = AssistBoard.clone(start)
        board[2][2] = board[0][1]
        board[0][1] = Piece.EMPTY
        return board
    }

    /** 两处棋子同时移动：不可能是单步，属于解释不了的跳变。 */
    private fun multiPieceChange(): Array<IntArray> {
        val board = AssistBoard.clone(start)
        board[5][0] = board[6][0]
        board[6][0] = Piece.EMPTY
        board[5][8] = board[6][8]
        board[6][8] = Piece.EMPTY
        return board
    }

    @Test fun `fresh baseline keeps the incoming turn`() {
        val decision = TurnInferencePolicy.infer(
            previous = null, current = start, currentRedGo = true, mySideRed = true,
        )
        assertEquals(TurnInferencePolicy.Reason.FRESH_BASELINE, decision.reason)
        assertTrue(decision.redGo)
        assertFalse(decision.changed)
    }

    @Test fun `unchanged board keeps the turn`() {
        val decision = TurnInferencePolicy.infer(
            previous = start, current = AssistBoard.clone(start),
            currentRedGo = false, mySideRed = true,
        )
        assertEquals(TurnInferencePolicy.Reason.UNCHANGED, decision.reason)
        assertFalse(decision.redGo)
    }

    @Test fun `legal opponent move hands the turn back to my side`() {
        val decision = TurnInferencePolicy.infer(
            previous = start, current = afterBlackKnightMove(),
            currentRedGo = false, mySideRed = true,
        )
        assertEquals(TurnInferencePolicy.Reason.OPPONENT_STEP, decision.reason)
        assertTrue("对方走完必须轮到我方", decision.redGo)
    }

    @Test fun `our own move without a landing transaction is treated as recognition jitter`() {
        // 自动模式下我方的着法一定由落子事务产生；没有事务的单帧“我方走子”只能是抖动，
        // 绝不能因此把回合推给“对方”。
        val decision = TurnInferencePolicy.infer(
            previous = start, current = afterRedKnightMove(),
            currentRedGo = true, mySideRed = true,
            autoMode = true, ourMoveInFlight = false,
        )
        assertEquals(TurnInferencePolicy.Reason.UNEXPLAINED_HOLD, decision.reason)
        assertTrue("该我方走时必须仍然是我方回合", decision.redGo)
    }

    @Test fun `our own move with a landing transaction hands the turn to the opponent`() {
        val decision = TurnInferencePolicy.infer(
            previous = start, current = afterRedKnightMove(),
            currentRedGo = true, mySideRed = true,
            autoMode = true, ourMoveInFlight = true,
        )
        assertEquals(TurnInferencePolicy.Reason.OUR_STEP, decision.reason)
        assertFalse(decision.redGo)
    }

    @Test fun `manual mode still allows our own move to hand the turn over`() {
        val decision = TurnInferencePolicy.infer(
            previous = start, current = afterRedKnightMove(),
            currentRedGo = true, mySideRed = true,
            autoMode = false, ourMoveInFlight = false,
        )
        assertEquals(TurnInferencePolicy.Reason.OUR_STEP, decision.reason)
        assertFalse(decision.redGo)
    }

    @Test fun `unexplained change while waiting on the opponent returns to my side`() {
        val decision = TurnInferencePolicy.infer(
            previous = start, current = multiPieceChange(),
            currentRedGo = false, mySideRed = true,
        )
        assertEquals(TurnInferencePolicy.Reason.UNEXPLAINED_RECOVER, decision.reason)
        assertTrue("解释不了的变化不得把回合留在“等对方走”", decision.redGo)
    }

    @Test fun `unexplained change on my own turn keeps my side`() {
        val decision = TurnInferencePolicy.infer(
            previous = start, current = multiPieceChange(),
            currentRedGo = true, mySideRed = true,
        )
        assertEquals(TurnInferencePolicy.Reason.UNEXPLAINED_HOLD, decision.reason)
        assertTrue(decision.redGo)
    }

    @Test fun `returning to the standard opening marks a new game with red to move`() {
        val decision = TurnInferencePolicy.infer(
            previous = afterRedKnightMove(), current = start,
            currentRedGo = false, mySideRed = false,
        )
        assertEquals(TurnInferencePolicy.Reason.NEW_GAME, decision.reason)
        assertTrue("新的一局固定红先", decision.redGo)
    }

    @Test fun `near-opening board with missing piece still counts as a new game`() {
        // 连场换局时识别可能漏掉一个兵，严格比对会失败；只要子力回到满盘级别、
        // 双王都在初始位置，就按新局处理（红先），否则换边后会一直等错的一方。
        val nearlyStart = AssistBoard.clone(start)
        nearlyStart[3][0] = Piece.EMPTY
        val decision = TurnInferencePolicy.infer(
            previous = afterRedKnightMove(), current = nearlyStart,
            currentRedGo = false, mySideRed = false,
        )
        assertEquals(TurnInferencePolicy.Reason.NEW_GAME, decision.reason)
        assertTrue(decision.redGo)
    }
}
