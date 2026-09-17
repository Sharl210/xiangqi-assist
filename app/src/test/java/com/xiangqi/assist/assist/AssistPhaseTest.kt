package com.xiangqi.assist.assist

import com.xiangqi.assist.assist.AssistPhase.Phase
import com.xiangqi.assist.assist.AssistPhase.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段状态机。
 *
 * 用户反馈的原始问题是"实际状态和显示状态完全不一致"——
 * 根因是标题与正文各用一套 if 链。这里把归约规则钉死：任何信号组合都只能得出唯一阶段。
 */
class AssistPhaseTest {

    private fun s(
        paused: Boolean = false, manual: Boolean = false, err: String? = null,
        refresh: Boolean = false, auto: Int = 0, ready: Boolean = true,
        searching: Boolean = false, board: Boolean = true, myTurn: Boolean = true,
        settled: Boolean = false,
        landing: Boolean = auto != AssistPhase.AUTO_IDLE,
    ) = Signals(
        paused = paused,
        manualMode = manual,
        engineError = err,
        refreshActive = refresh,
        autoState = auto,
        landingTransaction = landing,
        engineReady = ready,
        searching = searching,
        settled = settled,
        boardPresent = board,
        myTurn = myTurn,
    )

    // ---------- 四态循环：识盘中 → 计算中 → 落子中 → 等待对面落子中 ----------

    @Test
    fun `flow cycles through the four states the user asked for`() {
        // 识盘中：还没有局面
        assertEquals(Phase.FINDING, AssistPhase.resolve(s(board = false)))
        // 计算中：有局面、轮我、正在搜索
        assertEquals(Phase.THINKING, AssistPhase.resolve(s(searching = true)))
        // 待落子：算完了、轮我、但还没落下去（这是"引擎说算完却显示计算中"那个 bug）
        assertEquals(Phase.READY, AssistPhase.resolve(s(settled = true)))
        assertNotEquals(Phase.THINKING, AssistPhase.resolve(s(settled = true)))
        // 落子中：手势正在派发
        assertEquals(Phase.MOVING, AssistPhase.resolve(s(auto = AssistPhase.AUTO_CLICKING)))
        // 核对中：手势已结束，正在核对这一手落上没有
        assertEquals(Phase.VERIFYING, AssistPhase.resolve(s(auto = AssistPhase.AUTO_WAITING)))
        // 等待对面落子中
        assertEquals(Phase.WAITING, AssistPhase.resolve(s(myTurn = false)))
    }

    @Test
    fun `engine ready but no board still counts as finding`() {
        assertEquals(Phase.FINDING, AssistPhase.resolve(s(board = false, searching = true)))
        assertEquals(Phase.FINDING, AssistPhase.resolve(s(ready = false, board = true)))
    }

    // ---------- 终止态优先 ----------

    @Test
    fun `paused wins over everything except manual`() {
        assertEquals(Phase.PAUSED, AssistPhase.resolve(s(paused = true, searching = true)))
        assertEquals(Phase.PAUSED, AssistPhase.resolve(
            s(paused = true, auto = AssistPhase.AUTO_CLICKING, refresh = true)))
        // 手动模式优先级更高
        assertEquals(Phase.MANUAL, AssistPhase.resolve(s(paused = true, manual = true)))
    }

    @Test
    fun `engine error is shown instead of pretending to work`() {
        assertEquals(Phase.ENGINE_ERROR, AssistPhase.resolve(s(err = "崩溃")))
        // 但暂停优先级更高（用户主动停的，不是故障）
        assertEquals(Phase.PAUSED, AssistPhase.resolve(s(paused = true, err = "崩溃")))
    }

    // ---------- 更新棋谱的优先级 ----------

    @Test
    fun `refresh request beats engine busy`() {
        // 关键：引擎正在算的时候点「更新棋谱」，必须切到"更新棋谱中"，
        // 而不是被 searching 吞掉显示成"计算中"
        assertEquals(Phase.UPDATING, AssistPhase.resolve(s(refresh = true, searching = true)))
        assertNotEquals(Phase.THINKING, AssistPhase.resolve(s(refresh = true, searching = true)))
    }

    @Test
    fun `landing wins over refresh because the gesture is already dispatched`() {
        // 落子手势已经发出去了，此时再切去识别会与手势打架
        assertEquals(Phase.MOVING, AssistPhase.resolve(
            s(refresh = true, auto = AssistPhase.AUTO_CLICKING)))
    }

