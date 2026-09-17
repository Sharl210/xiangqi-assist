package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStabilityPolicyTest {
    private fun frame(color: Int): Frame = Frame(8, 8, IntArray(64) { 0xFF000000.toInt() or color })

    @Test
    fun `sampling cadence is four frames per second`() {
        assertEquals(4, FrameStabilityPolicy.SAMPLE_FPS)
        assertEquals(250L, FrameStabilityPolicy.SAMPLE_PERIOD_MS)
        assertEquals(8, FrameStabilityPolicy.REQUIRED_STABLE_FRAMES)
        assertTrue(FrameStabilityPolicy.shouldSample(1000L, Long.MIN_VALUE))
        assertFalse(FrameStabilityPolicy.shouldSample(1249L, 1000L))
        assertTrue(FrameStabilityPolicy.shouldSample(1250L, 1000L))
    }

    @Test
    fun `static stream is stable and changed stream is rejected`() {
        val a = FrameStabilityPolicy.signature(frame(0x112233))
        val b = FrameStabilityPolicy.signature(frame(0x112233))
        val c = FrameStabilityPolicy.signature(frame(0xFFFFFF))
        assertTrue(FrameStabilityPolicy.isStable(a, b))
        assertFalse(FrameStabilityPolicy.isStable(a, c))
        assertTrue(FrameStabilityPolicy.changedSampleFraction(a, b) == 0.0)
        assertEquals(0.0, FrameStabilityPolicy.meanChannelDelta(a, b), 0.000001)
    }

    @Test
    fun `clearer frame receives a different clarity score`() {
        val flat = frame(0x808080)
        val edgePixels = IntArray(64) { i -> if (i % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val edge = Frame(8, 8, edgePixels)
        assertTrue(FrameStabilityPolicy.clarityScore(edge) > FrameStabilityPolicy.clarityScore(flat))
    }
}
