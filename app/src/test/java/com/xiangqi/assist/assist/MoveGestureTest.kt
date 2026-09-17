package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 落子方式配置：0 永远表示点击式，1 才表示拖动式。 */
class MoveGestureTest {
    @Test
    fun `default and zero mean tap sequence`() {
        assertEquals(AssistConfig.GESTURE_TAP, 0)
        assertNotEquals(AssistConfig.GESTURE_TAP, AssistConfig.GESTURE_SWIPE)
    }

    @Test
    fun `one means optional swipe`() {
        assertEquals(AssistConfig.GESTURE_SWIPE, 1)
    }
}
