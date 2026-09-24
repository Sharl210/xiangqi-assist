package com.xiangqi.assist.assist

import kotlin.math.max
import kotlin.math.min

/**
 * 用户框选的屏幕识别区域（归一化坐标，x/y/width/height 均为 0..1）。
 * 纯 JVM，默认值、四角拖动和边界夹取都在这里集中验证。
 */
data class BoardRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    /** 夹取到屏幕内，并保证最小边长；返回新对象，不修改自身。 */
    fun clamp(): BoardRegion {
        val w = width.coerceIn(MIN_SIZE, 1f)
        val h = height.coerceIn(MIN_SIZE, 1f)
        val cx = x.coerceIn(0f, 1f - w)
        val cy = y.coerceIn(0f, 1f - h)
        return BoardRegion(cx, cy, w, h)
    }

    /** `x, y, x1, y1` 形式的归一化边界，供稳定帧签名与识别裁剪复用。 */
    fun boundsArray(): DoubleArray = doubleArrayOf(
        x.toDouble(), y.toDouble(), right.toDouble(), bottom.toDouble(),
    )

    fun toArray(): FloatArray = floatArrayOf(x, y, width, height)

    companion object {
        const val MIN_SIZE = 0.02f
    }
}

object BoardRegionGeometry {
    /** 识别到棋盘后，四边各向外留 5% 棋盘宽/高作为容错。 */
    const val DEFAULT_TOLERANCE = 0.05f

    /** 合并棋盘局部裁剪与用户识别区域，保证模型输入不会越过用户设置的边界。 */
    fun intersectCrop(
        crop: DoubleArray?,
        region: DoubleArray?,
        minDimension: Double = 64.0,
    ): DoubleArray? {
        if (region == null || region.size < 4) return crop
        val regionBounds = doubleArrayOf(
            min(region[0], region[2]),
            min(region[1], region[3]),
            max(region[0], region[2]),
            max(region[1], region[3]),
        )
        if (crop == null || crop.size < 4) return regionBounds
        val x0 = max(crop[0], regionBounds[0])
        val y0 = max(crop[1], regionBounds[1])
        val x1 = min(crop[2], regionBounds[2])
        val y1 = min(crop[3], regionBounds[3])
        return if (x1 - x0 >= minDimension && y1 - y0 >= minDimension) {
            doubleArrayOf(x0, y0, x1, y1)
        } else {
            regionBounds
        }
    }

    fun fromArray(value: FloatArray?): BoardRegion? {
        if (value == null || value.size < 4) return null
        if (value[0] < 0f || value[1] < 0f || value[2] <= 0f || value[3] <= 0f) return null
        return BoardRegion(value[0], value[1], value[2], value[3]).clamp()
    }

    /** 棋盘网格四边各外扩 [tolerance] 倍的棋盘宽/高。 */
    fun expandBoard(grid: BoardGrid, tolerance: Float = DEFAULT_TOLERANCE): BoardRegion {
        val bw = grid.nx1 - grid.nx0
        val bh = grid.ny1 - grid.ny0
        return BoardRegion(
            (grid.nx0 - bw * tolerance).toFloat(),
            (grid.ny0 - bh * tolerance).toFloat(),
            (bw * (1.0 + tolerance * 2.0)).toFloat(),
            (bh * (1.0 + tolerance * 2.0)).toFloat(),
        ).clamp()
    }

    /**
     * 识别不到棋盘时的可操作默认值：屏幕正中、正方形、宽度铺满屏幕。
     * 竖屏时取屏宽为边长，因此左右贴边、上下居中。
     */
    fun centeredSquare(screenW: Int, screenH: Int): BoardRegion {
        if (screenW <= 0 || screenH <= 0) return BoardRegion(0f, 0f, 1f, 1f)
        val size = min(screenW, screenH).toFloat()
        val left = (screenW - size) / 2f
        val top = (screenH - size) / 2f
        return BoardRegion(
            left / screenW,
            top / screenH,
            size / screenW,
            size / screenH,
        ).clamp()
    }

    /**
     * 按物理像素拖动某个角后的区域；corner：0 左上、1 右上、2 右下、3 左下。
     * 夹取保证：不越屏、不翻转、不小于 [minSizePx]。
     */
    fun resizeCorner(
        start: BoardRegion,
        corner: Int,
        dxPx: Float,
        dyPx: Float,
        screenW: Int,
        screenH: Int,
        minSizePx: Float,
    ): BoardRegion {
        if (screenW <= 0 || screenH <= 0) return start.clamp()
        val x0 = start.x * screenW
        val y0 = start.y * screenH
        val x1 = start.right * screenW
        val y1 = start.bottom * screenH
        var left = x0
        var top = y0
        var right = x1
        var bottom = y1
        val c = corner.coerceIn(0, 3)
        when (c) {
            0 -> { left += dxPx; top += dyPx }
            1 -> { right += dxPx; top += dyPx }
            2 -> { right += dxPx; bottom += dyPx }
            else -> { left += dxPx; bottom += dyPx }
        }
        val minW = minSizePx.coerceAtMost(screenW.toFloat())
        val minH = minSizePx.coerceAtMost(screenH.toFloat())
        when (c) {
            0 -> { left = min(left, right - minW); top = min(top, bottom - minH) }
            1 -> { right = max(right, left + minW); top = min(top, bottom - minH) }
            2 -> { right = max(right, left + minW); bottom = max(bottom, top + minH) }
            else -> { left = min(left, right - minW); bottom = max(bottom, top + minH) }
        }
        left = left.coerceIn(0f, screenW - minW)
        top = top.coerceIn(0f, screenH - minH)
        right = right.coerceIn(left + minW, screenW.toFloat())
        bottom = bottom.coerceIn(top + minH, screenH.toFloat())
        return BoardRegion(
            left / screenW,
            top / screenH,
            (right - left) / screenW,
            (bottom - top) / screenH,
        ).clamp()
    }

    /** 归一化区域换算到某一帧的像素裁剪框（x0, y0, x1, y1），并夹取到帧内。 */
    fun toFramePixels(region: BoardRegion, frameW: Int, frameH: Int): DoubleArray {
        val r = region.clamp()
        val x0 = (r.x * frameW).toDouble().coerceIn(0.0, (frameW - 1).toDouble())
        val y0 = (r.y * frameH).toDouble().coerceIn(0.0, (frameH - 1).toDouble())
        val x1 = (r.right * frameW).toDouble().coerceIn(x0 + 1.0, frameW.toDouble())
        val y1 = (r.bottom * frameH).toDouble().coerceIn(y0 + 1.0, frameH.toDouble())
        return doubleArrayOf(x0, y0, x1, y1)
    }
}