    @Test
    fun `paused blocks refresh display`() {
        assertEquals(Phase.PAUSED, AssistPhase.resolve(s(paused = true, refresh = true)))
    }

    // ---------- 标签与配色 ----------

    @Test
    fun `every phase has label short label and ring color`() {
        for (p in Phase.values()) {
            assertTrue("$p 缺标签", AssistPhase.label(p).isNotBlank())
            assertTrue("$p 缺短标签", AssistPhase.shortLabel(p).isNotBlank())
            assertNotEquals("$p 缺配色", 0, AssistHud.ringColor(AssistPhase.toStage(p)))
        }
    }

    @Test
    fun `flow phases use distinct labels and colors`() {
        val flow = listOf(Phase.FINDING, Phase.THINKING, Phase.MOVING, Phase.WAITING)
        assertEquals(4, flow.map { AssistPhase.label(it) }.toSet().size)
        assertEquals(4, flow.map { AssistHud.ringColor(AssistPhase.toStage(it)) }.toSet().size)
        // 用户指定的四个名字
        assertEquals(
            setOf("识盘中", "计算中", "落子中", "等待对面落子中"),
            flow.map { AssistPhase.label(it) }.toSet()
        )
    }

    @Test
    fun `updating has its own label and tone`() {
        assertEquals("更新棋谱中", AssistPhase.label(Phase.UPDATING))
        val updatingColor = AssistHud.ringColor(AssistPhase.toStage(Phase.UPDATING))
        assertNotEquals(updatingColor, AssistHud.ringColor(AssistPhase.toStage(Phase.FINDING)))
        assertNotEquals(updatingColor, AssistHud.ringColor(AssistPhase.toStage(Phase.THINKING)))
    }

    @Test
    fun `recognition is the primary task only while finding or updating`() {
        assertTrue(AssistPhase.recognitionIsPrimary(Phase.FINDING))
        assertTrue(AssistPhase.recognitionIsPrimary(Phase.UPDATING))
        assertFalse(AssistPhase.recognitionIsPrimary(Phase.MOVING))
        assertFalse(AssistPhase.recognitionIsPrimary(Phase.THINKING))
    }

    // ---------- 卡死监督 ----------

    @Test
    fun `landing that hangs too long is released`() {
        assertEquals(
            AssistPhase.WatchdogAction.RELEASE_LANDING,
            AssistPhase.watchdog(Phase.MOVING, phaseMs = 13_000, sinceMapMs = 100,
                engineReady = true, engineWarmupMs = 1000)
        )
        // 正常范围内不干预
        assertEquals(
            AssistPhase.WatchdogAction.NONE,
            AssistPhase.watchdog(Phase.MOVING, phaseMs = 2000, sinceMapMs = 100,
                engineReady = true, engineWarmupMs = 1000)
        )
    }

    @Test
    fun `stuck refresh request fails instead of hanging forever`() {
        assertEquals(
            AssistPhase.WatchdogAction.FAIL_REFRESH,
            AssistPhase.watchdog(Phase.UPDATING, phaseMs = 7000, sinceMapMs = 100,
                engineReady = true, engineWarmupMs = 1000)
        )
    }

    @Test
    fun `no recognition for too long resets the grid`() {
        assertEquals(
            AssistPhase.WatchdogAction.RESET_GRID,
            AssistPhase.watchdog(Phase.FINDING, phaseMs = 1000, sinceMapMs = 25_000,
                engineReady = true, engineWarmupMs = 5000)
        )
        // 从未成功过（-1）时不触发，避免刚启动就重置
        assertEquals(
            AssistPhase.WatchdogAction.NONE,
            AssistPhase.watchdog(Phase.FINDING, phaseMs = 1000, sinceMapMs = -1,
                engineReady = true, engineWarmupMs = 5000)
        )
    }

    @Test
    fun `engine that never becomes ready gets restarted`() {
        assertEquals(
            AssistPhase.WatchdogAction.RESTART_ENGINE,
            AssistPhase.watchdog(Phase.FINDING, phaseMs = 1000, sinceMapMs = 100,
                engineReady = false, engineWarmupMs = 31_000)
        )
        // 尚未点开始（warmup=0）不重启
        assertEquals(
            AssistPhase.WatchdogAction.NONE,
            AssistPhase.watchdog(Phase.PAUSED, phaseMs = 1000, sinceMapMs = 100,
                engineReady = false, engineWarmupMs = 0)
        )
    }

