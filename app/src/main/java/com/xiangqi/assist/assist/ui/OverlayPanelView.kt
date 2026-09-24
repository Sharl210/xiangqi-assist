package com.xiangqi.assist.assist.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.xiangqi.assist.R
import com.xiangqi.assist.assist.AssistHud
import com.xiangqi.assist.assist.OverlayGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 悬浮窗显示模型 */
class OverlayModel(
    /** 多行信息区（识别/引擎细节，可滚动） */
    val statusText: String = "识别中…",
    /** 标题行：当前阶段（识盘中/计算中/对方落子/落子中…） */
    val stageText: String = "识盘中",
    /** 胜率文本（如 63.25%），空则不显示 */
    val winRateText: String = "",
    /** 胜率颜色档 */
    val winRateLevel: AssistHud.RateLevel = AssistHud.RateLevel.EVEN,
    /** canonical 布局 90 子（红恒在 y=9），null 表示尚无局面 */
    val pieces: IntArray? = null,
    /** 推荐走法起止（canonical 内部坐标 x,y） */
    val arrowFrom: Pair<Int, Int>? = null,
    val arrowTo: Pair<Int, Int>? = null,
    /** 被识别屏幕红方在上（迷你棋盘随动：同样红在上显示） */
    val flipped: Boolean = false,
    val mySideIsRed: Boolean = true,
    /** 建议已"定着"（成熟）：箭头用绿色；false 为暂定（蓝箭头） */
    val mature: Boolean = false,
    /** 当前工作模式名（自动/半自动/手动） */
    val modeText: String = "自动",
    /** 自动走子开关是否开启（「自动走子」按钮点亮） */
    val autoPlay: Boolean = false,
    /** 是否处于运行中（决定「开始/暂停」按钮文案） */
    val running: Boolean = false,
    /** 前台保护期间的状态提示；按钮仍由暂停快照控制。 */
    val suspendedByForeground: Boolean = false,
    /** 本服务会话是否已真正开始过；暂停后按钮显示“继续”。 */
    val hasRunSession: Boolean = false,
    /** 仿真模式开关是否开启 */
    val sim: Boolean = false,
    /** 引擎强度按钮文案 */
    val strengthText: String = "时间3s",
    /** 标题左侧的己方颜色按钮文案，直接表示当前分析方 */
    val mySideText: String = "己方红",
    /** 手动模式可切换；自动检测模式下仅显示当前检测结果并禁用点击。 */
    val mySideSelectable: Boolean = true,
    /** 候选数量档位文本（1..6），与引擎实际 MultiPV 完全一致 */
    val candidateCountText: String = "候选1",
    /** 引擎 Hash 按钮文案 */
    val hashText: String = "Hash512",
    /** 紧急手动模式：小棋盘点按用于选子/走子/删子，而非切换候选 */
    val manualMode: Boolean = false,
    /** 手动模式下当前选中的格（canonical x,y），迷你棋盘绘制高亮 */
    val selectedCell: Pair<Int, Int>? = null,
    /** 已保存过用户框选范围；按钮会显示绿色，下一次打开直接恢复该区域。 */
    val boardRegionConfigured: Boolean = false,
    /** “下一手变招”意图尚未执行时保持绿色；实际非默认着法执行后恢复白色。 */
    val nextVariationPending: Boolean = false,
    /** 当前阶段的外圈颜色（只画边框圆环，不动整体配色） */
    val ringColor: Int = AssistHud.RING_IDLE,
)

enum class OverlayAction {
    /** 开始 / 暂停（同一个按钮） */
    TOGGLE_RUN,
    /** 模式轮换：自动 → 半自动 → 手动 → 自动 */
    CYCLE_MODE,
    /** 自动走子开关 */
    TOGGLE_AUTO,
    /** 仿真模式开关（随机偏移/候选起点预选）；点击式落子间隔由独立策略始终随机化 */
    TOGGLE_SIM,
    /** 候选数量档位 */
    CYCLE_CANDIDATE_COUNT,
    /** 预存下一次我方实际落子的非默认候选；可以在对方回合或尚无候选时点击。 */
    CYCLE_CANDIDATE,
    /** 悔棋：回退到上一个已确认局面 */
    UNDO,
    /** 更新棋谱：立刻截一帧并刷新棋面 */
    REFRESH_BOARD,
    /** 重置：打断思考并回到识盘状态 */
    STOP_RESET,
    /** 切换本次分析的己方颜色：红方 ↔ 黑方 */
    TOGGLE_MY_SIDE,
    /** 切换走棋方 */
    FLIP_TURN,
    /** 思考模式：切换深度/时间；仍与原强度按钮共用一个可见按钮。 */
    TOGGLE_THINKING_MODE,
    /** 当前思考模式下循环档位 */
    CYCLE_STRENGTH,
    /** 引擎 Hash 档位循环 */
    CYCLE_HASH,
    /** 框选棋盘识别范围 */
    EDIT_BOARD_REGION,
    /** 缩小为悬浮球 */
    COLLAPSE,
    /** 关闭连线 */
    CLOSE,
}

class OverlayPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val chess: OverlayChessView
    private val status: TextView
    private val statusScroll: android.widget.ScrollView
    private val title: TextView
    private val btnMySide: TextView
    private val winRate: TextView
    private val buttonBar: OverlayButtonBar
    private val btnMode: TextView
    private val btnAutoPlay: TextView
    private val btnSim: TextView
    private val btnStrength: TextView
    private val btnCandidateCount: TextView
    private val btnHash: TextView
    private val btnChange: TextView
    private val btnBoardRegion: TextView
    private val btnStop: TextView
    private val btnRun: TextView
    private val btnClose: TextView

    var onAction: ((OverlayAction) -> Unit)? = null
    /** 手动模式：小棋盘点格（canonical x,y），由服务端处理选子/走子/删子 */
    var onCellTap: ((x: Int, y: Int) -> Unit)? = null
    /** 拖动/缩放开始或结束 */
    var onDragStateChange: ((Boolean) -> Unit)? = null

    // 关闭二次确认：误触风险大
    private var closeArmed = false
    private val normalColor: Int = Color.WHITE
    private val closeReset = Runnable { resetCloseState() }

    private fun resetCloseState() {
        closeArmed = false
        btnClose.text = "关闭"
        btnClose.setTextColor(normalColor)
    }

    // ==================== 拖动 / 缩放 ====================

    private enum class DragMode { NONE, MOVE, RESIZE }

    private val edgeSlop = dp(14)

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var onMove: (() -> Unit)? = null

    private var screenW = 0
    private var screenH = 0
    private var minW = dp(240)
    private var minH = dp(110)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0
    private var dragging = false
    private var dragMode = DragMode.NONE

    private var activeEdge = 0
    private var hoveringEdge = 0

    /** 阶段外圈：圆角矩形描边 */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val ringRect = android.graphics.RectF()
    private var stageRingColor: Int = AssistHud.RING_IDLE

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD54F.toInt()
    }
    private val arrowPath = Path()

    private val EDGE_LEFT = 1
    private val EDGE_TOP = 2
    private val EDGE_RIGHT = 4
    private val EDGE_BOTTOM = 8

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_panel, this, true)
        chess = findViewById(R.id.overlay_chess)
        status = findViewById(R.id.overlay_status)
        statusScroll = findViewById(R.id.overlay_status_scroll)
        title = findViewById(R.id.overlay_title)
        btnMySide = findViewById(R.id.overlay_btn_my_side)
        winRate = findViewById(R.id.overlay_winrate)
        buttonBar = findViewById(R.id.overlay_button_bar)
        btnMode = findViewById(R.id.overlay_btn_mode)
        btnAutoPlay = findViewById(R.id.overlay_btn_autoplay)
        btnSim = findViewById(R.id.overlay_btn_sim)
        btnStrength = findViewById(R.id.overlay_btn_strength)
        btnCandidateCount = findViewById(R.id.overlay_btn_candidate_count)
        btnHash = findViewById(R.id.overlay_btn_hash)
        btnChange = findViewById(R.id.overlay_btn_change)
        btnBoardRegion = findViewById(R.id.overlay_btn_board_region)
        btnStop = findViewById(R.id.overlay_btn_stop)
        btnRun = findViewById(R.id.overlay_btn_run)
        btnClose = findViewById(R.id.overlay_btn_close)
        btnMySide.setOnClickListener { onAction?.invoke(OverlayAction.TOGGLE_MY_SIDE) }
        btnMode.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_MODE) }
        btnAutoPlay.setOnClickListener { onAction?.invoke(OverlayAction.TOGGLE_AUTO) }
        btnSim.setOnClickListener { onAction?.invoke(OverlayAction.TOGGLE_SIM) }
        btnChange.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_CANDIDATE) }
        findViewById<TextView>(R.id.overlay_btn_undo).setOnClickListener { onAction?.invoke(OverlayAction.UNDO) }
        findViewById<TextView>(R.id.overlay_btn_refresh).setOnClickListener { onAction?.invoke(OverlayAction.REFRESH_BOARD) }
        btnRun.setOnClickListener { onAction?.invoke(OverlayAction.TOGGLE_RUN) }
        btnStop.setOnClickListener { onAction?.invoke(OverlayAction.STOP_RESET) }
        findViewById<TextView>(R.id.overlay_btn_turn).setOnClickListener { onAction?.invoke(OverlayAction.FLIP_TURN) }
        btnStrength.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_STRENGTH) }
        btnStrength.setOnLongClickListener {
            onAction?.invoke(OverlayAction.TOGGLE_THINKING_MODE)
            true
        }
        btnCandidateCount.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_CANDIDATE_COUNT) }
        btnHash.setOnClickListener { onAction?.invoke(OverlayAction.CYCLE_HASH) }
        btnBoardRegion.setOnClickListener { onAction?.invoke(OverlayAction.EDIT_BOARD_REGION) }
        findViewById<TextView>(R.id.overlay_btn_collapse).setOnClickListener { onAction?.invoke(OverlayAction.COLLAPSE) }
        btnClose.setOnClickListener {
            if (!closeArmed) {
                closeArmed = true
                btnClose.text = "确认?"
                btnClose.setTextColor(0xFFFF5252.toInt())
                postDelayed(closeReset, 3000)
            } else {
                removeCallbacks(closeReset)
                closeReset.run()
                onAction?.invoke(OverlayAction.CLOSE)
            }
        }
        chess.onNextCandidate = { onAction?.invoke(OverlayAction.CYCLE_CANDIDATE) }
        chess.onCellTap = { x, y -> onCellTap?.invoke(x, y) }
    }

    private val buttons: List<android.view.View>
        get() = buttonBar.allButtons() + listOf(btnClose, findViewById(R.id.overlay_btn_collapse))

    fun render(model: OverlayModel) {
        // Keep the display-only button accent explicit so rapid status renders cannot clear it.
        status.text = model.statusText
        title.text = model.stageText
        if (model.winRateText.isEmpty()) {
            winRate.text = ""
        } else {
            winRate.text = model.winRateText
            winRate.setTextColor(
                when (model.winRateLevel) {
                    AssistHud.RateLevel.HIGH -> WIN_HIGH
                    AssistHud.RateLevel.LOW -> WIN_LOW
                    AssistHud.RateLevel.EVEN -> WIN_EVEN
                }
            )
        }
        if (model.ringColor != stageRingColor) {
            stageRingColor = model.ringColor
            invalidate()
        }
        btnRun.text = when {
            model.suspendedByForeground -> "暂停"
            model.running -> "暂停"
            model.hasRunSession -> "继续"
            else -> "开始"
        }
        // 前台保护只是内部临时暂停，不锁死用户按钮；“暂停”仍可被用户明确点击。
        btnRun.isEnabled = true
        btnRun.setTextColor(if (model.running || model.suspendedByForeground) ON_COLOR else normalColor)
        btnMySide.text = model.mySideText
        btnMySide.isEnabled = model.mySideSelectable
        btnMySide.alpha = if (model.mySideSelectable) 1f else 0.48f
        btnMode.text = model.modeText
        btnAutoPlay.setTextColor(if (model.autoPlay) ON_COLOR else normalColor)
        btnSim.setTextColor(if (model.sim) ON_COLOR else normalColor)
        btnStrength.text = model.strengthText
        btnCandidateCount.text = model.candidateCountText
        btnHash.text = model.hashText
        btnChange.setTextColor(if (model.nextVariationPending) ON_COLOR else normalColor)
        btnBoardRegion.setTextColor(if (model.boardRegionConfigured) ON_COLOR else normalColor)
        chess.pieces = model.pieces
        chess.arrowFrom = model.arrowFrom
        chess.arrowTo = model.arrowTo
        chess.flipped = model.flipped
        chess.mySideIsRed = model.mySideIsRed
        chess.mature = model.mature
        chess.manualMode = model.manualMode
        chess.selectedCell = model.selectedCell
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateButtonRows(h)
    }

    /**
     * 按面板高度决定按钮排几行：高了 3 行，变矮自动收成 2 行、1 行。
     * 1 行时按钮保持固定宽度，可在行内横向滑动后点按——这样高度能一直缩到只剩一排。
     */
    private fun updateButtonRows(h: Int) {
        val rowH = dp(30)
        buttonBar.setMetrics(rowH, dp(2))
        buttonBar.setRowCount(
            OverlayGeometry.buttonRows(h, dp(400), dp(300))
        )
    }

    fun setDragHandle(
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        screenW: Int,
        screenH: Int,
        onMove: () -> Unit,
    ) {
        this.params = params
        this.wm = wm
        this.screenW = screenW
        this.screenH = screenH
        this.onMove = onMove
    }

    /** 点是否落在按钮栏整块区域内（含行间空隙与外边距） */
    private fun isInsideButtonBar(x: Float, y: Float): Boolean {
        val bar = buttonBar
        if (bar.visibility != VISIBLE || bar.width <= 0) return false
        val (l, t) = offsetOf(bar)
        return x >= l && x <= l + bar.width && y >= t && y <= t + bar.height
    }

    /** 命中哪条边（0=不在边缘）。落在按钮栏或状态文本区时不算边缘。 */
    private fun hitEdge(x: Float, y: Float): Int {
        if (isInsideButtonBar(x, y) || isOnButton(x, y) || isInsideStatusScroll(x, y)) return 0
        var e = 0
        if (x <= edgeSlop) e = e or EDGE_LEFT
        if (x >= width - edgeSlop) e = e or EDGE_RIGHT
        if (y <= edgeSlop) e = e or EDGE_TOP
        if (y >= height - edgeSlop) e = e or EDGE_BOTTOM
        return e
    }

    /** 视图坐标 -> 相对本视图的偏移 */
    private fun offsetOf(v: android.view.View): Pair<Float, Float> {
        var l = 0f
        var t = 0f
        var cur: android.view.View? = v
        while (cur != null && cur !== this) {
            l += cur.left.toFloat()
            t += cur.top.toFloat()
            cur = cur.parent as? android.view.View
        }
        return l to t
    }

    private fun isOnButton(x: Float, y: Float): Boolean {
        for (b in buttons) {
            if (b.visibility != VISIBLE || b.width <= 0) continue
            val (l, t) = offsetOf(b)
            if (x >= l && x <= l + b.width && y >= t && y <= t + b.height) return true
        }
        return false
    }

    private fun isInsideStatusScroll(x: Float, y: Float): Boolean {
        val sc = statusScroll
        if (sc.visibility != VISIBLE || sc.width <= 0) return false
        val (l, t) = offsetOf(sc)
        return x >= l && x <= l + sc.width && y >= t && y <= t + sc.height
    }

    /** 本帧手势是否应从状态文本区开始（是则不做移动/缩放拦截） */
    private var touchOnStatus = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = params?.x ?: 0
                startY = params?.y ?: 0
                startW = params?.width ?: width
                startH = params?.height ?: height
                dragging = false
                val edge = hitEdge(ev.x, ev.y)
                if (edge != 0) {
                    dragMode = DragMode.RESIZE
                    activeEdge = edge
                    touchOnStatus = false
                    invalidate()
                    onDragStateChange?.invoke(true)
                    return true
                }
                // 落在状态文本区或底部按钮栏：整个手势交给它们自己处理
                // （文字区滚动 / 按钮横滑），不拖动、也不缩放窗口
                touchOnStatus = isInsideStatusScroll(ev.x, ev.y) || isInsideButtonBar(ev.x, ev.y)
                dragMode = DragMode.NONE
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.RESIZE) return true
                if (touchOnStatus) return false
                if (!dragging && (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop)) {
                    dragging = true
                    dragMode = DragMode.MOVE
                    onDragStateChange?.invoke(true)
                    return true
                }
                return dragging
            }
        }
        return false
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val p = params
                if (dragMode == DragMode.RESIZE && p != null) {
                    applyResize(p, ev.rawX - downX, ev.rawY - downY)
                    wm?.updateViewLayout(this, p)
                    onMove?.invoke()
                    return true
                }
                if (dragMode == DragMode.MOVE && p != null) {
                    p.x = startX + (ev.rawX - downX).toInt()
                    p.y = startY + (ev.rawY - downY).toInt()
                    wm?.updateViewLayout(this, p)
                    onMove?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == DragMode.RESIZE) {
                    dragMode = DragMode.NONE
                    activeEdge = 0
                    invalidate()
                    onDragStateChange?.invoke(false)
                    onMove?.invoke()
                    return true
                }
                if (dragMode == DragMode.MOVE) {
                    dragMode = DragMode.NONE
                    onDragStateChange?.invoke(false)
                    onMove?.invoke()
                    return true
                }
                touchOnStatus = false
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 尺寸计算在纯 JVM 的 [OverlayGeometry] 里，便于单元测试 */
    private fun applyResize(p: WindowManager.LayoutParams, dxF: Float, dyF: Float) {
        val r = OverlayGeometry.resize(
            OverlayGeometry.Rect(startX, startY, startW, startH),
            dxF.toInt(), dyF.toInt(), activeEdge,
            minW, minH, screenW, screenH
        )
        p.width = r.w
        p.height = r.h
        p.x = r.x
        p.y = r.y
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val edge = hitEdge(ev.x, ev.y)
            if (edge != hoveringEdge) {
                hoveringEdge = edge
                invalidate()
            }
            val icon = when {
                edge == 0 -> PointerIcon.getSystemIcon(context, PointerIcon.TYPE_DEFAULT)
                (edge and EDGE_LEFT != 0) && (edge and EDGE_TOP != 0) ||
                    (edge and EDGE_RIGHT != 0) && (edge and EDGE_BOTTOM != 0) ->
                    PointerIcon.getSystemIcon(context, PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW)
                (edge and EDGE_RIGHT != 0) && (edge and EDGE_TOP != 0) ||
                    (edge and EDGE_LEFT != 0) && (edge and EDGE_BOTTOM != 0) ->
                    PointerIcon.getSystemIcon(context, PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW)
                edge and (EDGE_LEFT or EDGE_RIGHT) != 0 ->
                    PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
                else -> PointerIcon.getSystemIcon(context, PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)
            }
            pointerIcon = icon
        }
        return super.onHoverEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 阶段外圈（圆环）：颜色随"识盘中/计算中/对方落子/落子中…"变化
        val inset = dp(2).toFloat()
        val radius = dp(18).toFloat()
        ringRect.set(inset, inset, width - inset, height - inset)
        ringPaint.color = stageRingColor
        canvas.drawRoundRect(ringRect, radius, radius, ringPaint)
        val edge = if (dragMode == DragMode.RESIZE) activeEdge else hoveringEdge
        if (edge == 0) return
        drawEdgeHighlight(canvas, edge)
        drawDoubleArrow(canvas, edge)
    }

    private fun drawEdgeHighlight(canvas: Canvas, edge: Int) {
        val t = dp(3).toFloat()
        edgePaint.color = EDGE_ACTIVE_COLOR
        if (edge and EDGE_LEFT != 0) canvas.drawRect(0f, 0f, t, height.toFloat(), edgePaint)
        if (edge and EDGE_RIGHT != 0) canvas.drawRect(width - t, 0f, width.toFloat(), height.toFloat(), edgePaint)
        if (edge and EDGE_TOP != 0) canvas.drawRect(0f, 0f, width.toFloat(), t, edgePaint)
        if (edge and EDGE_BOTTOM != 0) canvas.drawRect(0f, height - t, width.toFloat(), height.toFloat(), edgePaint)
    }

    private fun drawDoubleArrow(canvas: Canvas, edge: Int) {
        val cx = width / 2f
        val cy = height / 2f
        val s = dp(7).toFloat()
        if (edge and (EDGE_LEFT or EDGE_RIGHT) != 0) {
            val inward = if (edge and EDGE_LEFT != 0) 1f else -1f
            val baseX = if (edge and EDGE_LEFT != 0) dp(6).toFloat() else width - dp(6).toFloat()
            drawTriangle(canvas, baseX, cy, -inward * s, s)
            drawTriangle(canvas, baseX + inward * s * 1.4f, cy, inward * s, s)
        } else {
            val inward = if (edge and EDGE_TOP != 0) 1f else -1f
            val baseY = if (edge and EDGE_TOP != 0) dp(6).toFloat() else height - dp(6).toFloat()
            drawTriangleV(canvas, cx, baseY, -inward * s, s)
            drawTriangleV(canvas, cx, baseY + inward * s * 1.4f, inward * s, s)
        }
    }

    private fun drawTriangle(canvas: Canvas, x: Float, y: Float, dir: Float, size: Float) {
        arrowPath.reset()
        arrowPath.moveTo(x, y - size)
        arrowPath.lineTo(x, y + size)
        arrowPath.lineTo(x + dir, y)
        arrowPath.close()
        canvas.drawPath(arrowPath, glyphPaint)
    }

    private fun drawTriangleV(canvas: Canvas, x: Float, y: Float, dir: Float, size: Float) {
        arrowPath.reset()
        arrowPath.moveTo(x - size, y)
        arrowPath.lineTo(x + size, y)
        arrowPath.lineTo(x, y + dir)
        arrowPath.close()
        canvas.drawPath(arrowPath, glyphPaint)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 开关开启态高亮色 */
        private const val ON_COLOR = 0xFF69F0AE.toInt()

        /** 胜率颜色：高于五成绿、低于五成红、正好五成灰 */
        private const val WIN_HIGH = 0xFF69F0AE.toInt()
        private const val WIN_LOW = 0xFFFF5252.toInt()
        private const val WIN_EVEN = 0xFF9E9E9E.toInt()

        private val EDGE_ACTIVE_COLOR = Color.parseColor("#CCFFC107")
    }
}
