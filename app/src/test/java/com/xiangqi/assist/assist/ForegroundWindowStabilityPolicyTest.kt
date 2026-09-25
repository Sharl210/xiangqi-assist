package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWindowStabilityPolicyTest {
    private val owner = "com.xiangqi.assist"

    @Test
    fun `external baseline is committed only after required stable samples`() {
        var state = ForegroundWindowStabilityPolicy.State()
        repeat(7) { index ->
            val result = ForegroundWindowStabilityPolicy.observe(
                owner, state, "com.game", true, requiredStableFrames = 8,
            )
            state = result.state
            assertTrue(result.decision is ForegroundWindowStabilityPolicy.Decision.PENDING)
            assertEquals(index + 1, state.candidateFrames)
        }
        val committed = ForegroundWindowStabilityPolicy.observe(
            owner, state, "com.game", true, requiredStableFrames = 8,
        )
        assertEquals(ForegroundWindowStabilityPolicy.Decision.BASELINE("com.game"), committed.decision)
        assertEquals("com.game", committed.state.stablePackage)
    }

    @Test
    fun `switch is not reported before eight stable samples and then reports once`() {
        var state = ForegroundWindowStabilityPolicy.State(stablePackage = "com.game")
        repeat(7) {
            val result = ForegroundWindowStabilityPolicy.observe(
                owner, state, "com.chat", true, requiredStableFrames = 8,
            )
            state = result.state
            assertTrue(result.decision is ForegroundWindowStabilityPolicy.Decision.PENDING)
        }
        val changed = ForegroundWindowStabilityPolicy.observe(
            owner, state, "com.chat", true, requiredStableFrames = 8,
        )
        assertEquals(
            ForegroundWindowStabilityPolicy.Decision.CHANGED("com.game", "com.chat"),
            changed.decision,
        )
        val same = ForegroundWindowStabilityPolicy.observe(
            owner, changed.state, "com.chat", true, requiredStableFrames = 8,
        )
        assertTrue(same.decision is ForegroundWindowStabilityPolicy.Decision.IGNORE)
    }

    @Test
    fun `candidate switch is discarded when the original app returns early`() {
        var state = ForegroundWindowStabilityPolicy.State(stablePackage = "com.game")
        repeat(4) {
            state = ForegroundWindowStabilityPolicy.observe(
                owner, state, "com.chat", true, requiredStableFrames = 8,
            ).state
        }
        val returned = ForegroundWindowStabilityPolicy.observe(
            owner, state, "com.game", true, requiredStableFrames = 8,
        )
        assertTrue(returned.decision is ForegroundWindowStabilityPolicy.Decision.IGNORE)
        assertEquals(0, returned.state.candidateFrames)
        assertEquals("com.game", returned.state.stablePackage)
    }

    @Test
    fun `system overlays preserve candidate but unavailable observations break consecutive stability`() {
        var state = ForegroundWindowStabilityPolicy.State(stablePackage = "com.game")
        repeat(4) {
            state = ForegroundWindowStabilityPolicy.observe(
                owner, state, "com.chat", true, requiredStableFrames = 8,
            ).state
        }
        val pending = state
        val system = ForegroundWindowStabilityPolicy.observe(
            owner, state, "com.android.systemui", true, requiredStableFrames = 8,
        )
        val floating = ForegroundWindowStabilityPolicy.observe(
            owner, state, "com.chat", false, requiredStableFrames = 8,
        )
        assertTrue(system.decision is ForegroundWindowStabilityPolicy.Decision.IGNORE)
        assertTrue(floating.decision is ForegroundWindowStabilityPolicy.Decision.IGNORE)
        assertEquals(pending, system.state)
        assertEquals(pending, floating.state)

        val unavailable = ForegroundWindowStabilityPolicy.observe(
            owner, state, null, false, requiredStableFrames = 8, observationAvailable = false,
        )
        assertTrue(unavailable.decision is ForegroundWindowStabilityPolicy.Decision.IGNORE)
        assertEquals(
            ForegroundWindowStabilityPolicy.State(stablePackage = "com.game"),
            unavailable.state,
        )
        val resumed = ForegroundWindowStabilityPolicy.observe(
            owner, unavailable.state, "com.chat", true, requiredStableFrames = 8,
        )
        assertEquals(1, resumed.state.candidateFrames)
        assertTrue(resumed.decision is ForegroundWindowStabilityPolicy.Decision.PENDING)
    }

}
