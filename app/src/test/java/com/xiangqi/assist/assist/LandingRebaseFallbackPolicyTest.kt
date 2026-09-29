package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandingRebaseFallbackPolicyTest {
    private fun safeBoard(): Array<IntArray> = AssistBoard.canonicalStart()

    @Test
    fun `unrelated board needs three identical observations before fallback`() {
        val board = safeBoard()
        var state = LandingRebaseFallbackPolicy.State()
        repeat(2) {
            val result = LandingRebaseFallbackPolicy.observe(state, board, preRedGo = true)
            state = result.state
            assertFalse(result.accepted)
        }
        val result = LandingRebaseFallbackPolicy.observe(state, board, preRedGo = true)
        assertTrue(result.accepted)
        assertTrue(result.state.streak >= 3)
    }

    @Test
    fun `changed observation restarts the fallback streak`() {
        val first = safeBoard()
        val second = AssistBoard.clone(first).also { it[7][0] = Piece.EMPTY; it[6][0] = Piece.WJU }
        var state = LandingRebaseFallbackPolicy.State()
        state = LandingRebaseFallbackPolicy.observe(state, first, true).state
        state = LandingRebaseFallbackPolicy.observe(state, first, true).state
        val result = LandingRebaseFallbackPolicy.observe(state, second, true)
        assertFalse(result.accepted)
        assertTrue(result.state.streak == 1)
    }
}
