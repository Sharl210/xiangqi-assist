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
