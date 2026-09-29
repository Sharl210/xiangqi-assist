package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionThresholdRecoveryPolicyTest {
    @Test fun `threshold stays strict for first nine misses`() {
        var state = DetectionThresholdRecoveryPolicy.State()
        repeat(9) { state = DetectionThresholdRecoveryPolicy.onRejected(state) }
        assertEquals(0, state.level)
        assertEquals(DetectionThresholdRecoveryPolicy.BASE_THRESHOLD,
            DetectionThresholdRecoveryPolicy.threshold(state), 0.0001)
    }

    @Test fun `threshold drops only on each tenth miss and decay is exponential`() {
        var state = DetectionThresholdRecoveryPolicy.State()
        repeat(10) { state = DetectionThresholdRecoveryPolicy.onRejected(state) }
        val first = DetectionThresholdRecoveryPolicy.threshold(state)
        assertEquals(1, state.level)
        repeat(10) { state = DetectionThresholdRecoveryPolicy.onRejected(state) }
        val second = DetectionThresholdRecoveryPolicy.threshold(state)
        assertEquals(2, state.level)
        assertTrue(second < first)
        assertEquals(DetectionThresholdRecoveryPolicy.BASE_THRESHOLD *
            DetectionThresholdRecoveryPolicy.STEP_FACTOR * DetectionThresholdRecoveryPolicy.STEP_FACTOR,
            second, 0.0001)
    }

    @Test fun `accepted board resets misses and level`() {
        var state = DetectionThresholdRecoveryPolicy.State()
        repeat(30) { state = DetectionThresholdRecoveryPolicy.onRejected(state) }
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

