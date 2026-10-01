package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStabilityPolicyTest {
    @Test fun `stable window still requires eight samples before model input`() {
        assertEquals(8, FrameStabilityPolicy.REQUIRED_STABLE_FRAMES)
        assertEquals(125L, FrameStabilityPolicy.SAMPLE_PERIOD_MS)
    }

    private fun frame(color: Int): Frame = Frame(8, 8, IntArray(64) { 0xFF000000.toInt() or color })

    @Test fun `sampling cadence is eight fps with eight stable samples`() {
        assertEquals(8, FrameStabilityPolicy.SAMPLE_FPS)
        assertEquals(125L, FrameStabilityPolicy.SAMPLE_PERIOD_MS)
        assertEquals(8, FrameStabilityPolicy.REQUIRED_STABLE_FRAMES)
        assertTrue(FrameStabilityPolicy.shouldSample(1000L, Long.MIN_VALUE))
        assertFalse(FrameStabilityPolicy.shouldSample(1124L, 1000L))
        assertTrue(FrameStabilityPolicy.shouldSample(1125L, 1000L))
    }

    @Test fun `static stream is stable and changed stream is rejected`() {
        val a = FrameStabilityPolicy.signature(frame(0x112233))
        val b = FrameStabilityPolicy.signature(frame(0x112233))
        val c = FrameStabilityPolicy.signature(frame(0xFFFFFF))
        assertTrue(FrameStabilityPolicy.isStable(a, b))
        assertFalse(FrameStabilityPolicy.isStable(a, c))
        assertEquals(0.0, FrameStabilityPolicy.meanChannelDelta(a, b), 0.000001)
    }

    @Test fun `outside changes do not break selected region stability`() {
        val a = Frame(20, 20, IntArray(400) { 0xFF000000.toInt() })
        val outside = IntArray(400) { index ->
            val x = index % 20
            val y = index / 20
            if (x < 5 || x >= 15 || y < 5 || y >= 15) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val b = Frame(20, 20, outside)
        val region = doubleArrayOf(0.25, 0.25, 0.75, 0.75)
        assertTrue(FrameStabilityPolicy.isStable(FrameStabilityPolicy.signature(a, region = region),
            FrameStabilityPolicy.signature(b, region = region)))
    }

    @Test fun `clearer frame receives a different clarity score`() {
        val flat = frame(0x808080)
        val edge = Frame(8, 8, IntArray(64) { i -> if (i % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() })
        assertTrue(FrameStabilityPolicy.clarityScore(edge) > FrameStabilityPolicy.clarityScore(flat))
    }

    @Test fun `idle sampling period is half the active rate`() {
        assertEquals(250L, FrameStabilityPolicy.IDLE_SAMPLE_PERIOD_MS)
        assertFalse(FrameStabilityPolicy.shouldSample(1_249L, 1_000L, FrameStabilityPolicy.IDLE_SAMPLE_PERIOD_MS))
        assertTrue(FrameStabilityPolicy.shouldSample(1_250L, 1_000L, FrameStabilityPolicy.IDLE_SAMPLE_PERIOD_MS))
        // 默认周期（8fps）不受待机参数影响
        assertTrue(FrameStabilityPolicy.shouldSample(1_125L, 1_000L))
    }
}
