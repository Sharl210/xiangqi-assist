package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionThresholdRecoveryPolicyTest {
    @Test fun `threshold stays strict before the first step`() {
        var state = DetectionThresholdRecoveryPolicy.State()
        repeat(DetectionThresholdRecoveryPolicy.MISSES_PER_STEP - 1) {
            state = DetectionThresholdRecoveryPolicy.onRejected(state)
        }
        assertEquals(0, state.level)
        assertEquals(DetectionThresholdRecoveryPolicy.BASE_THRESHOLD,
            DetectionThresholdRecoveryPolicy.threshold(state), 0.0001)
    }

    @Test fun `threshold drops on each step and decay is exponential`() {
        // 用户要求：每三次失败就降一档，指数级下降。
        assertEquals(3, DetectionThresholdRecoveryPolicy.MISSES_PER_STEP)
        var state = DetectionThresholdRecoveryPolicy.State()
        repeat(DetectionThresholdRecoveryPolicy.MISSES_PER_STEP) {
            state = DetectionThresholdRecoveryPolicy.onRejected(state)
        }
        val first = DetectionThresholdRecoveryPolicy.threshold(state)
        assertEquals(1, state.level)
        repeat(DetectionThresholdRecoveryPolicy.MISSES_PER_STEP) {
            state = DetectionThresholdRecoveryPolicy.onRejected(state)
        }
        val second = DetectionThresholdRecoveryPolicy.threshold(state)
        assertEquals(2, state.level)
        assertTrue(second < first)
        assertEquals(DetectionThresholdRecoveryPolicy.BASE_THRESHOLD *
            DetectionThresholdRecoveryPolicy.STEP_FACTOR * DetectionThresholdRecoveryPolicy.STEP_FACTOR,
            second, 0.0001)
    }

    @Test fun `threshold never drops below the floor`() {
        var state = DetectionThresholdRecoveryPolicy.State()
        repeat(DetectionThresholdRecoveryPolicy.MISSES_PER_STEP * 40) {
            state = DetectionThresholdRecoveryPolicy.onRejected(state)
        }
        assertEquals(DetectionThresholdRecoveryPolicy.MAX_LEVEL, state.level)
        assertEquals(DetectionThresholdRecoveryPolicy.MIN_THRESHOLD,
            DetectionThresholdRecoveryPolicy.threshold(state), 0.0001)
    }

    @Test fun `accepted board resets misses and level`() {
        var state = DetectionThresholdRecoveryPolicy.State()
        repeat(DetectionThresholdRecoveryPolicy.MISSES_PER_STEP * 3) {
            state = DetectionThresholdRecoveryPolicy.onRejected(state)
        }
        assertTrue(state.level > 0)
        state = DetectionThresholdRecoveryPolicy.onAccepted()
        assertEquals(0, state.misses)
        assertEquals(0, state.level)
        assertEquals(DetectionThresholdRecoveryPolicy.BASE_THRESHOLD,
            DetectionThresholdRecoveryPolicy.threshold(state), 0.0001)
    }

    @Test fun `new session baseline is represented by the strict default state`() {
        val state = DetectionThresholdRecoveryPolicy.onAccepted()
        assertEquals(0, state.misses)
        assertEquals(0, state.level)
        assertEquals(DetectionThresholdRecoveryPolicy.BASE_THRESHOLD,
            DetectionThresholdRecoveryPolicy.threshold(state), 0.0001)
        assertEquals(0.05,
            DetectionThresholdRecoveryPolicy.margin(state), 0.0001)
    }
}
