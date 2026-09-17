package com.xiangqi.assist.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoChannelRestartPolicyTest {
    @Test
    fun `ready channel restarts once after two seconds`() {
        val since = 10_000L
        assertFalse(
            AutoChannelRestartPolicy.shouldRestartReadyChannel(
                AssistPhase.Phase.READY, since + 2_000L, since, Long.MIN_VALUE
            )
        )
        assertTrue(
            AutoChannelRestartPolicy.shouldRestartReadyChannel(
                AssistPhase.Phase.READY, since + 2_001L, since, Long.MIN_VALUE
            )
        )
        assertFalse(
            AutoChannelRestartPolicy.shouldRestartReadyChannel(
                AssistPhase.Phase.READY, since + 4_000L, since, since
            )
        )
    }

    @Test
    fun `restart is not requested outside ready`() {
        assertFalse(
            AutoChannelRestartPolicy.shouldRestartReadyChannel(
                AssistPhase.Phase.THINKING, 5_000L, 1_000L, Long.MIN_VALUE
            )
        )
    }

    @Test
    fun `pending move is released after hard timeout`() {
        val created = 10_000L
        assertFalse(AutoChannelRestartPolicy.shouldReleasePendingMove(created + 6_000L, created))
        assertTrue(AutoChannelRestartPolicy.shouldReleasePendingMove(created + 6_001L, created))
        assertFalse(AutoChannelRestartPolicy.shouldReleasePendingMove(created - 1L, created))
    }

    @Test
    fun `same move has a stable transaction key`() {
        assertEquals(
            AutoChannelRestartPolicy.moveTransactionKey("fen", "a0a1"),
            AutoChannelRestartPolicy.moveTransactionKey("fen", "a0a1")
        )
        assertTrue(
            AutoChannelRestartPolicy.moveTransactionKey("fen", "a0a1") !=
                AutoChannelRestartPolicy.moveTransactionKey("fen", "b0b1")
        )
    }
}
