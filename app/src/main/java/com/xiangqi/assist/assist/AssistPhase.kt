package com.xiangqi.assist.assist

/**
 * 阶段机（**表驱动**）。
 *
 * 设计要点：
 * 1. **规则表** [RULES] 是唯一判定依据。每行 = 「满足条件 → 落在某阶段」，
 *    按顺序求值、第一个命中者胜。要改行为就改表，不再散落在各处的 if 里。
 * 2. 取帧策略也是表（[capture]）：只有"必须读盘才能推进"的阶段才取画面。
 * 3. 每个阶段都有停留上限（[watchdog]），超时由系统自己处置，不会永远卡住。
 *
 * 为什么要表驱动：早先标题、圆环、悬浮球、正文状态各写一套 if 链，
 * 于是出现"标题写着落子中、正文写着未定位"这种自相矛盾的画面；
 * 后来虽然收敛成一个函数，但仍然是深嵌套 when，无法逐条验证、也不好改。
 */
object AssistPhase {

    /** 流程阶段 */
    enum class Phase {
        /** 已暂停（未开始 / 已停止） */
        PAUSED,

        /** 手动摆子 */
        MANUAL,

        /** 引擎异常 */
        ENGINE_ERROR,

        /** 更新棋谱：按用户要求取一帧识别 */
        UPDATING,

        /** 识盘中：还没有可用局面，或引擎未就绪 */
        FINDING,

        /** 计算中：引擎正在搜索 */
        THINKING,

        /** 待落子：算完了、轮到我方，但这一手还没落下去 */
        READY,

        /** 落子中：落子手势正在派发（很短，不读盘，免得与手势打架） */
        MOVING,

        /** 核对中：手势已结束，正在取帧核对这一手到底落上没有 */
        VERIFYING,

        /** 等待对面落子中 */
        WAITING,
    }

    /** 取帧策略 */
    enum class Capture {
        /** 完全不取帧 */
        OFF,

        /** 只保持管线存活（释放缓冲、不处理像素），用户要时才真正取一帧 */
        ON_DEMAND,

        /** 周期性取帧 */
        CONTINUOUS,
    }

    const val AUTO_IDLE = 0
    const val AUTO_CLICKING = 1
    const val AUTO_WAITING = 2

    /**
     * 判定所需的全部信号。用数据类收拢，避免各处零散读字段导致口径不一。
     */
    data class Signals(
        val paused: Boolean = true,
        val manualMode: Boolean = false,
        val engineError: String? = null,
        /** 用户点了「更新棋谱」，还没结束 */
        val refreshActive: Boolean = false,
        /** 自动落子状态：0=待命 1=正在点 2=已发出等待核对 */
        val autoState: Int = AUTO_IDLE,
        /** 自动落子事务真实存在；仅 autoState 数值不能证明发生过手势 */
        val landingTransaction: Boolean = false,
        /** 引擎进程已拉起并完成握手 */
        val engineReady: Boolean = false,
        /** 引擎正在搜索 */
        val searching: Boolean = false,
        /** 当前局面的搜索已有结论（bestmove 已收到） */
        val settled: Boolean = false,
        /** 已有可用局面 */
        val boardPresent: Boolean = false,
        /** 轮到我方走子 */
        val myTurn: Boolean = true,
    )

    /** 规则表的一行 */
    class Rule(val id: String, val phase: Phase, val test: (Signals) -> Boolean)

    /**
     * **规则表**：按顺序求值，第一个命中者胜。
     *
     * 顺序即优先级，两类约束决定了它：
     * - 终止态（手动/暂停/异常）必须最先，否则"暂停了还在动"；
     * - 「落子中」排在「更新棋谱」之前：落子手势已经派发出去了，是**已发生的副作用**，
     *   此时切去识别会与手势打架；
     * - 「更新棋谱」排在引擎忙之前：它是用户显式干预，必须盖过"正在搜索"这类内部状态，
     *   否则引擎一忙按钮就失灵（这正是之前那个 bug）。
     */
    val RULES: List<Rule> = listOf(
        Rule("manual", Phase.MANUAL) { it.manualMode },
        Rule("paused", Phase.PAUSED) { it.paused },
        Rule("engine-error", Phase.ENGINE_ERROR) { it.engineError != null },
        // 手势派发中：不读盘（识别会与手势打架、还会白闪）
        Rule("landing-gesture", Phase.MOVING) {
            it.landingTransaction && it.autoState == AUTO_CLICKING
        },
        // 手势已结束、正在核对：必须同时有真实事务；孤立枚举值不得进入核对
        Rule("landing-verify", Phase.VERIFYING) {
            it.landingTransaction && it.autoState == AUTO_WAITING
        },
        Rule("refresh-requested", Phase.UPDATING) { it.refreshActive },
        Rule("no-position-or-engine", Phase.FINDING) { !it.boardPresent || !it.engineReady },
        // 「对方回合」必须排在「引擎忙」之前，有两个硬理由：
        // 1. 对方回合我们只是做浅算预案，主题是"等对手走子"，显示"计算中"是误导；
        // 2. 这条规则同时决定要不要取帧——若被 engine-busy 抢先，取帧会被关掉，
        //    结果就是**永远看不到对手走了哪一步**。
        Rule("opponent-turn", Phase.WAITING) { !it.myTurn },
        Rule("engine-busy", Phase.THINKING) { it.searching },
        Rule("move-settled", Phase.READY) { it.settled },
        Rule("search-starting", Phase.THINKING) { true },
    )

