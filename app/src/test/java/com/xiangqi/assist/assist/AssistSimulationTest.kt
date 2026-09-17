package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仿真相关的纯逻辑：按钮行数自适应、随机偏移仍在棋子范围内。
 */
class AssistSimulationTest {

    // ---------- 按钮行数自适应（对应"窗口能一直缩到只剩一行"） ----------

    @Test
    fun `button rows shrink as the panel gets shorter`() {
        val high = 400
        val mid = 300
        assertEquals(3, OverlayGeometry.buttonRows(520, high, mid))
        assertEquals(3, OverlayGeometry.buttonRows(400, high, mid))
        assertEquals(2, OverlayGeometry.buttonRows(399, high, mid))
        assertEquals(2, OverlayGeometry.buttonRows(300, high, mid))
        assertEquals(1, OverlayGeometry.buttonRows(299, high, mid))
        // 极小高度也必须给出行数（而不是 0 行导致"缩不下去"）
        assertEquals(1, OverlayGeometry.buttonRows(112, high, mid))
        assertEquals(1, OverlayGeometry.buttonRows(1, high, mid))
    }

    // ---------- 仿真·随机偏移 ----------

    private fun plan() = AutoMove("b0b9", 100f, 200f, 400f, 500f)

    @Test
    fun `jitter always stays inside the same cell`() {
        val cellW = 60f
        val cellH = 70f
        val rnd = java.util.Random(42)
        repeat(500) {
            val j = AutoMovePlanner.applyJitter(plan(), cellW, cellH, rnd)
            // 偏移上限已收紧到 5% 格距：远在棋子半径之内，绝不会点到相邻格子或棋子外
            // （历史上 18% 时偏移会贴近格子边缘，是"落子点不中"的重要原因）
            assertTrue("起点 x 偏移过大：${j.fromX}", kotlin.math.abs(j.fromX - 100f) <= cellW * 0.06f)
            assertTrue("起点 y 偏移过大：${j.fromY}", kotlin.math.abs(j.fromY - 200f) <= cellH * 0.06f)
            assertTrue("终点 x 偏移过大：${j.toX}", kotlin.math.abs(j.toX - 400f) <= cellW * 0.06f)
            assertTrue("终点 y 偏移过大：${j.toY}", kotlin.math.abs(j.toY - 500f) <= cellH * 0.06f)
        }
    }

    @Test
    fun `jitter actually varies between calls`() {
        val rnd = java.util.Random(7)
        val first = AutoMovePlanner.applyJitter(plan(), 60f, 70f, rnd)
        var differs = false
        repeat(20) {
            val next = AutoMovePlanner.applyJitter(plan(), 60f, 70f, rnd)
            if (next.fromX != first.fromX || next.fromY != first.fromY) differs = true
        }
        assertTrue("随机偏移没有产生任何变化", differs)
    }

    @Test
    fun `jitter keeps the move identity`() {
        val j = AutoMovePlanner.applyJitter(plan(), 60f, 70f, java.util.Random(1))
        assertEquals("b0b9", j.ucci)
    }

    @Test
    fun `jitter with degenerate cell still bounded`() {
        // 网格尚未就绪（格距 0）时不放大偏移，也不抛异常
        val j = AutoMovePlanner.applyJitter(plan(), 0f, 0f, java.util.Random(3))
        assertEquals(100f, j.fromX, 1e-3f)
        assertEquals(500f, j.toY, 1e-3f)
    }

    @Test
    fun `zero jitter fraction keeps coordinates identical`() {
        val j = AutoMovePlanner.applyJitter(plan(), 60f, 70f, java.util.Random(5), fraction = 0f)
        assertEquals(100f, j.fromX, 1e-3f)
        assertEquals(200f, j.fromY, 1e-3f)
        assertEquals(400f, j.toX, 1e-3f)
        assertEquals(500f, j.toY, 1e-3f)
        assertNotEquals(0, j.ucci.length)
    }

    @Test
    fun `jitter is small enough to always land on the piece`() {
        // 棋子直径约等于格距。偏移必须远小于半径，才能保证点得中。
        // 取最坏情形（始终偏向同一侧）抽样验证。
        val cell = 60f
        val rnd = java.util.Random(2024)
        var maxOffset = 0f
        repeat(2000) {
            val j = AutoMovePlanner.applyJitter(plan(), cell, cell, rnd)
            // plan 是 (100,200) → (400,500)，所以偏移要各自对原位坐标比较
            maxOffset = maxOf(maxOffset,
                kotlin.math.abs(j.fromX - 100f), kotlin.math.abs(j.fromY - 200f),
                kotlin.math.abs(j.toX - 400f), kotlin.math.abs(j.toY - 500f))
        }
        // 留一半余量：偏移不超过格距的 6%（=半径的 12%）
        assertTrue("偏移最大 $maxOffset 超出安全范围", maxOffset <= cell * 0.06f)
    }
}
