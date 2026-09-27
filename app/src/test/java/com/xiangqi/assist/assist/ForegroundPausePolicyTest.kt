package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
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

    @Test
    fun `a single unavailable observation does not trigger the safety pause`() {
        var state = ForegroundPausePolicy.AvailabilityState()
        for (i in 1 until ForegroundPausePolicy.UNAVAILABLE_PAUSE_SAMPLES) {
            val step = ForegroundPausePolicy.observeAvailability(state, observationAvailable = false)
            state = step.first
            assertFalse("第 $i 次瞬时不可用不应触发安全暂停", step.second)
        }
        val confirmed = ForegroundPausePolicy.observeAvailability(state, observationAvailable = false)
        assertTrue(confirmed.second)
    }

    @Test
    fun `an available observation resets the unavailable streak`() {
        var state = ForegroundPausePolicy.AvailabilityState()
        repeat(ForegroundPausePolicy.UNAVAILABLE_PAUSE_SAMPLES - 1) {
            state = ForegroundPausePolicy.observeAvailability(state, observationAvailable = false).first
        }
        state = ForegroundPausePolicy.observeAvailability(state, observationAvailable = true).first
        val next = ForegroundPausePolicy.observeAvailability(state, observationAvailable = false)
        assertFalse(next.second)
        assertTrue(ForegroundPausePolicy.observeAvailability(next.first, true).second == false)
    }

    @Test
    fun `safety suspended session confirms return without a baseline change`() {
        val snapshot = ForegroundPausePolicy.capture(
            paused = false,
            resumePackage = "cn.jj.chess",
            suspendedByForeground = true,
        )
        var state = ForegroundPausePolicy.ReturnState()
        for (i in 1 until FrameStabilityPolicy.REQUIRED_STABLE_FRAMES) {
            val step = ForegroundPausePolicy.observeReturn(snapshot, state, "cn.jj.chess", isFullScreen = true)
            state = step.first
            assertFalse("第 $i 帧不应提前确认返回", step.second)
        }
        val confirmed = ForegroundPausePolicy.observeReturn(snapshot, state, "cn.jj.chess", isFullScreen = true)
        assertTrue(confirmed.second)
        assertEquals(0, confirmed.first.frames)
    }

    @Test
    fun `return confirmation fires once per pause so a failed resume cannot retry every second`() {
        val snapshot = ForegroundPausePolicy.capture(paused = false, resumePackage = "cn.jj.chess")
        var state = ForegroundPausePolicy.ReturnState()
        repeat(FrameStabilityPolicy.REQUIRED_STABLE_FRAMES) {
            state = ForegroundPausePolicy.observeReturn(snapshot, state, "cn.jj.chess", isFullScreen = true).first
        }
        assertTrue(state.fired)
        repeat(FrameStabilityPolicy.REQUIRED_STABLE_FRAMES * 3) {
            val step = ForegroundPausePolicy.observeReturn(snapshot, state, "cn.jj.chess", isFullScreen = true)
            state = step.first
            assertFalse("同一次暂停不应重复确认返回", step.second)
        }
        // 快照被清理后计数与已确认标记一起归零，下一次暂停仍能正常确认。
        assertEquals(
            ForegroundPausePolicy.ReturnState(),
            ForegroundPausePolicy.observeReturn(null, state, "cn.jj.chess", isFullScreen = true).first,
        )
    }

    @Test
    fun `return confirmation ignores other apps, non full screen samples and manual pauses`() {
        val snapshot = ForegroundPausePolicy.capture(paused = false, resumePackage = "cn.jj.chess")
        val start = ForegroundPausePolicy.ReturnState("cn.jj.chess", 5)
        assertFalse(
            ForegroundPausePolicy.observeReturn(snapshot, start, "com.android.launcher", isFullScreen = true).second
        )
        assertFalse(ForegroundPausePolicy.observeReturn(snapshot, start, "cn.jj.chess", isFullScreen = false).second)
        assertFalse(ForegroundPausePolicy.observeReturn(snapshot, start, null, isFullScreen = true).second)
        val manual = ForegroundPausePolicy.capture(
            paused = false,
            resumePackage = "cn.jj.chess",
            suspendedByForeground = false,
        )
        val blocked = ForegroundPausePolicy.observeReturn(manual, start, "cn.jj.chess", isFullScreen = true)
        assertFalse(blocked.second)
        assertEquals(ForegroundPausePolicy.ReturnState(), blocked.first)
        assertEquals(
            ForegroundPausePolicy.ReturnState(),
            ForegroundPausePolicy.observeReturn(null, start, "cn.jj.chess", isFullScreen = true).first,
        )
    }
}
