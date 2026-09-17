package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归“启动即核对 / 旧回调把新流程拉进核对 / 旧帧冒充回执”。 */
class LandingFlowTest {
    private fun board(seed: Int = 1): Array<IntArray> =
        Array(10) { y -> IntArray(9) { x -> if (x == 0 && y == 0) seed else 0 } }

    @Test
    fun `without a transaction verification is impossible`() {
        assertFalse(LandingFlow.canVerify(null))
        assertFalse(LandingFlow.isPostGestureFrame(null, 10_000L))
    }

    @Test
    fun `begin is dispatching not verifying`() {
        val s = LandingFlow.begin(1, "b0a2", "fen", board(), 100)
        assertEquals(LandingFlow.Stage.DISPATCHING, s.stage)
        assertFalse(LandingFlow.canVerify(s))
    }

    @Test
    fun `only matching completion enters verification`() {
        val s = LandingFlow.begin(7, "b0a2", "fen", board(), 100)
        assertNull(LandingFlow.complete(s, token = 6, now = 200))
        val done = LandingFlow.complete(s, token = 7, now = 200)
        assertNotNull(done)
        assertEquals(LandingFlow.Stage.VERIFYING, done!!.stage)
        assertTrue(LandingFlow.canVerify(done))
    }

    @Test
    fun `late completion from old move cannot mutate new move`() {
        val newer = LandingFlow.begin(9, "h0g2", "fen2", board(2), 300)
        assertNull(LandingFlow.complete(newer, token = 8, now = 400))
        assertEquals(LandingFlow.Stage.DISPATCHING, newer.stage)
    }

    @Test
    fun `only a frame captured after gesture completion is evidence`() {
        val start = LandingFlow.begin(1, "b0a2", "fen", board(), 100)
        val done = LandingFlow.complete(start, 1, 500)!!
        assertFalse(LandingFlow.isPostGestureFrame(done, 499))
        assertFalse(LandingFlow.isPostGestureFrame(done, 500))
        assertTrue(LandingFlow.isPostGestureFrame(done, 501))
    }

    @Test
    fun `pre board is copied and cannot drift with caller`() {
        val original = board(1)
        val s = LandingFlow.begin(1, "b0a2", "fen", original, 100)
        original[0][0] = 99
        assertEquals(1, s.preBoard[0][0])
    }
}
