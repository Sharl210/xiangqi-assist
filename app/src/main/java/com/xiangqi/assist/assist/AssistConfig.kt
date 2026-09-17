package com.xiangqi.assist.assist

import android.content.Context

/**
 * 连线功能的持久化配置（SharedPreferences）。
 */
class AssistConfig(context: Context) {
    private val sp = context.getSharedPreferences("assist_settings", Context.MODE_PRIVATE)

    /** 最近一次识别时的帧尺寸（悬浮窗遮挡判断用：把归一化棋盘框换算回像素） */
    var frameSize: Pair<Int, Int>
        get() {
            val w = sp.getInt(KEY_FRAME_W, 0)
            val h = sp.getInt(KEY_FRAME_H, 0)
            return w to h
        }
        set(value) {
            sp.edit().putInt(KEY_FRAME_W, value.first).putInt(KEY_FRAME_H, value.second).apply()
        }

    /** 单帧模型结果由录屏稳定窗口保护；此处保留配置字段仅用于旧设置兼容，不参与生产采样。 */
    var confirmCount: Int
        get() = sp.getInt(KEY_CONFIRM, 2).coerceIn(1, 8)
        set(value) { sp.edit().putInt(KEY_CONFIRM, value.coerceIn(1, 8)).apply() }

    /** 悬浮窗上次位置（左上角，归一化 0..1），未设置则默认 */
    var overlayPos: Pair<Float, Float>
        get() = sp.getFloat(KEY_OVERLAY_X, -1f) to sp.getFloat(KEY_OVERLAY_Y, -1f)
        set(value) {
            sp.edit().putFloat(KEY_OVERLAY_X, value.first).putFloat(KEY_OVERLAY_Y, value.second).commit()
        }

    /**
     * 悬浮窗矩形（归一化 0..1）：x, y, w, h。
     * 未设置或非法返回 null，表示"用默认值"（默认 = 贴满屏幕宽度、停在屏幕下方）。
     */
    var overlayRect: FloatArray?
        get() {
            if (!sp.getBoolean(KEY_RECT_SET, false)) return null
            val a = floatArrayOf(
                sp.getFloat(KEY_RECT_X, -1f), sp.getFloat(KEY_RECT_Y, -1f),
                sp.getFloat(KEY_RECT_W, -1f), sp.getFloat(KEY_RECT_H, -1f))
            if (a[2] <= 0f || a[3] <= 0f || a[0] < 0f || a[1] < 0f) return null
            return a
        }
        set(value) {
            if (value == null) {
                sp.edit().putBoolean(KEY_RECT_SET, false).commit()
                return
            }
            sp.edit()
                .putBoolean(KEY_RECT_SET, true)
                .putFloat(KEY_RECT_X, value[0]).putFloat(KEY_RECT_Y, value[1])
                .putFloat(KEY_RECT_W, value[2]).putFloat(KEY_RECT_H, value[3])
                .commit()
        }

    /** 悬浮窗是否处于"缩成小球"状态 */
    var overlayCollapsed: Boolean
        get() = sp.getBoolean(KEY_COLLAPSED, false)
        set(value) { sp.edit().putBoolean(KEY_COLLAPSED, value).commit() }

    /**
     * 当前工作模式（互斥）：0=指导 1=半自动 2=手动 3=自动走子。
     * 只有这一个入口，保证三种模式不会同时亮起。默认自动走子。
     */
    var workMode: Int
        get() = sp.getInt(KEY_WORK_MODE, MODE_AUTO).coerceIn(MODE_GUIDE, MODE_AUTO)
        set(value) { sp.edit().putInt(KEY_WORK_MODE, value.coerceIn(MODE_GUIDE, MODE_AUTO)).apply() }

    /** 旧设置字段仅为兼容；生产录屏采样固定为4帧/秒，不能再按档位改变采样频率。 */
    var captureThrottleMs: Int
        get() = sp.getInt(KEY_THROTTLE, 400).coerceIn(0, 10000)
        set(value) { sp.edit().putInt(KEY_THROTTLE, value.coerceIn(0, 10000)).apply() }

    /**
     * 仿真模式：自动走子时加入"随机偏移 + 随机延迟 + 预案预选"，
     * 让落子看起来更像人在下（默认开）。
     */
    var simEnabled: Boolean
        get() = sp.getBoolean(KEY_SIM, true)
        set(value) { sp.edit().putBoolean(KEY_SIM, value).apply() }

    /** 悬浮球缩放：以旧版球尺寸为 1.0，默认 0.9；为保证可见和可点击，允许 0.7..1.5。 */
    var overlayBallScale: Float
        get() = sp.getFloat(KEY_BALL_SCALE, 0.9f).coerceIn(0.7f, 1.5f)
        set(value) { sp.edit().putFloat(KEY_BALL_SCALE, value.coerceIn(0.7f, 1.5f)).commit() }

