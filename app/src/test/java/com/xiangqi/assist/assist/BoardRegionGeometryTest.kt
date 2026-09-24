package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRegionGeometryTest {
    @Test
    fun `expanded board leaves five percent tolerance on each side`() {
        val region = BoardRegionGeometry.expandBoard(BoardGrid(0.20, 0.20, 0.80, 0.80))
        assertEquals(0.17f, region.x, 0.0001f)
        assertEquals(0.17f, region.y, 0.0001f)
        assertEquals(0.66f, region.width, 0.0001f)
        assertEquals(0.66f, region.height, 0.0001f)
    }

    @Test
    fun `unconfigured portrait fallback fills screen width and stays centered`() {
        val region = BoardRegionGeometry.centeredSquare(1080, 1920)
        assertEquals(0f, region.x, 0.0001f)
        assertEquals(1f, region.width, 0.0001f)
        assertEquals(0.5625f, region.height / region.width, 0.0001f)
        assertEquals(0.21875f, region.y, 0.0001f)
    }

    @Test
    fun `corner resize is bounded and keeps a minimum size`() {
        val start = BoardRegion(0.25f, 0.25f, 0.5f, 0.5f)
        val resized = BoardRegionGeometry.resizeCorner(
            start = start,
            corner = 0,
            dxPx = -1000f,
            dyPx = -1000f,
            screenW = 1000,
            screenH = 1000,
            minSizePx = 100f,
        )
        assertTrue(resized.x >= 0f)
        assertTrue(resized.y >= 0f)
        assertTrue(resized.right <= 1f)
        assertTrue(resized.bottom <= 1f)
        assertTrue(resized.width >= 0.1f)
        assertTrue(resized.height >= 0.1f)
    }
}