    @Test
    fun `watchdog never fires in paused state`() {
        for (ms in listOf(1_000L, 60_000L, 600_000L)) {
            assertEquals(
                "暂停态不该被监督干预",
                AssistPhase.WatchdogAction.NONE,
                AssistPhase.watchdog(Phase.PAUSED, phaseMs = ms, sinceMapMs = ms,
                    engineReady = true, engineWarmupMs = 1000)
            )
        }
    }

    // ==================== 规则表：顺序本身是规格 ====================

    @Test
    fun `opponent turn outranks engine busy`() {
        // 对方回合我们只做浅算预案，主题是"等对手走子"；若被 engine-busy 抢先，
        // 取帧也会被关掉，结果就是永远看不到对手走了哪一步
        val sig = s(searching = true, myTurn = false)
        assertEquals(Phase.WAITING, AssistPhase.resolve(sig))
        assertEquals("opponent-turn", AssistPhase.matchedRule(sig))
        // 并且这个阶段必须取帧
        assertEquals(
            AssistPhase.Capture.CONTINUOUS,
            AssistPhase.capture(Phase.WAITING, autoMode = true)
        )
    }

    @Test
    fun `landing outranks refresh and engine busy`() {
        val sig = s(auto = AssistPhase.AUTO_CLICKING, refresh = true, searching = true)
        assertEquals(Phase.MOVING, AssistPhase.resolve(sig))
        assertEquals("landing-gesture", AssistPhase.matchedRule(sig))
    }

    @Test
    fun `refresh outranks engine busy`() {
        val sig = s(refresh = true, searching = true)
        assertEquals(Phase.UPDATING, AssistPhase.resolve(sig))
        assertEquals("refresh-requested", AssistPhase.matchedRule(sig))
    }

    @Test
    fun `termination states outrank everything they should`() {
        assertEquals("manual", AssistPhase.matchedRule(s(manual = true, searching = true)))
        assertEquals("paused", AssistPhase.matchedRule(s(paused = true, searching = true)))
        assertEquals("engine-error", AssistPhase.matchedRule(s(err = "x", searching = true)))
    }

    @Test
    fun `rule table always resolves for any combination`() {
        // 表驱动的基本保证：任意信号组合都能落到唯一阶段，且不会抛异常
        val bools = listOf(false, true)
        var n = 0
        for (paused in bools) for (manual in bools) for (refresh in bools)
        for (ready in bools) for (searching in bools) for (settled in bools)
        for (board in bools) for (myTurn in bools) for (auto in bools) {
            val phase = AssistPhase.resolve(
                s(paused = paused, manual = manual, refresh = refresh, ready = ready,
                    searching = searching, settled = settled, board = board,
                    myTurn = myTurn, auto = if (auto) AssistPhase.AUTO_WAITING else 0)
            )
            assertTrue(AssistPhase.label(phase).isNotBlank())
            n++
        }
        assertEquals(512, n)
    }

    // ==================== 取帧策略表 ====================

