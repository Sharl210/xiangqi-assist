package com.xiangqi.assist.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPolicyTest {
    @Test
    fun `waiting phase forces a scan at four seconds`() {
        val s = HeartbeatPolicy.State(lastSuccessfulScanAt = 1_000L)
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 4_999L, s))
        assertTrue(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 5_000L, s))
    }

    @Test
    fun `other phases never use waiting heartbeat`() {
        val s = HeartbeatPolicy.State(lastSuccessfulScanAt = 0L)
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.THINKING, 100_000L, s))
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.READY, 100_000L, s))
    }

    @Test
    fun `heartbeat has a cooldown after forced scan`() {
        val s = HeartbeatPolicy.State(
            lastSuccessfulScanAt = 1_000L,
            lastForcedScanAt = 5_000L,
        )
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 8_999L, s))
        assertTrue(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 9_000L, s))
    }

    @Test
    fun `successful scan postpones next heartbeat`() {
        val s = HeartbeatPolicy.State(lastSuccessfulScanAt = 1_000L, lastForcedScanAt = 5_000L)
        val after = HeartbeatPolicy.afterSuccessfulScan(5_100L, s)
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 9_099L, after))
        assertTrue(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 9_100L, after))
    }
}
