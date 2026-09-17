package com.xiangqi.assist.assist

import com.xiangqi.assist.assist.AutoLanding.PreVerdict
import com.xiangqi.assist.assist.AutoLanding.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 落子取证：两次截图。
 *
 * - **落子前**：眼前盘面是否仍等于"建议所依据的盘面"（不一致 = 建议过期，不能照落）；
 * - **落子后**：盘面有没有动（动了就算落上了，**不再**核对是否走了预期的那一步，
 *   也不再判断“能否由一手/两步合法到达”——那套判定曾造成死锁，已按用户要求删除）。
 */
class AutoLandingTest {

    private val start = AssistBoard.canonicalStart()

    private fun movedOneStep(): Array<IntArray> {
        // 红兵前进一步（模拟"盘面动过"）
        val b = AssistBoard.canonicalStart()
        b[6][0] = 0
        b[5][0] = com.xiangqi.assist.gamelogic.Piece.WBING
        return b
    }

    // ---------- 落子前：确认建议是否还对得上眼前这手棋 ----------

    @Test
    fun `pre check passes when the board is still the one advice was based on`() {
        assertEquals(PreVerdict.MATCH, AutoLanding.preCheck(start, AssistBoard.canonicalStart()))
    }

    @Test
    fun `pre check rejects a stale advice after the board moved`() {
        // 算完之后盘面又变了 → 这条建议已经过期，照落等于乱走
        assertEquals(PreVerdict.STALE, AutoLanding.preCheck(start, movedOneStep()))
    }

    @Test
    fun `pre check tolerates a single noisy cell when tolerance allows`() {
        val noisy = AssistBoard.canonicalStart()
        noisy[0][0] = 0 // 一格噪声
        assertEquals(PreVerdict.STALE, AutoLanding.preCheck(start, noisy, tolerance = 0))
        assertEquals(PreVerdict.MATCH, AutoLanding.preCheck(start, noisy, tolerance = 1))
    }

    @Test
    fun `pre check is unknown without a fresh picture`() {
        // 还没取到画面 → 先不落，等下一帧
        assertEquals(PreVerdict.UNKNOWN, AutoLanding.preCheck(start, null))
        assertEquals(PreVerdict.UNKNOWN, AutoLanding.preCheck(null, start))
    }

    // ---------- 落子后：只看盘面有没有动 ----------

    @Test
    fun `post check reports landed on any difference`() {
        assertEquals(Verdict.LANDED,
            AutoLanding.postCheck(start, movedOneStep(), 300, 900, 0, 5))
    }

    @Test
    fun `post check reports landed even on a large difference`() {
        // 吃掉一个帅这种大面积变化同样是“盘面动了”，不再被判成异常链路。
        val eaten = AssistBoard.canonicalStart()
        eaten[9][4] = 0
        assertEquals(Verdict.LANDED, AutoLanding.postCheck(start, eaten, 300, 900, 0, 5))
    }

    @Test
    fun `post check has no invalid verdict anymore`() {
        // 旧的 “一手/两步合法演进” 判定已完整删除：枚举里不应再存在 INVALID
        assertEquals(
            setOf("LANDED", "WAITING", "RETRY", "GIVE_UP", "UNKNOWN"),
            Verdict.values().map { it.name }.toSet()
        )
    }

    @Test
    fun `post check waits inside the window when nothing changed`() {
        assertEquals(Verdict.WAITING,
            AutoLanding.postCheck(start, AssistBoard.canonicalStart(), 500, 900, 0, 5))
    }

    @Test
    fun `post check asks for a retry after the window with nothing changed`() {
        // 窗口内盘面纹丝不动 = 没点上
        assertEquals(Verdict.RETRY,
            AutoLanding.postCheck(start, AssistBoard.canonicalStart(), 1500, 900, 0, 5))
    }

    @Test
    fun `post check gives up after too many retries`() {
        assertEquals(Verdict.GIVE_UP,
            AutoLanding.postCheck(start, AssistBoard.canonicalStart(), 1500, 900, 5, 5))
    }

    @Test
    fun `post check is unknown without boards`() {
        assertEquals(Verdict.UNKNOWN, AutoLanding.postCheck(null, start, 100, 900, 0, 5))
        assertEquals(Verdict.UNKNOWN, AutoLanding.postCheck(start, null, 100, 900, 0, 5))
    }

    @Test
    fun `landing flow is pre then post`() {
        // 串起来跑一遍完整判定：确认 → 落子 → 核对
        val advice = AssistBoard.canonicalStart()
        assertEquals(PreVerdict.MATCH, AutoLanding.preCheck(advice, AssistBoard.canonicalStart()))
        val preBoard = AssistBoard.canonicalStart()
        assertEquals(Verdict.WAITING, AutoLanding.postCheck(preBoard,
            AssistBoard.canonicalStart(), 100, 900, 0, 5))
        assertEquals(Verdict.LANDED, AutoLanding.postCheck(preBoard,
            movedOneStep(), 400, 900, 0, 5))
    }
}
