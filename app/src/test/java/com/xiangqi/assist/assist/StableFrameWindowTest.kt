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
        assertFalse(window.accept(frame(0x101010, 0), 1, 1000).ready)
        assertFalse(window.accept(frame(0x101010, 1), 1, 1250).ready)
        assertFalse(window.accept(frame(0x101010, 2), 1, 1500).ready)
        assertFalse(window.accept(frame(0x101010, 3), 1, 1750).ready)
        assertFalse(window.accept(frame(0x101010, 4), 1, 2000).ready)
        assertFalse(window.accept(frame(0x101010, 5), 1, 2250).ready)
        assertFalse(window.accept(frame(0x101010, 6), 1, 2500).ready)
        val result = window.accept(frame(0x101010, 7), 1, 2750)
        assertTrue(result.ready)
        assertEquals(8, result.stableCount)
        assertNotNull(result.selected)
    }

    @Test
    fun `motion rejects current window and does not use old frame`() {
        val window = StableFrameWindow()
        window.accept(frame(0x101010, 0), 1, 1000)
        window.accept(frame(0x101010, 1), 1, 1250)
        val result = window.accept(frame(0xFFFFFF, 2), 1, 1500)
        assertFalse(result.ready)
        assertTrue(result.restartedByChange)
        assertNull(result.selected)
        assertEquals(1, result.stableCount)
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
