package com.xiangqi.assist.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落子前的合法性闸门。
 *
 * 用途：引擎/开局库给的是"针对我们以为的棋面"的着法。如果这个棋面与真实棋面有出入，
 * 这一步打到游戏里就会被判"走子不符合规范"（用户听到的那种禁止提示）。
 * 这道闸门把非法着法拦在发出之前，转而去重新识别，而不是白点一次。
 */
class LegalMoveGateTest {

    /** 把 canonical 局面摊平成 90 格数组（与 AutoMovePlanner/AssistBoard 的约定一致） */
    private fun flat(b: Array<IntArray>): IntArray {
        val out = IntArray(90)
        for (y in 0 until 10) for (x in 0 until 9) out[y * 9 + x] = b[y][x]
        return out
    }

    private val start = AssistBoard.canonicalStart()

    // UCCI 坐标约定：x = 列字母，y = 9 - 数字（红方恒在 y=9）
    // 开局红方在下方，"b0" = 第 1 列最下面一行 = 红马位。

    @Test
    fun `opening horse move is accepted`() {
        // 红马 b0 -> a2（马走日，合法）
        val b = AssistBoard.canonicalStart()
        // 先用规则引擎确认这个坐标对应的是红马，避免测试自身写错
        val from = b[9][1]
        assertTrue("坐标约定应指向红方棋子", from in 1..7)
        assertTrue(AssistBoard.isLegalMoveInModel(flat(b), "b0a2", mySideIsRed = true))
    }

    @Test
    fun `moving a piece that is not there is rejected`() {
        val b = AssistBoard.canonicalStart()
        // 从空格出发
        assertFalse(AssistBoard.isLegalMoveInModel(flat(b), "a4a5", mySideIsRed = true))
    }

    @Test
    fun `moving opponent piece is rejected`() {
        val b = AssistBoard.canonicalStart()
        // 黑方棋子在 y=0 那一侧；红方不可能去动它
        assertFalse(AssistBoard.isLegalMoveInModel(flat(b), "b9b7", mySideIsRed = true))
    }

    @Test
    fun `illegal geometry is rejected`() {
        val b = AssistBoard.canonicalStart()
        // 车走直线，"a0" 的横坐标 0 是红车；斜着走一格不合法
        assertFalse(AssistBoard.isLegalMoveInModel(flat(b), "a0b1", mySideIsRed = true))
    }

    @Test
    fun `malformed ucci is rejected`() {
        val b = AssistBoard.canonicalStart()
        assertFalse(AssistBoard.isLegalMoveInModel(flat(b), "", mySideIsRed = true))
        assertFalse(AssistBoard.isLegalMoveInModel(flat(b), "zzzz", mySideIsRed = true))
        assertFalse(AssistBoard.isLegalMoveInModel(flat(b), "a0a0", mySideIsRed = true))
    }

    @Test
    fun `undersized board array is rejected`() {
        assertFalse(AssistBoard.isLegalMoveInModel(IntArray(10), "b0a2", mySideIsRed = true))
    }

    @Test
    fun `a move that would be legal for the other side is rejected for us`() {
        val b = AssistBoard.canonicalStart()
        // 同一着法：把我方设为黑方，红马的走法就不该被放行
        assertFalse(AssistBoard.isLegalMoveInModel(flat(b), "b0a2", mySideIsRed = false))
    }
}
