package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BoardCompletenessPolicyTest {
    @Test
    fun `unchanged and legal single move boards pass`() {
        val start = AssistBoard.canonicalStart()
        assertNull(BoardCompletenessPolicy.rejectionReason(start, AssistBoard.clone(start)))

        val moved = AssistBoard.clone(start)
        moved[5][0] = moved[6][0]
        moved[6][0] = Piece.EMPTY
        assertNull(BoardCompletenessPolicy.rejectionReason(start, moved))
    }

    @Test
    fun `single missing rook is rejected instead of sent to engine`() {
        val before = AssistBoard.canonicalStart()
        val missing = AssistBoard.clone(before)
        missing[0][0] = Piece.EMPTY
        assertNotNull(BoardCompletenessPolicy.rejectionReason(before, missing))
    }

    @Test
    fun `runtime log regression from 27 to 13 pieces is rejected`() {
        val previous = AssistBoard.piecesFromFen(
            "3akab2/9/2R1b1nR1/p3p1p1p/2p6/4P1P2/2P5P/4C1N1C/1r7/c1BAKAB2 w - - 0 1"
        )
        val collapsed = AssistBoard.piecesFromFen(
            "9/5R3/3k2R2/p3p4/6P2/9/2p6/2N6/9/2BAKAB2 w - - 0 1"
        )
        assertNotNull(BoardCompletenessPolicy.rejectionReason(previous, collapsed))
    }

    @Test
    fun `severe collapse is rejected even when almost all pieces disappeared`() {
        val before = AssistBoard.canonicalStart()
        val collapsed = Array(10) { IntArray(9) }
        collapsed[9][4] = Piece.WSHUAI
        collapsed[0][4] = Piece.BJIANG
        assertNotNull(BoardCompletenessPolicy.rejectionReason(before, collapsed))
    }

    @Test
    fun `radically different sparse position is not rejected as a retained subset`() {
        val before = AssistBoard.canonicalStart()
        val newPosition = Array(10) { IntArray(9) }
        // 只有两王和一车，且没有沿用旧盘面中的同格棋子；这是换盘/多步后的新基线，
        // 不能被“少子”规则误判成当前盘面只是漏检。
        newPosition[9][3] = Piece.WSHUAI
        newPosition[0][5] = Piece.BJIANG
        newPosition[5][8] = Piece.WJU
        assertNull(BoardCompletenessPolicy.rejectionReason(before, newPosition))
    }

    @Test
    fun `first baseline does not invent or reject pieces`() {
        val sparse = AssistBoard.piecesFromFen("4k4/9/9/9/9/9/9/9/9/4K4 w - - 0 1")
        assertNull(BoardCompletenessPolicy.rejectionReason(null, sparse))
    }
}
