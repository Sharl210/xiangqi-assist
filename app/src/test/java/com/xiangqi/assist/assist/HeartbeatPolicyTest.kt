package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPolicyTest {
    @Test fun `waiting stage timeout and cooldown are halved`() {
        assertEquals(250L, HeartbeatPolicy.WAITING_SCAN_TIMEOUT_MS)
        assertEquals(250L, HeartbeatPolicy.FORCE_SCAN_COOLDOWN_MS)
        val state = HeartbeatPolicy.State(lastSuccessfulScanAt = 10_000L)
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 10_249L, state))
        assertTrue(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 10_250L, state))
        val after = HeartbeatPolicy.afterForce(10_250L, state)
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 10_499L, after))
        assertTrue(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 10_500L, after))
    }

    @Test fun `other recognition phases timeout and cooldown are halved`() {
        assertEquals(500L, HeartbeatPolicy.OTHER_SCAN_TIMEOUT_MS)
        assertEquals(500L, HeartbeatPolicy.OTHER_SCAN_COOLDOWN_MS)
        for (phase in listOf(AssistPhase.Phase.FINDING, AssistPhase.Phase.READY, AssistPhase.Phase.VERIFYING)) {
            val state = HeartbeatPolicy.State(lastSuccessfulScanAt = 20_000L)
            assertFalse(HeartbeatPolicy.shouldForceWaitingScan(phase, 20_499L, state))
            assertTrue(HeartbeatPolicy.shouldForceWaitingScan(phase, 20_500L, state))
            assertFalse(HeartbeatPolicy.shouldForceWaitingScan(phase, 20_999L,
                HeartbeatPolicy.afterForce(20_500L, state)))
            assertTrue(HeartbeatPolicy.shouldForceWaitingScan(phase, 21_000L,
                HeartbeatPolicy.afterForce(20_500L, state)))
        }
    }

    @Test fun `non recognition phases are excluded`() {
        val state = HeartbeatPolicy.State(lastSuccessfulScanAt = 0L)
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.THINKING, 100_000L, state))
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.MOVING, 100_000L, state))
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.PAUSED, 100_000L, state))
    }

    @Test fun `processed sample postpones heartbeat even when board was rejected`() {
        val after = HeartbeatPolicy.afterSuccessfulScan(30_000L,
            HeartbeatPolicy.State(lastSuccessfulScanAt = 10_000L))
        assertFalse(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 30_249L, after))
        assertTrue(HeartbeatPolicy.shouldForceWaitingScan(AssistPhase.Phase.WAITING, 30_250L, after))
    }
}
