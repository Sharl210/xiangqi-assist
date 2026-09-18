package com.xiangqi.assist.assist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xiangqi.assist.R
import com.xiangqi.assist.assist.access.AssistAccessibilityService
import com.xiangqi.assist.assist.access.RootHelper
import com.xiangqi.assist.assist.ui.OverlayAction
import com.xiangqi.assist.assist.ui.OverlayBallView
import com.xiangqi.assist.assist.ui.OverlayModel
import com.xiangqi.assist.assist.ui.OverlayPanelView
import com.xiangqi.assist.gamelogic.Piece
import com.xiangqi.assist.gamelogic.Zobrist
import com.xiangqi.assist.openbook.BHOpenBook
import com.xiangqi.assist.openbook.BookData
import com.xiangqi.assist.openbook.OpenBook
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 连线服务：录制屏幕（MediaProjection）→ YOLO 识别棋盘 → 悬浮窗提示。
 *
 * 两种工作方式：
 * - 指导模式（默认）：只识别、只提示，走子由玩家手动完成；
 * - 自动模式（首次默认，面板可切换）：建议定着后，通过本应用的
 *   [AssistAccessibilityService]（屏幕操作通道）按坐标把着法落到屏幕上。
 *   自动模式需要用户先在系统设置里开启该无障碍服务（有 root 时可一键开启）。
 *
 * 隐私：识别只使用录屏画面；屏幕操作通道只派发点按/滑动，不读取界面内容。
 */
class ScreenAssistService : Service() {

    companion object {
        private const val TAG = "AssistService"
        const val ACTION_START = "com.xiangqi.assist.assist.START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "assist_foreground"
        private const val NOTIF_ID = 1001
        /**
         * 录屏流采样周期：每秒4帧。连续8帧稳定后只挑一帧送入模型。
         */
        private const val STREAM_SAMPLE_PERIOD_MS = FrameStabilityPolicy.SAMPLE_PERIOD_MS
        private const val STREAM_STABLE_FRAME_COUNT = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES
        /** 蓝色候选起点预选的最小间隔（同一 UCCI 起点已由策略去重）。 */
        private const val CANDIDATE_PREVIEW_MIN_GAP_MS = 150L
        /** 落子前提前把悬浮窗调透明的时间（避免点击动作按到悬浮窗上） */
        // 说明：原先"落子前提前 500ms 把悬浮窗调透明"的做法已删除。
        // 一是那 500ms 无条件白等（面板大多没压住棋盘），二是**透明并不能让点击穿透**——
        // 透明窗口照样接收触摸，棋盘收不到点击，落子自然失败。
        // 现在改为 suppressOverlayForLanding()：收起面板 + 全程不可触摸，且无需提前等待。
        /** 落子动作之间、以及动作与恢复之间的缓冲 */
        private const val AUTO_TAP_RESTORE_MS = 200L
        /** 建议成熟阈值：最佳着法连续未变化的时长，超过即视为"定着"（绿箭头） */
        private const val SUGGEST_STABLE_MS = 1200L
        /**
         * 单帧最少棋子数：低于该值视为"未见棋盘"（回全屏重定位）。
         * 残局可低至 3 子（帅仕 vs 将），不能沿用固定 10 子门槛——那会把合法残局
         * 永远拒之门外，反而让幻觉出 ≥10 检测的坏帧乘虚而入；合法性由 validate() 把关。
         */
        const val MIN_RECOGNIZED_PIECES = 3

        /** 与 YOLO letterbox 填充色一致的中性灰：面板区域涂成该色后不会被误检成棋子 */
        private const val LETTERBOX_GRAY = 0xFF727272.toInt()

        /** 半自动待机时的缓冲释放间隔（只释放、不处理，开销可忽略） */
        /** 同一手最多自动尝试几次（每次都"未见生效"就停止，避免无休止点击） */
        private const val MAX_AUTO_ATTEMPTS = 5

        /**
         * 落子确认窗口（毫秒）：点完到"盘面必须发生变化"的等待时间。
         * 比原来的 6 秒短很多——没落上就尽早重落，而不是干等。
         */
        private const val AUTO_ACK_WINDOW_MS = 900L

        /** 滑动落子后等待棋盘稳定的时间（够触发一次重绘即可，不必等太久） */
        private const val SWIPE_SETTLE_MS = 220L

        /** 连续这么多次识别失败后才丢弃网格、回全屏重找（避免裁剪/全屏来回抖动） */
        private const val CROP_RESET_MISSES = 6

        /** 自动走子要求的画面新鲜度：最近一次成功定位距今不得超过该毫秒数 */
        private const val AUTO_FRESH_MS = 1500L

        /** 自动走子容忍的最大"未确认帧数"（超过说明识别确实不稳，先不动） */
        private const val AUTO_MAX_UNSTABLE = 12

        /**
         * 半自动模式下"棋面有效期"：局面由用户点「更新棋谱」确认，
         * 不靠持续扫盘，因此用一个较宽松的上限兜底（15 分钟后视为过期）。
         */
        private const val AUTO_SEMI_VALID_MS = 15 * 60 * 1000L

        /** 状态监督间隔 */
        private const val REFRESH_WATCHDOG_TICK_MS = 800L
        /** 录屏/识别/落子管线独立监督的心跳间隔；不依赖普通阶段监督器是否仍在排队。 */
        private const val PIPELINE_WATCHDOG_TICK_MS = 600L
        /** 同一条“已整屏重新定位”提示的最小重复间隔，避免周期性恢复刷屏 */
        private const val VISION_RESET_STATUS_INTERVAL_MS = 30_000L
        /** 回执超时后给重新建基线保留的最大等待时间。 */
        private const val LANDING_REBASE_TIMEOUT_MS = 15_000L

        /**
         * 局面变化后的分析防抖窗口：只用来合并同一瞬间的重复提交，
         * 不能变成“为了看起来稳”的固定等待（旧值 250ms 会直接吃掉响应预算）。
         */
        private const val BOARD_CHANGE_DEBOUNCE_MS = 30L

        /** 内部通道恢复线程的硬超时；超时必须释放状态机，不能等用户暂停。 */
        private const val CHANNEL_RESTART_TIMEOUT_MS = 12_000L

        /** 每隔一段时间用整屏模型独立复核，防止局部裁剪在棋盘移动后自锁。 */
        private const val FULL_VISION_RECHECK_MS = 1800L
        /** 连续录屏输出的最长边；模型只需640输入，降低全屏拷贝和内存压力。 */
        private const val CAPTURE_MAX_DIMENSION = 1440
        /** 对方回合的最大搜索深度（再深也没人看，纯烧 CPU） */
        private const val OPPONENT_MAX_DEPTH = 12

        /** 无障碍通道检查/恢复最小间隔，通道断开时只做后台自愈，不关闭开关。 */
        private const val ACCESSIBILITY_RECOVERY_INTERVAL_MS = 2500L
        private const val ACCESSIBILITY_WATCHDOG_MS = 2500L
    }

    /**
     * 取帧策略（按当前模式决定是否需要画面）：
     * - [OFF]：完全不取帧。手动模式（自己摆棋）与暂停时使用，不产生任何截图开销；
     * - [ONE_SHOT]：待机时只把缓冲取出并释放（不做像素拷贝、不跑识别），
     *   仅在用户点「更新棋谱」时真正处理一帧——半自动模式使用；
     * - [CONTINUOUS]：周期性取帧识别（指导模式 / 自动走子）。
     */
    private enum class CaptureDemand { OFF, ONE_SHOT, CONTINUOUS }

    /** 供 Activity 绑定调用的接口 */
    inner class CastBinder : Binder() {
        fun service(): ScreenAssistService = this@ScreenAssistService
    }

    fun isCapturing(): Boolean = mediaProjection != null && virtualDisplay != null

    /** 用户可见的运行意图；切回本应用导致的临时安全暂停仍显示为可“一键关闭”。 */
    fun isRunning(): Boolean = AssistRunControlPolicy.hasRunningIntent(
        prepared = isCapturing(),
        paused = paused,
        foregroundWasRunning = foregroundPauseSnapshot?.wasRunning == true,
    )

    /** 最近一次状态文本（供辅助页显示） */
    fun getStatusText(): String = lastStatusText

    /** 自动走子是否开启（供辅助页显示）；配置和运行态只允许在用户明确关闭时变为 false。 */
    fun isAutoPlayOn(): Boolean = autoPlayOn || config.autoPlay

    /** 用户从应用控制页明确点击“一键启动”。 */
    fun requestStartFromControlEntry(): Boolean {
        if (!isCapturing()) return false
        mainHandler.post {
            if (AssistRunControlPolicy.canStart(
                    prepared = isCapturing(),
                    paused = paused,
                    foregroundWasRunning = foregroundPauseSnapshot?.wasRunning == true,
                )
            ) toggleRun()
        }
        return true
    }

    /** 用户从应用控制页明确点击“一键关闭”；保留录屏授权环境，停止识别、计算和落子。 */
    fun requestStopFromControlEntry(): Boolean {
        if (!isCapturing()) return false
        mainHandler.post {
            if (AssistRunControlPolicy.canStop(
                    prepared = isCapturing(),
                    paused = paused,
                    foregroundWasRunning = foregroundPauseSnapshot?.wasRunning == true,
                )
            ) {
                trace("RUN_STOP_FROM_APP", "landing=${landingState?.stage} searching=$searching")
                foregroundPauseSnapshot = null
                pauseRuntime(clearAnalysis = true)
                setStatus("当前已停止（不取帧、不识别、不计算）\n点『开始』后重新建立棋面基线")
                postRender()
            }
        }
        return true
    }

    /** App 设置页实时调整小球；重建窗口以便宽高、文字和触摸区域一起按同一比例生效。 */
    fun requestBallScale(scale: Float) {
        mainHandler.post {
            config.overlayBallScale = scale
            val old = overlayBall
            if (old != null) {
                saveBallPos()
                runCatching { windowManager?.removeView(old) }
                overlayBall = null
                overlayBallParams = null
                ensureBall()
                postRender()
            }
        }
    }

    fun ballScale(): Float = config.overlayBallScale

    /** 当前工作模式（供辅助页显示）：MODE_GUIDE / MODE_SEMI / MODE_MANUAL / MODE_AUTO */
    fun currentWorkMode(): Int = currentMode()

    /** 由辅助页切换工作模式（三态互斥）；内部转到主线程执行 */
    fun requestWorkMode(mode: Int) {
        mainHandler.post { setMode(mode) }
    }

    fun requestAutoPlay(on: Boolean) {
        mainHandler.post { setAutoPlayByUser(on) }
    }

    /** 用户明确操作自动走子开关的唯一写入口。系统故障恢复不得调用关闭分支。 */
    private fun setAutoPlayByUser(on: Boolean) {
        if (!on) {
            autoPlayOn = false
            config.autoPlay = false
            autoChannelRestartGeneration++
            mainHandler.removeCallbacks(accessibilityWatchdog)
            clearLandingTransaction(restoreWindow = true)
            autoLastResult = null
            setStatus("自动走子：已关闭（只提示不走子）")
            postRender()
            return
        }
        autoPlayOn = true
        config.autoPlay = true
        mainHandler.removeCallbacks(accessibilityWatchdog)
        clearLandingTransaction(restoreWindow = true)
        autoMoveAttempts = 0
        autoLastUcci = null
        if (paused) {
            autoLastResult = "已开启，等待用户开始"
            setStatus("自动走子：已开启，将在点击『开始』后生效")
            postRender()
        } else if (AssistAccessibilityService.isConnected()) {
            mainHandler.post(accessibilityWatchdog)
            autoLastResult = "通道已连接，等待我方定着"
            setStatus("自动走子：已开启")
            postRender()
        } else {
            mainHandler.post(accessibilityWatchdog)
            setStatus("自动走子已开启，正在检查屏幕操作通道…")
            ensureAccessibilityForAutoPlay(openSettings = true)
        }
    }

    /** 由辅助页设置引擎线程数（0=自动，1..8=指定）；对下一次搜索生效。 */
    fun requestEngineThreads(setting: Int) {
        mainHandler.post {
            val cores = Runtime.getRuntime().availableProcessors()
            config.engineThreads = setting
            analysisEngine?.setThreads(EngineTuningPolicy.resolveThreads(setting, cores))
            setStatus("引擎线程：${EngineTuningPolicy.label(setting)}（对下一次搜索生效）")
            postRender()
        }
    }

    /** 屏幕操作通道的连接状态变化后刷新面板（用户在辅助页开启后立即生效） */
    fun refreshAutoAvailability() {
        postRender()
    }

    /**
     * 延迟初始化：服务构造时 Context 尚未 attach（mBase==null），
     * 立即访问 getSharedPreferences 会崩溃；首次访问发生在 onCreate 及之后，安全。
     */
    private val config by lazy { AssistConfig(this) }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    /** 物理屏幕尺寸与录屏输出尺寸分开保存；落子坐标最终要缩放回物理屏幕。 */
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val workerThread = HandlerThread("assist-worker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    /**
     * **取帧专用线程**，与识别线程分开。
     *
     * 必须分开：取帧回调若与识别共用一个线程，识别一旦耗时（拷像素 + 跑模型），
     * 期间到达的帧就没人消费，ImageReader 缓冲池会被填满并停止投递，
     * 之后连"放弃这一帧"这种轻量动作都再也轮不到执行——直接死锁。
     */
    private val captureThread = HandlerThread("assist-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    /** 连续录屏流中的稳定关键帧窗口；仅在captureHandler线程驱动。 */
    private val stableFrameWindow = StableFrameWindow()

    @Volatile private var lastStreamSampleAt = Long.MIN_VALUE
    @Volatile private var stableFrameAt = 0L
    @Volatile private var stableFrameCount = 0
    @Volatile private var streamAnomalyStreak = 0
    @Volatile private var lastWaitingForceScanAt = Long.MIN_VALUE
    @Volatile private var lastStreamPhase: AssistPhase.Phase? = null

    /**
     * 待处理的最新一帧（**覆盖式槽位**）。
     *
     * 原来是一个 `pendingFrame` 布尔 + 丢弃后续帧的写法，有两个毛病：
     * - 处理慢时新帧被直接丢掉，核对落子时容易"看不到变化"；
     * - 只要有一处没把它复位（例如 post 出去的任务没能执行），
     *   它就永远是 true —— 之后再也不会处理任何一帧，彻底停摆。
     *
     * 现在改成槽位：新帧直接覆盖旧帧（实时场景本来就该用最新的），
     * 并且只保证有一个消费任务在排队。
     */
    private data class QueuedFrame(
        val frames: List<Frame>,
        val epoch: Long,
    )

    private data class DetectionObservation(
        val frame: Frame,
        val mapped: DetectionBoardMapper.MappedBoard,
        val canonical: Array<IntArray>,
        val epoch: Long,
    )
    private val frameQueueLock = Any()
    private var pendingFrame: QueuedFrame? = null
    private var frameConsumeScheduled = false
    /** 当前是否有一个批次正在执行识别；关闭时用屏障等待它退出。 */
    private val recognitionInFlight = AtomicBoolean(false)
    /** 模式/暂停/重置变化后递增；旧帧即使晚到也不能覆盖新状态。 */
    @Volatile private var recognitionEpoch = 0L
    /** 暂停期间用户可能在外部走了多手；恢复后首个稳定画面直接重建基线，不做历史差分拒绝。 */
    /** 模式/暂停/重置变化后递增；旧帧即使晚到也不能覆盖新状态。 */
    @Volatile private var resumeRebasePending = false
    /** 旧诊断字段，仅记录最近一次提交的FEN；周期去重由analysisCycleGate负责。 */
    @Volatile private var analyzedFen: String = ""

    /**
     * 是否暂停（默认暂停）。
     * 打开悬浮窗时先不跑识别、不截屏——必须由用户点「开始」才进入"识盘中"，
     * 这样也避免一开机就抢 CPU/内存。
     */
    @Volatile private var paused = AssistRunControlPolicy.PREPARED_PAUSED
    /**
     * 紧急手动模式：识别连续失效/产出幽灵局面时，暂停帧识别，由用户在小棋盘上
     * 点选"起点→终点"录入实战着法（自动翻转走子方）、再点一次已选中子将其删除。
     */
    /** 当前工作模式的唯一运行时真源；不能再由 manual/semi 两个布尔反推（指导与自动会撞值）。 */
    @Volatile private var activeWorkMode = AssistConfig.DEFAULT_WORK_MODE
    @Volatile private var manualMode = false
    /** 手动模式下小棋盘当前选中的格（canonical x,y） */
    @Volatile private var manualSelected: Pair<Int, Int>? = null
    /** 关闭流程闸门：置位后不再开始新的TFLite推理。 */
    @Volatile private var stopping = false
    /** 识别与模型释放共用的生命周期状态，关闭时先置位再等待在途推理退出。 */
    @Volatile private var overlayClosed = false
    /** 用户正在拖动/缩放悬浮窗（此时不做抓帧隐藏，免得边拖边闪） */
    @Volatile private var userDragging = false
    /** 当前取帧策略（见 CaptureDemand；手动模式/暂停=OFF，半自动=ONE_SHOT，其余=CONTINUOUS） */
    @Volatile private var captureDemand = CaptureDemand.CONTINUOUS
    /**
     * 「更新棋谱」请求（显式状态机）。
     * 取代了原来的两个隐式布尔 wantOneShot / refreshRequested —— 它们被多处
     * 各自置位与清除，任何一处漏掉，请求就被静默吞掉，界面上毫无反馈。
     */
    /** 用户主动点击“更新棋谱”的事务；自动视觉自愈不能抢占这个状态。 */
    @Volatile private var userRefreshActive = false
    private val refresh = RefreshRequest()

    /** 阶段状态机（唯一状态来源） */
    @Volatile private var phase: AssistPhase.Phase = AssistPhase.Phase.PAUSED
    /** 当前阶段是什么时候进入的（用于卡死监督） */
    @Volatile private var phaseSinceAt = System.currentTimeMillis()
    /** 距离最近一次成功识别的时间；< 0 表示本轮还没成功过 */
    @Volatile private var lastMapAtForWatchdog = -1L

    private var tracker = BoardTracker(2)
    private var yoloDetector: YoloBoardDetector? = null
    @Volatile private var yoloMissStreak = 0
    /** 裁剪推理下连续不稳定的帧数（棋盘挪动后残框自锁时回退全屏重定位） */
    @Volatile private var cropUnstableStreak = 0
    /** 本次会话 YOLO 检出的棋盘网格（用于悬浮窗遮挡判断与裁剪推理），识别失败时清空自愈 */
    @Volatile private var sessionGrid: BoardGrid? = null

    private var analysisEngine: AnalysisEngine? = null
    @Volatile private var engineReady = false
    /** 最近一次引擎错误（显示于状态栏，onEngineReady 时清除） */
    @Volatile private var engineErrorText: String? = null

    // ==================== 状态展示用运行时诊断 ====================

    /** 最近一次成功映射出棋盘的时刻（0=本次会话尚未成功） */
    @Volatile private var lastMappedAt = 0L
    /** 最近一次成功映射的棋子数 */
    @Volatile private var lastMappedPieces = 0
    /** 最近一次成功映射所用的网格锚点来源 */
    @Volatile private var lastAnchorSource: DetectionBoardMapper.AnchorSource? = null
    /** 最近一次识别失败的具体原因（"未见棋盘"/"棋子不足"/"映射失败"等） */
    @Volatile private var lastFailReason: String? = null
    /** 状态文案节流：结构异常时每帧都会来，不能每帧都重建通知。 */
    @Volatile private var lastStatusAt: Long? = null
    /** 引擎是否正在搜索 */
    @Volatile private var searching = false
    /** 本次搜索开始时刻 */
    @Volatile private var searchStartAt = 0L
    /** 引擎启动（预热）开始时刻 */
    @Volatile private var engineWarmupStartAt = 0L
    /** 引擎进程是否已拉起（延迟启动：点开始且首次需要分析时才启动，避免开机就申请大内存） */
    @Volatile private var engineStarted = false
    /** 当前搜索深度档位（展示用） */
    private val searchDepthTarget: Int get() = strengthLevels[strengthIndex].second

    // ==================== 自动走子 ====================

    /** 自动走子内部状态机 */
    private enum class AutoState {
        /** 待命：等待出现"我方 + 建议定着" */
        IDLE,
        /** 正在派发手势（含两次点按之间的间隔） */
        CLICKING,
        /** 已落子，等待棋盘变化回执 */
        WAITING_BOARD,
    }

    @Volatile private var autoState = AutoState.IDLE
    /** 自动走子开关（持久化于 AssistConfig） */
    @Volatile private var autoPlayOn = false
    /** 最近一次自动执行的着法（局面未变时不重复执行） */
    @Volatile private var autoLastUcci: String? = null
    /** 最近一次落子对应的 FEN（用于判断是否已生效） */
    @Volatile private var autoFenAtExec: String? = null
    /**
     * 落子依据的局面快照（= 这次建议所基于的那个局面，也就是"计算输入状态"）。
     * 落子后拿实际扫到的盘面与它比对：
     * - 有变化 → 这一手确实落上了，才能进入"等待对面落子"；
     * - 没变化 → 说明没点上，需要重落。
     */
    @Volatile private var autoBoardAtExec: Array<IntArray>? = null
    /** 最近一次自动动作的时间戳 */
    @Volatile private var autoLastActionAt = 0L
    /** 自动走子最近一次结果/原因（展示在状态栏） */
    @Volatile private var autoLastResult: String? = null
    /** 自动走子累计成功落子数 */
    @Volatile private var autoMoveCount = 0
    /** 连续落子失败后的短暂冷却，防止同一失效通道立即形成重试风暴；冷却后自动恢复。 */
    @Volatile private var autoMoveCooldownUntil = 0L
    /** 落子失败后要求下一张稳定画面重新触发一次分析，即使盘面文字上未变化。 */
    @Volatile private var reanalyzeAfterLandingFailure = false

    /** 建议成熟度跟踪：最佳着法连续 SUGGEST_STABLE_MS 未变（或本次搜索已结束）=> 定着（绿箭头） */
    @Volatile private var stableUcci: String? = null
    @Volatile private var stableSince = 0L
    @Volatile private var searchSettled = false

    @Volatile private var currentFen = ""
    /** 当前已提交局面的走子方；UI/引擎/自动落子只读它，不跨线程直接读 BoardTracker。 */
    @Volatile private var currentRedGo = true
    /** canonical 布局 90 子（红恒在 y=9；迷你棋盘视图按 flipped 自行做屏幕朝向映射） */
    @Volatile private var currentPieces: IntArray? = null
    /** 当前确认局面的 canonical 棋盘（开局库查询用） */
    @Volatile private var currentCanonical: Array<IntArray>? = null
    private var currentOrientation = Orientation.STANDARD
    private var lastAnalysis: AnalysisResult? = null
    private var selectedCandidate = 0

    /** 开局库候选（我方回合命中时非空；随 selectedCandidate 循环展示） */
    private var bookMoves: List<BookData>? = null
    /** 开局库（与对弈同款）；仅 worker 线程访问，首次查询时惰性初始化 */
    private var openBook: BHOpenBook? = null
    private var openBookFailed = false

    /** 已确认局面历史（悔棋用）：每次确认入栈，回退时弹出当前、显示上一条 */
    private class HistoryEntry(val result: RecognitionResult, val redGo: Boolean, val fen: String)
    private val history = ArrayDeque<HistoryEntry>()

    /** 引擎强度档位（固定深度；高档位仍受思考时间上限约束） */
    private val strengthLevels = AssistDepth.LEVELS
    private var strengthIndex = 0
    private var timeIndex = 0
    /** 引擎 Hash 档位循环（首档512MB；点击后进入1024MB等档位） */
    private val hashLevels = listOf(512, 1024, 2048, 4096)
    private var hashIndex = 0

    /** 仿真用随机源 */
    private val random = java.util.Random()
    /**
     * 棋面最后一次真正被更新的时刻。
     * 用于半自动模式的"画面新鲜度"判断——半自动只在点「更新棋谱」时刷新局面，
     * 因此不能再拿"最近抓到帧"来衡量局面是否有效。
     */
    @Volatile private var boardUpdatedAt = 0L
    /** 同一局面/预算的分析周期闸门；候选浏览与稳定重复识别不能清零。 */
    private val analysisCycleGate = AnalysisCycleGate()
    /** 当前允许写回界面的搜索会话；同 FEN 的旧 bestmove 也会被拒绝。 */
    @Volatile private var expectedAnalysisId = 0L

    // ==================== 响应链路计时（真机验证用） ====================
    // 口径：稳定窗口完成 → 提交搜索 → 首条 PV 返回 → 搜索结束。
    @Volatile private var analysisRequestAt = 0L
    @Volatile private var firstPvAt = 0L
    @Volatile private var searchDoneAt = 0L
    @Volatile private var lastLoggedSearchUpdateAt = 0L
    @Volatile private var lastLoggedSearchUpdateUcci: String? = null
    @Volatile private var lastAutoTickLogAt = 0L
    private var lastAutoTickSignature = ""
    private var lastAutoBlockLog = ""
    @Volatile private var searchProgressAt = 0L
    /** 搜索守护：只防止同一控制失效事件重复恢复，不按“无PV时长”打断正常深度搜索。 */
    private var searchRecoveryScheduled = false
    @Volatile private var lastSearchCpuTimeMs = -1L
    @Volatile private var searchCpuProgressAt = 0L

    // ==================== 录制/识别/落子管线的独立监督 ====================
    //
    // 判定只用原始证据时间戳（最后一帧、最后一次稳定窗口、最后一次识别结果），
    // 不读阶段机的展示状态：阶段机停摆时它反而最不可信（那正是“卡在计算中”的成因）。
    @Volatile private var pipelineWatchdogAt = 0L
    @Volatile private var lastStreamFrameAt = 0L
    @Volatile private var streamStartedAt = 0L
    @Volatile private var captureStartedAt = 0L
    @Volatile private var lastStableWindowAt = 0L
    @Volatile private var lastVisionAt = 0L
    @Volatile private var inferenceStartedAt = 0L
    @Volatile private var lastPipelineRecoveryAt = 0L
    @Volatile private var lastVisionResetStatusAt = 0L
    private var pipelineWatchdogArmed = false

    private fun ageOf(now: Long, at: Long): Long = if (at <= 0L) -1L else now - at

    private val pipelineWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            pipelineWatchdogAt = now
            if (stopping || overlayClosed) {
                pipelineWatchdogArmed = false
                return
            }
            val landing = landingState
            val action = PipelineHealthPolicy.evaluate(
                PipelineHealthPolicy.Health(
                    paused = paused,
                    manualMode = manualMode,
                    needsFrames = AssistPhase.needsBoard(refreshPhase()),
                    landingStage = landing?.stage,
                    landingStartedAt = landing?.startedAt ?: 0L,
                    landingCompletedAt = landing?.completedAt ?: 0L,
                    lastFrameAt = lastStreamFrameAt,
                    streamStartedAt = streamStartedAt,
                    captureStartedAt = captureStartedAt,
                    lastStableAt = lastStableWindowAt,
                    lastVisionAt = lastVisionAt,
                    inferenceInFlight = recognitionInFlight.get(),
                    inferenceStartedAt = inferenceStartedAt,
                    captureAlive = mediaProjection != null && imageReader != null && virtualDisplay != null,
                    lastRecoveryAt = lastPipelineRecoveryAt,
                    now = now,
                )
            )
            if (action != PipelineHealthPolicy.Action.NONE) {
                lastPipelineRecoveryAt = now
                trace(
                    "PIPELINE_WATCHDOG_ACTION",
                    "action=$action phase=${refreshPhase()} landing=${landing?.stage} " +
                        "frameAge=${ageOf(now, lastStreamFrameAt)} stableAge=${ageOf(now, lastStableWindowAt)} " +
                        "visionAge=${ageOf(now, lastVisionAt)} inference=${recognitionInFlight.get()} " +
                        "inferenceAge=${ageOf(now, inferenceStartedAt)}"
                )
                when (action) {
                    PipelineHealthPolicy.Action.REBASE_LANDING ->
                        retryOrStopLanding("落子核对超时，已自动重新建立基线")

                    PipelineHealthPolicy.Action.RESET_VISION -> recoverVisionPipeline()

                    PipelineHealthPolicy.Action.REBUILD_CAPTURE -> {
                        // 推理卡住时它自己不会再回到 false，会让判定一直命中同一条；
                        // 这里只把健康信号归零，真正的推理生命周期仍由 InferenceLifecycleGate 串行化。
                        if (recognitionInFlight.get() && inferenceStartedAt > 0L &&
                            now - inferenceStartedAt > PipelineHealthPolicy.INFERENCE_STUCK_MS
                        ) {
                            recognitionInFlight.set(false)
                            trace("CAPTURE_REBUILD_STUCK_INFERENCE", "age=${now - inferenceStartedAt}")
                        }
                        rebuildCapturePipeline()
                    }

                    PipelineHealthPolicy.Action.NONE -> Unit
                }
            }
            if (!stopping && !overlayClosed && (!paused || landingState != null)) {
                mainHandler.postDelayed(this, PIPELINE_WATCHDOG_TICK_MS)
            } else {
                pipelineWatchdogArmed = false
            }
        }
    }

