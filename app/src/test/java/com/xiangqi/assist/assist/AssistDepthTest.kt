package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistDepthTest {
    @Test
    fun `depth levels stop at sixty four`() {
        assertEquals(
            listOf(6, 12, 20, 28, 36, 50, 64),
            AssistDepth.LEVELS.map { it.second }
        )
        assertTrue(AssistDepth.LEVELS.zipWithNext().all { it.first.second < it.second.second })
        // 不再提供 128/256/512 档位
        assertFalse(AssistDepth.LEVELS.any { it.second > 64 })
        assertEquals(64, AssistDepth.MAX)
    }

    @Test
    fun `depth clamp uses the new bounds`() {
        assertEquals(6, AssistDepth.clamp(1))
        assertEquals(20, AssistDepth.clamp(20))
        assertEquals(64, AssistDepth.clamp(64))
        assertEquals(64, AssistDepth.clamp(512))
        assertEquals(64, AssistDepth.clamp(9999))
    }

    @Test
    fun `default depth is twenty`() {
        assertEquals(20, ThinkingOptions.DEFAULT_DEPTH)
        assertEquals(ThinkingOptions.DEFAULT_DEPTH, AssistDepth.clamp(ThinkingOptions.DEFAULT_DEPTH))
    }
}
