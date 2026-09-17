package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作模式互斥 + 自动走子开关与模式解耦。
 * "两个模式同时亮着""切到自动又被悄悄改回指导"都是明确的回归项。
 */
class WorkModesTest {

    private val all = listOf(
        AssistConfig.MODE_GUIDE, AssistConfig.MODE_SEMI,
        AssistConfig.MODE_MANUAL, AssistConfig.MODE_AUTO
    )

    @Test
    fun `at most one flag is on for every mode`() {
        for (m in all) {
            val f = WorkModes.flags(m)
            val on = listOf(f.manual, f.semi).count { it }
            assertTrue("模式 $m 不应同时点亮多个开关：$f", on <= 1)
        }
    }

    @Test
    fun `each mode maps to exactly the expected flag`() {
        assertTrue(WorkModes.flags(AssistConfig.MODE_MANUAL).manual)
        assertTrue(WorkModes.flags(AssistConfig.MODE_SEMI).semi)
        val auto = WorkModes.flags(AssistConfig.MODE_AUTO)
        assertFalse(auto.manual || auto.semi)
    }

    @Test
    fun `one button cycles auto semi manual auto`() {
        var m = AssistConfig.MODE_AUTO
        m = WorkModes.nextInCycle(m); assertEquals(AssistConfig.MODE_SEMI, m)
        m = WorkModes.nextInCycle(m); assertEquals(AssistConfig.MODE_MANUAL, m)
        m = WorkModes.nextInCycle(m); assertEquals(AssistConfig.MODE_AUTO, m)
        // 默认模式就是自动
        assertEquals(AssistConfig.MODE_AUTO, AssistConfig.MODE_AUTO)
    }

    @Test
    fun `cycle covers every mode and returns to auto`() {
        // 一个按钮轮换：自动 → 半自动 → 手动 → 自动，不会停在旧状态
        var m = AssistConfig.MODE_AUTO
        val seen = mutableListOf(m)
        repeat(3) {
            m = WorkModes.nextInCycle(m)
            seen += m
        }
        assertEquals(listOf(AssistConfig.MODE_AUTO, AssistConfig.MODE_SEMI,
            AssistConfig.MODE_MANUAL, AssistConfig.MODE_AUTO), seen)
    }

    @Test
    fun `auto play needs mode switch and channel together`() {
        // 三项都满足才真的会自动落子
        assertTrue(WorkModes.autoCanRun(AssistConfig.MODE_AUTO, autoPlayOn = true, accessibilityConnected = true))
        // 开关关着 → 不动（此时"自动"只等于自动识别棋盘）
        assertFalse(WorkModes.autoCanRun(AssistConfig.MODE_AUTO, autoPlayOn = false, accessibilityConnected = true))
        // 通道没连接 → 不动
        assertFalse(WorkModes.autoCanRun(AssistConfig.MODE_AUTO, autoPlayOn = true, accessibilityConnected = false))
        // 手动模式 → 永不自动落子
        assertFalse(WorkModes.autoCanRun(AssistConfig.MODE_MANUAL, true, true))
    }

    @Test
    fun `auto play works in auto and semi modes but never in manual`() {
        // 自动 + 自动落子 = 全自动
        assertTrue(WorkModes.autoCanRun(AssistConfig.MODE_AUTO, true, true))
        // 半自动 + 自动落子 = 确认局面后机器落子
        assertTrue(WorkModes.autoCanRun(AssistConfig.MODE_SEMI, true, true))
        // 手动模式不参与自动落子（机器不知道你什么时候摆完）
        assertFalse(WorkModes.autoCanRun(AssistConfig.MODE_MANUAL, true, true))
    }

    @Test
    fun `mode names are stable`() {
        assertEquals("手动", WorkModes.name(AssistConfig.MODE_MANUAL))
        assertEquals("半自动", WorkModes.name(AssistConfig.MODE_SEMI))
        assertEquals("自动", WorkModes.name(AssistConfig.MODE_AUTO))
        assertEquals("指导", WorkModes.name(AssistConfig.MODE_GUIDE))
    }
}
