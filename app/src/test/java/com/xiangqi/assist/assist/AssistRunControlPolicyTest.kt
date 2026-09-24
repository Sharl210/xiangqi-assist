package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
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
    fun `prepared session keeps close label after capture is released`() {
        assertEquals("一键关闭", AssistRunControlPolicy.primaryLabel(prepared = true, running = false))
        assertEquals("一键准备", AssistRunControlPolicy.primaryLabel(prepared = false, running = false))
        assertFalse(AssistRunControlPolicy.shouldHoldProjection(
            AssistRunControlPolicy.SessionState.PREPARED_PAUSED,
            retainWhilePaused = false,
        ))
        assertTrue(AssistRunControlPolicy.primaryButtonIsClose(preparedOrClosePending = true))
        assertFalse(AssistRunControlPolicy.primaryButtonIsClose(preparedOrClosePending = false))
        assertTrue(AssistRunControlPolicy.accessibilityShutdownRequired(closeRequested = true))
        assertTrue(AssistRunControlPolicy.PAUSED_RETAIN_PROJECTION)
        assertTrue(AssistRunControlPolicy.shouldHoldProjection(
            AssistRunControlPolicy.SessionState.PREPARED_PAUSED,
            AssistRunControlPolicy.PAUSED_RETAIN_PROJECTION,
        ))
        assertFalse(AssistRunControlPolicy.shouldRunCapturePipeline(
            AssistRunControlPolicy.SessionState.PREPARED_PAUSED,
        ))
        assertTrue(AssistRunControlPolicy.shouldRunCapturePipeline(
            AssistRunControlPolicy.SessionState.RUNNING,
        ))
        assertTrue(AssistRunControlPolicy.shouldWatchForeground(AssistRunControlPolicy.SessionState.PREPARED_PAUSED))
        assertFalse(AssistRunControlPolicy.shouldWatchForeground(AssistRunControlPolicy.SessionState.CLOSED))
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
