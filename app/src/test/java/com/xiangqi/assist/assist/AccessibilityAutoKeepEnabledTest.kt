package com.xiangqi.assist.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自动故障策略的无Android纯逻辑回归。 */
class AccessibilityAutoKeepEnabledTest {
    @Test
    fun `accessibility loss is a blocked channel, not an auto switch off`() {
        // WorkModes 只允许通道断开阻止动作，不能把用户开关降为 false。
        assertFalse(WorkModes.autoCanRun(AssistConfig.MODE_AUTO, true, false))
        assertTrue(WorkModes.autoCanRun(AssistConfig.MODE_AUTO, true, true))
    }
}