    fun resolve(s: Signals): Phase = RULES.first { it.test(s) }.phase

    /** 命中的规则名（诊断/测试用） */
    fun matchedRule(s: Signals): String = RULES.first { it.test(s) }.id

    // ==================== 取帧策略 ====================

    /**
     * 这个阶段是否**必须读盘才能推进**。
     *
     * - 识盘中：正在找棋盘，当然要读；
     * - 等待对面落子中：要盯着看对手走了哪一步；
     * - 待落子：**要读**。落子前必须先确认"眼前这个盘面就是建议所依据的盘面"
     *   （否则会照着过期的建议落子），落子后再取一张比对，看这一手有没有落上。
     *
     * 计算中、落子中、暂停、手动、异常：**都不需要读盘**。
     */
    fun needsBoard(phase: Phase): Boolean = when (phase) {
        Phase.FINDING, Phase.WAITING, Phase.READY, Phase.VERIFYING -> true
        else -> false
    }

    /**
     * 取帧策略表。
     *
     * **只有读盘阶段才截屏**：
     * - 计算中不截（画面不会变，截了只是白闪）；
     * - 落子中不截（避免识别与手势打架）；
     * - 待落子要截（落子前确认盘面、落子后核对是否落上）；
     * - 落完子恢复截屏（要盯对手走子）。
     */
    fun capture(phase: Phase, autoMode: Boolean): Capture = when {
        // 用户显式要求一帧：无论什么阶段都取
        phase == Phase.UPDATING -> Capture.ON_DEMAND
        // 不读盘的阶段一律不取
        !needsBoard(phase) -> Capture.OFF
        // 自动模式：持续扫盘；半自动：只让管线活着，用户点按钮才真正处理
        autoMode -> Capture.CONTINUOUS
        else -> Capture.ON_DEMAND
    }

    // ==================== 取帧判定 ====================

    /** 两次处理之间的最小间隔（毫秒），避免同屏重复跑模型 */
    const val MIN_FRAME_INTERVAL_MS = 150L

    /**
     * 这一帧要不要**真正处理像素**（拷贝 + 跑识别）。
     *
     * **与"是否消费缓冲"无关** —— 无论这里返回什么，缓冲都必须取走并关闭。
     * 这两件事必须彻底解耦，否则会出现真实的管线死锁：
     * ImageReader 缓冲池有限（通常 2~3 张），只要有一轮不消费，池子填满后
     * Surface 就停止投递、回调彻底停掉；回调停了以后连"现在需不需要帧"都无从判断，
     * 于是再也收不到画面 —— 表现就是"不管落子还是核对落子一直待定位"，
     * 而且每切换一次阶段就更糟。
     *
     * @param forceProcess 用户点了「更新棋谱」，或处于落子关键阶段（待落子 / 核对中）
     * @param sinceLastFrameMs 距上次真正处理的间隔
     * @param throttleMs 用户设置的抓帧节流；只有周期扫盘的阶段才受它约束
     */
    fun shouldProcessFrame(
        captureMode: Capture,
        forceProcess: Boolean,
        sinceLastFrameMs: Long,
        throttleMs: Long,
    ): Boolean = when {
        // 暂停/手动等明确 OFF 必须压过强制请求；否则启动即暂停仍会偷偷读屏。
        captureMode == Capture.OFF -> false
        // 显式请求或落子关键阶段：立刻处理，不受节流限制
        forceProcess -> true
        // 半自动待机：只保活管线，等用户点按钮
        captureMode == Capture.ON_DEMAND -> false
        // 周期扫盘：受"最小间隔"与用户节流共同约束
        else -> sinceLastFrameMs >= maxOf(MIN_FRAME_INTERVAL_MS, throttleMs)
    }

    // ==================== 文案与配色 ====================

    fun label(p: Phase): String = when (p) {
        Phase.PAUSED -> "已暂停"
        Phase.MANUAL -> "手动摆子"
        Phase.ENGINE_ERROR -> "引擎异常"
        Phase.UPDATING -> "更新棋谱中"
        Phase.FINDING -> "识盘中"
        Phase.THINKING -> "计算中"
        Phase.READY -> "待落子"
        Phase.MOVING -> "落子中"
        Phase.VERIFYING -> "核对落子中"
        Phase.WAITING -> "等待对面落子中"
    }

