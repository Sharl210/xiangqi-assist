package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppPolicyTest {
    private val owner = "com.xiangqi.assist"

    @Test
    fun `first full screen external app establishes baseline`() {
        val result = ForegroundAppPolicy.observe(owner, null, "com.game", true)
        assertEquals(ForegroundAppPolicy.Decision.BASELINE("com.game"), result)
    }

    @Test
    fun `switching full screen external app is reported`() {
        val result = ForegroundAppPolicy.observe(owner, "com.game", "com.weixin", true)
        assertEquals(ForegroundAppPolicy.Decision.CHANGED("com.game", "com.weixin"), result)
    }

    @Test
    fun `overlay system windows are ignored and owner is ignored only before a baseline exists`() {
        assertTrue(ForegroundAppPolicy.observe(owner, null, owner, true)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertEquals(
            ForegroundAppPolicy.Decision.CHANGED("com.game", owner),
            ForegroundAppPolicy.observe(owner, "com.game", owner, true)
        )
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.weixin", false)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.android.systemui", true)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.android.inputmethod.latin", true)
            is ForegroundAppPolicy.Decision.IGNORE)
    }

    @Test
    fun `permission dialogs and missing package names never trigger a pause`() {
        // 录屏授权对话框、无障碍设置里的系统弹窗都发生在“用户正在操作本应用”期间，
        // 它们不是真的换了前台应用，不能把自动走子停掉。
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.android.permissioncontroller", true)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(
            owner, "com.game", "com.google.android.permissioncontroller", true
        ) is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "android", true)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", null, true)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "   ", true)
            is ForegroundAppPolicy.Decision.IGNORE)
    }

    @Test
    fun `same package does not repeat the switch decision`() {
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.game", true)
            is ForegroundAppPolicy.Decision.IGNORE)
    }

    /**
     * 用户的实际动线：在棋盘应用里点开始 → 切去微信 → 回到棋盘应用。
     * 第一步建立基准，第二步必须报告切换，第三步把基准切回棋盘应用。
     */
    @Test
    fun `typical leave and return flow only reports the real switch`() {
        var baseline: String? = null
        val first = ForegroundAppPolicy.observe(owner, baseline, "com.game", true)
        assertTrue(first is ForegroundAppPolicy.Decision.BASELINE)
        baseline = (first as ForegroundAppPolicy.Decision.BASELINE).packageName

        val second = ForegroundAppPolicy.observe(owner, baseline, "com.weixin", true)
        assertEquals(ForegroundAppPolicy.Decision.CHANGED("com.game", "com.weixin"), second)
        baseline = (second as ForegroundAppPolicy.Decision.CHANGED).current

        val third = ForegroundAppPolicy.observe(owner, baseline, "com.game", true)
        assertEquals(ForegroundAppPolicy.Decision.CHANGED("com.weixin", "com.game"), third)
    }
}
