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
    fun `authorized return from owner control page reports the original target change`() {
        assertEquals(
            ForegroundAppPolicy.Decision.CHANGED(owner, "com.game"),
            ForegroundAppPolicy.observe(owner, owner, "com.game", true),
        )
    }

    @Test
    fun `owner package is a real return target after leaving the game`() {
        // 真实日志回归：离开 cn.jj.chess.nearme.gamecenter 进入本应用时，
        // 应记录切出并暂停；随后再次看到游戏包名才能恢复。
        assertEquals(
            ForegroundAppPolicy.Decision.CHANGED(
                "cn.jj.chess.nearme.gamecenter", owner
            ),
            ForegroundAppPolicy.observe(
                owner, "cn.jj.chess.nearme.gamecenter", owner, true
            )
        )
        assertTrue(ForegroundAppPolicy.observe(owner, null, owner, true)
            is ForegroundAppPolicy.Decision.IGNORE)
    }

    @Test
    fun `overlay notification keyboard and permission windows are ignored`() {
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.weixin", false)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.android.systemui", true)
            is ForegroundAppPolicy.Decision.IGNORE)
        assertTrue(ForegroundAppPolicy.observe(owner, "com.game", "com.android.inputmethod.latin", true)
            is ForegroundAppPolicy.Decision.IGNORE)
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
