package com.xiangqi.assist.assist.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 屏幕操作通道（无障碍服务）：提供按坐标点按、滑动和前台全屏窗口变化通知。
 *
 * 不读取控件文本、不遍历应用内容；窗口通知只使用事件包名、窗口类型和屏幕边界，
 * 用于在用户离开棋盘应用时立即停止自动落子，避免误触其它应用。
 */
class AssistAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
        emitCurrentForegroundWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        // 不直接信任事件包名：通知/权限弹窗的事件包名可能属于弹窗应用，
        // 但真正的全屏前台仍是底下的棋盘应用。只报告当前活动的 application window。
        emitApplicationWindow(e.windowId, e.packageName?.toString())
    }

    override fun onInterrupt() {
        Log.i(TAG, "accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun emitCurrentForegroundWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        emitApplicationWindow(windowId = null, eventPackage = null)
    }

    /**
     * 只从当前活动的 application window 发布包名和全屏状态。
     * 事件本身可能来自通知、权限面板或输入法，不能直接把事件包名当成前台应用。
     */
    private fun emitApplicationWindow(windowId: Int?, eventPackage: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val targetAndEventMatch = runCatching {
            val applicationWindows = windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .toList()
            val activeWindow = applicationWindows.firstOrNull { it.isActive || it.isFocused }
            val eventWindow = applicationWindows.firstOrNull { windowId != null && it.id == windowId }
            // 当前 active/focused application window 是前台真源；事件窗口只在 ROM
            // 没有标记活动窗口时兜底，避免后台窗口事件被误报成真实切出。
            val target = activeWindow ?: eventWindow
            target to (eventWindow != null && target === eventWindow)
        }.getOrNull() ?: return
        val target = targetAndEventMatch.first ?: return
        val eventWasApplicationWindow = targetAndEventMatch.second
        // canRetrieveWindowContent=false 的 ROM 可能不给 root；只有事件本身命中
        // application window 时，才允许把它携带的包名作为受限回退。系统通知事件
        // 即使当前活动 application 是棋盘，也不能把通知包名冒充前台应用。
        val packageName = runCatching { target.root?.packageName?.toString() }
            .getOrNull()?.trim().orEmpty().ifEmpty {
                if (eventWasApplicationWindow) eventPackage?.trim().orEmpty() else ""
            }.ifEmpty { return }
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        val dm = resources.displayMetrics
        globalForegroundObserver?.invoke(
            packageName,
            isFullScreenBounds(bounds, dm.widthPixels, dm.heightPixels),
        )
    }

    /** 供连线服务在已连接的无障碍通道上主动建立一次全屏基准。 */
    fun reportCurrentForegroundWindow() {
        emitCurrentForegroundWindow()
    }

    private fun isFullScreenBounds(bounds: Rect, screenW: Int, screenH: Int): Boolean {
        if (screenW <= 0 || screenH <= 0 || bounds.width() <= 0 || bounds.height() <= 0) return false
        val screenArea = screenW.toLong() * screenH.toLong()
        val windowArea = bounds.width().toLong() * bounds.height().toLong()
        // 状态栏/导航栏/圆角允许少量误差；分屏、小窗、画中画和浮层不会满足全部条件。
        val edgeX = maxOf(1, screenW / 12)
        val edgeY = maxOf(1, screenH / 12)
        return bounds.left <= edgeX &&
            bounds.top <= edgeY &&
            bounds.right >= screenW - edgeX &&
            bounds.bottom >= screenH - edgeY &&
            windowArea * 100L >= screenArea * 88L
    }

    /** 在指定屏幕坐标点按一次 */
    fun tap(x: Float, y: Float): Boolean = tap(x, y, null)

    /**
     * 在指定屏幕坐标点按一次，并在系统真正完成/取消手势时回报。
     * dispatchGesture 返回 true 只表示成功入队，不等于手势已经执行完。
     */
    fun tap(x: Float, y: Float, onFinished: ((Boolean) -> Unit)?): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchPath(path, 0L, TAP_DURATION_MS, onFinished)
    }

    /** 长按并在系统真正完成/取消手势时回报。 */
    fun pressAndHold(x: Float, y: Float, durationMs: Long = HOLD_DURATION_MS): Boolean =
        pressAndHold(x, y, durationMs, null)

    fun pressAndHold(
        x: Float,
        y: Float,
        durationMs: Long = HOLD_DURATION_MS,
        onFinished: ((Boolean) -> Unit)?,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchPath(path, 0L, durationMs, onFinished)
    }

    /** 点击式落子：起点和终点位于同一个系统手势事务中。 */
    fun tapSequence(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        gapMs: Long,
        onFinished: ((Boolean) -> Unit)?,
    ): Boolean {
        return try {
            val p1 = Path().apply { moveTo(fromX, fromY) }
            val p2 = Path().apply { moveTo(toX, toY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0L, TAP_DURATION_MS))
                .addStroke(
                    GestureDescription.StrokeDescription(
                        p2, TAP_DURATION_MS + gapMs.coerceAtLeast(0L), TAP_DURATION_MS
                    )
                )
                .build()
            dispatchGestureWithResult(gesture, onFinished)
        } catch (t: Throwable) {
            Log.w(TAG, "tapSequence failed", t)
            onFinished?.invoke(false)
            false
        }
    }

    /** 从起点滑到终点。 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        swipe(x1, y1, x2, y2, null)

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        onFinished: ((Boolean) -> Unit)?,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchPath(path, 0L, SWIPE_DURATION_MS, onFinished)
    }

    private fun dispatchPath(
        path: Path,
        startMs: Long,
        durationMs: Long,
        onFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        return try {
            val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGestureWithResult(gesture, onFinished)
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture failed", t)
            onFinished?.invoke(false)
            false
        }
    }

    private fun dispatchGestureWithResult(
        gesture: GestureDescription,
        onFinished: ((Boolean) -> Unit)?,
    ): Boolean {
        val callback = if (onFinished == null) null else object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onFinished(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onFinished(false)
            }
        }
        return dispatchGesture(gesture, callback, null)
    }

    companion object {
        private const val TAG = "AssistAccessibility"
        private const val TAP_DURATION_MS = 45L
        private const val HOLD_DURATION_MS = 320L
        private const val SWIPE_DURATION_MS = 180L

        @Volatile
        private var globalForegroundObserver: ((String?, Boolean) -> Unit)? = null

        /** 屏幕识别服务安装/移除前台切换回调；不读取窗口内容。 */
        fun setGlobalForegroundObserver(observer: ((String?, Boolean) -> Unit)?) {
            globalForegroundObserver = observer
        }

        /** 服务实例；未连接时为 null。 */
        @Volatile
        var instance: AssistAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        /** 本服务的无障碍组件全名。 */
        fun componentName(context: Context): String {
            val cn = ComponentName(context, AssistAccessibilityService::class.java)
            return cn.flattenToString()
        }

        /** 简写形式。 */
        fun componentShortName(context: Context): String {
            val cn = ComponentName(context, AssistAccessibilityService::class.java)
            return cn.flattenToShortString()
        }

        /** 查询系统设置中本服务是否已被勾选启用。 */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = readSetting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabled.isNullOrEmpty()) return false
            val target = componentName(context)
            val targetShort = componentShortName(context)
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (item in splitter) {
                if (item.equals(target, ignoreCase = true) || item.equals(targetShort, ignoreCase = true)) {
                    return true
                }
                val normalized = item.trim()
                if (normalized.equals(target, ignoreCase = true) ||
                    normalized.equals(targetShort, ignoreCase = true)
                ) return true
            }
            return false
        }

        /** 无障碍总开关是否打开。 */
        fun isAccessibilityEnabled(context: Context): Boolean =
            readSetting(context, Settings.Secure.ACCESSIBILITY_ENABLED) == "1"

        private fun readSetting(context: Context, key: String): String? = try {
            Settings.Secure.getString(context.contentResolver, key)
        } catch (t: Throwable) {
            Log.w(TAG, "read setting $key failed", t)
            null
        }

        /** 当前系统里已启用的全部无障碍组件。 */
        fun currentEnabledList(context: Context): String =
            readSetting(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

        /** 无障碍设置页。 */
        fun openSettingsIntent(): android.content.Intent =
            android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        val isGestureSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}
