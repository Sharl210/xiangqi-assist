package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandingVerificationPolicyTest {
    private val pre = AssistBoard.canonicalStart()
    private val preFen = AssistBoard.toFen(pre, redGo = false)
    private val move = "b9c7"

    private fun state(): LandingFlow.State =
        LandingFlow.begin(1, move, preFen, pre, 100L, preRedGo = false)

    @Test
    fun `exact expected board confirms our move`() {
        val s = state()
        val expected = s.expectedPostBoard
        assertNotNull(expected)
        assertEquals(
            LandingVerificationPolicy.Verdict.LANDED,
            LandingVerificationPolicy.evaluate(
                LandingVerificationPolicy.Input(pre, expected, expected, move, preRedGo = false)
            )
        )
    }

    @Test
    fun `expected board followed by opponent legal move confirms both turns`() {
        val s = state()
        val expected = s.expectedPostBoard!!
        val afterOurMoveFen = AssistBoard.toFen(expected, redGo = true)
        val afterOpponentFen = AssistBoard.applyUcci(afterOurMoveFen, "a3a4")
        val observed = AssistBoard.piecesFromFen(afterOpponentFen)
        assertEquals(
            LandingVerificationPolicy.Verdict.LANDED_AND_OPPONENT_MOVED,
            LandingVerificationPolicy.evaluate(
                LandingVerificationPolicy.Input(pre, expected, observed, move, preRedGo = false)
            )
        )
        assertTrue(AssistBoard.isLegalSingleMove(expected, observed))
    }

    @Test
    fun `tolerance cannot turn the pre-move frame into a landed result`() {
        val s = state()
        assertEquals(
            LandingVerificationPolicy.Verdict.WAITING,
            LandingVerificationPolicy.evaluate(
                LandingVerificationPolicy.Input(
                    pre, s.expectedPostBoard, pre, move, preRedGo = false, tolerance = 2
                )
            )
        )
    }

    @Test
    fun `unchanged pre board does not confirm landing`() {
        val s = state()
        assertEquals(
            LandingVerificationPolicy.Verdict.WAITING,
            LandingVerificationPolicy.evaluate(
                LandingVerificationPolicy.Input(pre, s.expectedPostBoard, pre, move, preRedGo = false)
            )
        )
    }

}
