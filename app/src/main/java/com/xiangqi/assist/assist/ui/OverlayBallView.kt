package com.xiangqi.assist.assist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.xiangqi.assist.assist.AssistHud
import kotlin.math.abs
import kotlin.math.min

/**
 * 缩起后的悬浮球：上下两行——
 * 第一行：当前胜率（高于五成绿 / 低于五成红 / 正好五成灰）；
 * 第二行：当前阶段短句（识盘中、计算中、对方落子、落子中…）。
 *
 * 手势：
 * - 单击 → 展开成完整面板；
 * - **双击 → 手动切换落子方**（把"轮到我方"切回来，重新识别→计算→落子）；
 * - 拖动 → 换位置。
 */
class OverlayBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class BallState { OK, WORKING, ERROR, IDLE }

    var state: BallState = BallState.IDLE
        set(value) { field = value; invalidate() }

    /** 外圈圆环颜色（按当前阶段着色；只影响圆环，不改球体与文字） */
    var ringColor: Int = 0xFF90A4AE.toInt()
        set(value) { field = value; invalidate() }

    /** 第一行：胜率文本（空则不显示） */
    var rateText: String = ""
        set(value) { field = value; invalidate() }

    /** 胜率颜色档 */
    var rateLevel: AssistHud.RateLevel = AssistHud.RateLevel.EVEN
        set(value) { field = value; invalidate() }

    /** 第二行：阶段短句 */
    var stageText: String = "待命"
        set(value) { field = value; invalidate() }

    /** 单击：还原面板 */
    var onExpand: (() -> Unit)? = null
    /** 双击：手动切换落子方 */
    var onDoubleTap: (() -> Unit)? = null
    var onDragStateChange: ((Boolean) -> Unit)? = null

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var onMove: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    /** 双击判定窗口 */
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var moved = false
    private var lastTapAt = 0L
    private var pendingSingleTap: Runnable? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE61B1E24.toInt()
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
    }
    private val ratePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setDragHandle(
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        onMove: () -> Unit,
    ) {
        this.params = params
        this.wm = wm
        this.onMove = onMove
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = min(width, height) / 2f - dp(2)
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(rect, fillPaint)
        // 外圈颜色 = 当前阶段色（由服务端按阶段设置）
        ringPaint.color = ringColor
        canvas.drawOval(rect, ringPaint)

        val width = width.toFloat()
        if (rateText.isNotEmpty()) {
            // 两行布局：上=胜率，下=阶段
            ratePaint.textSize = width * 0.27f * 0.80f
            ratePaint.color = when (rateLevel) {
                AssistHud.RateLevel.HIGH -> Color.parseColor("#FF69F0AE")
                AssistHud.RateLevel.LOW -> Color.parseColor("#FFFF5252")
                AssistHud.RateLevel.EVEN -> Color.parseColor("#FF9E9E9E")
            }
            stagePaint.textSize = width * 0.17f
            val fm1 = ratePaint.fontMetrics
            val fm2 = stagePaint.fontMetrics
            val total = (fm1.descent - fm1.ascent) + (fm2.descent - fm2.ascent)
            val top = cy - total / 2f
            canvas.drawText(rateText, cx, top - fm1.ascent, ratePaint)
            canvas.drawText(stageText, cx, top + (fm1.descent - fm1.ascent) - fm2.ascent, stagePaint)
        } else {
            // 暂无胜率：只显示阶段，占满球面
            stagePaint.textSize = width * 0.24f
            val fm = stagePaint.fontMetrics
            canvas.drawText(stageText, cx, cy - (fm.ascent + fm.descent) / 2f, stagePaint)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = params
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = p?.x ?: 0
                startY = p?.y ?: 0
                dragging = false
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) {
                    moved = true
                    dragging = true
                    onDragStateChange?.invoke(true)
                }
                if (dragging && p != null) {
                    p.x = startX + (event.rawX - downX).toInt()
                    p.y = startY + (event.rawY - downY).toInt()
                    wm?.updateViewLayout(this, p)
                    onMove?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onDragStateChange?.invoke(false)
                    onMove?.invoke()
                } else if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    handleTap()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 双击判定：第一次点击等一个双击窗口，窗口内再来一次算双击 */
    private fun handleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt <= doubleTapTimeout) {
            pendingSingleTap?.let { removeCallbacks(it) }
            pendingSingleTap = null
            lastTapAt = 0L
            onDoubleTap?.invoke()
            return
        }
        lastTapAt = now
        val r = Runnable {
            pendingSingleTap = null
            lastTapAt = 0L
            onExpand?.invoke()
        }
        pendingSingleTap = r
        postDelayed(r, doubleTapTimeout)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
