package com.xiangqi.assist.assist

import com.xiangqi.assist.assist.OverlayGeometry.EDGE_BOTTOM
import com.xiangqi.assist.assist.OverlayGeometry.EDGE_LEFT
import com.xiangqi.assist.assist.OverlayGeometry.EDGE_RIGHT
import com.xiangqi.assist.assist.OverlayGeometry.EDGE_TOP
import com.xiangqi.assist.assist.OverlayGeometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗几何：边缘拖拽改尺寸、默认贴满宽度、位置/尺寸的归一化往返。
 */
class OverlayGeometryTest {

    private val sw = 1080
    private val sh = 2400
    private val minW = 240
    private val minH = 200

    @Test
    fun `drag right edge only widens to the right`() {
        val start = Rect(0, 1800, 1080, 600)
        val r = OverlayGeometry.resize(start, dx = -300, dy = 0, edgeMask = EDGE_RIGHT,
            minW = minW, minH = minH, screenW = sw, screenH = sh)
        assertEquals(780, r.w)
        assertEquals(start.x, r.x)
        assertEquals(start.h, r.h)
    }

    @Test
    fun `drag left edge moves origin and keeps right edge fixed`() {
        val start = Rect(100, 1800, 800, 600) // 右边 = 900
        val r = OverlayGeometry.resize(start, dx = 200, dy = 0, edgeMask = EDGE_LEFT,
            minW = minW, minH = minH, screenW = sw, screenH = sh)
        assertEquals(300, r.x)
        assertEquals(600, r.w)
        assertEquals(900, r.x + r.w) // 右边不动
    }

    @Test
    fun `drag top edge moves origin and keeps bottom edge fixed`() {
        val start = Rect(0, 1000, 1000, 800) // 下边 = 1800
        val r = OverlayGeometry.resize(start, dx = 0, dy = -400, edgeMask = EDGE_TOP,
            minW = minW, minH = minH, screenW = sw, screenH = sh)
        assertEquals(600, r.y)
        assertEquals(1200, r.h)
        assertEquals(1800, r.y + r.h)
    }

    @Test
    fun `corner drag changes both width and height`() {
        val start = Rect(200, 500, 600, 500)
        val r = OverlayGeometry.resize(start, dx = 100, dy = 120,
            edgeMask = EDGE_RIGHT or EDGE_BOTTOM,
            minW = minW, minH = minH, screenW = sw, screenH = sh)
        assertEquals(700, r.w)
        assertEquals(620, r.h)
        assertEquals(200, r.x)
        assertEquals(500, r.y)
    }

    @Test
    fun `size never goes below the minimum`() {
        val start = Rect(0, 1000, 400, 400)
        val r = OverlayGeometry.resize(start, dx = -900, dy = -900,
            edgeMask = EDGE_RIGHT or EDGE_BOTTOM,
            minW = minW, minH = minH, screenW = sw, screenH = sh)
        assertEquals(minW, r.w)
        assertEquals(minH, r.h)
    }

    @Test
    fun `shrinking from the left keeps the right edge pinned`() {
        val start = Rect(100, 1000, 500, 400) // 右边 = 600
        val r = OverlayGeometry.resize(start, dx = 500, dy = 0, edgeMask = EDGE_LEFT,
            minW = minW, minH = minH, screenW = sw, screenH = sh)
        assertEquals(minW, r.w)
        assertEquals(600, r.x + r.w) // 抵住最小值后右边依然不动
    }

    @Test
    fun `rect stays inside the screen`() {
        val start = Rect(0, 0, 1080, 600)
        val r = OverlayGeometry.resize(start, dx = 0, dy = -400, edgeMask = EDGE_TOP,
            minW = minW, minH = minH, screenW = sw, screenH = sh)
        assertTrue(r.y >= 0)
        assertTrue(r.x + r.w <= sw)
        assertTrue(r.y + r.h <= sh)
    }

    @Test
    fun `default panel fills screen width at the bottom`() {
        val r = OverlayGeometry.defaultPanel(sw, sh, 324)
        assertEquals(0, r.x)
        assertEquals(sw, r.w)
        assertEquals(sh, r.y + r.h)
        assertTrue(r.h > 0)
    }

    @Test
    fun `normalize and restore round trip`() {
        val r = Rect(54, 1200, 972, 600)
        val n = OverlayGeometry.normalize(r, sw, sh)
        val back = OverlayGeometry.restore(n, OverlayGeometry.defaultPanel(sw, sh, 324),
            sw, sh, minW, minH)
        assertEquals(r.x, back.x)
        assertEquals(r.y, back.y)
        assertEquals(r.w, back.w)
        assertEquals(r.h, back.h)
    }

    @Test
    fun `restore falls back to default when nothing stored`() {
        val fallback = OverlayGeometry.defaultPanel(sw, sh, 324)
        val r = OverlayGeometry.restore(null, fallback, sw, sh, minW, minH)
        assertEquals(fallback.w, r.w)
        assertEquals(fallback.y, r.y)
    }

    @Test
    fun `restore clamps an off-screen stored rect back into view`() {
        // 存的是竖屏数值，横屏恢复（或屏幕变小）时不能跑到屏幕外
        val stored = floatArrayOf(0.9f, 0.95f, 0.5f, 0.5f)
        val r = OverlayGeometry.restore(stored, OverlayGeometry.defaultPanel(sw, sh, 324),
            sw, sh, minW, minH)
        assertTrue(r.x >= 0 && r.y >= 0)
        assertTrue(r.x + r.w <= sw)
        assertTrue(r.y + r.h <= sh)
    }

    @Test
    fun `ball is clamped between system bars with a visible margin`() {
        val (x, y) = OverlayGeometry.safeBallPosition(
            requestedX = 5000,
            requestedY = 5000,
            ballSize = 120,
            screenW = 1080,
            screenH = 2400,
            insetLeft = 0,
            insetTop = 80,
            insetRight = 0,
            insetBottom = 120,
            margin = 16,
        )
        assertEquals(944, x)
        assertEquals(2144, y)
    }

    @Test
    fun `ball saved behind status bar is restored into view`() {
        val (x, y) = OverlayGeometry.safeBallPosition(
            requestedX = -200,
            requestedY = -300,
            ballSize = 80,
            screenW = 1080,
            screenH = 2400,
            insetLeft = 4,
            insetTop = 72,
            insetRight = 4,
            insetBottom = 100,
            margin = 12,
        )
        assertEquals(16, x)
        assertEquals(84, y)
    }

    @Test
    fun `clamp enforces minimum size`() {
        val r = OverlayGeometry.clamp(Rect(0, 0, 10, 10), sw, sh, minW, minH)
        assertEquals(minW, r.w)
        assertEquals(minH, r.h)
    }
}
