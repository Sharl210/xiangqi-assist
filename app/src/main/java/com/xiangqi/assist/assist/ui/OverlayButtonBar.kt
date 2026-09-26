package com.xiangqi.assist.assist.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.xiangqi.assist.assist.ButtonScrollAnchorPolicy
import kotlin.math.ceil

/**
 * 自适应行数的按钮栏。
 *
 * XML 里按普通方式声明若干按钮即可，本容器会在运行时把它们**重新分配到 N 行**：
 * - 面板较高 → 3 行；
 * - 面板变矮 → 自动收成 2 行、1 行；
 * - 收到 1 行时按钮仍保持固定宽度，超出的部分可在行内**横向滑动**后点按。
 *
 * 这样窗口高度才能一直缩到只剩一行按钮，而不是被"按钮占位"卡住。
 */
class OverlayButtonBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** XML 中声明的按钮（按声明顺序） */
    private val items = mutableListOf<View>()

    /** 承载各行的容器 */
    private var host: LinearLayout? = null

    private var rowCount = 0

    /** 单行高度（px），由面板按自身尺寸设置 */
    var rowHeightPx: Int = 0
        private set

    /** 滚动锚点按按钮在原始列表中的索引保存，行数改变后仍能定位到同一按钮。 */
    private var scrollAnchorProvider: (() -> ButtonScrollAnchorPolicy.Anchor?)? = null
    private var scrollAnchorListener: ((ButtonScrollAnchorPolicy.Anchor) -> Unit)? = null

    fun setScrollState(
        provider: (() -> ButtonScrollAnchorPolicy.Anchor?)?,
        listener: ((ButtonScrollAnchorPolicy.Anchor) -> Unit)?,
    ) {
        scrollAnchorProvider = provider
        scrollAnchorListener = listener
        rebuild()
    }

    var buttonGapPx: Int = 0
        private set

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) items += getChildAt(i)
        removeAllViews()
        host = LinearLayout(context).apply { orientation = VERTICAL }
        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rowCount = 1
        rebuild()
    }

    fun setMetrics(rowHeightPx: Int, buttonGapPx: Int) {
        if (rowHeightPx == this.rowHeightPx && buttonGapPx == this.buttonGapPx) return
        this.rowHeightPx = rowHeightPx
        this.buttonGapPx = buttonGapPx
        rebuild()
    }

    /** 设置需要几行（1..[MAX_ROWS]） */
    fun setRowCount(rows: Int) {
        val target = rows.coerceIn(1, MAX_ROWS)
        if (target == rowCount) return
        rowCount = target
        rebuild()
    }

    val currentRowCount: Int get() = rowCount

    /** 所有按钮（供边缘命中判定排除按钮区） */
    fun allButtons(): List<View> = items

    /** 按钮栏中实际按钮不再强制统一宽度；每个 TextView 按文字和左右内边距自测量。 */
    private fun rebuild() {
        val host = host ?: return
        val rowH = if (rowHeightPx > 0) rowHeightPx else LayoutParams.WRAP_CONTENT
        // `btnW` 仅保留作为旧布局参数的兼容占位；实际子项全部使用 WRAP_CONTENT。
        // 先摘下所有按钮，避免"重新分配到更少的行"时残留
        for (v in items) (v.parent as? ViewGroup)?.removeView(v)
        host.removeAllViews()

        val perRow = ceil(items.size / rowCount.toFloat()).toInt().coerceAtLeast(1)
        var idx = 0
        for (r in 0 until rowCount) {
            val rowStart = idx
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                setOnScrollChangeListener { _, scrollX, _, _, _ ->
                    if (row.childCount == 0) return@setOnScrollChangeListener
                    val childIndex = (0 until row.childCount).firstOrNull { child ->
                        row.getChildAt(child).right > scrollX
                    } ?: (row.childCount - 1)
                    val child = row.getChildAt(childIndex)
                    scrollAnchorListener?.invoke(
                        ButtonScrollAnchorPolicy.Anchor(
                            buttonIndex = rowStart + childIndex,
                            offsetWithinButtonPx = scrollX - child.left,
                        )
                    )
                }
            }
            host.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, rowH))
            var c = 0
            while (idx < items.size && c < perRow) {
                val b = items[idx]
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                lp.marginEnd = buttonGapPx
                row.addView(b, lp)
                idx++
                c++
            }
            val rowEnd = idx
            val anchor = scrollAnchorProvider?.invoke()
                ?.takeIf { it.buttonIndex in rowStart until rowEnd }
            if (anchor != null) {
                val childIndex = anchor.buttonIndex - rowStart
                scroll.post {
                    val target = row.getChildAt(childIndex)
                    scroll.scrollTo(
                        ButtonScrollAnchorPolicy.scrollX(target.left, anchor.offsetWithinButtonPx),
                        0,
                    )
                }
            }
        }
    }

    companion object {
        const val MAX_ROWS = 3
    }
}