    private fun armPipelineWatchdog() {
        if (stopping || overlayClosed) return
        if (pipelineWatchdogArmed) return
        pipelineWatchdogArmed = true
        mainHandler.removeCallbacks(pipelineWatchdog)
        pipelineWatchdogAt = System.currentTimeMillis()
        mainHandler.postDelayed(pipelineWatchdog, PIPELINE_WATCHDOG_TICK_MS)
    }

    private fun disarmPipelineWatchdog() {
        pipelineWatchdogArmed = false
        mainHandler.removeCallbacks(pipelineWatchdog)
        pipelineWatchdogAt = 0L
    }

    /** 识别侧自愈：丢弃裁剪网格与稳定窗口，整屏重找。等价于用户点「更新棋谱」的强制重扫。 */
    private fun recoverVisionPipeline() {
        trace("PIPELINE_VISION_RESET", "grid=${sessionGrid != null} misses=$yoloMissStreak")
        sessionGrid = null
        cropUnstableStreak = 0
        lastFullVisionCheckAt = 0L
        recognitionEpoch++
        abortCaptureWindow()
        lastMappedAt = 0L
        lastVisionAt = 0L
        lastStableWindowAt = 0L
        syncCaptureDemand()
        kickCapture(forceProcess = true)
        // 这条恢复会周期性发生（比如棋盘被挡住），状态行不能每 6 秒刷一次同样的字。
        val now = System.currentTimeMillis()
        if (now - lastVisionResetStatusAt > VISION_RESET_STATUS_INTERVAL_MS) {
            lastVisionResetStatusAt = now
            setStatus("识别链路一段时间没有结果，已自动整屏重新定位…")
        }
        postRender()
    }

    /**
     * 录制侧自愈：保留 MediaProjection，只换掉 ImageReader 与 VirtualDisplay。
     *
     * ImageReader 缓冲池只要有一轮不消费就会被填满，Surface 随即停止投递，回调彻底停掉；
     * 这时任何“再踢一次取帧”都无效，必须重建读取器。用户的现场办法是手动暂停再开始，
     * 这里把它自动化，且不打断用户操作。
     */
    private fun rebuildCapturePipeline() {
        if (stopping || overlayClosed) return
        val projection = mediaProjection ?: return
        val w = config.frameSize.first
        val h = config.frameSize.second
        if (w <= 0 || h <= 0) return
        trace("CAPTURE_REBUILD", "size=${w}x$h")
        recognitionEpoch++
        abortCaptureWindow()
        val oldReader = imageReader
        val oldDisplay = virtualDisplay
        runCatching { oldReader?.setOnImageAvailableListener(null, null) }
        runCatching { oldDisplay?.release() }
        runCatching { oldReader?.close() }
        imageReader = null
        virtualDisplay = null
        val dpi = resources.displayMetrics.densityDpi
        val reader = runCatching {
            ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also {
                it.setOnImageAvailableListener(::onImageAvailable, captureHandler)
            }
        }.getOrNull()
        if (reader == null) {
            trace("CAPTURE_REBUILD_FAILED", "stage=reader")
            return
        }
        imageReader = reader
        val display = runCatching {
            projection.createVirtualDisplay(
                "assist-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, workerHandler
            )
        }.getOrNull()
        if (display == null) {
            trace("CAPTURE_REBUILD_FAILED", "stage=virtual_display")
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
            imageReader = null
            // 连虚拟显示都建不起来，说明这个 MediaProjection 已经失效（用户从通知栏停了录屏
            // 或系统回收）：不再反复重建，如实告诉用户重新开始屏幕识别。
            if (!projectionStillUsable()) mediaProjection = null
            setStatus("屏幕录制已中断且无法自动恢复\n请在悬浮窗辅助页重新点击『一键准备』")
            postRender()
            return
        }
        virtualDisplay = display
        // 重建后清掉所有旧的证据时间戳；下一帧到来后再重新建立 streamStartedAt。
        captureStartedAt = System.currentTimeMillis()
        lastStreamFrameAt = 0L
        streamStartedAt = 0L
        lastStableWindowAt = 0L
        lastVisionAt = 0L
        lastStreamSampleAt = Long.MIN_VALUE
        lastFullVisionCheckAt = 0L
        syncCaptureDemand()
        kickCapture(forceProcess = true)
        // 重建后必须继续被监督，否则下一次停摆就没人管了。
        armPipelineWatchdog()
        setStatus("识别画面已中断，已自动重建取帧管线…")
        postRender()
    }

