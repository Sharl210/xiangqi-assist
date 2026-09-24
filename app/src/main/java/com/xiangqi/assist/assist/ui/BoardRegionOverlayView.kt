package com.xiangqi.assist.assist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xiangqi.assist.assist.BoardRegion
import com.xiangqi.assist.assist.BoardRegionGeometry
import kotlin.math.abs

/**
 * 全屏框选层：把屏幕压暗，只把用户框出的识别范围留亮，四角可拖动。
 *
 * 它不参与识别、不读取屏幕内容；"完成"把归一化区域交回服务保存，"取消"则丢弃本次调整。
 * 这样用户就能把识别限制在棋盘那一小块，框外的动画和弹层不再影响稳定判定。
 */
class BoardRegionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var region: BoardRegion = BoardRegion(0f, 0f, 1f, 1f)
        private set

    var onComplete: ((BoardRegion) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        color = 0xFFFFD54F.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD54F.toInt()
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14).toFloat()
        isFakeBoldText = true
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDD17324D.toInt() }
    private val confirmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt() }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13).toFloat()
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val regionRect = RectF()
    private val confirmRect = RectF()
    private val cancelRect = RectF()
    private val handleRadius = dp(18).toFloat()
    private val minRegionPx = dp(96).toFloat()
    private val buttonBarTop = dp(8).toFloat()
    private val buttonHeight = dp(34).toFloat()
    private val buttonWidth = dp(72).toFloat()
    private var activeCorner = -1
    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true
        contentDescription = "框选棋盘范围"
    }

    fun setInitialRegion(value: BoardRegion) {
        region = value.clamp()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val r = region.clamp()
        regionRect.set(r.x * w, r.y * h, r.right * w, r.bottom * h)

        // 框外压暗，框内保留原屏幕画面：既看清当前范围，也还能辨认棋盘位置。
        canvas.drawRect(0f, 0f, w, regionRect.top, dimPaint)
        canvas.drawRect(0f, regionRect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, regionRect.top, regionRect.left, regionRect.bottom, dimPaint)
        canvas.drawRect(regionRect.right, regionRect.top, w, regionRect.bottom, dimPaint)
        canvas.drawRect(regionRect, borderPaint)

        for (corner in corners(regionRect)) {
            canvas.drawCircle(corner.first, corner.second, handleRadius, handlePaint)
        }

        canvas.drawText(
            "拖动四角调整识别范围",
            dp(14).toFloat(),
            dp(14).toFloat() + titlePaint.textSize,
            titlePaint,
        )

        val rightLimit = w - dp(14)
        cancelRect.set(rightLimit - buttonWidth, buttonBarTop, rightLimit, buttonBarTop + buttonHeight)
        confirmRect.set(
            cancelRect.left - dp(8) - buttonWidth, buttonBarTop,
            cancelRect.left - dp(8), buttonBarTop + buttonHeight,
        )
        canvas.drawRoundRect(confirmRect, dp(8).toFloat(), dp(8).toFloat(), confirmPaint)
        canvas.drawRoundRect(cancelRect, dp(8).toFloat(), dp(8).toFloat(), buttonPaint)
        drawButtonText(canvas, "完成", confirmRect)
        drawButtonText(canvas, "取消", cancelRect)
    }

    private fun drawButtonText(canvas: Canvas, text: String, rect: RectF) {
        val baseline = rect.centerY() - (buttonTextPaint.ascent() + buttonTextPaint.descent()) / 2f
        canvas.drawText(text, rect.centerX(), baseline, buttonTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (confirmRect.contains(x, y) || cancelRect.contains(x, y)) {
                    activeCorner = -1
                    downX = x
                    downY = y
                    return true
                }
                downX = x
                downY = y
                activeCorner = hitCorner(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner >= 0) {
                    region = BoardRegionGeometry.resizeCorner(
                        region,
                        activeCorner,
                        x - downX,
                        y - downY,
                        width,
                        height,
                        minRegionPx,
                    )
                    downX = x
                    downY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tapped = abs(x - downX) < dp(16) && abs(y - downY) < dp(16)
                if (tapped && confirmRect.contains(x, y)) {
                    onComplete?.invoke(region.clamp())
                } else if (tapped && cancelRect.contains(x, y)) {
                    onCancel?.invoke()
                }
                activeCorner = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeCorner = -1
                return true
            }
        }
        return true
    }

    private fun hitCorner(x: Float, y: Float): Int {
        val radius = handleRadius * 1.8f
        corners(regionRect).forEachIndexed { index, corner ->
            if (abs(x - corner.first) <= radius && abs(y - corner.second) <= radius) return index
        }
        return -1
    }

    /** 顺序固定为：0 左上、1 右上、2 右下、3 左下，与 [BoardRegionGeometry.resizeCorner] 一致。 */
    private fun corners(rect: RectF): Array<Pair<Float, Float>> = arrayOf(
        rect.left to rect.top,
        rect.right to rect.top,
        rect.right to rect.bottom,
        rect.left to rect.bottom,
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