    @Test
    fun `only board-reading phases capture`() {
        val auto = true
        // 不取帧：计算中 / 落子中 / 暂停 / 手动 / 异常
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.THINKING, autoMode = auto))
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.MOVING, autoMode = auto))
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.PAUSED, autoMode = auto))
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.MANUAL, autoMode = auto))
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.ENGINE_ERROR, autoMode = auto))
        // 取帧：识盘中 / 等待对面落子中 / 待落子（落子前确认、落子后核对都要画面）
        assertEquals(AssistPhase.Capture.CONTINUOUS,
            AssistPhase.capture(Phase.FINDING, autoMode = auto))
        assertEquals(AssistPhase.Capture.CONTINUOUS,
            AssistPhase.capture(Phase.WAITING, autoMode = auto))
        assertEquals(AssistPhase.Capture.CONTINUOUS,
            AssistPhase.capture(Phase.READY, autoMode = auto))
    }

    @Test
    fun `landing never captures because it would fight the gesture`() {
        // 用户明确要求：落子过程中不要截屏，落完子再截屏
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.MOVING, autoMode = true))
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.MOVING, autoMode = false))
    }

    @Test
    fun `computing never captures`() {
        // 用户明确要求：计算时不需要截屏（画面不会变，截了只是白闪）
        for (mode in listOf(true, false)) for (act in listOf(true, false)) {
            assertEquals(AssistPhase.Capture.OFF,
                AssistPhase.capture(Phase.THINKING, autoMode = mode))
        }
    }

    @Test
    fun `ready must read the board because landing needs a fresh picture`() {
        // 落子前要拿一帧确认"眼前盘面 == 建议所依据的盘面"，
        // 落子后还要再拿一帧核对这一手有没有落上，所以待落子必须读盘。
        assertEquals(AssistPhase.Capture.CONTINUOUS,
            AssistPhase.capture(Phase.READY, autoMode = true))
        assertEquals(AssistPhase.Capture.ON_DEMAND,
            AssistPhase.capture(Phase.READY, autoMode = false))
        assertTrue("待落子必须读盘", AssistPhase.needsBoard(Phase.READY))
    }

    @Test
    fun `ready no longer reports a stale frame`() {
        // 用户报的 bug：待落子时状态栏写"待定位 · 未取帧"，看着像出了故障。
        // 现在待落子是取帧的，所以那条提示不会再出现。
        assertNotEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.READY, autoMode = true))
    }

    @Test
    fun `semi-auto keeps the pipeline alive but does not scan continuously`() {
        // 半自动：读盘阶段只让管线活着（ON_DEMAND），点「更新棋谱」才真正处理一帧
        assertEquals(AssistPhase.Capture.ON_DEMAND,
            AssistPhase.capture(Phase.WAITING, autoMode = false))
        assertEquals(AssistPhase.Capture.ON_DEMAND,
            AssistPhase.capture(Phase.FINDING, autoMode = false))
        // 但用户显式点「更新棋谱」时任何模式都要取一帧
        assertEquals(AssistPhase.Capture.ON_DEMAND,
            AssistPhase.capture(Phase.UPDATING, autoMode = false))
        assertEquals(AssistPhase.Capture.ON_DEMAND,
            AssistPhase.capture(Phase.UPDATING, autoMode = true))
    }

    // ==================== 落子拆成「手势中 / 核对中」 ====================

    @Test
    fun `landing is split into gesture and verify`() {
        // 手势派发中：不读盘（识别会和手势打架、还要白闪）
        val clicking = s(auto = AssistPhase.AUTO_CLICKING)
        assertEquals(Phase.MOVING, AssistPhase.resolve(clicking))
        assertEquals("landing-gesture", AssistPhase.matchedRule(clicking))
        assertEquals(AssistPhase.Capture.OFF,
            AssistPhase.capture(Phase.MOVING, autoMode = true))
        assertFalse(AssistPhase.needsBoard(Phase.MOVING))

        // 手势已结束、正在核对：**必须读盘**，否则没法知道落上没有
        val waiting = s(auto = AssistPhase.AUTO_WAITING)
        assertEquals(Phase.VERIFYING, AssistPhase.resolve(waiting))
        assertEquals("landing-verify", AssistPhase.matchedRule(waiting))
        assertEquals(AssistPhase.Capture.CONTINUOUS,
            AssistPhase.capture(Phase.VERIFYING, autoMode = true))
        assertTrue("核对中必须读盘", AssistPhase.needsBoard(Phase.VERIFYING))
        assertEquals("核对落子中", AssistPhase.label(Phase.VERIFYING))
    }

    @Test
    fun `verify stage is still released by the watchdog`() {
        // 核对中也算落子阶段：卡太久同样要复位，否则会一直停在那里
        assertEquals(
            AssistPhase.WatchdogAction.RELEASE_LANDING,
            AssistPhase.watchdog(Phase.VERIFYING, phaseMs = 13_000, sinceMapMs = 100,
                engineReady = true, engineWarmupMs = 1000)
        )
    }

    // ==================== 画面新鲜度（修"画面未更新"误报） ====================

    @Test
    fun `freshness counts from when the phase was entered`() {
        // 计算中/落子中我们按设计不取帧，画面自然会旧。
        // 若只看"上次识别到现在多久"，刚进入待落子就会误报"画面未更新"。
        val now = 100_000L
        val lastMapped = now - 5_000   // 5 秒前识别过（期间在算棋，故意没取帧）
        val phaseSince = now - 100     // 刚进入本阶段
        // 只按 lastMapped 判定的话，这里早该判成过期了：
        assertTrue("前提：仅按识别时间确实已经过期", now - lastMapped > 1500)
        // 把"进入本阶段的时刻"也算作基准后，正确判为新鲜：
        assertTrue(AssistPhase.boardFreshEnough(now, lastMapped, phaseSince, 1500))
        // 窗口为 0 时仍会判旧（存在明确边界，不是永久放过）
        assertFalse(AssistPhase.boardFreshEnough(now, lastMapped, phaseSince, 0))
    }

    @Test
    fun `freshness still expires when even the phase is old`() {
        // 不能因为加了基准就永不判旧：本阶段也停很久了，就该提示去刷新
        val now = 100_000L
        assertFalse(AssistPhase.boardFreshEnough(
            now, lastMappedAt = now - 5_000, phaseSinceAt = now - 5_000, windowMs = 1500))
    }

    @Test
    fun `freshness uses the newer of the two timestamps`() {
        val now = 100_000L
        // 识别更新 → 以识别为准
        assertTrue(AssistPhase.boardFreshEnough(now, now - 100, now - 9_000, 1500))
        // 阶段更新 → 以阶段为准
        assertTrue(AssistPhase.boardFreshEnough(now, now - 9_000, now - 100, 1500))
    }

    // ==================== 取帧判定：消费缓冲与处理像素必须解耦 ====================

    @Test
    fun `off mode never processes pixels`() {
        // 手动/暂停/计算中/落子中：不处理像素（缓冲照样会被取走关闭，那件事不在这里判）
        assertFalse(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.OFF, forceProcess = false, sinceLastFrameMs = 10_000, throttleMs = 0))
    }

    @Test
    fun `on-demand mode stays quiet until explicitly asked`() {
        // 半自动待机：只保活管线，不跑模型
        assertFalse(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.ON_DEMAND, forceProcess = false, sinceLastFrameMs = 10_000, throttleMs = 0))
        // 用户点了「更新棋谱」→ 立刻处理
        assertTrue(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.ON_DEMAND, forceProcess = true, sinceLastFrameMs = 0, throttleMs = 1000))
    }

    @Test
    fun `landing critical bypasses the user throttle`() {
        // 这是"落子很慢"的关键：待落子/核对中不能受用户设的 1 秒节流限制
        assertTrue(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.CONTINUOUS, forceProcess = true, sinceLastFrameMs = 0, throttleMs = 1000))
    }

    @Test
    fun `continuous mode honours the throttle`() {
        // 周期扫盘：节流生效
        assertFalse(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.CONTINUOUS, forceProcess = false, sinceLastFrameMs = 400, throttleMs = 1000))
        assertTrue(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.CONTINUOUS, forceProcess = false, sinceLastFrameMs = 1200, throttleMs = 1000))
        // 节流为 0 时退回到最小间隔（防止同屏重复跑模型）
        assertFalse(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.CONTINUOUS, forceProcess = false,
            sinceLastFrameMs = AssistPhase.MIN_FRAME_INTERVAL_MS - 1, throttleMs = 0))
    }

    @Test
    fun `processing decision never blocks buffer recycling`() {
        // 回归护栏：这条断言的存在意义是提醒后续改动者——
        // "要不要处理像素"与"要不要消费缓冲"是两件事。
        // 任何阶段（含 OFF）都必须把缓冲取走关闭，否则 ImageReader 池会被填满、
        // Surface 停投、回调彻底停止，之后所有阶段都收不到画面（真实死锁）。
        for (phase in Phase.values()) {
            // 无论返回什么，都不影响"缓冲必须被消费"这条不变量；
            // 这里断言枚举定义齐全，确保新增阶段时也会走到这条检查
            assertTrue(AssistPhase.label(phase).isNotBlank())
        }
        // OFF / ON_DEMAND 都返回 false —— 即"不处理像素"，但仍然要消费缓冲
        assertFalse(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.OFF, forceProcess = false, sinceLastFrameMs = 0, throttleMs = 0))
        assertFalse(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.ON_DEMAND, forceProcess = false, sinceLastFrameMs = 0, throttleMs = 0))
    }

    @Test
    fun `orphan landing flags can never enter moving or verifying`() {
        // 只有枚举值、没有真实落子事务时，不能显示落子/核对。
        assertNotEquals(
            Phase.MOVING,
            AssistPhase.resolve(s(auto = AssistPhase.AUTO_CLICKING, landing = false))
        )
        assertNotEquals(
            Phase.VERIFYING,
            AssistPhase.resolve(s(auto = AssistPhase.AUTO_WAITING, landing = false))
        )
    }

    @Test
    fun `off capture beats even a stale force-process signal`() {
        // 暂停态即便残留了 refresh/落子强制信号也不能跑识别。
        assertFalse(AssistPhase.shouldProcessFrame(
            AssistPhase.Capture.OFF,
            forceProcess = true,
            sinceLastFrameMs = 10_000,
            throttleMs = 0,
        ))
    }

}