    /** 悬浮球最近位置（左上角，归一化 0..1） */
    var overlayBallPos: Pair<Float, Float>
        get() = sp.getFloat(KEY_BALL_X, -1f) to sp.getFloat(KEY_BALL_Y, -1f)
        set(value) {
            sp.edit().putFloat(KEY_BALL_X, value.first).putFloat(KEY_BALL_Y, value.second).commit()
        }

    /**
     * 半自动模式：帧识别照常运行，但**不自动刷新棋面**，
     * 由用户点「更新棋谱」时才把最近一次识别结果应用到棋面上。
     * 与手动模式（手工编辑棋子）互斥。
     */
    var semiAuto: Boolean
        get() = sp.getBoolean(KEY_SEMI_AUTO, false)
        set(value) { sp.edit().putBoolean(KEY_SEMI_AUTO, value).apply() }

    /** 有 root 时是否自动开启屏幕操作通道（无障碍），默认开 */
    var autoEnableAccessibility: Boolean
        get() = sp.getBoolean(KEY_AUTO_ENABLE_ACCESS, true)
        set(value) { sp.edit().putBoolean(KEY_AUTO_ENABLE_ACCESS, value).apply() }

    /** 自动走子开关（默认开）：与「模式」是两件事——模式决定识别与刷新方式，这个决定要不要自动落子 */
    var autoPlay: Boolean
        get() = sp.getBoolean(KEY_AUTO_PLAY, true)
        set(value) { sp.edit().putBoolean(KEY_AUTO_PLAY, value).apply() }

    /** 候选主变数量：1..6；默认1。自动/指导/半自动均完全遵从此设置，不按模式偷偷改写。 */
    var candidateCount: Int
        get() = sp.getInt(KEY_CANDIDATE_COUNT, ThinkingOptions.DEFAULT_CANDIDATE_COUNT)
            .coerceIn(ThinkingOptions.MIN_CANDIDATE_COUNT, ThinkingOptions.MAX_CANDIDATE_COUNT)
        set(value) {
            sp.edit().putInt(
                KEY_CANDIDATE_COUNT,
                value.coerceIn(ThinkingOptions.MIN_CANDIDATE_COUNT, ThinkingOptions.MAX_CANDIDATE_COUNT)
            ).apply()
        }


    var searchDepth: Int
        get() = sp.getInt(KEY_SEARCH_DEPTH, ThinkingOptions.DEFAULT_DEPTH).let(AssistDepth::clamp)
        set(value) { sp.edit().putInt(KEY_SEARCH_DEPTH, AssistDepth.clamp(value)).apply() }

    /** 思考模式：深度/总时/每候选时间；配置存储为三态。 */
    var thinkingMode: ThinkingMode
        get() = ThinkingMode.fromStored(sp.getString(KEY_THINKING_MODE, ThinkingOptions.DEFAULT_MODE.name))
        set(value) { sp.edit().putString(KEY_THINKING_MODE, value.name).commit() }

