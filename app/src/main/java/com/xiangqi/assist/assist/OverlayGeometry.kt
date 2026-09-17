package com.xiangqi.assist.assist

import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮窗几何计算（纯 JVM，便于单元测试）。
 *
 * 覆盖两件事：
 * 1. 边缘拖拽改尺寸：与桌面系统拖窗口边框一致——拖左边则左边跟着走、右边固定；
 *    拖上边同理；右下角可同时改宽高；始终不小于最小尺寸且不出屏；
 * 2. 位置/尺寸的归一化持久化与还原（跨分辨率可用）。
 */
object OverlayGeometry {

    /** 边缘位掩码 */
    const val EDGE_LEFT = 1
    const val EDGE_TOP = 2
    const val EDGE_RIGHT = 4
    const val EDGE_BOTTOM = 8

    class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        override fun toString(): String = "Rect(x=$x, y=$y, w=$w, h=$h)"
    }

    /**
     * 从起始矩形按拖拽位移 [dx]/[dy] 得到新矩形。
     *
     * @param edgeMask 当前抓取的边（EDGE_* 的组合）
     */
    fun resize(
        start: Rect,
        dx: Int,
        dy: Int,
        edgeMask: Int,
        minW: Int,
        minH: Int,
        screenW: Int,
        screenH: Int,
    ): Rect {
        var w = start.w
        var h = start.h
        var x = start.x
        var y = start.y

        if (edgeMask and EDGE_LEFT != 0) {
            w = start.w - dx
            x = start.x + dx
        }
        if (edgeMask and EDGE_RIGHT != 0) w = start.w + dx
        if (edgeMask and EDGE_TOP != 0) {
            h = start.h - dy
            y = start.y + dy
        }
        if (edgeMask and EDGE_BOTTOM != 0) h = start.h + dy

        // 最小尺寸：抵住后不再继续缩，并保持对侧边缘不动
        if (w < minW) {
            if (edgeMask and EDGE_LEFT != 0) x = start.x + start.w - minW
            w = minW
        }
        if (h < minH) {
            if (edgeMask and EDGE_TOP != 0) y = start.y + start.h - minH
            h = minH
        }
        // 屏幕范围
        if (x < 0) {
            if (edgeMask and EDGE_LEFT != 0) w += x
            x = 0
        }
        if (y < 0) {
            if (edgeMask and EDGE_TOP != 0) h += y
            y = 0
        }
        if (screenW > 0 && x + w > screenW) w = screenW - x
        if (screenH > 0 && y + h > screenH) h = screenH - y
        w = max(minW, if (screenW > 0) min(w, screenW) else w)
        h = max(minH, if (screenH > 0) min(h, screenH) else h)
        return Rect(x, y, w, h)
    }

    /** 矩形 -> 归一化 [x, y, w, h]（用于持久化） */
    fun normalize(r: Rect, screenW: Int, screenH: Int): FloatArray {
        if (screenW <= 0 || screenH <= 0) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            r.x.toFloat() / screenW, r.y.toFloat() / screenH,
            r.w.toFloat() / screenW, r.h.toFloat() / screenH
        )
    }

    /** 归一化值 -> 屏幕矩形（含最小尺寸与屏内夹取）。[norm] 为 null 时回落到 [fallback] */
    fun restore(
        norm: FloatArray?,
        fallback: Rect,
        screenW: Int,
        screenH: Int,
        minW: Int,
        minH: Int,
    ): Rect {
        if (norm == null || norm.size < 4 || norm[2] <= 0f || norm[3] <= 0f) {
            return clamp(fallback, screenW, screenH, minW, minH)
        }
        val w = (norm[2] * screenW).toInt()
        val h = (norm[3] * screenH).toInt()
        val x = (norm[0] * screenW).toInt()
        val y = (norm[1] * screenH).toInt()
        return clamp(Rect(x, y, w, h), screenW, screenH, minW, minH)
    }

    /** 把矩形限制为不小于最小尺寸且完全落在屏幕内 */
    fun clamp(r: Rect, screenW: Int, screenH: Int, minW: Int, minH: Int): Rect {
        var w = if (screenW > 0) min(max(r.w, minW), screenW) else max(r.w, minW)
        var h = if (screenH > 0) min(max(r.h, minH), screenH) else max(r.h, minH)
        var x = r.x
        var y = r.y
        if (screenW > 0) x = x.coerceIn(0, max(0, screenW - w)) else x = max(0, x)
        if (screenH > 0) y = y.coerceIn(0, max(0, screenH - h)) else y = max(0, y)
        if (screenW > 0 && x + w > screenW) w = screenW - x
        if (screenH > 0 && y + h > screenH) h = screenH - y
        return Rect(x, y, w, h)
    }

    /**
     * 按钮排几行（3 行 → 2 行 → 1 行）。纯函数，便于单测：
     * 窗口越矮行数越少，一直可以缩到只剩一行。
     */
    fun buttonRows(panelHeightPx: Int, rowHighPx: Int, rowMidPx: Int): Int = when {
        panelHeightPx >= rowHighPx -> 3
        panelHeightPx >= rowMidPx -> 2
        else -> 1
    }

    /**
     * 悬浮球的可见安全区。坐标仍是 WindowManager 的全屏坐标，但上下预留系统栏与额外边距，
     * 防止保存位置落进状态栏、挖孔或手势导航区后“缩小即消失”。
     */
    fun safeBallPosition(
        requestedX: Int,
        requestedY: Int,
        ballSize: Int,
        screenW: Int,
        screenH: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        margin: Int,
    ): Pair<Int, Int> {
        val minX = (insetLeft + margin).coerceAtLeast(0)
        val minY = (insetTop + margin).coerceAtLeast(0)
        val maxX = max(minX, screenW - insetRight - margin - ballSize)
        val maxY = max(minY, screenH - insetBottom - margin - ballSize)
        return requestedX.coerceIn(minX, maxX) to requestedY.coerceIn(minY, maxY)
    }

    /** 默认面板：宽度贴满屏幕、停在屏幕下方 */
    fun defaultPanel(screenW: Int, screenH: Int, height: Int): Rect {
        val h = if (screenH > 0) min(height, max(1, (screenH * 0.6f).toInt())) else height
        return Rect(0, max(0, screenH - h), screenW, h)
    }
}