    fun shortLabel(p: Phase): String = when (p) {
        Phase.PAUSED -> "已暂停"
        Phase.MANUAL -> "手动"
        Phase.ENGINE_ERROR -> "异常"
        Phase.UPDATING -> "更新中"
        Phase.FINDING -> "识盘中"
        Phase.THINKING -> "计算中"
        Phase.READY -> "待落子"
        Phase.MOVING -> "落子中"
        Phase.VERIFYING -> "核对中"
        Phase.WAITING -> "等待对方"
    }

    fun toStage(p: Phase): AssistHud.Stage = when (p) {
        Phase.PAUSED -> AssistHud.Stage.PAUSED
        Phase.MANUAL -> AssistHud.Stage.MANUAL
        Phase.ENGINE_ERROR -> AssistHud.Stage.ENGINE_ERROR
        Phase.UPDATING -> AssistHud.Stage.UPDATING
        Phase.FINDING -> AssistHud.Stage.FINDING
        Phase.THINKING -> AssistHud.Stage.THINKING
        Phase.READY -> AssistHud.Stage.READY
        Phase.MOVING -> AssistHud.Stage.MOVING
        Phase.VERIFYING -> AssistHud.Stage.VERIFYING
        Phase.WAITING -> AssistHud.Stage.WAITING
    }

    /** 识别是否是这一阶段的主任务（决定状态行用"识别："还是别的前缀） */
    fun recognitionIsPrimary(p: Phase): Boolean =
        p == Phase.FINDING || p == Phase.UPDATING

    // ==================== 卡死监督 ====================

    enum class WatchdogAction {
        /** 一切正常 */
        NONE,
        /** 落子阶段超时：复位自动落子状态，允许重新尝试 */
        RELEASE_LANDING,
        /** 更新棋谱超时：结束请求并如实报错 */
        FAIL_REFRESH,
        /** 长时间没识别到画面：丢弃网格缓存，回全屏重找 */
        RESET_GRID,
        /** 引擎长时间未就绪：重启引擎 */
        RESTART_ENGINE,
    }

    /**
     * 监督表：每个阶段的停留上限与处置动作。
     *
     * @param phaseMs 当前阶段已持续的时间
     * @param sinceMapMs 距最近一次成功识别的时间；< 0 表示本轮还没成功过
     */
    fun watchdog(
        phase: Phase,
        phaseMs: Long,
        sinceMapMs: Long,
        engineReady: Boolean,
        engineWarmupMs: Long,
    ): WatchdogAction = when {
        phase == Phase.PAUSED || phase == Phase.MANUAL -> WatchdogAction.NONE
        !engineReady && engineWarmupMs > ENGINE_READY_TIMEOUT_MS -> WatchdogAction.RESTART_ENGINE
        (phase == Phase.MOVING || phase == Phase.VERIFYING) && phaseMs > LANDING_TIMEOUT_MS ->
            WatchdogAction.RELEASE_LANDING
        phase == Phase.UPDATING && phaseMs > REFRESH_TIMEOUT_MS -> WatchdogAction.FAIL_REFRESH
        needsBoard(phase) && sinceMapMs >= 0 && sinceMapMs > NO_MAP_TIMEOUT_MS ->
            WatchdogAction.RESET_GRID
        else -> WatchdogAction.NONE
    }

    /**
     * 画面新鲜度：现有画面还算不算"眼前这一刻"。
     *
     * 关键点是 [phaseSinceAt] 也参与计算：
     * 计算中/落子中我们**故意不取帧**，那段时间画面自然会"变旧"，
     * 如果只按"上次成功识别到现在多久"来判，就会在刚进入待落子时误报"画面未更新"——
     * 这正是用户看到的误报。把"进入本阶段的时刻"也算作基准，
     * 就不会因为"我们按设计没取帧"而误判成故障。
     */
    fun boardFreshEnough(
        now: Long,
        lastMappedAt: Long,
        phaseSinceAt: Long,
        windowMs: Long,
    ): Boolean {
        val base = maxOf(lastMappedAt, phaseSinceAt)
        return now - base <= windowMs
    }

    /** 落子阶段上限：超过就认为这一手没指望了，复位重来 */
    const val LANDING_TIMEOUT_MS = 5_000L

    /** 更新棋谱上限 */
    const val REFRESH_TIMEOUT_MS = 6_000L

    /** 长时间识别不到画面的上限 */
    const val NO_MAP_TIMEOUT_MS = 20_000L

    /** 引擎就绪上限 */
    const val ENGINE_READY_TIMEOUT_MS = 30_000L
}
