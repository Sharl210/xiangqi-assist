package com.xiangqi.assist.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPausePolicyTest {
    @Test
    fun `running state resumes only after returning to the captured app`() {
        val snapshot = ForegroundPausePolicy.capture(paused = false, resumePackage = "com.game")
        assertFalse(ForegroundPausePolicy.shouldResume(snapshot, "com.chat"))
        assertTrue(ForegroundPausePolicy.shouldResume(snapshot, "com.game"))
    }

    @Test
    fun `manual pause and foreground suspension are distinct`() {
        val manual = ForegroundPausePolicy.capture(
            paused = false,
            resumePackage = "com.game",
            suspendedByForeground = false,
        )
        assertTrue(manual?.wasRunning == true)
        assertFalse(manual?.suspendedByForeground == true)
        assertFalse(ForegroundPausePolicy.shouldResume(manual, "com.game"))
        assertTrue(ForegroundPausePolicy.isResumeTarget(manual, "com.game"))
        assertFalse(ForegroundPausePolicy.isResumeTarget(manual, "com.chat"))

        val safety = ForegroundPausePolicy.capture(paused = false, resumePackage = "com.game")
        assertTrue(safety?.suspendedByForeground == true)
        assertTrue(ForegroundPausePolicy.shouldResume(safety, "com.game"))
        assertTrue(ForegroundPausePolicy.isResumeTarget(safety, "com.game"))
    }

    @Test
    fun `already paused state remains paused after return`() {
        val snapshot = ForegroundPausePolicy.capture(paused = true, resumePackage = "com.game")
        assertFalse(ForegroundPausePolicy.shouldResume(snapshot, "com.game"))
    }

    @Test
    fun `missing original package cannot create a false resume target`() {
        assertNull(ForegroundPausePolicy.capture(paused = false, resumePackage = null))
        assertNull(ForegroundPausePolicy.capture(paused = false, resumePackage = "   "))
    }
}