    /** 每候选时间模式的单候选预算；实际引擎总预算会乘以候选数量。 */
    var perCandidateTimeMs: Int
        get() = sp.getInt(KEY_PER_CANDIDATE_TIME, ThinkingOptions.DEFAULT_PER_CANDIDATE_TIME_MS)
            .coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS)
        set(value) {
            sp.edit().putInt(
                KEY_PER_CANDIDATE_TIME,
                value.coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS)
            ).commit()
        }

    /** 本次分析的己方颜色：true=红方，false=黑方；默认红方。 */
    var mySideRed: Boolean
        get() = sp.getBoolean(KEY_MY_SIDE_RED, true)
        set(value) { sp.edit().putBoolean(KEY_MY_SIDE_RED, value).commit() }

    /** 时间模式最大思考时间，默认3秒；上限 240 秒（旧配置里的 360000 会被夹到 240000）。 */
    var thinkTimeMs: Int
        get() = sp.getInt(KEY_THINK_TIME, ThinkingOptions.DEFAULT_TIME_MS)
            .coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS)
        set(value) {
            sp.edit().putInt(
                KEY_THINK_TIME,
                value.coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS)
            ).apply()
        }

    /**
     * 对方回合的思考时间上限（毫秒）：默认 300ms。
     * 对方走子的这段时间里我方并不需要答案，压低强度可以省电、降温，
     * 把算力留到我方回合。
     */
    var opponentThinkMs: Int
        get() = sp.getInt(KEY_OPP_THINK, 300).coerceIn(0, 10000)
        set(value) { sp.edit().putInt(KEY_OPP_THINK, value.coerceIn(0, 10000)).apply() }

    /** 引擎线程数：0=自动（核心数-1，上限8）；也可手动指定 1..8 */
    var engineThreads: Int
        get() = sp.getInt(KEY_THREADS, 0).coerceIn(0, 8)
        set(value) { sp.edit().putInt(KEY_THREADS, value.coerceIn(0, 8)).apply() }

    /** 引擎 Hash（MB；默认512，与悬浮窗按钮初始档位一致） */
    var hashMb: Int
        get() = sp.getInt(KEY_HASH_MB, DEFAULT_HASH_MB)
        set(value) { sp.edit().putInt(KEY_HASH_MB, value).commit() }

    /** 识别容错：允许候选局面间的少量格差（0=逐格全等，1=容忍单格抖动）。默认 1，显著降低"一直识别不到" */
    var matchTolerance: Int
        get() = sp.getInt(KEY_TOLERANCE, 1).coerceIn(0, 2)
        set(value) { sp.edit().putInt(KEY_TOLERANCE, value.coerceIn(0, 2)).apply() }


    /**
     * 自动走子的"定着等待"时长（毫秒）：建议连续稳定超过该时长才落子，
     * 给用户留出反悔窗口，也避免在建议还在变时误走。默认 1500。
     */
    var autoPlayDelayMs: Int
        get() = sp.getInt(KEY_AUTOPLAY_DELAY, 1500).coerceIn(200, 10000)
        set(value) { sp.edit().putInt(KEY_AUTOPLAY_DELAY, value.coerceIn(200, 10000)).apply() }

    /** 自动走子的落子方式：点击式=先点起点再点终点；拖动式=从起点滑到终点。 */
    var autoPlayGesture: Int
        get() = sp.getInt(KEY_AUTOPLAY_GESTURE, GESTURE_TAP)
            .coerceIn(GESTURE_TAP, GESTURE_SWIPE)
        set(value) {
            sp.edit().putInt(
                KEY_AUTOPLAY_GESTURE,
                value.coerceIn(GESTURE_TAP, GESTURE_SWIPE)
            ).apply()
        }

    /** 一手走完后等待棋盘变化的超时（毫秒）；超时视为落子未生效，回到待命 */
    var autoPlayAckTimeoutMs: Int
        get() = sp.getInt(KEY_AUTOPLAY_ACK, 6000).coerceIn(1500, 20000)
        set(value) { sp.edit().putInt(KEY_AUTOPLAY_ACK, value.coerceIn(1500, 20000)).apply() }

    /** 是否已尝试过用 root 自动开启无障碍（用于界面提示，不做自动重复尝试） */
    var rootAutoEnableTried: Boolean
        get() = sp.getBoolean(KEY_ROOT_TRIED, false)
        set(value) { sp.edit().putBoolean(KEY_ROOT_TRIED, value).apply() }

    companion object {
        /** 落子方式常量；默认 [GESTURE_TAP]，兼容不支持拖动走子的棋类应用。 */
        const val GESTURE_TAP = 0
        const val GESTURE_SWIPE = 1

        private const val KEY_FRAME_W = "frame_w"
        private const val KEY_FRAME_H = "frame_h"
        private const val KEY_CONFIRM = "confirm_count"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_RECT_SET = "overlay_rect_set"
        private const val KEY_RECT_X = "overlay_rect_x"
        private const val KEY_RECT_Y = "overlay_rect_y"
        private const val KEY_RECT_W = "overlay_rect_w"
        private const val KEY_RECT_H = "overlay_rect_h"
        private const val KEY_COLLAPSED = "overlay_collapsed"
        private const val KEY_WORK_MODE = "work_mode"
        private const val KEY_THROTTLE = "capture_throttle_ms"
        private const val KEY_SIM = "sim_enabled"

        /** 工作模式取值（与 [workMode] 对应） */
        const val MODE_GUIDE = 0
        const val MODE_SEMI = 1
        const val MODE_MANUAL = 2
        const val MODE_AUTO = 3
        private const val KEY_BALL_X = "overlay_ball_x"
        private const val KEY_BALL_Y = "overlay_ball_y"
        private const val KEY_BALL_SCALE = "overlay_ball_scale"
        private const val KEY_SEMI_AUTO = "semi_auto"
        private const val KEY_AUTO_ENABLE_ACCESS = "auto_enable_accessibility"
        private const val KEY_AUTO_PLAY = "auto_play"
        private const val KEY_CANDIDATE_COUNT = "candidate_count"
        private const val KEY_SEARCH_DEPTH = "search_depth"

        private const val KEY_THINKING_MODE = "thinking_mode"
        private const val KEY_PER_CANDIDATE_TIME = "per_candidate_time_ms"
        private const val KEY_MY_SIDE_RED = "my_side_red"
        const val DEFAULT_HASH_MB = 512
        private const val KEY_HASH_MB = "hash_mb"
        private const val KEY_THINK_TIME = "think_time_ms"
        private const val KEY_OPP_THINK = "opponent_think_ms"
        private const val KEY_THREADS = "engine_threads"
        private const val KEY_TOLERANCE = "match_tolerance"
        private const val KEY_AUTOPLAY = "auto_play"
        private const val KEY_AUTOPLAY_DELAY = "auto_play_delay_ms"
        private const val KEY_AUTOPLAY_GESTURE = "auto_play_gesture"
        private const val KEY_AUTOPLAY_ACK = "auto_play_ack_ms"
        private const val KEY_ROOT_TRIED = "root_auto_enable_tried"
    }
}
