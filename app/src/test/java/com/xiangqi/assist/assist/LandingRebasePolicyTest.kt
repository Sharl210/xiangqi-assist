package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandingRebasePolicyTest {
    private val preFen = AssistBoard.START_FEN
    private val pre = AssistBoard.piecesFromFen(preFen)
    private val ourMoveFen = AssistBoard.applyUcci(preFen, "a0a1")
    private val afterOurMove = AssistBoard.piecesFromFen(ourMoveFen)
    private val opponentMoveFen = AssistBoard.applyUcci(ourMoveFen, "a9a8")
    private val afterOpponentMove = AssistBoard.piecesFromFen(opponentMoveFen)

    @Test
    fun `same pre board keeps original side to move`() {
        val result = LandingRebasePolicy.resolve(pre, pre, afterOurMove, preRedGo = true)
        assertEquals(LandingRebasePolicy.Relation.PRE_MOVE, result.relation)
        assertEquals(true, result.redGo)
        assertTrue(result.accepted)
    }

    @Test
    fun `expected post board switches to the other side`() {
        val result = LandingRebasePolicy.resolve(
            observed = afterOurMove,
            preBoard = pre,
            expectedPostBoard = afterOurMove,
            preRedGo = true,
        )
        assertEquals(LandingRebasePolicy.Relation.EXPECTED_POST_MOVE, result.relation)
        assertEquals(false, result.redGo)
        assertTrue(result.accepted)
    }

    @Test
    fun `one legal move by the expected opponent is accepted`() {
        assertTrue(AssistBoard.isLegalSingleMove(afterOurMove, afterOpponentMove))
        val result = LandingRebasePolicy.resolve(
            observed = afterOpponentMove,
            preBoard = pre,
            expectedPostBoard = afterOurMove,
            preRedGo = true,
        )
        assertEquals(LandingRebasePolicy.Relation.OPPONENT_REPLY, result.relation)
        assertEquals(true, result.redGo)
        assertTrue(result.accepted)
    }

    @Test
    fun `legal move by the wrong side is not accepted as an opponent reply`() {
        val secondRedMoveFen = AssistBoard.applyUcci(ourMoveFen, "a1a2")
        val secondRedMove = AssistBoard.piecesFromFen(secondRedMoveFen)
        assertTrue(AssistBoard.isLegalSingleMove(afterOurMove, secondRedMove))
        val result = LandingRebasePolicy.resolve(
            observed = secondRedMove,
            preBoard = pre,
            expectedPostBoard = afterOurMove,
            preRedGo = true,
        )
        assertEquals(LandingRebasePolicy.Relation.UNRELATED, result.relation)
        assertNull(result.redGo)
        assertFalse(result.accepted)
    }

    @Test
    fun `unrelated or malformed board is rejected instead of promoted to a new baseline`() {
        val unrelated = AssistBoard.canonicalStart().also { it[4][4] = 99 }
        val result = LandingRebasePolicy.resolve(
            observed = unrelated,
            preBoard = pre,
            expectedPostBoard = afterOurMove,
            preRedGo = true,
        )
        assertEquals(LandingRebasePolicy.Relation.UNRELATED, result.relation)
        assertNull(result.redGo)
        assertFalse(result.accepted)
    }

    @Test
    fun `multi piece disappearance from a sparse valid board is not explained as a new baseline`() {
        val corrupted = AssistBoard.clone(afterOurMove)
        // 稀疏棋面仍可合法，但两个非王棋子突然缺失不是一次落子后的合法应手。
        corrupted[6][0] = com.xiangqi.assist.gamelogic.Piece.EMPTY
        corrupted[6][2] = com.xiangqi.assist.gamelogic.Piece.EMPTY
        val result = LandingRebasePolicy.resolve(
            observed = corrupted,
            preBoard = pre,
            expectedPostBoard = afterOurMove,
            preRedGo = true,
        )
        assertEquals(LandingRebasePolicy.Relation.UNRELATED, result.relation)
        assertNull(result.redGo)
        assertFalse(result.accepted)
    }

    @Test
    fun `latest field log nonmatching board that is a legal opponent reply is accepted`() {
        val preFen = "3k5/9/8R/9/9/4R4/9/9/4rp2c/4K2C1 w - - 0 1"
        val preBoard = AssistBoard.piecesFromFen(preFen)
        val expected = AssistBoard.piecesFromFen(AssistBoard.applyUcci(preFen, "e4e1"))
        val observed = AssistBoard.piecesFromFen(
            "3k5/9/8R/9/9/9/9/9/4cp3/4K2C1 w - - 0 1"
        )
        val result = LandingRebasePolicy.resolve(
            observed = observed,
            preBoard = preBoard,
            expectedPostBoard = expected,
            preRedGo = true,
        )
        assertEquals(LandingRebasePolicy.Relation.OPPONENT_REPLY, result.relation)
        assertEquals(true, result.redGo)
        assertTrue(result.accepted)
    }

    @Test
    fun `all nonmatching rebase boards in the field log must be legal opponent replies`() {
        val transitions = listOf(
            Triple("3k5/9/8R/9/9/4R4/9/9/4rp2c/4K2C1 w - - 0 1", "e4e1",
                "3k5/9/8R/9/9/9/9/9/4cp3/4K2C1 w - - 0 1"),
            Triple("3k5/9/8R/9/9/9/9/9/4cp3/4K2C1 w - - 0 1", "i7f7",
                "9/3k5/5R3/9/9/9/9/9/4cp3/4K2C1 w - - 0 1"),
            Triple("9/3k5/5R3/9/9/9/9/9/4cp3/4K2C1 w - - 0 1", "f7f1",
                "9/3k5/4c4/9/9/9/9/9/5R3/4K2C1 w - - 0 1"),
            Triple("9/3k5/4c4/9/9/9/9/9/5R3/4K2C1 w - - 0 1", "f1f8",
                "3k5/5R3/4c4/9/9/9/9/9/9/4K2C1 w - - 0 1"),
            Triple("3k5/5R3/4c4/9/9/9/9/9/9/4K2C1 w - - 0 1", "f8f7",
                "3k5/4c4/5R3/9/9/9/9/9/9/4K2C1 w - - 0 1"),
            Triple("3k5/4c4/5R3/9/9/9/9/9/9/4K2C1 w - - 0 1", "f7f9",
                "3kcR3/9/9/9/9/9/9/9/9/4K2C1 w - - 0 1"),
            Triple("3kcR3/9/9/9/9/9/9/9/9/4K2C1 w - - 0 1", "h0f0",
                "4cR3/3k5/9/9/9/9/9/9/9/4KC3 w - - 0 1"),
            Triple("4cR3/3k5/9/9/9/9/9/9/9/4KC3 w - - 0 1", "f9e9",
                "4R4/9/3k5/9/9/9/9/9/9/4KC3 w - - 0 1"),
        )
        transitions.forEachIndexed { index, (preFen, ucci, observedFen) ->
            val preBoard = AssistBoard.piecesFromFen(preFen)
            val expected = AssistBoard.piecesFromFen(AssistBoard.applyUcci(preFen, ucci))
            val observed = AssistBoard.piecesFromFen(observedFen)
            val resolution = LandingRebasePolicy.resolve(observed, preBoard, expected, preRedGo = true)
            assertEquals("log rebase ${index + 1}", LandingRebasePolicy.Relation.OPPONENT_REPLY, resolution.relation)
            assertEquals("log rebase ${index + 1}", true, resolution.redGo)
        }
    }
}
