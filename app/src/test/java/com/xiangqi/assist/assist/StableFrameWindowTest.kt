package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StableFrameWindowTest {
    private fun frame(color: Int, at: Long): Frame =
        Frame(8, 8, IntArray(64) { 0xFF000000.toInt() or color }, at)

    @Test fun `eight stable samples release one key frame and no early frame`() {
        val window = StableFrameWindow()
        repeat(7) { i ->
            val result = window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 125L)
            assertFalse(result.ready)
            assertEquals(i + 1, result.stableCount)
        }
        val result = window.accept(frame(0x101010, 7), 1, 1875L)
        assertTrue(result.ready)
        assertEquals(8, result.stableCount)
        assertNotNull(result.selected)
    }

    @Test fun `window advances through motion and waits for eight consecutive new samples`() {
        val window = StableFrameWindow()
        repeat(8) { i -> window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 125L) }
        val changed = window.accept(frame(0xFFFFFF, 8), 1, 2000L)
        assertFalse(changed.ready)
        assertTrue(changed.restartedByChange)
        assertEquals(8, changed.stableCount)

        var releasedAt = -1
        for (i in 9..15) {
            val result = window.accept(frame(0xFFFFFF, i.toLong()), 1, 2000L + (i - 8) * 125L)
            if (result.ready) releasedAt = i
            if (i < 15) assertFalse(result.ready)
        }
        assertEquals(15, releasedAt)
        val stableAgain = window.accept(frame(0xFFFFFF, 16), 1, 3000L)
        assertFalse(stableAgain.ready)
        assertNull(stableAgain.selected)
    }

    @Test fun `rearm retries current stable window on next sample`() {
        val window = StableFrameWindow()
        repeat(8) { i -> window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 125L) }
        window.rearm()
        val result = window.accept(frame(0x101010, 8), 1, 2000L)
        assertTrue(result.ready)
        assertEquals(8, result.stableCount)
    }

    @Test fun `epoch and sample gap start fresh window`() {
        val window = StableFrameWindow()
        window.accept(frame(0x101010, 0), 1, 1000L)
        val next = window.accept(frame(0x101010, 1), 2, 4000L)
        assertEquals(1, next.stableCount)
        assertFalse(next.ready)
    }

    // ==================== 真机 2026-10-01：静止/持续变化画面下的识别静默 ====================

    @Test fun `accepted board without rearm is still re-checked while the screen stays static`() {
        // 真机现象：一次棋面确认通过（跟 SAME_BOARD 一样不再 rearm）之后，屏幕静止、
        // VirtualDisplay 不再投递新帧，释放闸门就一直关着——整整 81 秒没有一次 VISION_RAW，
        // 看门狗每 10 秒误报一次"长时间未识别到棋盘"，把已经对好的棋盘网格反复丢弃。
        // 现在闸门关着也要按饿死间隔巡检，识别不会永久静默。
        val window = StableFrameWindow(requiredStableFrames = 3)
        val still = frame(0x101010, 0)
        assertFalse(window.accept(still, 1, 1000L).ready)
        assertFalse(window.accept(still, 1, 1125L).ready)
        assertTrue(window.accept(still, 1, 1250L).ready)
        // 故意不 rearm：这就是"确认通过之后没有新变化"的真实状态。

        assertFalse("静默不足 600ms 不得再放行", window.releaseWhenQuiet(1_400L).ready)
        assertFalse("闸门关着时必须按饿死间隔限速", window.releaseWhenQuiet(2_000L).ready)
        assertFalse("距上次放行 750ms，仍不应巡检", window.releaseWhenQuiet(2_400L).ready)
        val recheck = window.releaseWhenQuiet(3_800L)
        assertTrue("静止画面也必须被周期性复核，不能永久静默", recheck.ready)
        assertTrue(recheck.selected === still)
    }

    @Test fun `permanently changing screen still produces a starvation release`() {
        // 真机另一条路径：对局里的计时、动画、光影让稳定窗口永远闭合，
        // accept() 一次都不会 ready；若没有饿死放行，识别同样会彻底静默。
        val window = StableFrameWindow(requiredStableFrames = 3)
        var t = 1000L
        repeat(12) { i ->
            val color = if (i % 2 == 0) 0x101010 else 0xFFFFFF
            assertFalse(window.accept(frame(color, i.toLong()), 1, t).ready)
            t += 125L
        }
        assertFalse("饿死间隔未到不得放行", window.releaseWhenQuiet(2_000L).ready)
        val starved = window.releaseWhenQuiet(3_600L)
        assertTrue("画面一直不稳定也必须放行一次，否则识别永远不产出", starved.ready)
        assertNotNull(starved.selected)
    }

    @Test fun `starvation release is no-op on an empty window`() {
        val window = StableFrameWindow(requiredStableFrames = 3)
        assertFalse(window.releaseWhenQuiet(9_999L).ready)
        assertFalse(window.releaseWhenQuiet(99_999L).ready)
        assertEquals(0, window.sampleCount())
    }

    @Test fun `static screen is released without waiting for eight frames`() {
        // VirtualDisplay 在画面静止时不再投递新帧，八个样本永远凑不齐。
        val window = StableFrameWindow()
        val still = frame(0x101010, 0)
        window.accept(still, 1, 1000L)
        assertFalse(window.releaseWhenQuiet(1_200L).ready)
        val quiet = window.releaseWhenQuiet(1_700L)
        assertTrue(quiet.ready)
        assertTrue(quiet.selected === still)
    }

    @Test fun `quiet release honours the minimum gap so the same still frame is not re-run`() {
        val window = StableFrameWindow()
        window.accept(frame(0x101010, 0), 1, 1000L)
        assertTrue(window.releaseWhenQuiet(1_700L).ready)
        window.rearm()
        assertFalse(window.releaseWhenQuiet(1_750L).ready)
        assertTrue(window.releaseWhenQuiet(2_300L).ready)
    }

    @Test fun `quiet release refuses unstable samples`() {
        val window = StableFrameWindow()
        window.accept(frame(0x101010, 0), 1, 1000L)
        window.accept(frame(0xFFFFFF, 1), 1, 1_100L)
        assertFalse(window.releaseWhenQuiet(1_900L).ready)
    }

    @Test fun `quiet release is a no-op on an empty window`() {
        val window = StableFrameWindow()
        assertFalse(window.releaseWhenQuiet(5_000L).ready)
        assertEquals(0, window.sampleCount())
    }

    @Test fun `vision-only epoch change keeps the samples already collected`() {
        val window = StableFrameWindow()
        repeat(5) { i -> window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 125L) }
        // 识别侧自愈只换锚点：代号变了，但“画面已经连续稳定”这个结论必须保留，
        // 否则需要 1 秒积累的窗口会被每 1.5 秒一次的自愈反复打断。
        window.adoptEpoch(2, preserveSamples = true)
        var released = false
        for (i in 5..7) {
            val result = window.accept(frame(0x101010, i.toLong()), 2, 1000L + i * 125L)
            if (i < 7) assertFalse(result.ready)
            if (i == 7) released = result.ready
        }
        assertTrue("自愈不应清空已经攒够的稳定样本", released)
    }

    @Test fun `structural epoch change still clears stale samples`() {
        val window = StableFrameWindow()
        repeat(5) { i -> window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 125L) }
        window.adoptEpoch(2, preserveSamples = false)
        val result = window.accept(frame(0x101010, 5), 2, 1625L)
        assertEquals(1, result.stableCount)
        assertFalse(result.ready)
    }
}