    /** 重建失败时判断这个 MediaProjection 还能不能继续用（避免无意义地反复重建）。 */
    private fun projectionStillUsable(): Boolean {
        val projection = mediaProjection ?: return false
        val probe = runCatching {
            ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 1)
        }.getOrNull() ?: return false
        val created = runCatching {
            projection.createVirtualDisplay(
                "assist-probe", 16, 16, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                probe.surface, null, null
            )
        }.getOrNull()
        runCatching { created?.release() }
        runCatching { probe.close() }
        return created != null
    }
    /** 前台应用全屏基准；只由无障碍窗口事件更新。 */
    @Volatile private var foregroundBaselinePackage: String? = null
    @Volatile private var foregroundPauseSnapshot: ForegroundPausePolicy.Snapshot? = null
    @Volatile private var foregroundLastEventAt = 0L

    /**
     * **建议所依据的盘面**：提交给引擎计算时的那份快照。
     * 落子前拿它与当前能看到盘面比对：一致才说明这条建议是对着眼前这手棋算的。
     */
    @Volatile private var adviceBoard: Array<IntArray>? = null

    private data class SeenBoard(val board: Array<IntArray>, val capturedAt: Long)
    /** 最近一帧实际看到的盘面与时间，作为一个原子快照发布。 */
    @Volatile private var lastSeen: SeenBoard? = null

    /** 落子核对超时后，下一张合法稳定盘面用于建立新的观察基线，不再拿旧盘面推断。 */
    @Volatile private var landingRebasePending = false
    @Volatile private var landingRebasePreBoard: Array<IntArray>? = null
    @Volatile private var landingRebaseExpectedBoard: Array<IntArray>? = null
    @Volatile private var landingRebaseRedGo = true
    /** 这一轮日志是否已经启动；暂停/前台切换不创建新日志，重置/首次运行才创建。 */
    @Volatile private var runSessionStarted = false

    /** 当前落子事务；为空就绝不允许显示“落子中/核对中”。 */
    @Volatile private var landingState: LandingFlow.State? = null
    @Volatile private var landingToken = 0L

    /** 落子期间是否已让悬浮窗"让路"（收起面板 + 不可触摸） */
    @Volatile private var landingUiSuppressed = false

    /** 让路前面板本来是否展开着（是的话落完要还原成面板） */
    @Volatile private var landingWasPanel = false

    /** 局面变化后的分析防抖任务 */
    private val analysisDebounce = Runnable { scheduleAnalysis() }

    /**
     * 界面渲染与状态推进必须分离：渲染只读状态；自动落子和监督器由各自驱动器执行。
     * 这些标志用于合并同一时刻的大量 info/识别回调，避免递归重入。
     */
    private val renderScheduled = AtomicBoolean(false)
    @Volatile private var pendingStatusOverride: String? = null
    private val autoDriveScheduled = AtomicBoolean(false)
    private val watchdogScheduled = AtomicBoolean(false)

    /** 最近一次独立全屏复核的时刻；周期复核不依赖上一局面类别。 */
    @Volatile private var lastFullVisionCheckAt = 0L
    /** TFLite模型选择/关闭时的识别代号，关闭后所有在途帧只允许收尾不再推理。 */
    @Volatile private var lastRejectReason: String? = null

    /** 自动落子：同一回合的尝试次数（用于失败重试与放弃） */
    private var autoMoveAttempts = 0

    /** 自动开关永不因系统故障关闭；此闸门只限制恢复线程重复启动。 */
    private val accessibilityRecoveryInFlight = AtomicBoolean(false)
    /** 内部通道重启正在执行；不写用户开关、不更新状态栏、不触发界面闪烁。 */
    private val autoChannelRestartInFlight = AtomicBoolean(false)
    /** READY 阶段已经执行过一次内部通道重启的阶段时间戳。 */
    @Volatile private var readyChannelRestartedForPhaseAt = Long.MIN_VALUE
    /** 等待通道保险完成后再派发的自动落子事务；仅保存不可变的落子快照。 */
    private data class PendingAutoMove(
        val target: AutoMove,
        val fen: String,
        val preBoard: Array<IntArray>,
        val reuseSelectedOrigin: Boolean,
        val requireAutoSwitch: Boolean,
        val createdAt: Long,
    )
    @Volatile private var pendingAutoMove: PendingAutoMove? = null
    /** 本次 FEN+着法已经完成通道保险；下一手或失败重试会清除。 */
    @Volatile private var lastPreparedMoveKey: String? = null
    @Volatile private var lastAccessibilityRecoveryAt = 0L
    @Volatile private var accessibilitySettingsPrompted = false
    /** 内部重启若在队列中，用户关闭开关时必须让它失效，避免完成回调继续推进落子。 */
    @Volatile private var autoChannelRestartGeneration = 0L
    @Volatile private var autoChannelRestartStartedAt = 0L
    /** 同一次通道保险期间的等待者；不再用递归定时器叠加多个重启线程。 */
    private val autoChannelRestartWaiters = java.util.ArrayDeque<(Boolean) -> Unit>()
    private val accessibilityWatchdog = object : Runnable {
        override fun run() {
            if (stopping || overlayClosed || paused || !autoPlayOn) return
            if (!AssistAccessibilityService.isConnected()) {
                ensureAccessibilityForAutoPlay(openSettings = !accessibilitySettingsPrompted)
            }
            mainHandler.postDelayed(this, ACCESSIBILITY_WATCHDOG_MS)
        }
    }


    /** 预选手势是否仍在系统无障碍通道中执行；正式落子必须等待它结束或超时回退。 */
    @Volatile private var previewGestureInFlight = false
    @Volatile private var previewGestureStartedAt = 0L
    @Volatile private var previewGestureToken = 0L
    private var previewFinalizationScheduled = false
    /** 蓝色候选箭头预选状态（纯 JVM 状态类；渲染函数只读）。 */
    private val previewState = CandidatePreviewState()
    /** 预选手势派发的最小间隔（同一 UCP 起点已由策略去重）。 */
    private var lastPreviewDispatchAt = 0L
    /** 仿真·预案试选：上次试选时间与轮换下标 */

    private var windowManager: WindowManager? = null
    private var overlayPanel: OverlayPanelView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayBall: OverlayBallView? = null
    private var overlayBallParams: WindowManager.LayoutParams? = null

    /** 半自动模式：帧识别照跑但不自动刷新棋面，需点「更新棋谱」才应用 */
    @Volatile private var semiAuto = false
    /** 半自动模式下最近一次识别成功、等待用户确认应用的局面 */
    @Volatile private var pendingBoard: RecognitionResult? = null
    @Volatile private var pendingBoardAt = 0L

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val binder = CastBinder()
    /** 本轮连线状态、识别、计算、落子和恢复链路的持久日志。 */
    private val runtimeLogger by lazy { AssistRuntimeLogger(File(filesDir, "logs")) }

    private fun trace(event: String, message: String = "") {
        runtimeLogger.log(event, message)
    }

    override fun onCreate() {
        super.onCreate()
        runtimeLogger.startSession("service_create")
        runSessionStarted = false
        trace("SERVICE_CREATE", "files=${runtimeLogger.directory().absolutePath}")
        // 自动走子是持久用户开关：服务重建、无障碍断开、录屏重启都先恢复它，
        // 只有用户明确点击关闭入口才允许写成 false。
        autoPlayOn = config.autoPlay
        AssistAccessibilityService.setGlobalForegroundObserver { pkg, full ->
            mainHandler.post { onForegroundWindowEvent(pkg, full) }
        }
        AssistAccessibilityService.instance?.reportCurrentForegroundWindow()
        tracker = BoardTracker(confirmCount = 1)
        // 强度档位与持久化设置对齐（设置值不在档位表内时用”标准”）
        val idx = strengthLevels.indexOfFirst { it.second == config.searchDepth }
        strengthIndex = if (idx >= 0) idx else 2
        // Hash 档位与持久化设置对齐（默认512MB）
        val hidx = hashLevels.indexOfFirst { it == config.hashMb }
        if (hidx < 0) {
            config.hashMb = AssistConfig.DEFAULT_HASH_MB
            hashIndex = 0
        } else {
            hashIndex = hidx
        }
        val storedTime = ThinkingOptions.TIME_LEVELS_MS.indexOf(config.thinkTimeMs.toLong())
        timeIndex = if (storedTime >= 0) storedTime else {
            val fallback = ThinkingOptions.TIME_LEVELS_MS.indexOf(ThinkingOptions.DEFAULT_TIME_MS.toLong())
            config.thinkTimeMs = ThinkingOptions.DEFAULT_TIME_MS
            fallback
        }
        // YOLO 棋子检测：直接检测 14 类棋子 + 棋盘框，免校准、跨 App 泛化。
        // 初始化失败（含 JVM 测试环境无 TFLite 原生库的 UnsatisfiedLinkError）只提示，不阻塞服务
        yoloDetector = try {
            YoloBoardDetector(this)
        } catch (t: Throwable) {
            Log.w(TAG, "yolo detector init failed", t)
            null
        }
        Log.i(TAG, "yolo detector: ${yoloDetector?.modelFile ?: "unavailable"}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_RESULT_DATA)
            startForegroundInternal()
            startCapture(resultCode, data)
        }
        return START_STICKY
    }

    private fun startForegroundInternal() {
        createChannel()
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 29)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "悬浮窗辅助·屏幕识别", NotificationManager.IMPORTANCE_LOW)
        ch.description = "悬浮窗辅助的屏幕识别服务"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(contentText: String? = null): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.xiangqi.assist.assist.ui.AssistActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            paused -> "象棋辅助·悬浮窗辅助（已停止）"
            autoPlayOn -> "象棋辅助·悬浮窗辅助（自动走子已开启）"
            else -> "象棋辅助·悬浮窗辅助运行中"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText ?: when {
                paused -> "环境已准备，等待用户点击开始"
                autoPlayOn -> "正在识别局面并按条件自动落子"
                else -> "正在识别局面并给出走法建议"
            })
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    // ==================== 截屏 ====================

    private fun startCapture(resultCode: Int, data: Intent?) {
        if (mediaProjection != null || data == null) return
        if (!runSessionStarted) {
            runtimeLogger.startSession("capture_start")
        }
        trace("CAPTURE_START", "resultCode=$resultCode")
        lastFullVisionCheckAt = 0L
        abortCaptureWindow()
        // 新的一轮录制：管线证据时间戳全部归零，监督器按新管线的第一帧重新计时。
        lastStreamFrameAt = 0L
        streamStartedAt = 0L
        captureStartedAt = 0L
        lastStableWindowAt = 0L
        lastVisionAt = 0L
        inferenceStartedAt = 0L
        sessionGrid = null
        stopping = false
        overlayClosed = false
        paused = AssistRunControlPolicy.PREPARED_PAUSED
        runSessionStarted = false
        foregroundPauseSnapshot = null
        mainHandler.removeCallbacks(accessibilityWatchdog)
        recognitionEpoch++
        clearLandingTransaction(restoreWindow = true)
        searching = false
        searchSettled = false
        searchRecoveryScheduled = false
        engineReady = false
        engineStarted = false
        runCatching { analysisEngine?.shutdown() }
        analysisEngine = null
        synchronized(frameQueueLock) {
            pendingFrame = null
            frameConsumeScheduled = false
        }
        userRefreshActive = false
        refresh.cancel()

        // 重新启动时允许再次显示悬浮窗。
        overlayClosed = false
        val metrics = screenMetrics()
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        screenWidth = screenW
        screenHeight = screenH
        val scale = min(1.0, CAPTURE_MAX_DIMENSION.toDouble() /
            max(screenW, screenH).coerceAtLeast(1).toDouble())
        val captureW = (screenW * scale).roundToInt().coerceAtLeast(1)
        val captureH = (screenH * scale).roundToInt().coerceAtLeast(1)
        val dpi = metrics.densityDpi

        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        // 录屏输出用较低分辨率降低像素拷贝/内存压力；模型输入仍由TFLite统一letterbox到640。
        // config.frameSize记录的是录屏帧尺寸，不是物理触摸尺寸，落子派发时会反向缩放。
        config.frameSize = captureW to captureH
        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        // 挂到取帧专用线程：与识别线程解耦，识别再慢也不会堵住缓冲消费
        reader.setOnImageAvailableListener(::onImageAvailable, captureHandler)
        imageReader = reader
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "assist-capture", captureW, captureH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, workerHandler
        )
        imageReader = reader
        captureStartedAt = if (virtualDisplay != null) System.currentTimeMillis() else 0L
        Log.i(TAG, "capture prepared: screen=${screenW}x${screenH} stream=${captureW}x${captureH}; paused=true")

        // 模式恢复：workMode 是唯一真源，三态互斥由 setModeInternal 保证。
        // 自动落子是独立开关（默认开）；通道没连接时由 autoBlockReason 明确说明原因，
        // 不会偷偷改写用户的模式选择。
        autoMoveCount = 0
        autoMoveCooldownUntil = 0L
        reanalyzeAfterLandingFailure = false
        autoLastResult = null
        setModeInternal(config.workMode)
        pendingBoard = null
        setStatus("准备完成，当前已停止\n点击『开始』后才会识别和计算")

        // 引擎**不在这里启动**。
        // 启动要加载 NNUE 权重并申请 Hash 内存（几百 MB 起），一开机就吃内存会把
        // 后台的其他应用（包括游戏本体）挤掉。改为"用户点开始 + 首次需要分析"时才拉起。
        engineWarmupStartAt = System.currentTimeMillis()
        analysisEngine = AnalysisEngine(this, analysisListener, config.searchDepth)
            .also {
                it.setHash(config.hashMb)
                // 线程数：0=自动（核心数-1，上限 8）；留一个核给前台游戏与识别
                it.setThreads(
                    EngineTuningPolicy.resolveThreads(
                        config.engineThreads, Runtime.getRuntime().availableProcessors()
                    )
                )
                it.setThinkTime(config.thinkTimeMs)
                // 多条候选共享同一个 go 的总时间/深度预算；“变招”只浏览缓存。
                it.setMultiPv(config.candidateCount)
            }
        postRender()
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    /** 系统栏/挖孔安全边距；旧系统用资源高度兜底。 */
    private fun ballSafeInsets(): IntArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return intArrayOf(insets.left, insets.top, insets.right, insets.bottom)
        }
        fun dimension(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        return intArrayOf(0, dimension("status_bar_height"), 0, dimension("navigation_bar_height"))
    }

    // ==================== 取帧策略与悬浮窗遮挡处理 ====================

    /** 当前阶段是否需要接收录屏流；模型只在稳定关键帧上调用。 */
    private fun isContinuousScanMode(): Boolean = activeWorkMode != AssistConfig.MODE_MANUAL

    private fun desiredCaptureDemand(p: AssistPhase.Phase = refreshPhase()): CaptureDemand {
        return when (AssistPhase.capture(p, autoMode = isContinuousScanMode())) {
            AssistPhase.Capture.OFF -> CaptureDemand.OFF
            AssistPhase.Capture.ON_DEMAND -> CaptureDemand.ONE_SHOT
            AssistPhase.Capture.CONTINUOUS -> CaptureDemand.CONTINUOUS
        }
    }

    private fun syncCaptureDemand(p: AssistPhase.Phase = refreshPhase()) {
        if (lastStreamPhase != p) {
            lastStreamPhase = p
            resetStreamWindow()
        }
        val want = desiredCaptureDemand(p)
        if (want != captureDemand) {
            captureDemand = want
            if (want != CaptureDemand.OFF && !paused && !manualMode) {
                // 清空稳定窗口后让录屏流立即提供一个样本；后续样本仍按250ms节拍进入。
                kickCapture()
            }
        }
        if (!paused && !manualMode && want != CaptureDemand.OFF) {
            mainHandler.removeCallbacks(capturePump)
            mainHandler.post(capturePump)
        } else {
            mainHandler.removeCallbacks(capturePump)
        }
    }

    /** 录屏流保活：ImageReader回调正常时只消费回调；回调失活时主动取一张样本。 */
    private val capturePump = object : Runnable {
        override fun run() {
            val p = refreshPhase()
            if (!paused && !manualMode &&
                (isContinuousScanMode() || refresh.isActive || AssistPhase.needsBoard(p))) {
                val now = System.currentTimeMillis()
                if (lastStreamSampleAt == Long.MIN_VALUE ||
                    now - lastStreamSampleAt >= STREAM_SAMPLE_PERIOD_MS * 2) {
                    kickCapture()
                }
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    /** 录屏流保活：系统回调失活时主动取一张，但不绕过稳定窗口。 */
    private fun kickCapture(forceProcess: Boolean = false) {
        captureHandler.post {
            if (stopping || overlayClosed || paused || manualMode ||
                (captureDemand == CaptureDemand.OFF && !refresh.isActive)) return@post
            if (forceProcess) lastStreamSampleAt = Long.MIN_VALUE
            val reader = imageReader ?: return@post
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@post
            try {
                val now = System.currentTimeMillis()
                lastStreamFrameAt = now
                if (streamStartedAt <= 0L) streamStartedAt = now
                if (FrameStabilityPolicy.shouldSample(now, lastStreamSampleAt)) {
                    lastStreamSampleAt = now
                    consumeStreamSample(imageToFrame(image, now), recognitionEpoch, now)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stream fallback sample failed", t)
            } finally {
                runCatching { image.close() }
            }
        }
    }

    /** 清空稳定窗口但保留当前阶段标记。 */
    private fun resetStreamWindow() {
        stableFrameWindow.reset()
        lastStreamSampleAt = Long.MIN_VALUE
        streamStartedAt = 0L
        stableFrameCount = 0
        stableFrameAt = 0L
        streamAnomalyStreak = 0
    }

    /** 状态切换、暂停、关闭时清空稳定窗口；不修改用户已确认的棋面。 */
    private fun abortCaptureWindow() {
        resetStreamWindow()
        lastStreamPhase = null
    }

    /** 由无障碍窗口事件调用；只处理全屏应用候选，浮窗/系统层由策略过滤。 */
    private fun onForegroundWindowEvent(packageName: String?, isFullScreen: Boolean) {
        if (stopping || overlayClosed) return
        foregroundLastEventAt = System.currentTimeMillis()
        when (val decision = ForegroundAppPolicy.observe(
            ownerPackage = packageNameForForegroundOwner(),
            baselinePackage = foregroundBaselinePackage,
            packageName = packageName,
            isFullScreen = isFullScreen,
        )) {
            ForegroundAppPolicy.Decision.IGNORE -> Unit
            is ForegroundAppPolicy.Decision.BASELINE -> {
                foregroundBaselinePackage = decision.packageName
                trace("FOREGROUND_BASELINE", "package=${decision.packageName}")
            }
            is ForegroundAppPolicy.Decision.CHANGED -> {
                val from = decision.previous
                foregroundBaselinePackage = decision.current
                val snapshot = foregroundPauseSnapshot
                if (snapshot != null && decision.current == snapshot.resumePackage) {
                    foregroundPauseSnapshot = null
                    trace(
                        "FOREGROUND_RETURN",
                        "package=${decision.current} wasRunning=${snapshot.wasRunning}"
                    )
                    if (ForegroundPausePolicy.shouldResume(snapshot, decision.current)) {
                        resumeAfterForegroundReturn(decision.current)
                    } else {
                        // 切出前本来就是暂停：回来后保持原状，绝不擅自开始。
                        setStatus("已回到原前台应用，恢复切出前状态：暂停")
                        postRender()
                    }
                } else if (snapshot == null) {
                    val captured = ForegroundPausePolicy.capture(paused, from)
                    if (captured != null) {
                        foregroundPauseSnapshot = captured
                        trace(
                            "FOREGROUND_SWITCH_PAUSE",
                            "from=$from to=${decision.current} wasRunning=${captured.wasRunning}"
                        )
                        if (captured.wasRunning) {
                            pauseForForegroundSwitch(decision.current)
                        }
                    }
                }
            }
        }
    }

    private fun packageNameForForegroundOwner(): String = packageName

    /**
     * 前台全屏应用切走：立即停掉录屏识别、搜索和落子，但保留用户的“运行中”意图。
     * 返回原应用时仅当快照记录为运行中才自动恢复，并从当前画面重建棋面基线。
     */
    private fun pauseForForegroundSwitch(currentPackage: String) {
        pauseRuntime(clearAnalysis = true)
        setStatus("检测到前台应用已切换（$currentPackage）\n已临时暂停，避免误触；回到原应用将恢复切出前状态")
        postRender()
    }

    /** 回到切出前的应用：恢复运行意图，但旧画面、旧建议和旧手势一律不复用。 */
    private fun resumeAfterForegroundReturn(returnedPackage: String) {
        if (stopping || overlayClosed || mediaProjection == null) {
            paused = true
            setStatus("已回到原前台应用，但屏幕录制已中断\n请在悬浮窗辅助页重新准备环境")
            postRender()
            return
        }
        trace("FOREGROUND_RESUME", "package=$returnedPackage mode=$activeWorkMode")
        resumeRuntime(rebaseBoard = true, userInitiated = false)
        setStatus("已回到原前台应用\n已恢复切出前的运行状态，正在重新确认当前棋面…")
        postRender()
    }

    /** 暂停运行管线。日志会话与用户配置保持不变。 */
    private fun pauseRuntime(clearAnalysis: Boolean) {
        paused = true
        mainHandler.removeCallbacks(accessibilityWatchdog)
        recognitionEpoch++
        abortCaptureWindow()
        mainHandler.removeCallbacks(capturePump)
        disarmPipelineWatchdog()
        clearLandingTransaction(restoreWindow = true)
        pendingAutoMove = null
        lastPreparedMoveKey = null
        if (clearAnalysis) {
            stopCurrentSearch()
            analysisCycleGate.invalidate()
            resetSuggestionState()
        }
        requestRenderOnly()
    }

    /**
     * 启动或继续运行。继续不创建日志起点；首次运行与“重置”由各自入口显式创建。
     * [rebaseBoard] 为 true 时丢弃暂停前的视觉基线，以免外部应用期间已走多手。
     */
    private fun resumeRuntime(rebaseBoard: Boolean, userInitiated: Boolean) {
        if (mediaProjection == null || imageReader == null || virtualDisplay == null) {
            paused = true
            setStatus("运行环境尚未准备：缺少屏幕录制\n请打开悬浮窗辅助页并点击『一键准备』")
            return
        }
        paused = false
        if (autoPlayOn) {
            mainHandler.removeCallbacks(accessibilityWatchdog)
            mainHandler.post(accessibilityWatchdog)
            if (!AssistAccessibilityService.isConnected()) {
                ensureAccessibilityForAutoPlay(openSettings = true)
            }
        }
        recognitionEpoch++
        abortCaptureWindow()
        if (rebaseBoard) {
            resumeRebasePending = true
            tracker.reset(currentRedGo)
            currentCanonical = null
            currentPieces = null
            currentFen = ""
            lastSeen = null
            adviceBoard = null
            sessionGrid = null
            analysisCycleGate.invalidate()
            resetSuggestionState()
            lastFullVisionCheckAt = 0L
        }
        workerHandler.post { ensureEngineStarted() }
        autoMoveAttempts = 0
        autoMoveCooldownUntil = 0L
        reanalyzeAfterLandingFailure = false
        lastStreamFrameAt = 0L
        streamStartedAt = 0L
        captureStartedAt = System.currentTimeMillis()
        lastStableWindowAt = 0L
        lastVisionAt = 0L
        inferenceStartedAt = 0L
        lastPipelineRecoveryAt = 0L
        armPipelineWatchdog()
        syncCaptureDemand(AssistPhase.Phase.FINDING)
        kickCapture(forceProcess = true)
        if (userInitiated) {
            foregroundBaselinePackage = null
            foregroundPauseSnapshot = null
            AssistAccessibilityService.instance?.reportCurrentForegroundWindow()
        }
    }


    private fun panelRectInFrame(fw: Int, fh: Int): IntArray? {
        val (_, params) = overlayWindow() ?: return null
        val w = params.width
        val h = params.height
        if (w <= 0 || h <= 0 || fw <= 0 || fh <= 0) return null
        val sw = screenWidth.takeIf { it > 0 } ?: fw
        val sh = screenHeight.takeIf { it > 0 } ?: fh
        val sx = fw.toDouble() / sw.toDouble()
        val sy = fh.toDouble() / sh.toDouble()
        val x0 = max(0, (params.x * sx).toInt())
        val y0 = max(0, (params.y * sy).toInt())
        val x1 = min(fw, ((params.x + w) * sx).toInt())
        val y1 = min(fh, ((params.y + h) * sy).toInt())
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    /** 当前可见的悬浮窗（面板优先，其次小球）及其窗口参数 */
    private fun overlayWindow(): Pair<android.view.View, WindowManager.LayoutParams>? {
        val p = overlayPanel
        if (p != null) {
            val params = overlayParams
            if (params != null) return p to params
        }
        val b = overlayBall
        if (b != null) {
            val params = overlayBallParams
            if (params != null) return b to params
        }
        return null
    }

    /** 把帧内某矩形涂成中性灰（模型在该区域不会产出检测框） */
    private fun maskRegion(frame: Frame, r: IntArray, color: Int) {
        for (y in r[1] until r[3]) {
            val base = y * frame.width
            java.util.Arrays.fill(frame.argb, base + r[0], base + r[2], color)
        }
    }

    /**
     * 悬浮窗面板遮挡棋盘的面积占比（0..1）。
     * 用于在状态栏提示"面板挡住了棋盘"，引导用户拖开或缩小——因为带面板抓帧时
     * 被遮挡区域无法识别（MediaProjection 拿不到悬浮窗底下的像素）。
     */
    private fun panelOverlapRatio(): Double {
        val grid = sessionGrid ?: return 0.0
        val (fw, fh) = config.frameSize
        if (fw <= 0 || fh <= 0) return 0.0
        val rect = panelRectInFrame(fw, fh) ?: return 0.0
        val bx0 = grid.nx0 * fw; val by0 = grid.ny0 * fh
        val bx1 = grid.nx1 * fw; val by1 = grid.ny1 * fh
        val iw = max(0.0, min(bx1, rect[2].toDouble()) - max(bx0, rect[0].toDouble()))
        val ih = max(0.0, min(by1, rect[3].toDouble()) - max(by0, rect[1].toDouble()))
        val boardArea = (bx1 - bx0) * (by1 - by0)
        if (boardArea <= 0.0) return 0.0
        return (iw * ih) / boardArea
    }

    private var frameRowBytes: ByteArray? = null

    private fun imageToFrame(image: Image, capturedAt: Long = System.currentTimeMillis()): Frame {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        val px = IntArray(w * h)
        if (pixelStride == 4) {
            // 快路径：整行一次批量 JNI 读，再在 JVM 内解 RGBA（避免每像素 4 次单字节 JNI 调用）
            var row = frameRowBytes
            if (row == null || row.size < w * 4) row = ByteArray(w * 4)
            frameRowBytes = row
            var p = 0
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, w * 4)
                var i = 0
                while (i < w * 4) {
                    px[p++] = (0xFF shl 24) or
                        ((row[i].toInt() and 0xFF) shl 16) or
                        ((row[i + 1].toInt() and 0xFF) shl 8) or
                        (row[i + 2].toInt() and 0xFF)
                    i += 4
                }
            }
        } else {
            for (y in 0 until h) {
                val rowStart = y * rowStride
                for (x in 0 until w) {
                    val off = rowStart + x * pixelStride
                    val r = buffer.get(off).toInt() and 0xFF
                    val g = buffer.get(off + 1).toInt() and 0xFF
                    val b = buffer.get(off + 2).toInt() and 0xFF
                    px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Frame(w, h, px, capturedAt)
    }

    /**
     * 调试开关：当 /sdcard/assist_debug_save 存在时，把服务实际收到的帧原始字节存到
     * /sdcard/assist_frame.raw（magic=ASFR, 大端 int w/h, 之后 RGB 三字节每像素）。
     */
    private fun debugSaveFrameIfRequested(frame: Frame) {
        if (!com.xiangqi.assist.BuildConfig.DEBUG) return
        val trigger = java.io.File("/sdcard/assist_debug_save")
        if (!trigger.exists()) return
        trigger.delete()
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val outPath = java.io.File(dir, "assist_frame.raw").absolutePath
            java.io.DataOutputStream(java.io.FileOutputStream(outPath)).use { out ->
                out.writeInt(0x41534652) // 'ASFR'
                out.writeInt(frame.width)
                out.writeInt(frame.height)
                for (p in frame.argb) {
                    out.writeByte((p shr 16) and 0xFF)
                    out.writeByte((p shr 8) and 0xFF)
                    out.writeByte(p and 0xFF)
                }
            }
            Log.i(TAG, "debug frame saved $outPath")
        } catch (e: Exception) {
            Log.w(TAG, "debug frame save failed", e)
        }
    }

    // ==================== 识别与分析 ====================

    /**
     * 裁剪推理提示：已知棋盘位置时只把棋盘外扩约 1.2 格的区域送入模型，
     * 减少整屏缩放开销并提高棋子有效分辨率；尺寸向下取 16 的倍数以稳定裁剪位图复用。
     * 返回 null 表示整帧。
     */
    private fun cropHint(frame: Frame): DoubleArray? {
        val g = sessionGrid ?: return null
        val x0 = g.nx0 * frame.width
        val y0 = g.ny0 * frame.height
        val x1 = g.nx1 * frame.width
        val y1 = g.ny1 * frame.height
        val mx = (x1 - x0) / 8.0 * 1.2
        val my = (y1 - y0) / 9.0 * 1.2
        val cx0 = max(0.0, x0 - mx)
        val cy0 = max(0.0, y0 - my)
        val cx1 = min(frame.width.toDouble(), x1 + mx)
        val cy1 = min(frame.height.toDouble(), y1 + my)
        val w = ((cx1 - cx0).toInt() / 16) * 16
        val h = ((cy1 - cy0).toInt() / 16) * 16
        if (w < 64 || h < 64) return null
        return doubleArrayOf(cx0, cy0, cx0 + w, cy0 + h)
    }

    /**
     * 刷新识别只有得到可安全计算的棋面才算成功；否则保留请求并继续取帧。
     * @return true 表示本帧已由刷新流程消费，调用方应立即返回。
     */
    private fun consumeRefreshResult(result: RecognitionResult, epoch: Long): Boolean {
        if (!refresh.isActive) return false
        val unusable = structuralProblem(result.canonical)
        if (unusable != null) {
            // 结构不合法：本次关键帧直接拒绝，下一组稳定窗口重新检测；不把旧棋面写回当前结果。
            refresh.consumeAttempt(unusable)
            if (refresh.checkGiveUp(System.currentTimeMillis(), unusable)) {
                finishRefreshRequest()
                postRender()
            } else {
                setStatus("棋面仍不合法：$unusable\n拒绝当前关键帧，继续录屏稳定窗口轮询…")
            }
            return true
        }

        refresh.consumeAttempt(null)
        refresh.succeed()
        if (userRefreshActive) userRefreshActive = false
        pendingBoard = result
        pendingBoardAt = System.currentTimeMillis()
        val refreshResult = result
        mainHandler.post {
            if (epoch != recognitionEpoch) return@post
            // 成功应用前先结束“更新棋谱”事务；否则旧的 SUCCEEDED/WAITING 文案会继续
            // 覆盖正常的计算/待落子阶段，用户会误以为状态机仍卡在更新中。
            finishRefreshRequest()
            applyRecognizedBoard(refreshResult, fromManualRefresh = true)
            postRender()
        }
        return true
    }

    /** 用户主动刷新结束后立刻回到普通阶段；成功/失败都不能继续占用UPDATING。 */
    private fun finishRefreshRequest() {
        trace("REFRESH_FINISH", "active=$userRefreshActive")
        if (userRefreshActive) userRefreshActive = false
        refresh.cancel()
    }

    /**
     * 中国象棋的被将状态可以直接确定当前轮次：被攻击的一方必须走；
     * 以用户指定的己方颜色解释“我方/对方”，不使用屏幕上下位置。
     */
    private fun forcedTurnFromCheck(board: Array<IntArray>): Boolean? {
        val myRed = mySideIsRed()
        val myChecked = AssistBoard.kingAttacked(board, myRed)
        val opponentChecked = AssistBoard.kingAttacked(board, !myRed)
        return when {
            myChecked && !opponentChecked -> myRed
            opponentChecked && !myChecked -> !myRed
            else -> null
        }
    }

    /** 返回 null 表示可用，否则返回可直接显示给用户的原因。 */
    private fun structuralProblem(canonical: Array<IntArray>): String? {
        AssistBoard.validate(canonical).firstOrNull()?.let { return it }
        AssistBoard.invalidPiecePlacement(canonical)?.let { return it }
        if (!landingRebasePending && !refresh.isActive && !resumeRebasePending) {
            // 已有可靠基线时，缺子不是“局面变化”：只有规则确认的一步合法走子
            // 才能解释棋子消失。绝不拿上一局面把缺失棋子补回当前结果。
            BoardCompletenessPolicy.rejectionReason(currentCanonical, canonical)?.let { return it }
        }
        if (landingRebasePending) {
            // 核对超时后的第一张画面可能是落子前、预期落子后，或期间已经走了新着法。
            // 不要求它必须是一两步可达，但仍拒绝相对保存快照明显“只少了棋子”的残缺子集，
            // 否则日志中 27→26→…→13 的坏盘面会在每次超时重建时重新成为基线。
            val rebaseBaselines = listOf(landingRebasePreBoard, landingRebaseExpectedBoard)
                .filterNotNull()
            val rebaseIssues = rebaseBaselines
                .mapNotNull { BoardCompletenessPolicy.rejectionReason(it, canonical) }
            // 观察结果只要能由任一保存快照解释，就继续交给轮次重建；只有相对所有
            // 可用快照都呈现残缺子集时才拒绝，避免“预期落子后盘面”被落子前快照误杀。
            if (rebaseBaselines.isNotEmpty() && rebaseIssues.size == rebaseBaselines.size) {
                return rebaseIssues.first()
            }
            // 两种轮次只要有一种对引擎安全就应放行，避免“将军着”因暂用旧轮次被误拒。
            val before = AssistBoard.engineUnsafeReason(canonical, landingRebaseRedGo)
            val after = AssistBoard.engineUnsafeReason(canonical, !landingRebaseRedGo)
            return if (before == null || after == null) null else before
        }
        val legalityRedGo = forcedTurnFromCheck(canonical)
            ?: if (currentCanonical == null) mySideIsRed() else currentRedGo
        return AssistBoard.engineUnsafeReason(canonical, legalityRedGo)
    }

    /** 落子核对恢复后的首张稳定画面只用于建立基线；轮次判定委托给纯逻辑策略。 */
    private fun rebaseTurnForObservedBoard(
        observed: Array<IntArray>,
        preBoard: Array<IntArray>?,
        expectedPostBoard: Array<IntArray>?,
    ): Boolean = LandingRebasePolicy.redGoForObserved(
        observed = observed,
        preBoard = preBoard,
        expectedPostBoard = expectedPostBoard,
        preRedGo = landingRebaseRedGo,
    )
    /** 落子核对帧可以处在我方刚走完或对方刚走完两个合法轮次之一。 */
    private fun landingFrameStructuralProblem(
        canonical: Array<IntArray>,
        landing: LandingFlow.State,
    ): String? {
        AssistBoard.validate(canonical).firstOrNull()?.let { return it }
        AssistBoard.invalidPiecePlacement(canonical)?.let { return it }
        val baseline = landing.expectedPostBoard ?: landing.preBoard
        BoardCompletenessPolicy.rejectionReason(baseline, canonical)?.let { return it }
        val afterOurMove = AssistBoard.engineUnsafeReason(canonical, !landing.preRedGo)
        val afterOpponentMove = AssistBoard.engineUnsafeReason(canonical, landing.preRedGo)
        return if (afterOurMove == null || afterOpponentMove == null) null else afterOurMove
    }


    private fun recognizeFrame(
        frame: Frame,
        epoch: Long,
    ): DetectionObservation? {
        if (stopping || overlayClosed || epoch != recognitionEpoch || manualMode || (paused && !refresh.isActive)) return null
        val yolo = yoloDetector ?: return null
        // 不再用透明度/隐藏躲开悬浮窗：可见窗口（面板或悬浮球）始终会进入画面，
        // 因此始终把它的矩形涂成中性灰，并从检测结果里剔除该区域。
        val excluded = panelRectInFrame(frame.width, frame.height)
        if (excluded != null) maskRegion(frame, excluded, LETTERBOX_GRAY)
        val mapped = try {
            detectVision(frame, yolo, excluded)
        } catch (t: Throwable) {
            // Java层异常不能击穿识别线程；原生SIGSEGV由生命周期闸门避免，
            // 这里负责把可恢复的TFLite错误转成当前帧无效并继续下一轮。
            Log.w(TAG, "yolo detect failed", t)
            null
        } ?: return null
        if (epoch != recognitionEpoch || mapped.pieceCount < MIN_RECOGNIZED_PIECES) return null
        // 不用上一局面补子、改字或判断“哪一类更像”：此处只发布当前模型的独立结果。
        // 图像稳定性和结构校验负责运行时保险，模型自身负责字形类别。
        return DetectionObservation(frame, mapped, mapped.canonical, epoch)
    }

    /**
     * 连续八个录屏样本稳定后，只对挑出的一个关键帧调用一次模型。
     * 稳定窗口只挑选清晰关键帧；异常结果直接拒绝，等待下一个相邻样本。
     */
    private fun handleFrameBatch(frames: List<Frame>, epoch: Long) {
        if (frames.isEmpty() || stopping || overlayClosed || epoch != recognitionEpoch ||
            manualMode || (paused && !refresh.isActive)) return
        val frame = frames.first()
        debugSaveFrameIfRequested(frame)
        val observation = recognizeFrame(frame, epoch)
        if (observation == null) {
            // 模型没有给出可用盘面：保留滑动窗口，只把释放闸门重新打开；
            // 下一个 250ms 样本即可再次推理，不必重新等待八帧。
            stableFrameWindow.rearm()
            trace("VISION_REJECT", "reason=unavailable epoch=$epoch phase=${refreshPhase()} misses=${yoloMissStreak + 1}")
            streamAnomalyStreak++
            yoloMissStreak++
            lastFailReason = "稳定关键帧未得到可用棋面"
            if (streamAnomalyStreak >= STREAM_STABLE_FRAME_COUNT) {
                sessionGrid = null
                lastFullVisionCheckAt = 0L
            }
            if (refresh.isActive) refresh.consumeAttempt(lastFailReason)
            if (yoloMissStreak == 1 || yoloMissStreak % 4 == 0) {
                setStatus("关键帧未确认：$lastFailReason\n继续从录屏流寻找稳定画面…")
            }
            return
        }

        val mapped = observation.mapped
        val canonical = observation.canonical
        val activeLanding = landingState
        trace(
            "VISION_RESULT",
            "pieces=${mapped.pieceCount} anchor=${mapped.anchorSource} orientation=${mapped.orientation} " +
                "stable=$stableFrameCount landing=${activeLanding?.stage}"
        )
        val unsafe = if (activeLanding != null) {
            landingFrameStructuralProblem(canonical, activeLanding)
        } else {
            structuralProblem(canonical)
        }
        if (unsafe != null) {
            // 当前关键帧被结构/缺子门拒绝；滑动窗口不清空，下一张相邻样本直接重试。
            stableFrameWindow.rearm()
            trace("VISION_REJECT", "reason=$unsafe epoch=$epoch")
            streamAnomalyStreak++
            yoloMissStreak++
            lastFailReason = unsafe
            if (streamAnomalyStreak >= STREAM_STABLE_FRAME_COUNT) {
                sessionGrid = null
                lastFullVisionCheckAt = 0L
            }
            if (refresh.isActive) refresh.consumeAttempt(unsafe)
            setStatus("关键帧局面异常：$unsafe\n继续周期重检，稳定后再计算…")
            return
        }

        streamAnomalyStreak = 0
        yoloMissStreak = 0
        lastSeen = SeenBoard(canonical, frame.capturedAt)
        sessionGrid = mapped.grid
        lastMappedAt = System.currentTimeMillis()
        lastMapAtForWatchdog = lastMappedAt
        lastVisionAt = lastMappedAt
        val trackingPieceCount = canonical.sumOf { row -> row.count { it != Piece.EMPTY } }
        lastMappedPieces = trackingPieceCount
        lastAnchorSource = mapped.anchorSource
        lastFailReason = null
        val result = RecognitionResult(
            canonical, mapped.screenRaw, mapped.orientation,
            emptyList(), 0, trackingPieceCount, mapped.avgScore)
        Log.d(TAG, "rec(stable-stream): stable=$stableFrameCount pieces=$trackingPieceCount " +
            "orient=${mapped.orientation} anchor=${mapped.anchorSource}")

        if (activeLanding != null) {
            // 落子事务独占识别提交：MOVING 期间的旧帧不进入跟踪器；VERIFYING
            // 期间只交给回执判定，不能直接改写 currentFen 或启动新搜索。
            val observed = AssistBoard.clone(canonical)
            if (activeLanding.stage == LandingFlow.Stage.VERIFYING) {
                mainHandler.post {
                    processLandingVerification(
                        token = activeLanding.token,
                        observed = observed,
                        capturedAt = frame.capturedAt,
                        mapped = mapped,
                        pieceCount = trackingPieceCount,
                    )
                }
            } else {
                trace("VISION_DURING_GESTURE", "token=${activeLanding.token} capturedAt=${frame.capturedAt}")
            }
            return
        }

        if (semiAuto) {
            pendingBoard = result
            pendingBoardAt = System.currentTimeMillis()
        }
        if (consumeRefreshResult(result, epoch)) return
        if (semiAuto) {
            postRender()
            return
        }
        when (tracker.onFrame(result, config.matchTolerance)) {
            BoardTracker.Event.NEW_BOARD -> {
                cropUnstableStreak = 0
                onBoardConfirmed(epoch)
            }
            BoardTracker.Event.UNSTABLE -> {
                // 跟踪器尚未接受这张盘面时也要继续消费相邻样本；窗口本身仍保留最近八帧。
                stableFrameWindow.rearm()
                cropUnstableStreak++
                if (cropUnstableStreak >= CROP_RESET_MISSES) {
                    cropUnstableStreak = 0
                    sessionGrid = null
                    lastFullVisionCheckAt = 0L
                }
            }
            BoardTracker.Event.SAME_BOARD -> Unit
        }
    }

    /**
     * 落子事务专属回执处理：不经过 BoardTracker，不根据任意差异翻转轮次。
     * 回执先确认本次着法的预期盘面，再决定是否已经看到对手的一步。
     */
    private fun processLandingVerification(
        token: Long,
        observed: Array<IntArray>,
        capturedAt: Long,
        mapped: DetectionBoardMapper.MappedBoard,
        pieceCount: Int,
    ) {
        val landing = landingState
        if (landing == null || landing.token != token || landing.stage != LandingFlow.Stage.VERIFYING) {
            trace("LANDING_FRAME_STALE", "token=$token capturedAt=$capturedAt")
            return
        }
        if (capturedAt <= landing.completedAt) {
            trace("LANDING_FRAME_OLD", "token=$token capturedAt=$capturedAt completedAt=${landing.completedAt}")
            return
        }
        val verdict = LandingVerificationPolicy.evaluate(
            LandingVerificationPolicy.Input(
                preBoard = landing.preBoard,
                expectedPostBoard = landing.expectedPostBoard,
                observedBoard = observed,
                ucci = landing.ucci,
                preRedGo = landing.preRedGo,
                tolerance = config.matchTolerance,
            )
        )
        trace(
            "LANDING_VERDICT",
            "token=$token verdict=$verdict ucci=${landing.ucci} observedPieces=$pieceCount " +
                "capturedAt=$capturedAt expected=${landing.expectedPostBoard != null}"
        )
        when (verdict) {
            LandingVerificationPolicy.Verdict.WAITING -> {
                // 当前稳定关键帧还不能证明落子已生效；保留窗口内容，
                // 但解除一次性释放闸门，让下一个250ms样本立即重新核对。
                stableFrameWindow.rearm()
                autoLastResult = "等待确认本次落子结果"
                requestRenderOnly()
            }
            LandingVerificationPolicy.Verdict.LANDED,
            LandingVerificationPolicy.Verdict.LANDED_WITH_UNCERTAIN_FOLLOWUP -> {
                val expected = landing.expectedPostBoard ?: return
                commitLandingBoard(
                    landing = landing,
                    board = expected,
                    redGo = !landing.preRedGo,
                    mapped = mapped,
                    pieceCount = countPieces(expected),
                    verdict = verdict,
                )
            }
            LandingVerificationPolicy.Verdict.LANDED_AND_OPPONENT_MOVED -> {
                commitLandingBoard(
                    landing = landing,
                    board = observed,
                    redGo = landing.preRedGo,
                    mapped = mapped,
                    pieceCount = pieceCount,
                    verdict = verdict,
                )
            }
        }
    }

    /** 回执已经确认后，单次、原子地提交局面和轮次，再释放落子事务。 */
    private fun commitLandingBoard(
        landing: LandingFlow.State,
        board: Array<IntArray>,
        redGo: Boolean,
        mapped: DetectionBoardMapper.MappedBoard,
        pieceCount: Int,
        verdict: LandingVerificationPolicy.Verdict,
    ) {
        val current = landingState
        if (current == null || current.token != landing.token || current.stage != LandingFlow.Stage.VERIFYING) return
        val fen = AssistBoard.toFen(board, redGo)
        val oldFen = currentFen
        val result = RecognitionResult(
            AssistBoard.clone(board),
            mapped.screenRaw,
            mapped.orientation,
            emptyList(),
            0,
            pieceCount,
            mapped.avgScore,
        )
        currentCanonical = AssistBoard.clone(board)
        currentPieces = flatten(board)
        currentOrientation = mapped.orientation
        currentRedGo = redGo
        tracker.forceSet(result, redGo)
        currentFen = fen
        boardUpdatedAt = System.currentTimeMillis()
        stableFrameAt = boardUpdatedAt
        autoLastUcci = if (verdict == LandingVerificationPolicy.Verdict.LANDED_AND_OPPONENT_MOVED) {
            null
        } else {
            landing.ucci
        }
        adviceBoard = AssistBoard.clone(board)
        if (history.lastOrNull()?.fen != fen) {
            history.addLast(HistoryEntry(result, redGo, fen))
            if (history.size > 60) history.removeFirst()
        }
        trace(
            "LANDING_COMMIT",
            "token=${landing.token} verdict=$verdict oldFen=$oldFen newFen=$fen redGo=$redGo"
        )
        clearLandingTransaction(restoreWindow = true)
        autoMoveAttempts = 0
        autoMoveCount++
        autoLastResult = if (verdict == LandingVerificationPolicy.Verdict.LANDED_AND_OPPONENT_MOVED) {
            "我方落子与对手落子均已确认（累计 ${autoMoveCount} 手）"
        } else {
            "已生效，等待对手落子（累计 ${autoMoveCount} 手）"
        }
        previewState.clear()
        resetSuggestionState(newAnalysisCycle = true)
        postRender()
        scheduleAnalysisDebounced()
    }
    private fun onBoardConfirmed(epoch: Long) {
        val confirmed = tracker.confirmed ?: return
        val isLandingRebase = landingRebasePending
        val rebasePre = landingRebasePreBoard
        val rebaseExpected = landingRebaseExpectedBoard
        var confirmedRedGo = when {
            isLandingRebase -> rebaseTurnForObservedBoard(confirmed.canonical, rebasePre, rebaseExpected)
            currentCanonical == null -> mySideIsRed()
            else -> tracker.redGo
        }
        forcedTurnFromCheck(confirmed.canonical)?.let { forced ->
            confirmedRedGo = forced
            Log.i(TAG, "turn forced by check: ${if (forced == mySideIsRed()) "my side" else "opponent side"} to move")
        }
        // 识别在 worker 线程；所有业务状态只在主线程提交。模式/暂停若已变化，旧结果丢弃。
        mainHandler.post {
            if (epoch != recognitionEpoch) return@post
            // 该确认可能早于无障碍回调排队；一旦落子事务已经建立，普通确认
            // 不得再写 currentFen/轮次或启动分析。回执专属处理会消费这张画面。
            val landing = landingState
            if (landing != null) {
                trace(
                    "BOARD_CONFIRM_SUPPRESSED",
                    "epoch=$epoch landingToken=${landing.token} stage=${landing.stage}"
                )
                return@post
            }
            val unsafe = AssistBoard.engineUnsafeReason(confirmed.canonical, confirmedRedGo)
            if (unsafe != null) {
                // 这是当前关键帧自身的异常：只拒绝并清空跟踪候选，等待下一组录屏样本。
                // 不把旧棋面复制回候选，也不以旧棋面补齐缺失棋子。
                tracker.reset(currentRedGo)
                setStatus("棋面结构异常：$unsafe\n拒绝当前关键帧，继续周期重检…")
                return@post
            }

            boardUpdatedAt = System.currentTimeMillis()
            stableFrameAt = System.currentTimeMillis()
            if (isLandingRebase) {
                trace(
                    "LANDING_REBASE_BOARD",
                    "preMatch=${rebasePre?.let { AssistBoard.equal(it, confirmed.canonical) }} " +
                        "expectedMatch=${rebaseExpected?.let { AssistBoard.equal(it, confirmed.canonical) }} " +
                        "redGo=$confirmedRedGo"
                )
                landingRebasePending = false
                landingRebasePreBoard = null
                landingRebaseExpectedBoard = null
                reanalyzeAfterLandingFailure = false
                autoMoveAttempts = 0
                analysisCycleGate.invalidate()
            }
            if (resumeRebasePending) {
                // 这是暂停后第一次稳定画面：它是新的观察基线，不能被旧回合/旧建议覆盖。
                resumeRebasePending = false
                analysisCycleGate.invalidate()
            }
            val previousBoard = currentCanonical
            val previousFen = currentFen

            // ==================== 轮次（不再由差分推断） ====================
            // 1) 我方刚落子并拿到回执时，轮次已经被显式切到对方；此后屏幕上出现的
            //    任何新稳定盘面，就是对方走过的那一手 → 切回我方。
            // 2) 其余情况只看“这一帧里动的是哪一方的棋子”这一个纯结构信息：
            //    动的是我方棋子 → 我方刚落完 → 轮到对方；判断不出来就保持当前轮次。
            // 两条都不会拒收盘面，也不枚举合法着法、不要求“一手/两步可达”。
            var nextRedGo = if (previousBoard == null) confirmedRedGo else currentRedGo
            val boardReallyChanged = previousBoard != null &&
                !AssistBoard.equal(previousBoard, confirmed.canonical)
            if (boardReallyChanged) {
                nextRedGo = if (currentRedGo != mySideIsRed()) {
                    mySideIsRed()
                } else {
                    when (AssistBoard.movedSide(previousBoard!!, confirmed.canonical)) {
                        mySideCode() -> !mySideIsRed()
                        else -> currentRedGo
                    }
                }
            }

            val newFen = AssistBoard.toFen(confirmed.canonical, nextRedGo)
            val boardChanged = newFen != previousFen
            tracker.redGo = nextRedGo
            currentRedGo = nextRedGo
            currentCanonical = confirmed.canonical
            currentPieces = flatten(confirmed.canonical)
            currentOrientation = confirmed.orientation
            currentFen = newFen
            trace(
                "BOARD_CONFIRMED",
                "changed=$boardChanged pieces=${confirmed.recognizedPieces} fen=$newFen previous=$previousFen"
            )

            if (!boardChanged) {
                Log.d(TAG, "board confirmed but unchanged: $newFen")
                if (reanalyzeAfterLandingFailure) {
                    reanalyzeAfterLandingFailure = false
                    analysisCycleGate.invalidate()
                    resetSuggestionState(newAnalysisCycle = false)
                    trace("REANALYZE_AFTER_LANDING_FAILURE", "fen=$newFen")
                    scheduleAnalysisDebounced()
                }
                requestRenderOnly()
                return@post
            }

            if (history.lastOrNull()?.fen != newFen) {
                history.addLast(HistoryEntry(confirmed, nextRedGo, newFen))
                if (history.size > 60) history.removeFirst()
            }
            autoLastUcci = null
            previewState.clear()
            resetSuggestionState(newAnalysisCycle = true)
            Log.i(TAG, "board confirmed: $newFen")
            postRender()
            scheduleAnalysisDebounced()
        }
    }

    /**
     * 局面变化后的分析防抖。
     *
     * 识别的瞬时抖动会让"新局面"在很短时间内连续出现几次；每次都直接重算，
     * 就等于反复打断引擎。等局面稍微稳定一点再算，既省时间也不会自我打断。
     */
    private fun scheduleAnalysisDebounced() {
        workerHandler.removeCallbacks(analysisDebounce)
        workerHandler.postDelayed(analysisDebounce, BOARD_CHANGE_DEBOUNCE_MS)
    }

    private fun flatten(p: Array<IntArray>): IntArray {
        val out = IntArray(90)
        for (y in 0 until 10) for (x in 0 until 9) out[y * 9 + x] = p[y][x]
        return out
    }

    /** 重置建议状态：局面/轮次变化后，旧的分析结果与成熟度跟踪全部作废 */
    /**
     * 打断当前搜索：停止思考并清掉"正在算"的状态。
     * 棋盘被改动（手动编辑、点更新棋谱且局面有变）时必须调用，
     * 否则引擎会继续拿旧局面算，产出与实际棋面对不上的建议。
     */
    private fun stopCurrentSearch() {
        trace("SEARCH_STOP", "fen=$currentFen id=$expectedAnalysisId searching=$searching")
        runCatching { analysisEngine?.stopSearch() }
        expectedAnalysisId = 0L
        searching = false
        searchSettled = false
        lastSearchCpuTimeMs = -1L
        searchCpuProgressAt = 0L
        stableUcci = null
        stableSince = 0L
    }

    /**
     * 棋面已变更：打断旧任务 → 重建缓存 → 立刻按新局面重新计算。
     * [reason] 只用于状态提示。
     */
    private fun restartAnalysisForNewBoard(reason: String) {
        clearLandingTransaction(restoreWindow = true)
        stopCurrentSearch()
        boardUpdatedAt = System.currentTimeMillis()
        bookMoves = null
        autoLastUcci = null
        resetSuggestionState(newAnalysisCycle = true)
        postRender()
        scheduleAnalysis()
        if (reason.isNotEmpty()) autoLastResult = reason
    }

    private fun resetSuggestionState(newAnalysisCycle: Boolean = false) {
        if (newAnalysisCycle) analysisCycleGate.invalidate()
        analyzedFen = ""   // 兼容旧诊断字段；真正的周期闸门由analysisCycleGate控制。
        expectedAnalysisId = 0L
        lastAnalysis = null
        selectedCandidate = 0
        bookMoves = null
        stableUcci = null
        stableSince = 0L
        searchSettled = false
    }

    /** MoveChinese.describe 容错版：解析失败时原样返回 UCCI */
    private fun describeSafe(fen: String, ucci: String): String =
        try { MoveChinese.describe(fen, ucci) } catch (e: Exception) { ucci }

    /**
     * 分析调度（worker 线程）：我方回合先查开局库（与对弈同款，命中即秒出且不占用引擎），
     * 未命中走引擎深度搜索；对方回合直接引擎预案。
     */
    private fun scheduleAnalysis() {
        workerHandler.post {
            if (landingState != null || pendingAutoMove != null) {
                trace(
                    "ANALYSIS_SUPPRESSED",
                    "reason=landing-transaction landing=${landingState?.stage} pending=${pendingAutoMove != null} fen=$currentFen"
                )
                return@post
            }
            trace("ANALYSIS_SCHEDULE", "fen=$currentFen gate=${analysisCycleGate.activePosition()} ready=$engineReady")
            if (resumeRebasePending) {
                Log.d(TAG, "scheduleAnalysis: waiting for post-pause stable baseline")
                return@post
            }
            // 首次真正需要分析时才拉起引擎进程（此时用户已点开始、局面也已识别到，
            // 游戏处于前台，被系统回收的风险最低）
            ensureEngineStarted()
            val canonical = currentCanonical ?: return@post
            val fen = currentFen
            if (fen.isEmpty()) return@post
            val redGo = currentRedGo
            val myTurn = redGo == mySideIsRed()
            // 登记"这份建议所依据的盘面"。必须放在开局库分支**之前**：
            // 走开局库时若漏登记，落子前确认会拿旧快照去比，误判成"盘面已变"而反复丢弃建议。
            adviceBoard = AssistBoard.clone(canonical)
            // 只有已经真正提交过搜索/开局库结果的周期才占用闸门；引擎预热期间不能提前占位。
            if (analysisCycleGate.activePosition() == fen) {
                trace("ANALYSIS_REUSE", "fen=$fen")
                Log.d(TAG, "scheduleAnalysis: reuse active analysis cycle for fen")
                return@post
            }
            if (myTurn) {
                val moves = queryBook(canonical, redGo)
                if (!moves.isNullOrEmpty()) {
                    analysisCycleGate.beginOrReuse(fen)
                    analyzedFen = fen
                    expectedAnalysisId = 0L
                    bookMoves = moves.take(config.candidateCount)
                    searching = false
                    searchSettled = true
                    mainHandler.post { postRender() }
                    return@post
                }
            }
            bookMoves = null
            if (!engineReady) {
                val waited = (System.currentTimeMillis() - engineWarmupStartAt) / 1000
                mainHandler.post {
                    setStatus(engineErrorText ?: "引擎预热中…（已等待 ${waited}s，首次加载 NNUE 约需数秒）")
                }
                return@post
            }
            // 发送前自检：皮卡鱼 2026 版遇到非法局面会直接退出进程，
            // 与其让引擎崩掉再重启，不如先自查（识别错误时很常见）
            val unsafe = AssistBoard.engineUnsafeReason(canonical, redGo)
            if (unsafe != null) {
                lastRejectReason = unsafe
                mainHandler.post {
                    notifyBoardProblem(unsafe)
                }
                return@post
            }
            analysisCycleGate.beginOrReuse(fen)
            analyzedFen = fen
            val mode = config.thinkingMode
            val budget = if (mode == ThinkingMode.DEPTH) {
                val depth = if (myTurn) searchDepthTarget else min(searchDepthTarget, OPPONENT_MAX_DEPTH)
                // 深度模式只设置用户选择的深度上限；不额外塞一个人为的 movetime 截断。
                // 引擎会自然搜索到该深度并返回 bestmove，棋力优化来自线程、Hash、预热、
                // MultiPV共享和任务去重，而不是把搜索预算砍短后冒充“响应优化”。
                AnalysisBudget.forDepth(depth, config.candidateCount)
            } else {
                val totalMs = if (mode == ThinkingMode.PER_CANDIDATE_TIME) {
                    ThinkingOptions.effectivePerCandidateTotal(
                        config.perCandidateTimeMs,
                        config.candidateCount,
                    )
                } else if (myTurn) {
                    config.thinkTimeMs
                } else {
                    config.opponentThinkMs.coerceAtLeast(100)
                }
                if (mode == ThinkingMode.PER_CANDIDATE_TIME) {
                    AnalysisBudget.forPerCandidateTime(config.perCandidateTimeMs, config.candidateCount)
                } else {
                    AnalysisBudget.forTotalTime(totalMs, config.candidateCount)
                }
            }
            analysisRequestAt = System.currentTimeMillis()
            searchProgressAt = analysisRequestAt
            searchStartAt = analysisRequestAt
            firstPvAt = 0L
            lastSearchCpuTimeMs = -1L
            searchCpuProgressAt = 0L
            expectedAnalysisId = analysisEngine?.request(fen, budget) ?: 0L
            searching = expectedAnalysisId > 0L
            if (!searching) searchStartAt = 0L
            previewState.onContext(fen, expectedAnalysisId)
            Log.i(TAG, "analysis request fen=$fen budget=${budget.goCommand()} id=$expectedAnalysisId")
            trace(
                "ANALYSIS_REQUEST",
                "id=$expectedAnalysisId fen=$fen budget=${budget.goCommand()} myTurn=$myTurn"
            )
            mainHandler.post { postRender() }
            if (myTurn) {
                val label = if (mode == ThinkingMode.DEPTH) {
                    "深度 $searchDepthTarget · ${config.candidateCount}条共享"
                } else {
                    val total = if (mode == ThinkingMode.PER_CANDIDATE_TIME) {
                        ThinkingOptions.effectivePerCandidateTotal(config.perCandidateTimeMs, config.candidateCount)
                    } else config.thinkTimeMs
                    "${config.candidateCount}条 · 每候选 ${ThinkingOptions.formatTime(config.perCandidateTimeMs.toLong())}（总计 ${ThinkingOptions.formatTime(total.toLong())}）"
                }
                mainHandler.post { setStatus("引擎计算中…（$label）") }
            }
        }
    }

    /** 启动引擎进程（只启动一次）。调用点只有"确实要分析局面"的地方。 */
    private fun ensureEngineStarted() {
        val eng = analysisEngine ?: return
        if (engineStarted) return
        engineStarted = true
        engineWarmupStartAt = System.currentTimeMillis()
        Log.i(TAG, "analysis engine starting (lazy) depth=${config.searchDepth} hash=${config.hashMb}MB")
        eng.start()
    }

    /** 查询开局库（惰性初始化；仅 worker 线程调用）。异常时降级为 null（走引擎）。 */
    private fun queryBook(canonical: Array<IntArray>, redGo: Boolean): List<BookData>? {
        if (openBookFailed) return null
        val book = openBook ?: try {
            BHOpenBook(this).also { openBook = it }
        } catch (t: Throwable) {
            Log.w(TAG, "openbook init failed", t)
            openBookFailed = true
            return null
        }
        return try {
            book.query(Zobrist.getZobristFromBoard(canonical, redGo), redGo, OpenBook.SortRule.BEST_SCORE)
        } catch (t: Throwable) {
            Log.w(TAG, "book query failed", t)
            null
        }
    }

    private val analysisListener = object : AnalysisEngine.Listener {
        override fun onEngineReady() {
            trace("ENGINE_READY", "fen=$currentFen")
            mainHandler.post {
                engineReady = true
                engineErrorText = null
                searching = false
                setStatus("引擎已就绪")
                scheduleAnalysis()
                postRender()
            }
        }

        override fun onSearchUpdate(result: AnalysisResult) {
            val best = result.bestUcci
            val now = System.currentTimeMillis()
            if (best != lastLoggedSearchUpdateUcci || now - lastLoggedSearchUpdateAt >= 500L) {
                lastLoggedSearchUpdateUcci = best
                lastLoggedSearchUpdateAt = now
                trace(
                    "SEARCH_UPDATE",
                    "id=${result.analysisId} fen=${result.fen} best=$best depth=${result.bestLine?.depth}"
                )
            }
            mainHandler.post {
                if (result.fen != currentFen || result.analysisId != expectedAnalysisId) return@post
                if (!searching) {
                    searching = true
                    searchStartAt = System.currentTimeMillis()
                }
                searchProgressAt = System.currentTimeMillis()
                if (firstPvAt <= 0L) {
                    firstPvAt = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "first PV after ${firstPvAt - analysisRequestAt}ms " +
                            "(stable-frame->request ${analysisRequestAt - stableFrameAt}ms)"
                    )
                }
                lastAnalysis = result
                val best = result.bestLine?.pv?.firstOrNull() ?: result.bestUcci
                if (best != stableUcci) {
                    stableUcci = best
                    stableSince = System.currentTimeMillis()
                    searchSettled = false
                }
                // 蓝色候选箭头出现 → 由分析回调直接驱动起点预选（渲染函数保持纯显示）。
                scheduleCandidateOriginPreview(currentShownUcci())
                postRender()
            }
        }

        override fun onSearchDone(result: AnalysisResult) {
            trace(
                "SEARCH_DONE",
                "id=${result.analysisId} fen=${result.fen} best=${result.bestMove}"
            )
            mainHandler.post {
                if (result.fen != currentFen || result.analysisId != expectedAnalysisId) return@post
                lastAnalysis = result
                searchDoneAt = System.currentTimeMillis()
                searchProgressAt = searchDoneAt
                // 只有真正收到 bestmove 才算最终定着；超时但只剩 info/pv 时仍保持蓝色，
                // 不能因为“有一条候选”就提前进入正式落子。
                searchSettled = result.bestMove != null
                searching = false
                Log.i(
                    TAG,
                    "search done bestmove=${result.bestMove} total=${searchDoneAt - analysisRequestAt}ms"
                )
                // 搜索完成只意味着待落子/等待对面，绝不能硬写成“识别中”。
                postRender()
            }
        }

        override fun onEngineRestarted(reason: String) {
            trace("ENGINE_RESTART", reason)
            Log.i(TAG, "engine restarted: $reason")
            mainHandler.post {
                engineReady = false
                searching = false
                searchProgressAt = 0L
                searchSettled = false
                // 引擎进程重启是当前分析周期的真实失败；清除闸门后由 ready 回调重新提交同一局面。
                analysisCycleGate.invalidate()
                expectedAnalysisId = 0L
                setStatus("$reason\n稍后自动恢复分析")
                postRender()
            }
        }

        override fun onEngineError(message: String) {
            trace("ENGINE_ERROR", message)
            Log.w(TAG, "engine error: $message")
            mainHandler.post {
                if (overlayClosed) return@post
                engineReady = false
                searching = false
                searchProgressAt = 0L
                searchStartAt = 0L
                lastSearchCpuTimeMs = -1L
                searchCpuProgressAt = 0L
                searchSettled = false
                lastAnalysis = null
                bookMoves = null
                stableUcci = null
                adviceBoard = null
                analysisCycleGate.invalidate()
                expectedAnalysisId = 0L
                val short = if (message.length > 40) message.take(40) + "…" else message
                engineErrorText = short
                setStatus("引擎异常：$short")
                postRender()
            }
        }
    }

    // ==================== 悬浮窗 ====================

    // ==================== 悬浮窗 / 悬浮球 ====================

    private fun ensureOverlay(): OverlayPanelView? {
        if (overlayPanel != null) return overlayPanel
        if (!android.provider.Settings.canDrawOverlays(this)) return null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val panel = OverlayPanelView(this)
        val dm = screenMetrics()
        val sw = dm.widthPixels
        val sh = dm.heightPixels

        val defaultH = dp(324)
        val fallback = OverlayGeometry.defaultPanel(sw, sh, defaultH)
        val minW = dp(240)
        val minH = dp(112)
        // 有历史矩形就严格恢复面板自己的位置和大小；旧版本只有位置记录时，
        // 用旧位置作为一次性迁移，宽高仍使用默认值，随后写入完整矩形。
        val legacy = config.overlayPos
        val legacyFallback = if (config.overlayRect == null &&
            legacy.first in 0f..1f && legacy.second in 0f..1f
        ) {
            OverlayGeometry.Rect(
                (legacy.first * sw).toInt(), (legacy.second * sh).toInt(),
                fallback.w, fallback.h
            )
        } else fallback
        val r = OverlayGeometry.restore(config.overlayRect, legacyFallback, sw, sh, minW, minH)
        val w = r.w
        val h = r.h
        val x = r.x
        val y = r.y

        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        panel.onAction = ::onOverlayAction
        panel.onCellTap = ::onManualCellTap
        // 拖动/缩放悬浮窗期间不隐藏（否则会边拖边闪）
        panel.onDragStateChange = { dragging -> userDragging = dragging }
        panel.setDragHandle(params, wm, sw, sh) { persistOverlayGeometry(params, sw, sh) }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // 某些 ROM 会在 WindowManager 重排后才最终提交尺寸；布局回调再落一次盘，
            // 防止只保存了拖动前的 LayoutParams。
            if (overlayPanel === panel) persistOverlayGeometry(params, sw, sh)
        }
        wm.addView(panel, params)
        overlayPanel = panel
        overlayParams = params
        windowManager = wm
        // 立即把实际恢复后的坐标/尺寸写回，保证首次恢复、旋转或分辨率变化后也有稳定基线。
        persistOverlayGeometry(params, sw, sh)
        return panel
    }

    /** 记录面板位置与尺寸（归一化，跨分辨率可用）。保存前先把窗口坐标夹回屏内，
     * 防止 FLAG_LAYOUT_NO_LIMITS 或拖动越界写入负值，导致下次读取被判为无效而回到默认位置。 */
    private fun persistOverlayGeometry(params: WindowManager.LayoutParams, sw: Int, sh: Int) {
        if (sw <= 0 || sh <= 0 || params.width <= 0 || params.height <= 0) return
        val safeW = params.width.coerceIn(1, sw)
        val safeH = params.height.coerceIn(1, sh)
        val safeX = params.x.coerceIn(0, max(0, sw - safeW))
        val safeY = params.y.coerceIn(0, max(0, sh - safeH))
        if (params.x != safeX || params.y != safeY || params.width != safeW || params.height != safeH) {
            params.x = safeX
            params.y = safeY
            params.width = safeW
            params.height = safeH
        }
        config.overlayRect = floatArrayOf(
            safeX.toFloat() / sw, safeY.toFloat() / sh,
            safeW.toFloat() / sw, safeH.toFloat() / sh
        )
        // 兼容旧字段，但它不再作为恢复的唯一来源。
        config.overlayPos = safeX.toFloat() / sw to safeY.toFloat() / sh
    }

    private fun persistOverlayGeometryNow(params: WindowManager.LayoutParams) {
        val dm = screenMetrics()
        persistOverlayGeometry(params, dm.widthPixels, dm.heightPixels)
    }

    /** 收缩成悬浮球（先确认球已成功显示，再移除面板，避免两者同时消失） */
    private fun collapseOverlay() {
        if (overlayPanel == null && overlayBall != null) return
        overlayParams?.let { p ->
            val dm = screenMetrics()
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                persistOverlayGeometry(p, dm.widthPixels, dm.heightPixels)
            }
        }
        val ball = ensureBall()
        if (ball == null) {
            config.overlayCollapsed = false
            setStatus("悬浮球显示失败，已保留面板\n请检查悬浮窗权限后重试")
            postRender()
            return
        }
        config.overlayCollapsed = true
        overlayPanel?.let { p -> runCatching { windowManager?.removeView(p) } }
        overlayPanel = null
        overlayParams = null
        saveBallPos()
        postRender()
        setStatus("已缩成悬浮球\n点悬浮球可展开面板")
    }

    /** 把悬浮球夹回系统栏之间的可见区并保存归一化坐标。 */
    private fun saveBallPos() {
        val params = overlayBallParams ?: return
        val dm = screenMetrics()
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return
        val size = params.width.coerceAtLeast(dp(40))
        val insets = ballSafeInsets()
        val (safeX, safeY) = OverlayGeometry.safeBallPosition(
            requestedX = params.x,
            requestedY = params.y,
            ballSize = size,
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            insetLeft = insets[0],
            insetTop = insets[1],
            insetRight = insets[2],
            insetBottom = insets[3],
            margin = dp(8),
        )
        val changed = params.x != safeX || params.y != safeY
        params.x = safeX
        params.y = safeY
        if (changed) {
            overlayBall?.let { ball ->
                runCatching { windowManager?.updateViewLayout(ball, params) }
                    .onFailure { Log.w(TAG, "failed to clamp overlay ball into visible area", it) }
            }
        }
        config.overlayBallPos = safeX.toFloat() / dm.widthPixels to safeY.toFloat() / dm.heightPixels
    }

    /** 还原成完整面板：面板成功创建后才移除悬浮球。 */
    private fun expandOverlay() {
        saveBallPos()
        config.overlayCollapsed = false
        val panel = ensureOverlay()
        if (panel == null) {
            config.overlayCollapsed = true
            setStatus("面板恢复失败，悬浮球继续保留")
            return
        }
        overlayBall?.let { b -> runCatching { windowManager?.removeView(b) } }
        overlayBall = null
        overlayBallParams = null
        postRender()
    }

    private fun ensureBall(): OverlayBallView? {
        if (overlayBall != null) return overlayBall
        if (!android.provider.Settings.canDrawOverlays(this)) return null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = screenMetrics()
        val baseSize = dp(56)
        val size = (baseSize * config.overlayBallScale).roundToInt().coerceAtLeast(dp(40))
        val (bx, by) = config.overlayBallPos
        val requestedX = if (bx < 0) dm.widthPixels - size - dp(16) else (bx * dm.widthPixels).toInt()
        val requestedY = if (by < 0) dm.heightPixels - size - dp(16) else (by * dm.heightPixels).toInt()
        val insets = ballSafeInsets()
        val (x, y) = OverlayGeometry.safeBallPosition(
            requestedX = requestedX,
            requestedY = requestedY,
            ballSize = size,
            screenW = dm.widthPixels,
            screenH = dm.heightPixels,
            insetLeft = insets[0],
            insetTop = insets[1],
            insetRight = insets[2],
            insetBottom = insets[3],
            margin = dp(8),
        )
        val ball = OverlayBallView(this).apply {
            elevation = dp(12).toFloat()
            alpha = 1f
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        ball.onExpand = { expandOverlay() }
        // 双击悬浮球：
        // - 处于待落子 / 落子中 / 核对中 → **手动落子**（机器没落、或等不及了，由人踢一脚）
        // - 其余阶段 → 手动切换走棋方（把"轮到对方"切回"轮到我方"，重新识别→计算→落子）
        ball.onDoubleTap = {
            when (refreshPhase()) {
                AssistPhase.Phase.READY,
                AssistPhase.Phase.MOVING,
                AssistPhase.Phase.VERIFYING -> manualLandNow()
                else -> {
                    flipTurn()
                    setStatus("已切换走棋方（现${if (currentRedGo == mySideIsRed()) "轮到我方" else "轮到对方"}）")
                }
            }
        }
        // 拖动过程实时记录位置，避免"拖完立刻点开"时丢位置
        ball.onDragStateChange = { dragging ->
            userDragging = dragging
            if (!dragging) saveBallPos()
        }
        ball.setDragHandle(params, wm) {
            saveBallPos()
        }
        ball.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (overlayBall === ball) saveBallPos()
        }
        val added = runCatching { wm.addView(ball, params) }
        if (added.isFailure) {
            Log.e(TAG, "failed to show overlay ball", added.exceptionOrNull())
            return null
        }
        overlayBall = ball
        overlayBallParams = params
        windowManager = wm
        saveBallPos()
        return ball
    }

    /**
     * 持续录屏回调：始终消费ImageReader缓冲；每250ms只取一个样本。
     * 连续八个样本稳定后挑最清晰的一帧，才进入一次TFLite识别。
     */
    private fun onImageAvailable(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (t: Throwable) {
            Log.w(TAG, "acquireLatestImage failed", t)
            null
        } ?: return
        val receivedAt = System.currentTimeMillis()
        // 拿到缓冲就说明录屏流还活着——这是“录制侧是否停摆”的原始证据，
        // 必须在任何阶段过滤之前记录，否则停摆与“按设计不取帧”无法区分。
        lastStreamFrameAt = receivedAt
        if (streamStartedAt <= 0L) streamStartedAt = receivedAt
        try {
            val epoch = recognitionEpoch
            if (stopping || overlayClosed || paused || manualMode ||
                (captureDemand == CaptureDemand.OFF && !refresh.isActive)) return
            val now = receivedAt
            if (!FrameStabilityPolicy.shouldSample(now, lastStreamSampleAt)) return
            lastStreamSampleAt = now
            val frame = imageToFrame(image, now)
            consumeStreamSample(frame, epoch, now)
        } catch (t: Throwable) {
            Log.w(TAG, "stream frame handling failed", t)
        } finally {
            runCatching { image.close() }
        }
    }

    /** 把一帧录屏样本加入稳定窗口；此处不做模型推理。 */
    private fun consumeStreamSample(frame: Frame, epoch: Long, now: Long) {
        if (epoch != recognitionEpoch || stopping || overlayClosed) return
        val excluded = panelRectInFrame(frame.width, frame.height)
        if (excluded != null) maskRegion(frame, excluded, LETTERBOX_GRAY)

        val result = stableFrameWindow.accept(frame, epoch, now)
        stableFrameCount = result.stableCount
        if (result.restartedByChange) {
            // 动画/移动/遮挡只会让当前窗口失效；不拿上一关键帧或上一棋面补齐。
            streamAnomalyStreak++
        }
        if (!result.ready) return

        val selected = result.selected ?: frame
        stableFrameCount = STREAM_STABLE_FRAME_COUNT
        stableFrameAt = now
        // 稳定窗口成立 = 准备送模型；“稳定窗口是否还在成立”是识别侧的关键证据。
        lastStableWindowAt = now
        streamAnomalyStreak = 0
        // 单次模型识别：稳定窗口中的样本只用于选出一张关键帧。
        publishFrames(listOf(selected))
    }

    /**
     * 只保留最新的稳定关键帧任务；模型推理在线程池中执行，录屏消费不被阻塞。
     */
    private fun publishFrames(frames: List<Frame>) {
        if (frames.isEmpty()) return
        val item = QueuedFrame(frames, recognitionEpoch)
        val shouldSchedule = synchronized(frameQueueLock) {
            pendingFrame = item
            if (frameConsumeScheduled) false else {
                frameConsumeScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        workerHandler.post {
            while (true) {
                val item = synchronized(frameQueueLock) {
                    val next = pendingFrame
                    if (next == null) {
                        frameConsumeScheduled = false
                        null
                    } else {
                        pendingFrame = null
                        next
                    }
                } ?: break
                if (stopping || overlayClosed) break
                recognitionInFlight.set(true)
                inferenceStartedAt = System.currentTimeMillis()
                try {
                    runCatching {
                        handleFrameBatch(item.frames, epoch = item.epoch)
                    }.onFailure { Log.w(TAG, "handle stable frame failed", it) }
                } finally {
                    recognitionInFlight.set(false)
                    inferenceStartedAt = 0L
                }
            }
        }
    }

    /**
     * 搜索守护恢复：只有控制器/进程明确失活才触发。
     * 没有新的info/PV不在这里直接作为死亡证据；深度搜索可以长时间静默。
     */
    private fun recoverBrokenSearch(now: Long, action: SearchWatchdogPolicy.Action) {
        if (searchRecoveryScheduled || paused || manualMode || refresh.isActive || currentFen.isEmpty()) return
        searchRecoveryScheduled = true
        val snapshot = analysisEngine?.activitySnapshot()
        trace(
            "SEARCH_WATCHDOG_RECOVER",
            "action=$action now=$now started=$searchStartAt request=$analysisRequestAt " +
                "progress=$searchProgressAt fen=$currentFen id=$expectedAnalysisId " +
                "active=${snapshot?.activeJobId} pending=${snapshot?.pendingJobId} " +
                "heartbeat=${snapshot?.controllerHeartbeatAt} output=${snapshot?.lastOutputAt} " +
                "cpu=${snapshot?.processCpuTimeMs} cpuProgress=$searchCpuProgressAt " +
                "process=${snapshot?.processAlive} broken=${snapshot?.engineBroken}"
        )
        expectedAnalysisId = 0L
        analysisCycleGate.invalidate()
        stopCurrentSearch()
        analysisEngine?.requestRestart("搜索守护恢复：$action")
        setStatus("引擎控制链异常，正在自动恢复；不会修改当前棋面")
        postRender()
        mainHandler.postDelayed({
            searchRecoveryScheduled = false
            if (!paused && !manualMode && !refresh.isActive && currentFen.isNotEmpty()) {
                trace("SEARCH_WATCHDOG_WAIT", "action=$action fen=$currentFen engineReady=$engineReady")
                postRender()
            }
        }, 300L)
    }

    /**
     * 卡死监督：检查“当前阶段是否停留过久”，超时就自己处置。
     *
     * 上一版完全没有这一层，所以一旦某个环节失败（手势没生效、引擎被杀、取帧停了），
     * 界面就永远停在那个状态，只能靠用户手动重启——这正是"一直卡在那里没人管"的原因。
     */
    /** 落子阶段超时只能复位真实事务；没有事务时不做任何事。 */
    private fun tickWatchdog() {
        val now = System.currentTimeMillis()
        val p = refreshPhase()

        // 落子事务超时统一由独立管线监督（PipelineHealthPolicy）按原始证据判定，
        // 这里不再重复一套，避免两条恢复路径互相打架。

        if (p != AssistPhase.Phase.WAITING) {
            lastWaitingForceScanAt = Long.MIN_VALUE
        }

        val pending = pendingAutoMove
        if (pending != null && AutoChannelRestartPolicy.shouldReleasePendingMove(now, pending.createdAt)) {
            trace(
                "MOVE_PENDING_TIMEOUT",
                "ucci=${pending.target.ucci} age=${now - pending.createdAt} inFlight=${autoChannelRestartInFlight.get()}"
            )
            pendingAutoMove = null
            lastPreparedMoveKey = null
            autoChannelRestartGeneration++
            autoLastResult = "通道保险超时，已释放挂起落子并重新检查"
            requestAutoDrive()
        }

        // 半自动只保留用户点击更新棋谱产生的一次 refresh；普通阶段不再保活扫描。
        if (!isContinuousScanMode() && !refresh.isActive) return
        // 读盘阶段必须具备持续唤醒，不依赖某一次状态切换时恰好拿到图片。
        // 尤其是 THINKING→READY 或落子回执后，ImageReader 可能已经没有待回调帧；
        // 看门狗每轮主动踢一次，避免首次识别后永远停在“待定位”。
        if (AssistPhase.needsBoard(p) && !refresh.isActive) {
            val sinceSample = if (lastStreamSampleAt == Long.MIN_VALUE) Long.MAX_VALUE
                else now - lastStreamSampleAt
            if (sinceSample >= STREAM_SAMPLE_PERIOD_MS * 2) kickCapture()
        }

        if (p == AssistPhase.Phase.WAITING) {
            // 等待对方时只负责“别让取帧管线停住”。对方是否已经走子，由正常的稳定关键帧识别
            // 与跟踪器确认（新盘面确认后 onBoardConfirmed 会把轮次切回我方），
            // 这里不再做任何差分/一两步推断，避免又造出一条会自我锁死的旁路。
            val heartbeatBase = maxOf(lastMapAtForWatchdog, phaseSinceAt)
            val heartbeat = HeartbeatPolicy.State(
                lastSuccessfulScanAt = heartbeatBase,
                lastForcedScanAt = lastWaitingForceScanAt,
            )
            if (HeartbeatPolicy.shouldForceWaitingScan(p, now, heartbeat)) {
                lastWaitingForceScanAt = now
                kickCapture()
            }
        }

        val searchActivity = analysisEngine?.activitySnapshot(includeProcessCpu = searching)
        if (searching) {
            val cpuNow = searchActivity?.processCpuTimeMs ?: -1L
            if (cpuNow >= 0L && (lastSearchCpuTimeMs < 0L || cpuNow > lastSearchCpuTimeMs)) {
                lastSearchCpuTimeMs = cpuNow
                searchCpuProgressAt = now
            }
        } else {
            lastSearchCpuTimeMs = -1L
            searchCpuProgressAt = 0L
        }
        val searchAction = SearchWatchdogPolicy.decide(
            SearchWatchdogPolicy.Input(
                now = now,
                expectedAnalysisId = expectedAnalysisId,
                requestAt = analysisRequestAt,
                appSearching = searching,
                engineReady = engineReady,
                processAlive = searchActivity?.processAlive == true,
                engineBroken = searchActivity?.engineBroken == true,
                activeJobId = searchActivity?.activeJobId,
                pendingJobId = searchActivity?.pendingJobId,
                controllerAlive = searchActivity?.controllerAlive == true,
                controllerHeartbeatAt = searchActivity?.controllerHeartbeatAt ?: 0L,
                processCpuTimeMs = searchActivity?.processCpuTimeMs ?: -1L,
                cpuProgressAt = searchCpuProgressAt,
                lastOutputAt = searchActivity?.lastOutputAt ?: 0L,
                recoveryAlreadyScheduled = searchRecoveryScheduled,
            )
        )
        if (searchAction != SearchWatchdogPolicy.Action.NONE) {
            trace(
                "SEARCH_WATCHDOG_CHECK",
                "action=$searchAction id=$expectedAnalysisId active=${searchActivity?.activeJobId} " +
                    "pending=${searchActivity?.pendingJobId} heartbeat=${searchActivity?.controllerHeartbeatAt} " +
                    "cpu=${searchActivity?.processCpuTimeMs} cpuProgress=$searchCpuProgressAt " +
                    "output=${searchActivity?.lastOutputAt} process=${searchActivity?.processAlive}"
            )
            recoverBrokenSearch(now, searchAction)
        }

        val sinceMap = if (lastMapAtForWatchdog > 0) now - lastMapAtForWatchdog else -1L
        val action = AssistPhase.watchdog(
            phase = p,
            phaseMs = now - phaseSinceAt,
            sinceMapMs = sinceMap,
            engineReady = engineReady,
            engineWarmupMs = if (engineStarted) now - engineWarmupStartAt else 0L,
        )
        if (action != AssistPhase.WatchdogAction.NONE) {
            trace(
                "WATCHDOG_ACTION",
                "action=$action phase=$p phaseAge=${now - phaseSinceAt} sinceMap=$sinceMap " +
                    "landing=${landingState?.stage} pending=${pendingAutoMove != null} searching=$searching"
            )
        }
        when (action) {
            AssistPhase.WatchdogAction.NONE -> Unit

            AssistPhase.WatchdogAction.RELEASE_LANDING -> {
                trace("WATCHDOG_LANDING_RELEASE", "stage=${landingState?.stage} age=${now - phaseSinceAt}")
                if (landingState != null) {
                    retryOrStopLanding("落子流程超时")
                }
            }

            AssistPhase.WatchdogAction.FAIL_REFRESH -> {
                refresh.consumeAttempt("取画面超时")
                if (refresh.checkGiveUp(now, "取画面超时（未收到可用画面）")) {
                    finishRefreshRequest()
                    syncCaptureDemand()
                    setStatus("更新棋谱失败：${refresh.failReason}\n请确认已完成一键准备并点击一键启动")
                    postRender()
                }
            }

            AssistPhase.WatchdogAction.RESET_GRID -> {
                sessionGrid = null
                cropUnstableStreak = 0
                lastMapAtForWatchdog = now
                setStatus("长时间未识别到棋盘，已重置定位\n正在重新查找…")
                postRender()
            }

            AssistPhase.WatchdogAction.RESTART_ENGINE -> {
                if (!engineReady) {
                    Log.w(TAG, "engine not ready for too long, requesting restart")
                    engineWarmupStartAt = now
                    workerHandler.post { analysisEngine?.requestRestart("启动超时") }
                }
            }
        }
    }

    private fun onOverlayAction(action: OverlayAction) {
        when (action) {
            OverlayAction.CYCLE_MODE -> cycleMode()
            OverlayAction.TOGGLE_AUTO -> toggleAutoPlaySwitch()
            OverlayAction.TOGGLE_SIM -> toggleSim()
            OverlayAction.CYCLE_CANDIDATE_COUNT -> cycleCandidateCount()
            OverlayAction.CYCLE_CANDIDATE -> cycleCandidate()
            OverlayAction.UNDO -> undoOneStep()
            OverlayAction.TOGGLE_RUN -> toggleRun()
            OverlayAction.REFRESH_BOARD -> refreshBoardFromScreen()
            OverlayAction.STOP_RESET -> stopAndReset()
            OverlayAction.TOGGLE_MY_SIDE -> toggleMySide()
            OverlayAction.FLIP_TURN -> flipTurn()
            OverlayAction.CYCLE_STRENGTH -> cycleStrength()
            OverlayAction.TOGGLE_THINKING_MODE -> toggleThinkingMode()
            OverlayAction.CYCLE_HASH -> cycleHash()
            OverlayAction.COLLAPSE -> collapseOverlay()
            OverlayAction.CLOSE -> {
                // 关闭前先保存真实面板/小球几何；否则移除窗口后再销毁服务会丢失最后位置。
                runCatching { persistOverlayPosNow() }
                // 关闭：停止录屏、移除面板/小球（之后可在悬浮窗辅助页重新准备）
                // 先切断所有新识别入口，再释放录屏和模型；YoloBoardDetector.close()
                // 会与当前detect串行化，避免主线程关闭Interpreter时assist-worker仍在run。
                stopping = true
                overlayClosed = true
                recognitionEpoch++
                synchronized(frameQueueLock) {
                    pendingFrame = null
                    frameConsumeScheduled = false
                }
                landingToken++
                landingState = null
                pendingAutoMove = null
                lastPreparedMoveKey = null
                autoChannelRestartGeneration++
                autoState = AutoState.IDLE
                landingUiSuppressed = false
                landingWasPanel = false
                overlayPanel?.let { v -> runCatching { windowManager?.removeView(v) } }
                overlayPanel = null
                overlayParams = null
                overlayBall?.let { v -> runCatching { windowManager?.removeView(v) } }
                overlayBall = null
                overlayBallParams = null
                paused = true
                releaseCapture()
                // 识别线程与模型由 detector 内部生命周期闸门串行化；此调用会等待当前
                // Interpreter.run 完成后再释放，UI线程不可能与原生推理并发close。
                val detector = yoloDetector
                yoloDetector = null
                runCatching { detector?.close() }
                setStatus("已关闭（识别与录屏均已停止，可在悬浮窗辅助页重新准备）")
                stopSelf()
            }
        }
    }

    /**
     * 开始 / 继续 / 暂停共用一个按钮：
     * - 首次运行显示“开始”，建立本轮唯一日志起点；
     * - 暂停后显示“继续”，沿用当前日志并从屏幕重新建立视觉基线；
     * - 运行中显示“暂停”，只停运行管线，不清棋谱和日志。
     */
    private fun toggleRun() {
        val suspendedSnapshot = foregroundPauseSnapshot
        if (suspendedSnapshot?.wasRunning == true) {
            // 用户在临时前台保护期间点“暂停”：这是明确取消恢复意图，不是恢复。
            foregroundPauseSnapshot = null
            pauseRuntime(clearAnalysis = true)
            trace("RUN_PAUSE_DURING_FOREGROUND_SWITCH", "resumePackage=${suspendedSnapshot.resumePackage}")
            setStatus("已暂停（不会在返回原应用后自动继续）")
            postRender()
            return
        }
        if (!paused) {
            trace("RUN_PAUSE", "landing=${landingState?.stage} searching=$searching fen=$currentFen")
            pauseRuntime(clearAnalysis = true)
            foregroundPauseSnapshot = null
            setStatus("已暂停（不取帧、不识别）\n点『继续』恢复当前会话")
            postRender()
            return
        }

        val firstStart = !runSessionStarted
        if (firstStart) {
            runtimeLogger.startSession("run_start")
            runSessionStarted = true
        }
        trace(if (firstStart) "RUN_START" else "RUN_CONTINUE", "mode=$activeWorkMode auto=$autoPlayOn")
        resumeRuntime(rebaseBoard = true, userInitiated = true)
        if (paused) {
            // resumeRuntime 已给出缺失环境的具体原因。
            postRender()
            return
        }
        val foregroundGuard = if (AssistAccessibilityService.isConnected()) {
            ""
        } else {
            trace("FOREGROUND_GUARD_UNAVAILABLE", "accessibility=disconnected")
            "\n（屏幕操作通道未连接：前台切换保护与自动落子暂不可用）"
        }
        setStatus(
            if (firstStart) {
                "已开始\n正在从录屏流建立棋面基线…$foregroundGuard"
            } else {
                "已继续当前会话\n正在从录屏流重新对齐棋面…$foregroundGuard"
            }
        )
        postRender()
    }

    /**
     * 重置：打断当前事务与搜索、清空棋面/历史，并建立一个新的日志起点。
     * 录屏环境仍有效时直接回到识盘；环境缺失时保持暂停并引导到悬浮窗辅助页准备。
     */
    private fun stopAndReset() {
        runtimeLogger.startSession("user_reset")
        runSessionStarted = true
        trace("RUN_RESET", "mode=$activeWorkMode auto=$autoPlayOn")
        foregroundPauseSnapshot = null
        clearLandingTransaction(restoreWindow = true)
        stopCurrentSearch()
        searchSettled = false
        lastAnalysis = null
        searchStartAt = 0L
        stableUcci = null
        stableSince = 0L
        resetBoard()
        if (mediaProjection != null && imageReader != null && virtualDisplay != null) {
            resumeRuntime(rebaseBoard = false, userInitiated = true)
            setStatus("已重置并建立新的日志起点\n正在重新识盘")
        } else {
            paused = true
            setStatus("已重置并建立新的日志起点\n请到悬浮窗辅助页点击『一键准备』")
        }
        postRender()
    }

    /**
     * 变招浏览：候选数量由悬浮窗“候选N”档位控制；这里仅在已计算出的候选中移动显示索引，
     * 不改变候选数量，也不启动新的搜索。
     */
    /** 候选数量档位：1 → 2 → … → 6 → 1；所有工作模式统一使用这个持久化值。 */
    private fun cycleCandidateCount() {
        val next = if (config.candidateCount >= ThinkingOptions.MAX_CANDIDATE_COUNT) {
            ThinkingOptions.MIN_CANDIDATE_COUNT
        } else {
            config.candidateCount + 1
        }
        config.candidateCount = next
        selectedCandidate = 0
        analysisEngine?.setMultiPv(next)
        if (analysisCycleGate.activePosition() == currentFen && currentFen.isNotEmpty()) {
            // 当前3秒周期已经开始：新候选数只对下一周期生效，绝不重发go或清零计时。
            setStatus("候选数量：$next（当前思考周期不重置，下回合生效）")
        } else {
            resetSuggestionState(newAnalysisCycle = true)
            setStatus("候选数量：$next（自动/指导/半自动统一使用）")
            if (!paused) scheduleAnalysis()
        }
        postRender()
    }

    /**
     * 候选浏览：所有路线已在同一个搜索会话中计算完，这里只移动显示索引。
     * 到最后一条即停住，不回到第一条形成“兵→马→车→兵”的视觉循环。
     */
    private fun cycleCandidate() {
        val count = bookMoves?.size ?: lastAnalysis?.lines?.size ?: 0
        if (count <= 1) {
            setStatus("当前棋面只有一个可用候选，不重新计算")
            requestRenderOnly()
            return
        }
        if (selectedCandidate >= count - 1) {
            setStatus("已看完全部 $count 条候选；自动落子仍采用排名第1的最优结果")
            requestRenderOnly()
            return
        }
        selectedCandidate++
        val shown = selectedCandidate + 1
        setStatus("已切换到候选 $shown/$count（复用本次计算结果）")
        requestRenderOnly()
    }

    /** 一个按钮轮换模式：自动 → 半自动 → 手动 → 自动（默认自动） */
    private fun cycleMode() {
        setMode(WorkModes.nextInCycle(currentMode()))
    }

    /** 悬浮窗内切换自动走子；真正写配置仍集中在用户操作入口。 */
    private fun toggleAutoPlaySwitch() {
        setAutoPlayByUser(!(autoPlayOn || config.autoPlay))
    }

    /**
     * 自动走子通道自愈：无障碍被系统回收时不改变用户开关；有 root 就重新写入启用列表，
     * 没有 root 才引导用户去设置页。重复调用会限流，避免每帧启动线程。
     */
    private fun ensureAccessibilityForAutoPlay(openSettings: Boolean, silent: Boolean = false) {
        if (!autoPlayOn && !config.autoPlay) return
        if (AssistAccessibilityService.isConnected()) {
            accessibilitySettingsPrompted = false
            if (!silent) {
                autoLastResult = "通道已连接，等待我方定着"
                postRender()
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAccessibilityRecoveryAt < ACCESSIBILITY_RECOVERY_INTERVAL_MS) return
        if (!accessibilityRecoveryInFlight.compareAndSet(false, true)) return
        lastAccessibilityRecoveryAt = now
        if (openSettings) accessibilitySettingsPrompted = true
        Thread {
            try {
                val enabledBefore = AssistAccessibilityService.isEnabledInSettings(this)
                var rootResult: RootHelper.Result? = null
                if (RootHelper.isRooted()) {
                    // 即使设置列表仍包含本服务也再次写回，覆盖“服务进程被ROM杀掉”的情况；
                    // RootHelper 会保留其它无障碍服务，不会清空用户已有配置。
                    rootResult = RootHelper.enableAccessibility(this)
                }
                mainHandler.post {
                    if (silent) {
                        if (AssistAccessibilityService.isConnected()) {
                            accessibilitySettingsPrompted = false
                        }
                        return@post
                    }
                    if (AssistAccessibilityService.isConnected()) {
                        autoLastResult = "通道已连接，等待我方定着"
                        setStatus("自动走子：已开启")
                    } else {
                        val enabled = AssistAccessibilityService.isEnabledInSettings(this)
                        autoLastResult = if (enabled || enabledBefore) {
                            "通道已启用但尚未连接，继续自动恢复"
                        } else {
                            "请在系统无障碍设置中开启本应用服务"
                        }
                        setStatus(
                            if (enabled || enabledBefore || rootResult?.ok == true)
                                "自动走子已开启，屏幕操作通道正在恢复…"
                            else "自动走子已开启，等待屏幕操作通道"
                        )
                        if (openSettings && !enabled && rootResult?.ok != true) {
                            runCatching {
                                startActivity(AssistAccessibilityService.openSettingsIntent())
                            }
                        }
                    }
                    postRender()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "accessibility recovery failed", t)
                mainHandler.post {
                    if (silent) return@post
                    autoLastResult = "通道恢复异常，稍后自动重试"
                    setStatus("自动走子仍为开启状态，正在重试屏幕操作通道…")
                    postRender()
                }
            } finally {
                accessibilityRecoveryInFlight.set(false)
            }
        }.apply { isDaemon = true }.start()
    }
    /**
     * 自动落子前的静默通道保险：有 root 时短暂重写启用列表，等待本服务重新连接；
     * 无 root 时不碰系统设置，只把当前连接状态作为结果。
     * 回调始终回到主线程，且不更新状态栏/通知。
     */
    private fun restartAutoChannelSilently(
        requireAutoSwitch: Boolean,
        onFinished: (Boolean) -> Unit,
    ) {
        if (stopping || overlayClosed || (requireAutoSwitch && !autoPlayOn)) {
            trace("CHANNEL_SKIP", "requireAuto=$requireAutoSwitch auto=$autoPlayOn stopping=$stopping closed=$overlayClosed")
            onFinished(false)
            return
        }
        if (!autoChannelRestartInFlight.compareAndSet(false, true)) {
            synchronized(autoChannelRestartWaiters) {
                autoChannelRestartWaiters.addLast(onFinished)
            }
            trace("CHANNEL_BUSY", "queuedWaiter=true requireAuto=$requireAutoSwitch pending=${pendingAutoMove != null}")
            return
        }
        val previousInstance = AssistAccessibilityService.instance
        val generation = autoChannelRestartGeneration
        autoChannelRestartStartedAt = System.currentTimeMillis()
        trace("CHANNEL_START", "generation=$generation requireAuto=$requireAutoSwitch")
        Thread {
            var connected = false
            try {
                if (!RootHelper.isRooted()) {
                    // 没有 root 时无法重写系统启用列表；只验证现有通道，绝不伪造重启成功。
                    connected = AssistAccessibilityService.isConnected()
                } else {
                    val restarted = RootHelper.restartAccessibility(this).ok
                    if (restarted) {
                        val deadline = System.currentTimeMillis() + 1_500L
                        while (System.currentTimeMillis() < deadline) {
                            val current = AssistAccessibilityService.instance
                            if (current != null && (previousInstance == null || current !== previousInstance)) {
                                connected = true
                                break
                            }
                            try { Thread.sleep(100L) } catch (_: InterruptedException) { break }
                        }
                    }
                    // 某些 ROM 重启服务时复用同一实例；只要当前实例仍可用，就允许继续。
                    if (!connected) connected = AssistAccessibilityService.isConnected()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "silent accessibility restart failed", t)
            }
            mainHandler.post {
                autoChannelRestartInFlight.set(false)
                val valid = generation == autoChannelRestartGeneration &&
                    !stopping && !overlayClosed && (!requireAutoSwitch || autoPlayOn)
                val waiters = synchronized(autoChannelRestartWaiters) {
                    val collected = ArrayList<(Boolean) -> Unit>()
                    while (autoChannelRestartWaiters.isNotEmpty()) {
                        collected.add(autoChannelRestartWaiters.removeFirst())
                    }
                    collected
                }
                trace(
                    "CHANNEL_RESULT",
                    "connected=$connected valid=$valid generation=$generation currentGeneration=$autoChannelRestartGeneration " +
                        "waiters=${waiters.size} elapsed=${System.currentTimeMillis() - autoChannelRestartStartedAt}"
                )
                if (!valid) {
                    onFinished(false)
                    waiters.forEach { it(false) }
                    return@post
                }
                onFinished(connected)
                waiters.forEach { it(connected) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 待落子超过阈值只做一次静默通道重启；阶段变化后允许下一次保险。 */
    private fun maybeRestartReadyChannel(now: Long): Boolean {
        if (!autoPlayOn || pendingAutoMove != null || landingState != null ||
            (currentMode() != AssistConfig.MODE_AUTO && currentMode() != AssistConfig.MODE_SEMI)
        ) return false
        val currentPhase = refreshPhase()
        if (!AutoChannelRestartPolicy.shouldRestartReadyChannel(
                currentPhase, now, phaseSinceAt, readyChannelRestartedForPhaseAt
            )) return false
        val phaseAtRestart = phaseSinceAt
        readyChannelRestartedForPhaseAt = phaseAtRestart
        trace("CHANNEL_READY_TIMEOUT", "phaseSince=$phaseAtRestart phase=$currentPhase fen=$currentFen")
        restartAutoChannelSilently(requireAutoSwitch = true) { connected ->
            if (!connected) {
                ensureAccessibilityForAutoPlay(openSettings = false, silent = true)
            }
            requestAutoDrive()
        }
        return true
    }


    private fun toggleSim() {
        val on = !config.simEnabled
        config.simEnabled = on
        setStatus(if (on) "仿真：已开启（随机偏移/延迟/预案试选）" else "仿真：已关闭")
        postRender()
    }

    /** 切换手动指定的己方颜色；不改当前盘面走子方，只重算“我方/对方”的解释和建议。 */
    private fun toggleMySide() {
        config.mySideRed = !config.mySideRed
        stopCurrentSearch()
        resetSuggestionState(newAnalysisCycle = true)
        autoLastUcci = null
        setStatus("分析方已指定为${if (config.mySideRed) "红方" else "黑方"}\n不再根据屏幕位置自动判断己方颜色")
        if (!paused) scheduleAnalysis()
        postRender()
    }

    /** 手动切换当前棋面轮到哪一方；与“己方颜色”是两个独立设置。 */
    private fun flipTurn() {
        clearLandingTransaction(restoreWindow = true)
        currentRedGo = !currentRedGo
        tracker.redGo = currentRedGo
        resetSuggestionState(newAnalysisCycle = true)
        val canonical = currentCanonical
        if (canonical != null) {
            currentFen = AssistBoard.toFen(canonical, currentRedGo)
        }
        postRender()
        scheduleAnalysis()
        setStatus("已切换走棋方\n现${if (currentRedGo == mySideIsRed()) "轮到我方走" else "轮到对方走"}")
    }

    /**
     * 半自动模式开关：开启后帧识别照常运行（用于状态显示与网格跟踪），
     * 但**不自动刷新棋面**；用户点「更新棋谱」时把最近一次识别结果应用到棋面。
     * 与手动模式、自动走子互斥。
     */
    /** 当前模式只读唯一状态值；指导与自动不能再被两个布尔压成同一个状态。 */
    private fun currentMode(): Int = activeWorkMode

    /**
     * 应用目标模式（唯一入口）：互斥 + 持久化 + 取帧策略联动 + 状态提示。
     *
     * 注意：**不再因"屏幕操作通道未连接"而改写模式**——通道只影响能不能自动落子
     * （见 [WorkModes.autoCanRun]），模式本身照用户选择保留。
     */
    private fun setMode(mode: Int) {
        setModeInternal(mode)
    }

    /** 只做状态切换与提示（不校验前置条件），供 setMode 与 onCreate 恢复使用 */
    private fun setModeInternal(mode: Int) {
        // 互斥的落点：手动/半自动由模式值派生；自动走子是独立开关（默认开）
        val normalizedMode = mode.coerceIn(AssistConfig.MODE_GUIDE, AssistConfig.MODE_AUTO)
        activeWorkMode = normalizedMode
        abortCaptureWindow()
        recognitionEpoch++
        stopCurrentSearch()
        val flags = WorkModes.flags(normalizedMode)
        manualMode = flags.manual
        semiAuto = flags.semi
        autoPlayOn = config.autoPlay
        if (!manualMode) manualSelected = null
        if (!semiAuto) pendingBoard = null
        clearLandingTransaction(restoreWindow = true)
        autoLastUcci = null
        autoFenAtExec = null
        if (!autoPlayOn) autoLastResult = null
        else if (autoLastResult == null) autoLastResult = "已开启，等待我方定着"

        // 持久化：workMode 是唯一真源（自动走子是另一个开关，由它自己维护）
        config.workMode = normalizedMode
        config.semiAuto = semiAuto

        when (normalizedMode) {
            AssistConfig.MODE_MANUAL -> {
                if (currentCanonical == null) {
                    val start = AssistBoard.canonicalStart()
                    currentCanonical = start
                    currentPieces = flatten(start)
                    currentOrientation = Orientation.STANDARD
                    currentRedGo = tracker.redGo
                    currentFen = AssistBoard.toFen(start, currentRedGo)
                }
                val canonical = currentCanonical
                if (canonical != null && history.lastOrNull()?.fen != currentFen) {
                    pushManualHistory(canonical, currentRedGo)
                }
                setStatus("手动模式（不截图）\n点子选中→点目标格=走子\n再点选中子=删除\n『更新棋谱』不可用")
            }
            AssistConfig.MODE_SEMI -> {
                setStatus("半自动模式（持续识图，不自动应用棋面）\n改完局面后点『更新棋谱』确认")
            }
            AssistConfig.MODE_AUTO -> {
                setStatus("自动走子已开启\n建议定着后自动落子")
            }
            else -> {
                setStatus("指导模式\n自动识别并给出建议\n走子由你手动完成")
                if (!paused) scheduleAnalysis()
            }
        }
        postRender()
    }

    /**
     * 更新棋谱：半自动模式下把最近一轮持续识别到的棋面正式应用；其他模式也会
     * 重新开启稳定录屏窗口，成功后立即应用到棋面。
     */
    private fun refreshBoardFromScreen() {
        if (paused) {
            setStatus("已暂停：请先点『开始』再更新棋面")
            requestRenderOnly()
            return
        }
        if (manualMode) {
            setStatus("手动模式下不支持更新棋谱\n请先退出手动模式")
            postRender()
            return
        }
        if (yoloDetector == null) {
            setStatus("识别引擎不可用\n请重启应用后重试")
            postRender()
            return
        }
        // 发起一次显式刷新请求：先作废队列里的旧帧和残留稳定窗口，状态机自己负责重试与超时。
        abortCaptureWindow()
        recognitionEpoch++
        userRefreshActive = true
        refresh.begin(System.currentTimeMillis())
        trace("REFRESH_START", "phase=${refreshPhase()} epoch=$recognitionEpoch")
        syncCaptureDemand()
        // 主动踢一次取帧管线：半自动待机时缓冲池可能正被占满而不再回调，
        // 不踢一下就会出现"点了按钮毫无反应"
        kickCapture(forceProcess = true)
        setStatus("正在更新棋谱…\n已启动稳定录屏循环")
        postRender()
        requestWatchdog(REFRESH_WATCHDOG_TICK_MS)
    }

    /**
     * 把一帧识别结果应用到棋面（并纠正走子方）。
     * 半自动「更新棋谱」与单帧强制同步都走这里。
     */
    private fun applyRecognizedBoard(res: RecognitionResult, fromManualRefresh: Boolean) {
        lastRejectReason = null
        // 半自动「更新棋谱」只把盘面更新到当前识别结果，**不凭盘面差异翻转轮次**——
        // 否则每点一次「更新棋谱」都可能让轮次跳一下。需要改轮次时用「切换走棋方」。
        val fenBefore = currentFen
        // 手动刷新同样优先采用当前画面中唯一被将的一方作为当前走子方；
        // 这样“我方被将”不会被旧的轮次状态覆盖。
        val nextRedGo = forcedTurnFromCheck(res.canonical) ?: currentRedGo
        tracker.forceSet(res, nextRedGo)
        currentRedGo = nextRedGo
        currentCanonical = res.canonical
        currentPieces = flatten(res.canonical)
        currentOrientation = res.orientation
        currentFen = AssistBoard.toFen(res.canonical, nextRedGo)
        autoLastUcci = null
        history.addLast(HistoryEntry(res, nextRedGo, currentFen))
        if (history.size > 60) history.removeFirst()
        val changed = currentFen != fenBefore
        if (changed) {
            // 局面变了：打断旧任务并按新局面重算
            restartAnalysisForNewBoard("")
        } else {
            // 局面没变（例如只是重新确认了一次）：不必打断，保留当前计算结果
            boardUpdatedAt = System.currentTimeMillis()
            postRender()
        }
        setStatus(
            if (fromManualRefresh) {
                "棋谱已更新（${res.recognizedPieces}子）\n现${if (nextRedGo == mySideIsRed()) "轮到我方走" else "轮到对方走"}"
            } else {
                "已同步当前画面（${res.recognizedPieces}子）"
            }
        )
    }

    /**
     * 重置棋盘：清空局面、历史、候选、网格与待应用结果，回到"等待识别"状态。
     * 不停止录屏与引擎（只重置棋面），便于马上重新开始。
     */
    private fun resetBoard() {
        abortCaptureWindow()
        recognitionEpoch++
        resumeRebasePending = false
        tracker.reset(config.mySideRed)
        currentRedGo = config.mySideRed
        history.clear()
        currentCanonical = null
        currentPieces = null
        currentFen = ""
        currentOrientation = Orientation.STANDARD
        sessionGrid = null
        // 盘面已清空：连同"最近看到的盘面/建议所依据的盘面"一起清掉，
        // 否则落子前确认会拿已经作废的快照去做比对
        lastSeen = null
        adviceBoard = null
        analyzedFen = ""
        pendingBoard = null
        pendingBoardAt = 0L
        boardUpdatedAt = 0L
        resetSuggestionState(newAnalysisCycle = true)
        clearLandingTransaction(restoreWindow = true)
        landingRebasePending = false
        landingRebasePreBoard = null
        landingRebaseExpectedBoard = null
        autoLastUcci = null
        autoFenAtExec = null
        yoloMissStreak = 0
        cropUnstableStreak = 0
        lastMappedAt = 0L
        lastMappedPieces = 0
        lastAnchorSource = null
        lastFailReason = null
        postRender()
        setStatus("棋盘已重置\n等待重新识别")
    }

    /**
     * 悔棋：回退到上一个已确认局面并重新分析（上一手之前的建议）。
     * 纯本地状态回退，不操作游戏 App；回退后暂停实时同步，点『继续』恢复。
     */
    private fun undoOneStep() {
        if (history.size <= 1) {
            setStatus("没有可悔的局面（会随对弈自动积累）")
            return
        }
        history.removeLast()
        val prev = history.last()
        tracker.restore(prev.result, prev.redGo)
        currentCanonical = prev.result.canonical
        currentPieces = flatten(prev.result.canonical)
        currentOrientation = prev.result.orientation
        currentFen = prev.fen
        currentRedGo = prev.redGo
        tracker.redGo = currentRedGo
        resetSuggestionState(newAnalysisCycle = true)
        // 暂停实时同步：否则屏幕上的当前局面会在下一帧覆盖悔棋结果
        // （手动模式下帧识别本就被 manualMode 拦截，退出后无需再点"继续"）
        if (!manualMode) paused = true
        postRender()
        scheduleAnalysis()
        setStatus("已悔棋一步\n点『继续』恢复")
    }

    /**
     * 紧急手动模式开关：识别彻底失效（如某些残局界面）或产出幽灵局面时，
     * 暂停帧识别，由用户在小棋盘上手工维护局面让引擎继续指导。
     * 种子局面取最近一次确认局面；识别从未成功时从起始局面开始录入。
     */
    /** 手动模式：小棋盘点格（canonical x,y）——选子 / 走子 / 删子 */
    private fun onManualCellTap(x: Int, y: Int) {
        if (!manualMode) return
        val board = currentCanonical ?: return
        val sel = manualSelected
        if (sel == null) {
            // 未选中：点有子的格则选中，空格无操作
            if (board[y][x] != Piece.EMPTY) {
                manualSelected = x to y
                postRender()
            }
            return
        }
        if (sel == x to y) {
            // 再点一次已选中的子：删除（帅/将必须保留，否则局面非法、引擎无法分析）
            val p = board[y][x]
            manualSelected = null
            if (p == Piece.WSHUAI || p == Piece.BJIANG) {
                setStatus("手动模式\n帅/将不可删除")
                postRender()
                return
            }
            board[y][x] = Piece.EMPTY
            applyManualBoard(board, flipTurn = false)
            return
        }
        // 走子：起点必须有子；目标格可为空格或对方子（吃子），按实战着法录入并翻转走子方
        val moving = board[sel.second][sel.first]
        manualSelected = null
        if (moving == Piece.EMPTY) {
            postRender()
            return
        }
        board[sel.second][sel.first] = Piece.EMPTY
        board[y][x] = moving
        applyManualBoard(board, flipTurn = true)
    }

    /** 手动局面快照入历史栈（复用悔棋：可逐步撤销手工编辑） */
    private fun pushManualHistory(board: Array<IntArray>, redGo: Boolean) {
        val result = RecognitionResult(
            AssistBoard.clone(board), AssistBoard.clone(board), currentOrientation,
            emptyList(), 0, countPieces(board), 1.0)
        history.addLast(HistoryEntry(result, redGo, AssistBoard.toFen(board, redGo)))
        if (history.size > 60) history.removeFirst()
    }

    private fun countPieces(board: Array<IntArray>): Int =
        board.sumOf { row -> row.count { it != Piece.EMPTY } }

    /** 手动编辑生效：入栈新局面（history.last 恒等于当前显示局面，悔棋可逐步回退），重建缓存并重新查库/分析 */
    private fun applyManualBoard(board: Array<IntArray>, flipTurn: Boolean) {
        lastRejectReason = null
        // 盘面由用户手工改写：清掉"最近看到的盘面"，否则落子前确认会拿上一帧旧画面来比
        lastSeen = null
        if (flipTurn) currentRedGo = !currentRedGo
        tracker.redGo = currentRedGo
        currentCanonical = board
        currentPieces = flatten(board)
        currentFen = AssistBoard.toFen(board, currentRedGo)
        pushManualHistory(board, currentRedGo)
        // 手动模式不靠扫盘：用户每改一次棋面，就代表真实棋面已更新——
        // 立即打断正在跑的那次思考，按新局面重新计算
        restartAnalysisForNewBoard("")
        setStatus("棋面已手工更新\n已按新局面重新计算")
    }

    /** 当前思考模式下循环档位：深度/总时/每候选时间分别循环各自档位。 */
    private fun cycleStrength() {
        when (config.thinkingMode) {
            ThinkingMode.DEPTH -> {
                strengthIndex = (strengthIndex + 1) % strengthLevels.size
                val (name, depth) = strengthLevels[strengthIndex]
                config.searchDepth = depth
                analysisEngine?.setSearchDepth(depth)
                setStatus("思考模式：深度 $name")
            }
            ThinkingMode.TOTAL_TIME -> {
                timeIndex = (timeIndex + 1) % ThinkingOptions.TIME_LEVELS_MS.size
                val ms = ThinkingOptions.TIME_LEVELS_MS[timeIndex].toInt()
                config.thinkTimeMs = ms
                analysisEngine?.setThinkTime(ms)
                setStatus("思考模式：总时 ${ThinkingOptions.formatTime(ms.toLong())}（全部候选共享）")
            }
            ThinkingMode.PER_CANDIDATE_TIME -> {
                timeIndex = (timeIndex + 1) % ThinkingOptions.TIME_LEVELS_MS.size
                val ms = ThinkingOptions.TIME_LEVELS_MS[timeIndex].toInt()
                config.perCandidateTimeMs = ms
                setStatus(
                    "思考模式：每候选 ${ThinkingOptions.formatTime(ms.toLong())}\n" +
                        "${config.candidateCount}条总计 ${ThinkingOptions.formatTime(
                            ThinkingOptions.effectivePerCandidateTotal(ms, config.candidateCount).toLong()
                        )}"
                )
            }
        }
        if (analysisCycleGate.activePosition() == currentFen && currentFen.isNotEmpty()) {
            setStatus("预算参数已保存，当前思考周期不重置，下回合生效")
        } else {
            resetSuggestionState(newAnalysisCycle = true)
            if (!paused) scheduleAnalysis()
        }
        postRender()
    }

    /** 长按思考档位按钮，在深度→总时→每候选时间三种模式之间切换。 */
    private fun toggleThinkingMode() {
        val next = config.thinkingMode.next()
        config.thinkingMode = next
        timeIndex = when (next) {
            ThinkingMode.TOTAL_TIME -> ThinkingOptions.TIME_LEVELS_MS.indexOf(config.thinkTimeMs.toLong())
            ThinkingMode.PER_CANDIDATE_TIME -> ThinkingOptions.TIME_LEVELS_MS.indexOf(config.perCandidateTimeMs.toLong())
            ThinkingMode.DEPTH -> 0
        }.coerceAtLeast(0)
        when (next) {
            ThinkingMode.DEPTH -> setStatus("思考模式：深度\n当前深度 $searchDepthTarget\n短按切换深度，长按切换下一模式")
            ThinkingMode.TOTAL_TIME -> setStatus("思考模式：总时\n当前 ${ThinkingOptions.formatTime(config.thinkTimeMs.toLong())}\n所有候选共享该总预算")
            ThinkingMode.PER_CANDIDATE_TIME -> setStatus(
                "思考模式：每候选时间\n当前每候选 ${ThinkingOptions.formatTime(config.perCandidateTimeMs.toLong())}\n" +
                    "${config.candidateCount}条共 ${ThinkingOptions.formatTime(
                        ThinkingOptions.effectivePerCandidateTotal(config.perCandidateTimeMs, config.candidateCount).toLong()
                    )}"
            )
        }
        if (analysisCycleGate.activePosition() == currentFen && currentFen.isNotEmpty()) {
            setStatus("思考模式已保存，当前周期不重置，下回合生效")
        } else {
            resetSuggestionState(newAnalysisCycle = true)
            if (!paused) scheduleAnalysis()
        }
        postRender()
    }

    private fun strengthButtonText(): String = when (config.thinkingMode) {
        ThinkingMode.DEPTH -> "深度$searchDepthTarget"
        ThinkingMode.TOTAL_TIME -> "总时${ThinkingOptions.formatTime(config.thinkTimeMs.toLong())}"
        ThinkingMode.PER_CANDIDATE_TIME -> "每候选${ThinkingOptions.formatTime(config.perCandidateTimeMs.toLong())}"
    }

    /** 引擎参数热更新（改完对下一次搜索生效，不打断当前计算） */
    private fun applyEngineTuning() {
        analysisEngine?.setHash(config.hashMb)
        analysisEngine?.setThinkTime(config.thinkTimeMs)
        analysisEngine?.setThreads(
            EngineTuningPolicy.resolveThreads(
                config.engineThreads, Runtime.getRuntime().availableProcessors()
            )
        )
    }

    private fun cycleHash() {
        hashIndex = (hashIndex + 1) % hashLevels.size
        val mb = hashLevels[hashIndex]
        config.hashMb = mb
        analysisEngine?.setHash(mb)
        postRender()
        setStatus("引擎 Hash：${mb}MB")
    }

    private fun hashButtonText(): String =
        "Hash" + hashLevels[hashIndex]

    /** 我方颜色由用户明确指定，不再根据棋盘上下位置自动推断。 */
    private fun mySideIsRed(): Boolean = config.mySideRed

    /** 我方阵营代码，与 AssistBoard.movedSide 的约定一致：红=1、黑=0 */
    private fun mySideCode(): Int = if (mySideIsRed()) 1 else 0

    private fun setStatus(text: String) {
        lastStatusText = text
        trace("STATUS", text)
        // 状态文本只触发显示刷新，绝不能因为“写了一行文字”反向启动落子。
        pendingStatusOverride = text
        requestRenderOnly()
        mainHandler.post {
            runCatching {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(text))
            }
        }
    }

    @Volatile private var lastStatusText = "未启动"

    // ==================== 状态栏文本（识别 / 引擎 / 自动 三段） ====================

    /** 状态正文：识别 + 引擎细节（阶段已移到标题，不再重复） */
    private fun statusText(): String {
        val sb = StringBuilder()
        if (!manualMode && !paused) {
            sb.append('\n').append(recognitionStatusLine())
            sb.append('\n').append(engineStatusLine())
        }
        lastRejectReason?.let { sb.append('\n').append("局面不可用：").append(it) }
        if (userRefreshActive) {
            refresh.statusLine()?.let { sb.append('\n').append(it) }
        }
        semiAutoStatusLine()?.let { sb.append('\n').append(it) }
        autoStatusLine()?.let { sb.append('\n').append(it) }
        // 卡住时直接说明原因，不用让用户猜
        val reject = tracker.lastReject
        if (!manualMode && !paused && reject != null && tracker.confirmed != null) {
            sb.append('\n').append("未采纳：").append(reject)
        }
        return sb.toString()
    }

    /**
     * 计算当前阶段并（在阶段变化时）重置计时器。**唯一的阶段来源**。
     *
     * 标题、圆环颜色、悬浮球、状态行全部读同一个 [phase]，因此不可能再出现
     * "标题写着落子中、正文写着未定位"这类自相矛盾的画面。
     */
    private fun refreshPhase(): AssistPhase.Phase {
        val p = AssistPhase.resolve(
            AssistPhase.Signals(
                paused = paused,
                manualMode = manualMode,
                engineError = engineErrorText,
                refreshActive = refresh.isActive && userRefreshActive,
                autoState = when (landingState?.stage) {
                    LandingFlow.Stage.DISPATCHING -> AssistPhase.AUTO_CLICKING
                    LandingFlow.Stage.VERIFYING -> AssistPhase.AUTO_WAITING
                    null -> AssistPhase.AUTO_IDLE
                },
                landingTransaction = landingState != null,
                engineReady = engineReady,
                searching = searching,
                settled = searchSettled,
                boardPresent = currentFen.isNotEmpty(),
                myTurn = currentRedGo == mySideIsRed(),
            )
        )
        if (p != phase) {
            val previous = phase
            phase = p
            phaseSinceAt = System.currentTimeMillis()
            trace(
                "PHASE",
                "$previous->$p fen=$currentFen searching=$searching settled=$searchSettled " +
                    "landing=${landingState?.stage} refresh=${refresh.isActive}"
            )
        }
        return p
    }

    /** 供悬浮窗/悬浮球读取的阶段（各绘制入口统一调这里） */
    private fun stage(): AssistHud.Stage = AssistPhase.toStage(refreshPhase())

    /**
     * 当前胜率——**始终是我方视角**，不会因为轮到对手走而翻到对面去。
     * 优先用引擎自带的 wdl（胜/和/负期望）；没有时退回用分数换算。
     */
    private fun winRateInfo(): AssistHud.WinRate? {
        val line = lastAnalysis?.lines?.firstOrNull() ?: return null
        val myRed = mySideIsRed()
        if (line.redWinPermille != null && line.drawPermille != null && line.redLossPermille != null) {
            return AssistHud.winRateFromWdl(
                line.redWinPermille, line.drawPermille, line.redLossPermille, myRed
            )
        }
        return AssistHud.winRate(line.redScoreCp, line.mateIn, myRed)
    }

    /** 半自动模式状态：待应用的识别结果与时长 */
    private fun semiAutoStatusLine(): String? {
        if (!semiAuto) return null
        val p = pendingBoard ?: return "半自动：点『更新棋谱』取一次画面"
        val age = ((System.currentTimeMillis() - pendingBoardAt) / 1000L).coerceAtLeast(0)
        return "半自动：棋面 ${p.recognizedPieces}子（${age}s前）\n点『更新棋谱』再取一次"
    }

    /** 识别状态：是否定位到棋盘、检出子数、单帧耗时、所用锚点；失败时给出具体原因 */
    private fun recognitionStatusLine(): String {
        val yolo = yoloDetector ?: return "识别：模型未加载（请重启应用）"
        val now = System.currentTimeMillis()
        // 口径跟随阶段：只有"识盘中/更新棋谱中"才以识别为主任务。
        // 落子/计算阶段若还写"识别：未定位"，就会出现"标题写着落子中、正文写着未定位"的矛盾
        val prefix = when (phase) {
            AssistPhase.Phase.READY -> "落子确认："
            AssistPhase.Phase.VERIFYING -> "落子核对："
            AssistPhase.Phase.MOVING -> "落点校验："
            AssistPhase.Phase.THINKING -> "局面："
            AssistPhase.Phase.WAITING -> "盯盘："
            else -> "识别："
        }
        // 取帧策略提示：让用户明确"现在到底有没有在截图"
        // 「未取帧」只在"本阶段本该读盘却读不到"时才提示。
        // 计算中/落子中本来就不该取帧（避免白闪、避免与手势打架），
        // 这时再写"未取帧"会让人以为出了问题。
        val captureNote = when {
            captureDemand == CaptureDemand.ONE_SHOT -> " · 单次取帧"
            captureDemand == CaptureDemand.OFF && AssistPhase.needsBoard(phase) -> " · 未取帧"
            else -> ""
        }
        // 取帧策略只说明“我们打算取不取”，这里再补一句“画面实际上还在不在进来”，
        // 用户才能分清“一直在识别只是没认出来”和“画面根本断了”。
        val pipelineNote = captureNote + streamLivenessNote()
        if (lastMappedAt > 0 && now - lastMappedAt < 1500) {
            val anchor = when (lastAnchorSource) {
                DetectionBoardMapper.AnchorSource.BOARD_BOX -> "棋盘框"
                DetectionBoardMapper.AnchorSource.PREVIOUS_GRID -> "沿用网格"
                DetectionBoardMapper.AnchorSource.PIECE_BBOX -> "棋子包围盒"
                null -> "—"
            }
            val cover = panelOverlapRatio()
            val coverNote = if (cover > 0.08) " · 面板挡住${(cover * 100).toInt()}%" else ""
            return "${prefix}已定位 ${lastMappedPieces}子 · ${yolo.lastInferMs}ms · $anchor$pipelineNote$coverNote"
        }
        val streak = maxOf(cropUnstableStreak, tracker.unstableStreak)
        if (streak >= 5) {
            return "${prefix}校验中 ×$streak（画面未稳定）$pipelineNote"
        }
        val fail = lastFailReason
        val miss = if (captureDemand == CaptureDemand.OFF) "" else " ×$yoloMissStreak"
        return if (fail != null) "${prefix}识图重试中$miss · $fail$pipelineNote"
        else "${prefix}识图中…$pipelineNote"
    }

    /**
     * 录屏流真实活性：只按“最后一次拿到画面”的原始时间戳说话，不看任何展示状态。
     * 读盘阶段没有新画面时给出秒数，恢复动作发生后由 [rebuildCapturePipeline] 归零重算。
     */
    private fun streamLivenessNote(): String {
        if (!AssistPhase.needsBoard(phase)) return ""
        if (lastStreamFrameAt <= 0L) return ""
        val age = System.currentTimeMillis() - lastStreamFrameAt
        return when {
            age <= 1_200L -> ""
            age <= 3_000L -> " · 画面 ${age / 1000}秒前"
            else -> " · 画面已中断 ${age / 1000}秒"
        }
    }

    /** 引擎状态：预热进度 / 计算中的深度与耗时 / 已算完 / 异常 */
    private fun engineStatusLine(): String {
        engineErrorText?.let { return "引擎：异常（$it）" }
        if (analysisEngine == null) return "引擎：未启动"
        if (!engineReady) {
            if (!engineStarted) return "引擎：待启动（点『开始』后加载权重）"
            val waited = (System.currentTimeMillis() - engineWarmupStartAt) / 1000L
            if (waited <= 0) return "引擎：启动中…"
            return "引擎：预热中 ${waited}s（加载 NNUE）"
        }
        if (searching) {
            val d = lastAnalysis?.bestLine?.depth ?: 0
            val statusNow = System.currentTimeMillis()
            val elapsedMs = (statusNow - searchStartAt).coerceAtLeast(0L)
            val snapshot = analysisEngine?.activitySnapshot()
            val controllerAt = snapshot?.controllerHeartbeatAt ?: 0L
            val heartbeatAge = if (controllerAt > 0L) {
                statusNow - controllerAt
            } else {
                Long.MAX_VALUE
            }
            val attached = snapshot?.activeJobId == expectedAnalysisId ||
                snapshot?.pendingJobId == expectedAnalysisId
            val controlNote = when {
                snapshot?.engineBroken == true || snapshot?.processAlive == false -> " · 控制链正在恢复"
                attached && snapshot?.controllerAlive == true && heartbeatAge <= SearchWatchdogPolicy.CONTROLLER_HEARTBEAT_TIMEOUT_MS ->
                    if ((snapshot?.lastOutputAt ?: 0L) <= 0L ||
                        statusNow - (snapshot?.lastOutputAt ?: 0L) > 1500L
                    ) " · 暂无新状态行，控制链正常" else " · 控制链正常"
                else -> " · 等待控制链响应"
            }
            val el = elapsedMs / 1000.0
            return if (config.thinkingMode == ThinkingMode.DEPTH) {
                "引擎：计算中 深度$d/$searchDepthTarget · ${"%.1f".format(el)}s$controlNote"
            } else {
                "引擎：计算中 自适应深度 · ${"%.1f".format(el)}/${ThinkingOptions.formatTime(config.thinkTimeMs.toLong())}$controlNote"
            }
        }
        return if (searchSettled) {
            val d = lastAnalysis?.bestLine?.depth ?: 0
            "引擎：已算完（深度$d）"
        } else {
            "引擎：就绪"
        }
    }

    /** 自动走子状态（未开启时返回 null，不占状态行） */
    private fun autoStatusLine(): String? {
        if (!autoPlayOn) return null
        val access = if (AssistAccessibilityService.isConnected()) "通道已连接" else "通道未连接"
        val landing = landingState
        return when (landing?.stage) {
            null -> {
                val block = autoBlockReason()
                val tail = block ?: autoLastResult ?: "等待我方定着"
                "自动：待命（$access）· $tail"
            }
            LandingFlow.Stage.DISPATCHING ->
                "自动：正在落子（第 ${autoMoveAttempts + 1} 次尝试）…"
            LandingFlow.Stage.VERIFYING ->
                "自动：手势已完成，正在等待落子后新画面…"
        }
    }

    /**
     * 自动走子当下为什么还没动（null=条件已满足，可以走）。
     * 这些判定必须与 [maybeAutoPlay] 保持一致，否则状态栏会误导用户。
     */
    private fun autoBlockReason(): String? {
        if (paused) return "已暂停"
        if (autoMoveCooldownUntil > System.currentTimeMillis()) {
            val left = ((autoMoveCooldownUntil - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
            return "落子失败冷却中（${left}s后自动恢复）"
        }
        if (refresh.isActive) return "正在更新棋面"
        // 手动模式不参与自动落子：机器不知道你什么时候摆完
        if (manualMode) return "手动模式（不自动落子）"
        if (!AssistAccessibilityService.isConnected()) return "屏幕操作通道未连接"
        if (sessionGrid == null) return "正在寻找棋盘"
        if (currentPieces == null || currentFen.isEmpty()) return "等待首个局面"
        val now = System.currentTimeMillis()
        if (semiAuto) {
            // 半自动：局面由「更新棋谱」确认，只要别放太久就行
            if (boardUpdatedAt <= 0L) return "尚未更新棋谱"
            if (now - boardUpdatedAt > AUTO_SEMI_VALID_MS) return "棋面太久没更新（点「更新棋谱」）"
        } else {
            // 计算中/落子中我们按设计不取帧，那段时间画面自然"变旧"。
            // 如果只用"上次成功识别到现在多久"来判，刚进入待落子时就会误报
            // "画面未更新（识别停滞）"——这正是用户反复看到的误报。
            // 现在把"进入本阶段的时刻"也算作基准，并在落子阶段放宽不稳定计数
            // （落子前后的真实校验由 AutoLanding 的两次取证负责）。
            val landingCritical = phase == AssistPhase.Phase.READY ||
                phase == AssistPhase.Phase.VERIFYING
            if (!landingCritical && !AssistPhase.boardFreshEnough(now, lastMappedAt, phaseSinceAt, AUTO_FRESH_MS)) {
                return "正在刷新画面…"
            }
            if (!landingCritical && tracker.unstableStreak >= AUTO_MAX_UNSTABLE) {
                return "识别不稳定（${tracker.unstableStreak} 帧未确认）"
            }
        }
        if (currentRedGo != mySideIsRed()) return "对方回合"
        val ucci = currentAdviceUcci() ?: return "暂无建议着法"
        if (ucci == autoLastUcci) return "该着法已执行过"
        val mature = searchSettled
        if (!mature) return "建议还在变（等定着）"
        return null
    }

    // ==================== 自动走子 ====================

    /**
     * 自动/手动执行始终采用本次统一预算下排名第一的最优候选。
     * `selectedCandidate` 只影响悬浮窗浏览，绝不改变实际落子，也不会触发重算。
     */
    /** 当前**实际展示**的候选箭头对应的着法（受“变招”浏览索引影响）。 */
    private fun currentShownUcci(): String? {
        bookMoves?.let { book ->
            if (book.isNotEmpty()) {
                val idx = selectedCandidate.coerceIn(0, book.size - 1)
                return book[idx].move
            }
        }
        val lines = lastAnalysis?.lines ?: return null
        if (lines.isEmpty()) return null
        val line = lines[selectedCandidate.coerceIn(0, lines.size - 1)]
        return line.pv.firstOrNull()
    }

    private fun currentAdviceUcci(): String? {
        val book = bookMoves
        if (!book.isNullOrEmpty()) return book.first().move
        val a = lastAnalysis ?: return null
        val best = a.bestLine
        return best?.pv?.firstOrNull() ?: a.bestUcci
    }

    /**
     * **手动落子**（悬浮球双击触发）。
     *
     * 用途：机器没落子、或者你不想等它的定着等待时，由人踢一脚。
     * 因此它跳过"定着等待"和"画面新鲜度"这两道自动流程才会用的闸门，
     * 但**保留最要紧的一道**：如果当前盘面已经不等于建议所依据的盘面，
     * 说明这条建议过期了，宁可不落、先重算，也不能照它乱走。
     *
     * 依赖自动落子之外的独立通道判断：只要屏幕操作通道连着就能用，
     * 不要求「自动走子」开关打开（这是人工指令，不是自动行为）。
     */
    private fun manualLandNow() {
        val reason: String? = when {
            landingState != null -> "已有一手正在执行或核对，请勿重复触发"
            paused -> "已暂停（先点『开始』）"
            manualMode -> "手动模式由你摆子，不需要机器落子"
            !AssistAccessibilityService.isConnected() -> "屏幕操作通道未连接，无法落子"
            sessionGrid == null -> "正在寻找棋盘"
            currentPieces == null || currentFen.isEmpty() -> "还没有识别到局面"
            currentRedGo != mySideIsRed() -> "现在不是我方回合（双击可切换走棋方）"
            currentAdviceUcci() == null -> "还没有可走的建议"
            else -> null
        }
        if (reason != null) {
            setStatus("手动落子：$reason")
            postRender()
            return
        }
        val grid = sessionGrid ?: return
        val pieces = currentPieces ?: return
        val ucci = currentAdviceUcci() ?: return
        val seenSnapshot = lastSeen
        when (AutoLanding.preCheck(adviceBoard, seenSnapshot?.board, config.matchTolerance)) {
            AutoLanding.PreVerdict.STALE -> {
                // 建议过期：宁可重算也不照它落
                setStatus("手动落子：盘面已变，已重新计算，稍候再双击")
                restartAnalysisForNewBoard("盘面已变，已重新计算")
                return
            }
            else -> Unit // MATCH / UNKNOWN：人工明确要求，允许落
        }
        if (!AssistBoard.isLegalMoveInModel(pieces, ucci, mySideIsRed())) {
            notifyBoardProblem("建议着法与当前棋面不一致")
            return
        }
        val (fw, fh) = config.frameSize
        val plan = AutoMovePlanner.plan(ucci, grid, currentOrientation, fw, fh, pieces, mySideIsRed())
            ?.let { toScreenPlan(it) }
            ?: run {
                setStatus("手动落子：无法换算落点（网格/坐标异常）")
                postRender()
                return
            }
        // 清掉"刚发过"的记忆，确保这一次一定发得出去
        autoLastUcci = null
        autoMoveAttempts = 0
        setStatus("手动落子：${describeSafe(currentFen, ucci)}")
        executeAutoMove(plan, currentFen, requireAutoSwitch = false)
    }

    /**
     * 自动走子判定与执行（主线程；由 renderOverlay 驱动，即每次局面/建议变化后重判）。
     *
     * 全部满足才落子：
     * 1. 自动开关开启且「屏幕操作通道」已连接；
     * 2. 未暂停、非手动模式；
     * 3. 轮到我方；
     * 4. 识别健康（未丢帧、未长期不稳定）；
     * 5. 有棋盘网格（落点换算基准）；
     * 6. 建议已"定着"（引擎算完，或最佳着法连续不变超过 autoPlayDelayMs）；
     * 7. 起点确为我方棋子、落点在屏内（由 AutoMovePlanner 校验）。
     */
    private fun maybeAutoPlay() {
        val now = System.currentTimeMillis()
        if (now < autoMoveCooldownUntil) return
        val autoTickSignature = "${refreshPhase()}|${landingState?.stage}|${pendingAutoMove != null}|$searching|$searchSettled|$currentFen"
        if (autoTickSignature != lastAutoTickSignature || now - lastAutoTickLogAt >= 1000L) {
            lastAutoTickSignature = autoTickSignature
            lastAutoTickLogAt = now
            trace("AUTO_TICK", autoTickSignature)
        }
        val blockNow = autoBlockReason() ?: "READY_TO_EXECUTE"
        if (blockNow != lastAutoBlockLog) {
            lastAutoBlockLog = blockNow
            trace("AUTO_DECISION", "$blockNow phase=${refreshPhase()} ucci=${currentAdviceUcci()} id=$expectedAnalysisId")
        }
        // 待落子超过2秒先静默重启一次通道；即使通道已经断开，也不能被连接检查提前短路。
        if (maybeRestartReadyChannel(now)) return
        if (autoChannelRestartInFlight.get() || pendingAutoMove != null) return
        // 自动推进只允许在主线程串行执行，并且一次只能有一个落子事务。
        if (!WorkModes.autoCanRun(currentMode(), autoPlayOn, AssistAccessibilityService.isConnected())) return

        // 落子事务由识别回调的 `processLandingVerification` 独占收口。
        // 自动驱动不能再直接读取 lastSeen 判定“任意变化即落子成功”，否则会与
        // 回执处理并发消费同一张图，重新产生重复落子。
        if (landingState != null) return

        if (paused || manualMode || refresh.isActive) return
        val grid = sessionGrid ?: return
        val pieces = currentPieces ?: return
        val fen = currentFen
        if (fen.isEmpty()) return

        val freshOk = if (semiAuto) {
            boardUpdatedAt > 0 && now - boardUpdatedAt <= AUTO_SEMI_VALID_MS
        } else {
            val landingCritical = phase == AssistPhase.Phase.READY ||
                phase == AssistPhase.Phase.VERIFYING
            (landingCritical || AssistPhase.boardFreshEnough(now, lastMappedAt, phaseSinceAt, AUTO_FRESH_MS)) &&
                (tracker.unstableStreak < AUTO_MAX_UNSTABLE || landingCritical)
        }
        if (!freshOk || currentRedGo != mySideIsRed()) return
        val ucci = currentAdviceUcci() ?: return
        if (ucci == autoLastUcci && now - autoLastActionAt < config.autoPlayAckTimeoutMs) return
        if (autoMoveAttempts >= MAX_AUTO_ATTEMPTS) return
        val mature = searchSettled
        if (!mature) {
            // 还没定着：只显示蓝色候选箭头，不落子。
            // 起点预选由分析回调（onSearchUpdate）驱动，渲染函数不产生任何触摸副作用。
            return
        }

        val seenSnapshot = lastSeen
        when (AutoLanding.preCheck(adviceBoard, seenSnapshot?.board, config.matchTolerance)) {
            AutoLanding.PreVerdict.STALE -> {
                autoLastResult = "盘面已变，丢弃过期建议并重算"
                restartAnalysisForNewBoard("局面已变，已重新计算")
                return
            }
            AutoLanding.PreVerdict.UNKNOWN -> {
                autoLastResult = "等待落子前新画面"
                return
            }
            AutoLanding.PreVerdict.MATCH -> Unit
        }

        if (!AssistBoard.isLegalMoveInModel(pieces, ucci, mySideIsRed())) {
            autoLastResult = "建议着法在当前棋面不合法，持续重新识别"
            notifyBoardProblem("建议着法与当前棋面不一致")
            return
        }

        val (fw, fh) = config.frameSize
        val plan = AutoMovePlanner.plan(ucci, grid, currentOrientation, fw, fh, pieces, mySideIsRed())
            ?.let { toScreenPlan(it) }
            ?: run {
                autoLastResult = "无法换算落点（网格/坐标异常）"
                requestRenderOnly()
                return
            }
        executeAutoMove(plan, fen)
    }

    /** 清掉当前落子事务并让所有旧回调失效。 */
    private fun clearLandingTransaction(restoreWindow: Boolean) {
        trace(
            "LANDING_CLEAR",
            "restore=$restoreWindow oldStage=${landingState?.stage} pending=${pendingAutoMove != null} " +
                "token=$landingToken"
        )
        // 注意：这里**不**解除管线监督。落子事务结束只代表这一手结束，
        // 录制/识别管线还要继续被守着（用户那次“卡住”就是事务没了、管线也停了）。
        landingToken++
        // 落子事务结束/作废：候选预选状态一并失效，避免旧的“已选中起点”被下一次落子误用。
        previewState.clear()
        previewGestureToken++
        previewGestureInFlight = false
        pendingAutoMove = null
        lastPreparedMoveKey = null
        autoChannelRestartGeneration++
        readyChannelRestartedForPhaseAt = Long.MIN_VALUE
        landingState = null
        autoState = AutoState.IDLE // 兼容旧展示字段；阶段机只认 landingState
        autoBoardAtExec = null
        autoFenAtExec = null
        phase = if (paused) AssistPhase.Phase.PAUSED else AssistPhase.Phase.FINDING
        phaseSinceAt = System.currentTimeMillis()
        if (restoreWindow) restoreOverlayAfterLanding()
    }

    private fun retryOrStopLanding(reason: String) {
        val abandoned = landingState
        trace(
            "LANDING_REBASE",
            "reason=$reason ucci=${abandoned?.ucci} token=${abandoned?.token} " +
                "stage=${abandoned?.stage}"
        )
        // 超时后的第一原则是“先确认屏幕现在到底是什么局面”，而不是复用旧建议。
        // 保留“落子前”和“预期落子后”两份只读快照，仅用于下一张真实画面决定轮次；
        // 当前显示棋面本身清空，强制取帧阶段重新走完整的识别→确认→分析链。
        if (abandoned != null) {
            landingRebasePending = true
            landingRebasePreBoard = AssistBoard.clone(abandoned.preBoard)
            landingRebaseExpectedBoard = abandoned.expectedPostBoard?.let { AssistBoard.clone(it) }
            landingRebaseRedGo = abandoned.preRedGo
        }
        clearLandingTransaction(restoreWindow = true)
        stopCurrentSearch()
        analysisCycleGate.invalidate()
        resetSuggestionState(newAnalysisCycle = false)
        adviceBoard = null
        lastSeen = null
        currentCanonical = null
        currentPieces = null
        currentFen = ""
        resumeRebasePending = true
        tracker.reset(landingRebaseRedGo)
        reanalyzeAfterLandingFailure = true
        autoLastUcci = null
        autoMoveAttempts++
        if (autoMoveAttempts >= MAX_AUTO_ATTEMPTS) {
            stopAutoAfterLandingFailure(reason)
            return
        }
        autoLastResult = "$reason，正在重新确认当前棋面"
        // 重新打开稳定窗口并立即踢一次读取器；仅依赖 requestAutoDrive 会停在 FINDING，
        // 因为它只负责“是否落子”，并不会负责启动识别→确认。
        syncCaptureDemand(AssistPhase.Phase.FINDING)
        kickCapture(forceProcess = true)
        requestRenderOnly()
        requestAutoDrive(180L)
    }

    private fun stopAutoAfterLandingFailure(reason: String) {
        // 达到单手重试上限后也不能停在旧盘面：继续完成这次重建，只是进入短冷却，
        // 新画面确认后再按真实局面分析。用户的自动开关保持不变。
        clearLandingTransaction(restoreWindow = true)
        autoPlayOn = config.autoPlay
        autoMoveCooldownUntil = System.currentTimeMillis() + 3000L
        reanalyzeAfterLandingFailure = true
        stopCurrentSearch()
        analysisCycleGate.invalidate()
        resetSuggestionState(newAnalysisCycle = false)
        autoLastUcci = null
        autoLastResult = "$reason，已达到本手重试上限；正在重新确认棋面"
        setStatus("$reason\n正在重新确认当前棋面，自动走子开关保持开启…")
        syncCaptureDemand(AssistPhase.Phase.FINDING)
        kickCapture(forceProcess = true)
        ensureAccessibilityForAutoPlay(openSettings = false)
        requestWatchdog()
    }

    /**
     * 蓝色候选箭头的**起点预选**（由分析回调驱动，渲染函数绝不参与）。
     *
     * 每当界面展示的候选箭头换成一个新的合法候选，就异步按住它的起点一次，
     * 让棋盘出现“把棋子拿起来”的动效；不会点终点，因此不会真的走子。
     *
     * 去重、模式限制、棋面/会话失效、手势完成回执统一由
     * [CandidatePreviewPolicy] 与 [CandidatePreviewState]（纯 JVM）决定。
     */
    private fun scheduleCandidateOriginPreview(ucci: String?) {
        if (ucci == null) return
        if (previewGestureInFlight) {
            if (System.currentTimeMillis() - previewGestureStartedAt < 1500L) return
            previewGestureInFlight = false
            previewGestureToken++
            trace("PREVIEW_TIMEOUT", "ucci=$ucci")
        }
        if (landingState != null || pendingAutoMove != null || autoChannelRestartInFlight.get()) return
        if (currentRedGo != mySideIsRed()) return
        val grid = sessionGrid ?: return
        val pieces = currentPieces ?: return
        // 换棋面/换分析会话 → 旧的预选状态立即失效。
        previewState.onContext(currentFen, expectedAnalysisId)
        val snap = previewState.snapshot()
        val decision = CandidatePreviewPolicy.decidePreview(
            CandidatePreviewPolicy.PreviewInput(
                autoCanRun = WorkModes.autoCanRun(
                    currentMode(), autoPlayOn, AssistAccessibilityService.isConnected()
                ),
                landingInProgress = landingState != null,
                currentFen = currentFen,
                analysisId = expectedAnalysisId,
                ucci = ucci,
                originPointValid = originPointValid(ucci, pieces),
                selectedFen = snap.fen,
                selectedAnalysisId = snap.analysisId,
                selectedOrigin = snap.origin,
                selectionGestureCompleted = snap.gestureCompleted,
                    selectionAttempted = snap.selectionAttempted,
            )
        )
        if (decision != CandidatePreviewPolicy.PreviewDecision.DISPATCH_ORIGIN_PREVIEW) return

        val now = System.currentTimeMillis()
        if (now - lastPreviewDispatchAt < CANDIDATE_PREVIEW_MIN_GAP_MS) return
        val (fw, fh) = config.frameSize
        val plan = AutoMovePlanner.plan(
            ucci, grid, currentOrientation, fw, fh, pieces, mySideIsRed()
        )?.let { toScreenPlan(it) } ?: return
        val access = AssistAccessibilityService.instance ?: return

        lastPreviewDispatchAt = now
        val token = previewState.markDispatched(CandidatePreviewPolicy.originOf(ucci))
        val gestureToken = ++previewGestureToken
        previewGestureInFlight = true
        previewGestureStartedAt = now
        autoLastResult = "候选预选 ${plan.ucci}"
        Log.i(TAG, "candidate preview dispatch analysis=$expectedAnalysisId fen=$currentFen ucci=${plan.ucci} from=${plan.fromX},${plan.fromY}")
        // 预选只点击起点，不点击终点。预选期间临时让悬浮窗不接收触摸，
        // 否则全局无障碍手势可能落在本应用的悬浮窗上，目标棋盘看不到任何动作。
        setPanelTouchable(false)
        val accepted = runCatching {
            access.pressAndHold(plan.fromX, plan.fromY) { completed ->
                mainHandler.post {
                    if (gestureToken == previewGestureToken) {
                        previewGestureInFlight = false
                    }
                    previewState.markCompleted(token, completed)
                    trace("PREVIEW_RESULT", "ucci=${plan.ucci} completed=$completed current=$gestureToken/$previewGestureToken")
                    Log.i(TAG, "candidate preview result analysis=$expectedAnalysisId ucci=${plan.ucci} completed=$completed")
                    if (landingState == null && pendingAutoMove == null && !autoChannelRestartInFlight.get()) {
                        setPanelTouchable(true)
                    }
                    if (gestureToken == previewGestureToken) requestAutoDrive()
                }
            }
        }.getOrDefault(false)
        if (!accepted) {
            if (gestureToken == previewGestureToken) previewGestureInFlight = false
            previewState.markCompleted(token, false)
            trace("PREVIEW_DISPATCH_FAILED", "ucci=${plan.ucci}")
            if (landingState == null && pendingAutoMove == null && !autoChannelRestartInFlight.get()) {
                setPanelTouchable(true)
            }
        }
    }

    /** 该 UCCI 的起点是否确实是我方棋子（送子防线：轮次错乱时不乱点）。 */
    private fun originPointValid(ucci: String, pieces: IntArray): Boolean {
        if (ucci.length < 4) return false
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        if (fx !in 0..8 || fy !in 0..9) return false
        val p = pieces[fy * 9 + fx]
        return p in 1..14 && Piece.isRed(p) == mySideIsRed()
    }

    /** 把规划器在录屏坐标中算出的点转换为物理屏幕坐标。 */
    private fun toScreenPlan(plan: AutoMove): AutoMove {
        val (fw, fh) = config.frameSize
        return AutoMovePlanner.scaleToScreen(plan, fw, fh, screenWidth, screenHeight)
    }
    private fun cellSize(): Pair<Float, Float> {
        val g = sessionGrid ?: return 0f to 0f
        val (fw, fh) = config.frameSize
        val sw = screenWidth.takeIf { it > 0 } ?: fw
        val sh = screenHeight.takeIf { it > 0 } ?: fh
        if (sw <= 0 || sh <= 0) return 0f to 0f
        return ((g.nx1 - g.nx0) * sw / 8.0).toFloat() to ((g.ny1 - g.ny0) * sh / 9.0).toFloat()
    }

    /**
     * 建立一手不可变的落子请求，并先执行一次静默通道保险。
     * 真正的 Gesture 只在保险回调确认通道可用后派发；保险期间不改状态栏、通知或自动开关。
     */
    private fun executeAutoMove(plan: AutoMove, fen: String, requireAutoSwitch: Boolean = true) {
        if (landingState != null || pendingAutoMove != null || autoChannelRestartInFlight.get()) return
        val preBoard = (currentCanonical ?: lastSeen?.board)?.let { AssistBoard.clone(it) } ?: run {
            autoLastResult = "缺少落子前棋面，等待重新识别"
            requestRenderOnly()
            return
        }

        val sim = config.simEnabled
        val (cw, ch) = cellSize()
        val target = if (sim && cw > 0f && ch > 0f)
            AutoMovePlanner.applyJitter(plan, cw, ch, random) else plan
        val tapDelay = if (sim) 50L + random.nextInt(451).toLong() else 220L

        // 绿色最终箭头：如果起点已经被蓝色候选预选过，就只补一下终点，
        // 不再点一次起点（否则会把选中取消）。拖动式始终用完整手势。
        val snap = previewState.snapshot()
        val finalDecision = if (config.autoPlayGesture == AssistConfig.GESTURE_TAP) {
            CandidatePreviewPolicy.decideFinal(
                CandidatePreviewPolicy.FinalInput(
                    currentFen = fen,
                    analysisId = expectedAnalysisId,
                    finalUcci = target.ucci,
                    selectedFen = snap.fen,
                    selectedAnalysisId = snap.analysisId,
                    selectedOrigin = snap.origin,
                    selectionGestureCompleted = snap.gestureCompleted,
                    selectionAttempted = snap.selectionAttempted,
                )
            )
        } else {
            CandidatePreviewPolicy.FinalDecision.FULL_MOVE
        }
        if (finalDecision == CandidatePreviewPolicy.FinalDecision.WAIT_SELECTION) {
            // 预选只是可选的交互加速，不能阻塞正式落子。无障碍回调可能因目标应用、
            // 系统切窗或服务状态丢失；此时直接回退完整起点→终点，避免永久卡死。
            autoLastResult = "预选未回执，改用完整落子手势"
        }
        // 无论自动还是手动，正式落子前都会执行通道保险；保险可能取消尚未完成的起点预选。
        // 因此保险通过后一律发送完整起点→终点手势，不能只补终点。
        val reuseSelectedOrigin = false

        // 先保存不可变请求；通道保险完成前不创建 LandingFlow，避免界面先显示“落子中”。
        val pending = PendingAutoMove(
            target = target,
            fen = fen,
            preBoard = preBoard,
            reuseSelectedOrigin = reuseSelectedOrigin,
            requireAutoSwitch = requireAutoSwitch,
            createdAt = System.currentTimeMillis(),
        )
        trace(
            "MOVE_PENDING",
            "ucci=${target.ucci} fen=$fen attempt=${autoMoveAttempts + 1} requireAuto=$requireAutoSwitch"
        )
        pendingAutoMove = pending
        previewState.clear()
        previewGestureToken++
        previewGestureInFlight = false
        lastPreviewDispatchAt = 0L
        prepareAutoMoveChannel(pending, tapDelay)
    }

    /** 每一手只执行一次通道保险；失败时保留请求并等待周期恢复，不发出半截手势。 */
    private fun prepareAutoMoveChannel(pending: PendingAutoMove, tapDelay: Long) {
        if (pendingAutoMove !== pending || stopping || overlayClosed || paused || manualMode ||
            (pending.requireAutoSwitch && !autoPlayOn)) return
        if (currentFen != pending.fen) {
            pendingAutoMove = null
            lastPreparedMoveKey = null
            requestAutoDrive()
            return
        }
        val key = AutoChannelRestartPolicy.moveTransactionKey(pending.fen, pending.target.ucci)
        trace("MOVE_PREPARE", "key=${pending.target.ucci} fen=${pending.fen} already=${lastPreparedMoveKey == key}")
        if (lastPreparedMoveKey == key) {
            dispatchAutoMoveNow(pending, tapDelay, pending.requireAutoSwitch)
            return
        }
        lastPreparedMoveKey = key
        restartAutoChannelSilently(requireAutoSwitch = pending.requireAutoSwitch) { connected ->
            if (pendingAutoMove !== pending) return@restartAutoChannelSilently
            if (connected) {
                dispatchAutoMoveNow(pending, tapDelay, pending.requireAutoSwitch)
            } else {
                // 不清除用户的自动开关；无障碍恢复线程会继续尝试，稍后再重试这一手。
                lastPreparedMoveKey = null
                ensureAccessibilityForAutoPlay(openSettings = false, silent = true)
                mainHandler.postDelayed({
                    prepareAutoMoveChannel(pending, tapDelay)
                }, ACCESSIBILITY_RECOVERY_INTERVAL_MS)
            }
        }
    }

    /** 通道保险通过后重新核对局面，再创建并派发真实落子事务。 */
    private fun dispatchAutoMoveNow(
        pending: PendingAutoMove,
        tapDelay: Long,
        requireAutoSwitch: Boolean,
    ) {
        if (stopping || overlayClosed || paused || manualMode || (requireAutoSwitch && !autoPlayOn)) return
        if (pending.createdAt + CHANNEL_RESTART_TIMEOUT_MS < System.currentTimeMillis()) {
            trace("MOVE_DISPATCH_TIMEOUT", "ucci=${pending.target.ucci} age=${System.currentTimeMillis() - pending.createdAt}")
            pendingAutoMove = null
            lastPreparedMoveKey = null
            autoLastResult = "通道保险超时，已释放本次落子"
            requestAutoDrive()
            return
        }
        if (currentFen != pending.fen) {
            pendingAutoMove = null
            lastPreparedMoveKey = null
            requestAutoDrive()
            return
        }
        val access = AssistAccessibilityService.instance
        if (access == null) {
            lastPreparedMoveKey = null
            ensureAccessibilityForAutoPlay(openSettings = false, silent = true)
            mainHandler.postDelayed({
                prepareAutoMoveChannel(pending, tapDelay)
            }, ACCESSIBILITY_RECOVERY_INTERVAL_MS)
            return
        }
        val pieces = currentPieces
        if (pieces == null || !AssistBoard.isLegalMoveInModel(pieces, pending.target.ucci, mySideIsRed())) {
            pendingAutoMove = null
            lastPreparedMoveKey = null
            notifyBoardProblem("建议着法与当前棋面不一致")
            requestAutoDrive()
            return
        }
        when (AutoLanding.preCheck(adviceBoard, lastSeen?.board, config.matchTolerance)) {
            AutoLanding.PreVerdict.STALE -> {
                pendingAutoMove = null
                lastPreparedMoveKey = null
                restartAnalysisForNewBoard("局面已变，已重新计算")
                return
            }
            AutoLanding.PreVerdict.UNKNOWN -> {
                pendingAutoMove = null
                lastPreparedMoveKey = null
                requestAutoDrive()
                return
            }
            AutoLanding.PreVerdict.MATCH -> Unit
        }

        val target = pending.target
        trace("MOVE_DISPATCH", "ucci=${target.ucci} fen=${pending.fen} attempt=${autoMoveAttempts + 1}")
        // 正式落子接管当前回合时，先撤销分析回调的发布资格。即使旧的
        // onSearchDone/onSearchUpdate 已经排进主线程，也不能在落子事务期间重新把
        // searchSettled 或旧建议写回来。
        val detachedAnalysisId = expectedAnalysisId
        expectedAnalysisId = 0L
        searching = false
        searchSettled = false
        trace("ANALYSIS_DETACH_FOR_LANDING", "id=$detachedAnalysisId fen=${pending.fen}")
        val token = ++landingToken
        val now = System.currentTimeMillis()
        landingState = LandingFlow.begin(token, target.ucci, pending.fen, pending.preBoard, now, preRedGo = currentRedGo)
        armPipelineWatchdog()
        // 落子事务内的证据从这一刻起重新计时：旧时间戳会把“刚派发的手势”误判成早就卡住。
        lastStableWindowAt = 0L
        lastVisionAt = 0L
        autoState = AutoState.CLICKING
        autoLastUcci = target.ucci
        autoFenAtExec = pending.fen
        autoBoardAtExec = pending.preBoard
        autoLastActionAt = now
        pendingAutoMove = null
        requestRenderOnly()

        // 先提交“准备落子”状态；真正开始派发时才收球。渲染/识别绝不会自行调用这段。
        mainHandler.post {
            if (!LandingFlow.isCurrent(landingState, token)) return@post
            suppressOverlayForLanding()
            requestRenderOnly()

            try {
                if (config.autoPlayGesture == AssistConfig.GESTURE_SWIPE) {
                    val accepted = access.swipe(
                        target.fromX, target.fromY, target.toX, target.toY
                    ) { completed ->
                        mainHandler.post {
                            if (completed) finishLandingGesture(token)
                            else abortLandingGesture(token, "拖动手势被系统取消")
                        }
                    }
                    if (!accepted) abortLandingGesture(token, "拖动手势未被无障碍服务接受")
                } else if (pending.reuseSelectedOrigin) {
                    // 起点已被预选手势按住并完成：只补一下终点。
                    val accepted = access.tap(target.toX, target.toY) { completed ->
                        mainHandler.post {
                            if (completed) finishLandingGesture(token)
                            else abortLandingGesture(token, "终点点击被系统取消")
                        }
                    }
                    if (!accepted) abortLandingGesture(token, "终点点击未被无障碍服务接受")
                } else {
                    // 默认点击式：两次点按封装在同一个系统 GestureDescription 内，
                    // 完成回调只在“起点 + 终点”都执行以后触发。
                    val accepted = access.tapSequence(
                        target.fromX,
                        target.fromY,
                        target.toX,
                        target.toY,
                        tapDelay,
                    ) { completed ->
                        mainHandler.post {
                            if (completed) finishLandingGesture(token)
                            else abortLandingGesture(token, "点击式落子被系统取消")
                        }
                    }
                    if (!accepted) abortLandingGesture(token, "点击式落子未被无障碍服务接受")
                }
            } catch (t: Throwable) {
                abortLandingGesture(token, "落子异常：${t.javaClass.simpleName}")
            }
        }
    }

    /** 系统确认整套落子手势结束后，才允许进入核对阶段。 */
    private fun finishLandingGesture(token: Long) {
        if (!LandingFlow.isCurrent(landingState, token)) return
        // 给游戏的走子动画留一个短窗口；这期间仍是“落子中”且不取帧，
        // 避免把点选高亮/半途动画误认成棋面已变化。
        mainHandler.postDelayed({
            val completed = LandingFlow.complete(landingState, token, System.currentTimeMillis())
                ?: return@postDelayed
            landingState = completed
            autoState = AutoState.WAITING_BOARD
            // 手势期间可能已经释放过一帧旧稳定画面；进入回执阶段必须允许
            // 下一张相邻样本再次进入模型，而不是等画面签名先变化一次。
            stableFrameWindow.rearm()
            trace("GESTURE_FINISHED", "token=$token ucci=${completed.ucci}")
            autoLastActionAt = completed.completedAt
            autoLastResult = "手势已完成，等待落子后新画面"
            restoreOverlayAfterLanding()
            requestRenderOnly()
            requestAutoDrive(120L)
            requestWatchdog(200L)
        }, AUTO_TAP_RESTORE_MS)
    }

    private fun abortLandingGesture(token: Long, reason: String) {
        if (!LandingFlow.isCurrent(landingState, token)) return
        trace("GESTURE_ABORT", "token=$token reason=$reason")
        retryOrStopLanding(reason)
    }


    /** 手势期间临时切换悬浮窗的可触摸性（避免自动点击打到自己身上） */
    private fun setPanelTouchable(touchable: Boolean) {
        fun applyNow() {
            val (view, params) = overlayWindow() ?: return
            val wm = windowManager ?: return
            params.flags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            runCatching { wm.updateViewLayout(view, params) }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 落子派发就在主线程；必须在 dispatchGesture 前同步生效，不能再排到它后面。
            applyNow()
        } else {
            mainHandler.post { applyNow() }
        }
    }

    /**
     * 棋面异常时的统一提示。
     *
     * 历史上这里挂着一套「非法棋面恢复」状态机：它会持续重识别并反过来锁住刷新请求，
     * 一旦某个状态位没被复位，整个识别循环就永久卡死。现在只做两件事：
     * 1. 如实把原因写进状态栏（节流，避免每帧刷屏）；
     * 2. 什么都不锁——连续模式下录屏流本来就一直跑，下一组稳定样本自然会重试。
     */
    private fun notifyBoardProblem(reason: String) {
        if (paused || manualMode || overlayClosed) {
            setStatus("棋面不合法：$reason")
            return
        }
        yoloMissStreak = maxOf(yoloMissStreak, 1)
        lastFailReason = reason
        setStatus("棋面仍不合法：$reason\n拒绝当前关键帧，继续录屏稳定窗口轮询识别…")
    }

    /**
     * 落子前给悬浮窗"让路"。
     *
     * 单靠把透明度调成 0 是**不够**的：透明窗口照样接收触摸，
     * 落子点击只要落在悬浮窗矩形内，就会被它吃掉，棋盘根本收不到这一下——
     * 这正是"落子一直不成功"的直接原因。
     *
     * 所以做两件事：
     * 1. 面板展开着就**临时收成小球**（小球占地极小，几乎不会压住棋盘落点）；
     * 2. 无论面板还是小球，手势期间都置为**不可触摸**，双保险。
     *
     * 内部动作：不写状态栏、不触发重绘，避免与落子流程互相触发。
     */
    private fun suppressOverlayForLanding() {
        if (landingUiSuppressed) return
        landingUiSuppressed = true
        // 临时收球前只读取并记录当时的显示形态；不改持久化的 overlayCollapsed。
        landingWasPanel = overlayPanel != null && !config.overlayCollapsed
        if (landingWasPanel) {
            // 临时形态只存在于运行时：绝不改 config.overlayCollapsed，避免进程中断后
            // 把“落子时临时收球”错误保存成用户偏好。
            overlayParams?.let { p ->
                val dm = screenMetrics()
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    persistOverlayGeometry(p, dm.widthPixels, dm.heightPixels)
                    // 临时落子同样不能覆盖用户保存的小球位置。
                }
            }
            val ball = ensureBall()
            if (ball != null) {
                overlayPanel?.let { p -> runCatching { windowManager?.removeView(p) } }
                overlayPanel = null
                overlayParams = null
            } else {
                Log.w(TAG, "landing overlay suppression kept panel because ball could not be shown")
            }
        }
        // 小球仍可能压住某个格；真正让点击穿透的是 NOT_TOUCHABLE，而不是透明度。
        setPanelTouchable(false)
    }

    /**
     * 落子手势结束/失败：把悬浮窗恢复到事务开始前的形态。
     * 原来展开着才展开；原来就是小球则保留小球。
     */
    private fun restoreOverlayAfterLanding() {
        if (!landingUiSuppressed) return
        landingUiSuppressed = false
        setPanelTouchable(true)
        if (landingWasPanel) {
            landingWasPanel = false
            overlayBall?.let { b -> runCatching { windowManager?.removeView(b) } }
            overlayBall = null
            overlayBallParams = null
            ensureOverlay()
        }
        requestRenderOnly()
    }

    /**
     * 状态变化入口：合并高频事件，依次执行“归约阶段 → 同步取帧 → 纯渲染 → 推进一步”。
     *
     * 关键约束：界面绘制本身不得执行落子、监督复位或折叠窗口。过去把这些副作用放在
     * renderOverlay() 里，任何状态文字、引擎 info 行甚至绘制重入都会再次落子，正是本次
     * “识别→核对→收球→又识别”循环的结构性根因。
     */
    private fun postRender() {
        requestRenderOnly()
        requestAutoDrive()
        requestWatchdog()
    }

    /** 只刷新显示；不推进任何业务状态。 */
    private fun requestRenderOnly() {
        if (!renderScheduled.compareAndSet(false, true)) return
        mainHandler.post {
            renderScheduled.set(false)
            val resolved = refreshPhase()
            syncCaptureDemand(resolved)
            val override = pendingStatusOverride
            pendingStatusOverride = null
            renderOverlay(override, resolved)
        }
    }

    /** 自动走子唯一驱动入口；同一时刻只允许一个驱动任务。 */
    private fun requestAutoDrive(delayMs: Long = 0L) {
        if (!autoDriveScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            autoDriveScheduled.set(false)
            // 进入 READY/WAITING 后由稳定录屏流和落子回执推进；这里不能再因旧的
            // lastMappedAt 触发 refresh，否则会把“待落子”重新变成“更新棋面”，
            // 阻断自动落子并重新开启计算循环。
            maybeAutoPlay()
            // maybeAutoPlay 可能刚改变落子事务；显示与取帧策略都按新状态刷新。
            requestRenderOnly()
        }, delayMs)
    }

    /** 监督器独立运行，不再借“渲染一次”偷偷执行。 */
    private fun requestWatchdog(delayMs: Long = REFRESH_WATCHDOG_TICK_MS) {
        if (!watchdogScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            watchdogScheduled.set(false)
            tickWatchdog()
            if (searching) {
                // 只刷新“已计算多久/控制链是否正常”的展示，不向引擎发送任何命令，
                // 因而不会打断正常搜索。
                requestRenderOnly()
            }
            if (landingState?.stage == LandingFlow.Stage.VERIFYING) {
                requestAutoDrive()
            }
            val p = refreshPhase()
            if (p == AssistPhase.Phase.READY && autoPlayOn) {
                // READY 期间没有其它回调也会继续触发自动通道保险，避免卡在待落子。
                requestAutoDrive()
            }
            if (landingState != null || refresh.isActive ||
                (!paused && isContinuousScanMode() &&
                    (AssistPhase.needsBoard(p) || searching || (engineStarted && !engineReady)))) {
                requestWatchdog()
            }
        }, delayMs)
    }

    /** 按网格生成局部推理区域；局部区域只负责提高像素分辨率，不参与类别猜测。 */
    private fun cropHintForGrid(frame: Frame, grid: BoardGrid): DoubleArray? {
        val x0 = grid.nx0 * frame.width
        val y0 = grid.ny0 * frame.height
        val x1 = grid.nx1 * frame.width
        val y1 = grid.ny1 * frame.height
        val mx = (x1 - x0) / 8.0 * 1.2
        val my = (y1 - y0) / 9.0 * 1.2
        val cx0 = max(0.0, x0 - mx)
        val cy0 = max(0.0, y0 - my)
        val cx1 = min(frame.width.toDouble(), x1 + mx)
        val cy1 = min(frame.height.toDouble(), y1 + my)
        val w = ((cx1 - cx0).toInt() / 16) * 16
        val h = ((cy1 - cy0).toInt() / 16) * 16
        if (w < 64 || h < 64) return null
        return doubleArrayOf(cx0, cy0, cx0 + w, cy0 + h)
    }

    /** 模型按当前定位策略只运行一次：稳定关键帧通常使用棋盘裁剪，周期到期时用整屏重新定位。 */
    private fun detectVision(
        frame: Frame,
        yolo: YoloBoardDetector,
        excluded: IntArray?,
    ): DetectionBoardMapper.MappedBoard? {
        val grid = sessionGrid
        val crop = grid?.let { cropHintForGrid(frame, it) }
        val anchor = grid?.let {
            DetectionBoardMapper.AnchorHint(
                it.nx0 * frame.width, it.ny0 * frame.height,
                it.nx1 * frame.width, it.ny1 * frame.height,
            )
        }
        val now = System.currentTimeMillis()
        val fullDue = crop == null || now - lastFullVisionCheckAt >= FULL_VISION_RECHECK_MS
        if (fullDue) {
            lastFullVisionCheckAt = now
            // 本次稳定关键帧只执行一次整屏推理；下一次样本再切换到裁剪定位。
            return yolo.detect(frame, null, null, excluded)
        }
        // 已有网格时只执行一次裁剪推理；锚点仅提供几何基准，不替换当前类别。
        return yolo.detect(frame, crop, anchor, excluded)
    }


    private fun renderOverlay(
        statusOverride: String? = null,
        resolvedPhase: AssistPhase.Phase = refreshPhase(),
    ) {
        // 用户点“关闭”后不重建（直到重新启动服务）
        if (overlayClosed) return
        val resolvedStage = AssistPhase.toStage(resolvedPhase)
        // 用户主动折叠，或真实落子事务临时让路时显示小球；临时让路绝不写持久化状态。
        if (config.overlayCollapsed || landingUiSuppressed) {
            val ball = ensureBall() ?: return
            val st = resolvedStage
            ball.state = when (st) {
                AssistHud.Stage.ENGINE_ERROR -> OverlayBallView.BallState.ERROR
                AssistHud.Stage.PAUSED, AssistHud.Stage.MANUAL -> OverlayBallView.BallState.IDLE
                AssistHud.Stage.WAITING -> OverlayBallView.BallState.OK
                else -> OverlayBallView.BallState.WORKING
            }
            // 第一行胜率（高于五成绿/低于五成红/正好五成灰），第二行阶段短句
            val wr = winRateInfo()
            ball.rateText = wr?.text ?: ""
            ball.rateLevel = wr?.level ?: AssistHud.RateLevel.EVEN
            ball.stageText = AssistHud.shortLabel(st)
            ball.ringColor = AssistHud.ringColor(st)
            return
        }
        val panel = ensureOverlay() ?: return
        val fen = currentFen
        val myRed = mySideIsRed()
        val a = lastAnalysis

        val status = statusOverride ?: statusText()
        val turnRed = currentRedGo
        val myTurn = if (fen.isEmpty()) false else (turnRed == myRed)

        // 选择当前展示的候选
        val lines = a?.lines ?: emptyList()
        val line = if (lines.isNotEmpty()) lines[selectedCandidate.coerceIn(0, lines.size - 1)] else null

        // 左列多行小字（\n 分行）；尚未识别到局面时留空，避免与状态行重复
        var headText = when {
            fen.isEmpty() -> ""
            else -> "我方${if (myRed) "红方" else "黑方"}\n${if (myTurn) "轮到我方走" else "轮到对方走"}"
        }
        var arrowFrom: Pair<Int, Int>? = null
        var arrowTo: Pair<Int, Int>? = null
        var suggestMature = false
        // 识别中断/持续不稳定时，旧建议所依据的局面可能已失效——不再展示，避免误导
        // （手动模式局面由用户维护，不受识别健康度影响）
        val recHealthy = manualMode || (yoloMissStreak == 0 && tracker.unstableStreak < 5)
        val book = if (myTurn) bookMoves else null

        // 送子防线：着法起点必须是我方棋子——轮次错乱/幽灵 FEN 时宁可不显示
        fun fromIsOurs(ucci: String): Boolean {
            val arr = currentPieces ?: return false
            if (ucci.length < 4) return false
            val fx = ucci[0] - 'a'
            val fy = 9 - (ucci[1] - '0')
            if (fx !in 0..8 || fy !in 0..9) return false
            val p = arr[fy * 9 + fx]
            return p in 1..14 && Piece.isRed(p) == myRed
        }

        when {
            myTurn && recHealthy && book != null && book.isNotEmpty() -> {
                // 开局库命中：直接展示库内着法（定着绿✓，零等待）
                val idx = selectedCandidate.coerceIn(0, book.size - 1)
                val bd = book[idx]
                val ucci = bd.move
                if (fromIsOurs(ucci)) {
                    suggestMature = true
                    val arrow = ucciToCells(ucci)
                    arrowFrom = arrow.first
                    arrowTo = arrow.second
                    val chs = describeSafe(fen, ucci)
                    val tag = if (idx > 0) "开局库备选${idx + 1} $chs ✓" else "开局库 $chs ✓"
                    // 库内 vwin/vdraw/vlost 是胜/和/负**场数**，与 WDL 用同一套精确公式：
                    // 裁剪负值 → 归一化 → 和棋算半胜 → 四舍五入到两位小数。
                    val bookRate = AssistHud.winRateFromCounts(
                        bd.winRate.toInt(), bd.drawNum, bd.loseNum
                    )
                    val wrTxt = bookRate?.let { "胜率 ${it.text}" } ?: "库内推荐"
                    headText = "$tag\n$wrTxt\n$headText"
                } else {
                    headText = "轮次可能识别有误\n走一步后自动纠正\n$headText"
                }
            }
            myTurn && recHealthy && line != null -> {
                // 我方回合：建议（分析中会随 info 行实时更新）
                val ucci = line.pv.firstOrNull() ?: a?.bestUcci
                if (ucci != null && fromIsOurs(ucci)) {
                    suggestMature = searchSettled
                    val chs = describeSafe(fen, ucci)
                    val arrow = ucciToCells(ucci)
                    arrowFrom = arrow.first
                    arrowTo = arrow.second
                    val tag = if (selectedCandidate > 0) "备选${selectedCandidate + 1} $chs" else "建议 $chs"
                    val suffix = if (suggestMature) " ✓" else "（暂定）"
                    headText = "$tag$suffix\n${lineScoreText(line)} 深度${line.depth}\n$headText"
                } else if (ucci != null) {
                    headText = "轮次可能识别有误\n走一步后自动纠正\n$headText"
                }
            }
            !myTurn && line != null && line.pv.size >= 2 -> {
                // 对方回合：算力产出我方应对，但只给文字预案——对方还没走，把"我方应手"
                // 画在当前盘面上会指向我方棋子，看起来像"我方打我方"
                val oppU = line.pv[0]
                val myU = line.pv[1]
                if (fromIsOurs(myU)) {
                    val oppDesc = describeSafe(fen, oppU)
                    val myDesc = describeSafe(AssistBoard.applyUcci(fen, oppU), myU)
                    headText = "若对方走$oppDesc\n我方应$myDesc\n$headText"
                }
            }
            myTurn && engineReady && fen.isNotEmpty() -> {
                headText = "引擎思考中…\n$headText"
            }
        }

        val wr = winRateInfo()
        panel.render(OverlayModel(
            statusText = if (headText.isEmpty()) status else "$status\n$headText",
            stageText = AssistHud.titleLabel(resolvedStage),
            winRateText = wr?.text ?: "",
            winRateLevel = wr?.level ?: AssistHud.RateLevel.EVEN,
            pieces = currentPieces,
            arrowFrom = arrowFrom,
            arrowTo = arrowTo,
            flipped = currentOrientation == Orientation.FLIPPED,
            mySideIsRed = myRed,
            mature = suggestMature,
            modeText = WorkModes.name(currentMode()),
            autoPlay = autoPlayOn,
            running = !paused,
            suspendedByForeground = foregroundPauseSnapshot?.wasRunning == true,
            hasRunSession = runSessionStarted,
            sim = config.simEnabled,
            strengthText = strengthButtonText(),
            mySideText = if (myRed) "己方红" else "己方黑",
            candidateCountText = "候选${config.candidateCount}",
            hashText = hashButtonText(),
            manualMode = manualMode,
            selectedCell = manualSelected,
            ringColor = AssistHud.ringColor(resolvedStage),
        ))
    }

    private fun lineScoreText(line: AnalysisLine): String {
        val adv = line.scoreText()
        return if (line.redScoreCp >= 0) "红方优势 $adv" else "红方劣势 $adv"
    }

    /** ucci(4字符) -> 内部 (x,y) 起止 */
    private fun ucciToCells(ucci: String): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        if (ucci.length < 4) return Pair(0 to 0, 0 to 0)
        val fx = ucci[0] - 'a'
        val fy = 9 - (ucci[1] - '0')
        val tx = ucci[2] - 'a'
        val ty = 9 - (ucci[3] - '0')
        return Pair(fx to fy, tx to ty)
    }

    override fun onDestroy() {
        // 先设置停止闸门并使所有旧帧失效；已经进入TFLite的任务由 detector.close()
        // 的同一把锁自然排空，禁止在Interpreter.run期间并发close。
        stopping = true
        overlayClosed = true
        recognitionEpoch++
        landingToken++
        landingState = null
        pendingAutoMove = null
        lastPreparedMoveKey = null
        previewState.clear()
        abortCaptureWindow()
        mainHandler.removeCallbacksAndMessages(null)
        workerHandler.removeCallbacksAndMessages(null)
        captureHandler.removeCallbacksAndMessages(null)
        // 退出/被杀前把悬浮窗位置存下来，下次启动还原到同一处
        runCatching { persistOverlayPosNow() }
        Log.i(TAG, "service destroyed")
        releaseCapture()
        val detectorToClose = yoloDetector
        yoloDetector = null
        // InferenceLifecycleGate 会先拒绝新推理，再等待 assist-worker 的当前 run 结束。
        runCatching { detectorToClose?.close() }
        analysisEngine?.shutdown()
        analysisEngine = null
        overlayPanel?.let { p ->
            runCatching { windowManager?.removeView(p) }
        }
        overlayPanel = null
        overlayParams = null
        overlayBall?.let { b ->
            runCatching { windowManager?.removeView(b) }
        }
        overlayBall = null
        overlayBallParams = null
        workerThread.quitSafely()
        captureThread.quitSafely()
        super.onDestroy()
    }

    /** 把当前悬浮窗位置落到持久化（销毁/退出前调用，避免被杀后位置丢失） */
    private fun persistOverlayPosNow() {
        val dm = screenMetrics()
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return
        overlayParams?.let { p -> persistOverlayGeometry(p, dm.widthPixels, dm.heightPixels) }
        if (overlayBallParams != null) saveBallPos()
    }

    private fun releaseCapture() {
        // 录屏服务可能被系统直接打断：必须先把抓帧窗口事务作废并恢复正确形态，
        // 不能留下“面板被收走了、球或面板都没了”的状态。
        disarmPipelineWatchdog()
        abortCaptureWindow()
        synchronized(frameQueueLock) {
            pendingFrame = null
            frameConsumeScheduled = false
        }
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (e: Exception) { }
        try { virtualDisplay?.release() } catch (e: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (e: Exception) { }
        imageReader = null
        try { mediaProjection?.stop() } catch (e: Exception) { }
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun OverlayPanelView.dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
