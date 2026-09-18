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

    @Test
    fun `eight stable samples release one key frame`() {
        val window = StableFrameWindow()
        repeat(7) { i ->
            val result = window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 250L)
            assertFalse(result.ready)
        }
        val result = window.accept(frame(0x101010, 7), 1, 2750)
        assertTrue(result.ready)
        assertEquals(8, result.stableCount)
        assertNotNull(result.selected)
    }

    @Test
    fun `stable window advances one sample after motion instead of restarting at zero`() {
        val window = StableFrameWindow()
        repeat(8) { i -> window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 250L) }

        // 第9个样本发生变化：窗口变成“旧7帧+新1帧”，不是清空为0。
        val changed = window.accept(frame(0xFFFFFF, 8), 1, 3000)
        assertFalse(changed.ready)
        assertTrue(changed.restartedByChange)
        assertEquals(8, changed.stableCount)

        // 新画面只需再提供7个样本；第8个新样本与前7个共同组成稳定窗口。
        var result: StableFrameWindow.Result? = null
        for (i in 9..14) {
            result = window.accept(frame(0xFFFFFF, i.toLong()), 1, 3000L + (i - 8) * 250L)
            assertFalse(result.ready)
        }
        result = window.accept(frame(0xFFFFFF, 15), 1, 4750)
        assertTrue(result.ready)
        assertEquals(8, result.stableCount)
        assertNotNull(result.selected)
        result = window.accept(frame(0xFFFFFF, 16), 1, 5000)
        // 后续静止样本不会重复推理。
        assertFalse(result.ready)
        assertNull(result.selected)

        // 重新构造一次，精确验证第8个新样本释放。
        val second = StableFrameWindow()
        repeat(8) { i -> second.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 250L) }
        second.accept(frame(0xFFFFFF, 8), 1, 3000)
        var releasedAt = -1
        for (i in 9..16) {
            val r = second.accept(frame(0xFFFFFF, i.toLong()), 1, 3000L + (i - 8) * 250L)
            if (r.ready) releasedAt = i
        }
        assertEquals(15, releasedAt)
    }

    @Test
    fun `rearm retries the current stable window on the next sample`() {
        val window = StableFrameWindow()
        repeat(8) { i -> window.accept(frame(0x101010, i.toLong()), 1, 1000L + i * 250L) }
        window.rearm()
        val result = window.accept(frame(0x101010, 8), 1, 3000)
        assertTrue(result.ready)
        assertEquals(8, result.stableCount)
    }

    @Test
    fun `epoch and timeout start a fresh window`() {
        val window = StableFrameWindow()
        window.accept(frame(0x101010, 0), 1, 1000)
        val next = window.accept(frame(0x101010, 1), 2, 4000)
        assertEquals(1, next.stableCount)
        assertFalse(next.ready)
    }
}
