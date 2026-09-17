package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「更新棋谱」请求状态机。
 *
 * 用户反馈这个按钮"完全没用"。根因是请求被分散的布尔量与各种节流**静默吞掉**，
 * 界面没有任何反馈。这里把它的行为钉死：有请求就必然有结论（成功或失败+原因）。
 */
class RefreshRequestTest {

    @Test
    fun `idle request is inactive`() {
        val r = RefreshRequest()
        assertEquals(RefreshRequest.State.IDLE, r.state)
        assertFalse(r.isActive)
        assertNull(r.statusLine())
    }

    @Test
    fun `a new request is immediately active and makes frames yield`() {
        val r = RefreshRequest()
        r.begin(now = 1000)
        assertEquals(RefreshRequest.State.WAITING_FRAME, r.state)
        assertTrue(r.isActive)
        // 这一条是关键：取帧与识别都必须为它让路（跳过节流）
        assertTrue(r.shouldServeFrame())
        assertNotNull(r.statusLine())
    }

    @Test
    fun `frames are consumed one by one until success`() {
        val r = RefreshRequest(maxAttempts = 3)
        r.begin(1000)
        r.consumeAttempt("未定位")
        assertEquals(1, r.attempts)
        assertTrue("还没到上限，应继续等待", r.isActive)
        r.consumeAttempt("未定位")
        assertEquals(2, r.attempts)
        assertTrue(r.checkGiveUp(1200, "未定位").not())
        r.consumeAttempt("未定位")
        // 用尽尝试次数 → 必须放弃并给出原因
        assertTrue(r.checkGiveUp(1300, "未定位"))
        assertEquals(RefreshRequest.State.FAILED, r.state)
        assertEquals("未定位", r.failReason)
    }

    @Test
    fun `timeout fails even if attempts remain`() {
        val r = RefreshRequest(maxAttempts = 5, timeoutMs = 2000)
        r.begin(1000)
        r.consumeAttempt(null)
        // 还没超时
        assertFalse(r.checkGiveUp(2500, null))
        // 超时 → 失败，并且说明是取画面的问题
        assertTrue(r.checkGiveUp(3500, null))
        assertEquals(RefreshRequest.State.FAILED, r.state)
        assertTrue(r.failReason!!.contains("取画面"))
    }

    @Test
    fun `success clears failure and stops yielding`() {
        val r = RefreshRequest()
        r.begin(1000)
        r.consumeAttempt("未定位")
        r.succeed()
        assertEquals(RefreshRequest.State.SUCCEEDED, r.state)
        assertFalse("已完成后不应再让路", r.shouldServeFrame())
        assertNull(r.failReason)
        assertTrue(r.statusLine()!!.contains("已完成"))
    }

    @Test
    fun `failure always carries a readable reason`() {
        val r = RefreshRequest(maxAttempts = 1)
        r.begin(1000)
        r.consumeAttempt("未见棋盘（检出2子）")
        assertTrue(r.checkGiveUp(1100, "未见棋盘（检出2子）"))
        val line = r.statusLine()!!
        assertTrue("失败必须在界面上说清楚", line.contains("失败"))
        assertTrue(line.contains("未见棋盘"))
    }

    @Test
    fun `status line always tells the user something is happening`() {
        val r = RefreshRequest(maxAttempts = 3)
        r.begin(1000)
        // 请求进行中绝不能是"什么都不显示"——那正是用户说"完全没用"的观感来源
        val line = r.statusLine()!!
        assertTrue(line.contains("更新棋谱"))
        r.consumeAttempt("未定位")
        assertTrue(r.statusLine()!!.contains("2/3"))
    }

    @Test
    fun `cancel resets everything`() {
        val r = RefreshRequest()
        r.begin(1000)
        r.consumeAttempt("x")
        r.cancel()
        assertEquals(RefreshRequest.State.IDLE, r.state)
        assertFalse(r.isActive)
        assertNull(r.statusLine())
    }
}
