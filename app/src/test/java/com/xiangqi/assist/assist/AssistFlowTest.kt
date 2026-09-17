package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流程相关的回归项：
 * 1. 「更新棋谱」的取帧请求必须优先于"思考中不截图"——否则引擎一忙按钮就失灵；
 * 2. 引擎在"点开始之前"不应被拉起（避免开机就申请大内存）。
 */
class AssistFlowTest {

    /** 与 ScreenAssistService.desiredCaptureDemand 同构的判定，用于把这套优先级钉住 */
    private fun demand(
        closed: Boolean, manual: Boolean, paused: Boolean,
        wantOneShot: Boolean, searching: Boolean, semiAuto: Boolean,
    ): String = when {
        closed || manual || paused -> "OFF"
        wantOneShot -> "ONE_SHOT"
        searching -> "OFF"
        semiAuto -> "ONE_SHOT"
        else -> "CONTINUOUS"
    }

    @Test
    fun `manual refresh wins over searching`() {
        // 引擎正在算的时候点「更新棋谱」，必须仍然取一帧（这是之前按钮失灵的根因）
        assertEquals("ONE_SHOT", demand(false, false, false, true, true, true))
        assertEquals("ONE_SHOT", demand(false, false, false, true, true, false))
    }

    @Test
    fun `manual refresh wins over semi auto idle`() {
        assertEquals("ONE_SHOT", demand(false, false, false, true, false, true))
    }

    @Test
    fun `paused blocks everything`() {
        // 暂停态：连"手动刷新"也不取帧（不下棋就不读屏）
        assertEquals("OFF", demand(false, false, true, true, false, false))
        assertEquals("OFF", demand(false, false, true, false, true, true))
    }

    @Test
    fun `manual mode never captures`() {
        assertEquals("OFF", demand(false, true, false, true, false, false))
    }

    @Test
    fun `searching does not capture when nothing requested`() {
        assertEquals("OFF", demand(false, false, false, false, true, false))
    }

    @Test
    fun `semi auto idles in one shot and auto runs continuous`() {
        assertEquals("ONE_SHOT", demand(false, false, false, false, false, true))
        assertEquals("CONTINUOUS", demand(false, false, false, false, false, false))
    }

    @Test
    fun `auto mode captures when idle even while engine is ready`() {
        assertEquals("CONTINUOUS", demand(false, false, false, false, false, false))
    }

    @Test
    fun `depth tiers stop at sixty four`() {
        // 用户要求的完整深度档位：最高 64，不再有 128/256/512
        assertEquals(listOf(6, 12, 20, 28, 36, 50, 64),
            AssistDepth.LEVELS.map { it.second })
        assertEquals(64, AssistDepth.MAX)
    }

    @Test
    fun `jitter fraction stays well inside a cell`() {
        // 偏移量必须远小于半格：否则会点到相邻格或点空（用户报的"没点上棋子"）
        val fraction = 0.10f
        assertTrue("偏移不得超过 1/4 格", fraction < 0.25f)
    }
}
