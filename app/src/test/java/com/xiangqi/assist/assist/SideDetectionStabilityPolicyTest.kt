package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideDetectionStabilityPolicyTest {

    @Test fun `first observation keeps current side`() {
        val decision = SideDetectionStabilityPolicy.observe(
            SideDetectionStabilityPolicy.State(), detected = true, current = false,
        )
        assertEquals(false, decision.red)
        assertFalse(decision.changed)
        assertTrue(decision.observed)
        assertEquals(1, decision.state.streak)
    }

    @Test fun `side flips only after required consecutive frames`() {
        var state = SideDetectionStabilityPolicy.State()
        var flipped = false
        repeat(SideDetectionStabilityPolicy.REQUIRED_CONSECUTIVE_FRAMES) { index ->
            val decision = SideDetectionStabilityPolicy.observe(state, detected = true, current = false)
            state = decision.state
            if (decision.changed) flipped = true
            if (index < SideDetectionStabilityPolicy.REQUIRED_CONSECUTIVE_FRAMES - 1) {
                assertFalse("第 ${index + 1} 帧不应换边", decision.changed)
            }
        }
        assertTrue(flipped)
        assertEquals(0, state.streak)
    }

    @Test fun `agreeing frame clears the streak`() {
        var state = SideDetectionStabilityPolicy.State()
        state = SideDetectionStabilityPolicy.observe(state, detected = true, current = false).state
        state = SideDetectionStabilityPolicy.observe(state, detected = true, current = false).state
        assertEquals(2, state.streak)
        state = SideDetectionStabilityPolicy.observe(state, detected = false, current = false).state
        assertEquals(0, state.streak)
    }

    @Test fun `unreadable king keeps current side and keeps the streak`() {
        var state = SideDetectionStabilityPolicy.State()
        state = SideDetectionStabilityPolicy.observe(state, detected = true, current = false).state
        val decision = SideDetectionStabilityPolicy.observe(state, detected = null, current = false)
        assertEquals(null, decision.red)
        assertFalse(decision.changed)
        assertFalse(decision.observed)
        assertEquals(1, decision.state.streak)
    }

    @Test fun `manual mode never observes`() {
        // 手动模式调用方不会调用本策略；这里固化“未检测就不改动”的契约。
        val decision = SideDetectionStabilityPolicy.observe(
            SideDetectionStabilityPolicy.State(), detected = null, current = true,
        )
        assertEquals(null, decision.red)
        assertFalse(decision.observed)
    }
}
