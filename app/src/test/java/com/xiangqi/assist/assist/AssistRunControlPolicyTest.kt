package com.xiangqi.assist.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistRunControlPolicyTest {
    @Test
    fun `prepared environment stays stopped until explicit start`() {
        assertTrue(AssistRunControlPolicy.PREPARED_PAUSED)
        assertFalse(
            AssistRunControlPolicy.hasRunningIntent(
                prepared = true,
                paused = true,
                foregroundWasRunning = false,
            )
        )
        assertTrue(AssistRunControlPolicy.canStart(true, paused = true, foregroundWasRunning = false))
    }

    @Test
    fun `active session exposes close action`() {
        assertTrue(AssistRunControlPolicy.hasRunningIntent(true, paused = false, foregroundWasRunning = false))
        assertTrue(AssistRunControlPolicy.canStop(true, paused = false, foregroundWasRunning = false))
    }

    @Test
    fun `foreground safety pause keeps running intent until user closes`() {
        assertTrue(AssistRunControlPolicy.hasRunningIntent(true, paused = true, foregroundWasRunning = true))
        assertFalse(AssistRunControlPolicy.canStart(true, paused = true, foregroundWasRunning = true))
        assertTrue(AssistRunControlPolicy.canStop(true, paused = true, foregroundWasRunning = true))
    }

    @Test
    fun `unprepared environment cannot start or stop`() {
        assertFalse(AssistRunControlPolicy.canStart(false, paused = true, foregroundWasRunning = false))
        assertFalse(AssistRunControlPolicy.canStop(false, paused = false, foregroundWasRunning = false))
    }
}
