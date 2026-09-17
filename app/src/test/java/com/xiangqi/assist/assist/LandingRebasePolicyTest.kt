package com.xiangqi.assist.assist

import org.junit.Assert.assertFalse
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
        assertTrue(
            LandingRebasePolicy.redGoForObserved(
                observed = pre,
                preBoard = pre,
                expectedPostBoard = afterOurMove,
                preRedGo = true,
            )
        )
    }

    @Test
    fun `expected post board switches to the other side`() {
        assertFalse(
            LandingRebasePolicy.redGoForObserved(
                observed = afterOurMove,
                preBoard = pre,
                expectedPostBoard = afterOurMove,
                preRedGo = true,
            )
        )
    }

    @Test
    fun `a subsequent black move returns the turn to red`() {
        assertTrue(
            LandingRebasePolicy.redGoForObserved(
                observed = afterOpponentMove,
                preBoard = pre,
                expectedPostBoard = afterOurMove,
                preRedGo = true,
            )
        )
    }

    @Test
    fun `unknown relation preserves the transaction side without claiming a move`() {
        val unrelated = AssistBoard.canonicalStart()
        unrelated[4][4] = 99
        assertTrue(
            LandingRebasePolicy.redGoForObserved(
                observed = unrelated,
                preBoard = pre,
                expectedPostBoard = afterOurMove,
                preRedGo = true,
            )
        )
    }
}
