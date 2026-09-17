package com.xiangqi.assist.assist

import android.content.Context
import android.util.Log
import org.petero.droidfish.engine.EngineConfig
import org.petero.droidfish.engine.UCIEngine
import org.petero.droidfish.engine.UCIEngineBase
import org.petero.droidfish.player.EngineListener

import java.util.concurrent.Executors

/**
 * 分析引擎：直接驱动一个独立 pikafish UCI 进程（与游戏内引擎进程互不干扰）。
 * 线程模型：单一串行线程执行"初始化/搜索"，保证同一时刻只有一个 position+go 在跑；
 * 收到 bestmove 或 search 超时后自动空闲，等待下一个局面。
 */
class AnalysisEngine(
    private val context: Context,
    private val listener: Listener,
    searchDepth: Int = 20,
    private val maxPv: Int = 3,
) {
    interface Listener {
        fun onEngineReady()
        fun onSearchUpdate(result: AnalysisResult)
        fun onSearchDone(result: AnalysisResult)
        fun onEngineError(message: String)
        /** 引擎因非法局面退出后已自动重启（默认不处理） */
        fun onEngineRestarted(reason: String) {}
    }

    data class ActivitySnapshot(
        val engineReady: Boolean,
        val processAlive: Boolean,
        val engineBroken: Boolean,
        val activeJobId: Long?,
        val pendingJobId: Long?,
        val controllerAlive: Boolean,
        val controllerHeartbeatAt: Long,
        val lastOutputAt: Long,
        val processCpuTimeMs: Long,
    )

    private val tag = "AnalysisEngine"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "analysis-engine").apply { isDaemon = true }
    }
    private val engineListener = object : EngineListener {
        override fun reportEngineError(errMsg: String?) {
            engineBroken = true
            touchControllerHeartbeat()
            Log.w(tag, "engine error: $errMsg")
            listener.onEngineError(errMsg ?: "引擎错误")
        }
        override fun notifyEngineName(engineName: String?) {}
        override fun notifySearchResult(searchId: Int, bestMove: String?, nextPonderMove: String?) {}
        override fun notifyEvalResult(searchId: Int, eval: Float) {}
        override fun notifyEngineInitialized() {}
    }

    @Volatile private var engine: UCIEngine? = null
    @Volatile private var ready = false
    @Volatile private var started = false

    private data class SearchJob(
        val id: Long,
        val fen: String,
        val budget: AnalysisBudget,
        val cancellationEpoch: Long,
    )

    @Volatile private var pendingJob: SearchJob? = null
    /** 当前已经取出并正在搜索的不可变任务。 */
    @Volatile private var activeJob: SearchJob? = null
    private var nextAnalysisId = 0L
    private val lock = Object()

    /** 搜索深度（与对弈"固定深度"同制式），可热更新，对下一次搜索生效 */
    @Volatile private var searchDepth: Int = AssistDepth.clamp(searchDepth)

    /** 引擎 Hash 默认值与应用设置一致。 */
    @Volatile private var hashMb: Int = AssistConfig.DEFAULT_HASH_MB
    @Volatile private var appliedHash = -1

    /** 引擎线程数（0=自动：核心数-1，上限 8） */
    @Volatile private var threads: Int = defaultThreads()
    @Volatile private var appliedThreads = -1

    /** 主变条数：同一次搜索内评估的候选数；共享一份总时间/深度预算。 */
    @Volatile private var multiPv: Int = maxPv.coerceIn(
        ThinkingOptions.MIN_CANDIDATE_COUNT, ThinkingOptions.MAX_CANDIDATE_COUNT
    )
    @Volatile private var appliedMultiPv = -1

    /** 每手总思考时间上限（毫秒）；MultiPV所有路径共享这一份总预算。 */
    @Volatile private var thinkTimeMs: Int = ThinkingOptions.DEFAULT_TIME_MS

    /** 热更新搜索深度（引擎强度档位），对下一次搜索生效 */
    fun setSearchDepth(depth: Int) {
        searchDepth = AssistDepth.clamp(depth)
    }

    /** 热更新 Hash（面板"Hash"按钮）：只在引擎空闲的两次搜索之间下发 setoption */
    fun setHash(mb: Int) {
        hashMb = mb
    }

    /** 热更新线程数（1..8）：只在引擎空闲的两次搜索之间下发 setoption */
    fun setThreads(n: Int) {
        threads = n.coerceIn(EngineTuningPolicy.MIN_THREADS, EngineTuningPolicy.MAX_THREADS)
    }

    /** 设置同一次搜索内需要比较的候选数；不会为每条候选单独重启计时。 */
    fun setMultiPv(n: Int) {
        multiPv = n.coerceIn(
            ThinkingOptions.MIN_CANDIDATE_COUNT, ThinkingOptions.MAX_CANDIDATE_COUNT
        )
    }

    /** 热更新每手思考时间上限（毫秒）；上限 240 秒 */
    fun setThinkTime(ms: Int) {
        thinkTimeMs = ms.coerceIn(ThinkingOptions.MIN_TIME_MS, ThinkingOptions.MAX_TIME_MS)
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        launchController()
    }

    /**
     * 启动或重新拉起唯一的引擎控制循环。
     * 搜索线程异常退出时也从这里重建，避免只设置一个“需要重启”标志却没有线程接手。
     */
    private fun launchController() {
        synchronized(lock) {
            if (shuttingDown || controllerLoopActive || controllerLaunchScheduled) return
            controllerLaunchScheduled = true
        }
        executor.execute {
            controllerLaunchScheduled = false
            controllerThread = Thread.currentThread()
            controllerLoopActive = true
            var attempt = 0
            try {
                while (!shuttingDown) {
                    try {
                        doStart()
                        if (!shuttingDown) {
                            throw IllegalStateException("引擎控制循环意外结束")
                        }
                    } catch (e: Throwable) {
                        ready = false
                        engineBroken = true
                        synchronized(lock) {
                            pendingJob = null
                            activeJob = null
                            cancellationEpoch++
                        }
                        Log.e(tag, "engine controller stopped (attempt ${attempt + 1})", e)
                        if (shuttingDown) break
                        attempt++
                        listener.onEngineError(
                            "引擎控制链异常：${e.message ?: e.javaClass.simpleName}，正在自动恢复"
                        )
                        runCatching { engine?.shutDown() }
                        engine = null
                        val delayMs = (300L shl attempt.coerceAtMost(4)).coerceAtMost(5_000L)
                        try {
                            Thread.sleep(delayMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        engineBroken = false
                    }
                }
            } finally {
                controllerLoopActive = false
                controllerThread = null
            }
        }
    }

    private fun doStart() {
        val ec = EngineConfig().apply { workDir = context.filesDir.absolutePath }
        val eng: UCIEngine = UCIEngineBase.getEngine("pikafish", ec, engineListener)
            ?: throw IllegalStateException("无法创建 pikafish 引擎")
        engine = eng
        eng.initialize()

        // pikafish 只在收到 "uci" 后才输出 uciok（与游戏内 ComputerPlayer 的握手一致）
        eng.writeLineToEngine("uci")

        // 初始化握手：读 uciok，注册所有 option 行
        var uciok = false
        var elapsed = 0L
        val dl = 200L
        while (!uciok && elapsed < 10000) {
            // null = 引擎输出流已关闭（进程退出）：立即失败，不再空等超时
            val line = eng.readLineFromEngine(dl.toInt()) ?: throw IllegalStateException("引擎进程意外退出")
            elapsed += dl
            if (line.isEmpty()) continue
            val tokens = line.trim().split(Regex("\\s+"))
            when {
                tokens[0] == "uciok" -> uciok = true
                tokens[0] == "id" || tokens[0] == "option" -> eng.registerOption(tokens.toTypedArray())
            }
        }
        if (!uciok) throw IllegalStateException("引擎握手超时（uciok），多为 CPU/兼容性问题")

        // 与游戏内 ComputerPlayer 一致：uciok 后必须调用 initConfig 标记配置完成，
        // 否则 ExternalEngine 的启动看门狗会在 10 秒后误报 "UCI protocol error"
        eng.initConfig(ec)

        // 配置：权重/线程/Hash/多路PV（与对弈 setOptimizedThreads/applyEngineSetting 同口径）
        eng.setOption("EvalFile", "libpikafish.nnue.so")
        // 线程数：**不占满全部核心**。
        // 占满会同时踩两个坑：(1) 触发手机热节流，主频掉下来后实际算力反而更差；
        // (2) 抢走前台游戏 App 与 YOLO 识别的 CPU，画面卡、识别慢。
        // 留出余量后长时间对局的综合棋力更好。
        eng.setOption("Threads", threads)
        eng.setOption("Hash", hashMb)
        // MultiPV 数量由用户档位决定，所有候选仍在同一个 go 会话中共享搜索；
        // 不为每条候选启动独立引擎。
        eng.setOption("MultiPV", multiPv)
        // 让引擎连"胜/和/负的期望比例"一起报出来（UCI_ShowWDL）。
        // 有了它，界面上的胜率就直接用引擎自己算的数，不必由我们换算。
        eng.setOption("UCI_ShowWDL", true)
        appliedHash = hashMb
        appliedThreads = threads
        appliedMultiPv = multiPv

        // 与游戏内流程一致：新对局标记 + 就绪握手（显式发送 isready，引擎才会回复 readyok）
        eng.writeLineToEngine("ucinewgame")
        eng.writeLineToEngine("isready")
        var readyOk = false
        elapsed = 0L
        while (!readyOk && elapsed < 15000) {
            val line = eng.readLineFromEngine(dl.toInt()) ?: throw IllegalStateException("引擎进程意外退出")
            elapsed += dl
            if (line.trim() == "readyok") readyOk = true
        }
        if (!readyOk) throw IllegalStateException("引擎就绪握手失败（readyok）")
        ready = true
        engineBroken = false
        lastOutputAt = 0L
        touchControllerHeartbeat()
        Log.i(tag, "analysis engine ready")
        listener.onEngineReady()

        // 进入搜索循环：等待 request() 投递局面，无任务时休眠。
        // 注意：不做"同局面跳过"——重复局面（对局来回换子/悔棋重发）必须重新搜索，
        // 否则会永远停在旧结果上（表现为"卡在预测那一步、停止对我方指导"）。
        while (!shuttingDown) {
            touchControllerHeartbeat()
            if (engineBroken) {
                // 由搜索控制器或外部看门狗标记的故障交给外层唯一恢复循环处理；
                // 不在 doStart() 内递归调用自己，避免恢复线程越积越深。
                Log.w(tag, "engine controller marked broken; leave for outer recovery")
                return
            }
            val job = synchronized(lock) {
                val queued = pendingJob
                if (queued != null) {
                    pendingJob = null
                    activeJob = queued
                }
                queued
            }
            if (job == null) {
                synchronized(lock) {
                    try { lock.wait(300) } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                continue
            }
            try {
                searchOnce(job)
            } catch (t: Throwable) {
                engineBroken = true
                touchControllerHeartbeat()
                Log.e(tag, "search controller failed; engine will recover", t)
                listener.onEngineError("搜索控制线程异常：${t.javaClass.simpleName}")
            } finally {
                if (activeJob?.id == job.id) activeJob = null
            }
        }
    }

    @Volatile private var shuttingDown = false
    /**
     * 引擎进程需要重建（崩溃 / 遇到非法局面被强制终止）。
     *
     * 皮卡鱼 2026 版起，遇到它判定为非法的局面会打印 CRITICAL ERROR 并**直接退出进程**，
     * 这是新行为；老版本会照常应付过去。所以必须能自己发现并拉起来，
     * 否则用户看到的就是"识别着识别着引擎就不动了"。
     */
    @Volatile private var engineBroken = false
    /** stopSearch/request 新任务与引擎读取循环之间的无锁取消信号。 */
    @Volatile private var interruptRequested = false
    @Volatile private var cancellationEpoch = 0L
    @Volatile private var controllerLoopActive = false
    @Volatile private var controllerLaunchScheduled = false
    @Volatile private var controllerThread: Thread? = null
    /** 控制线程活性：每次轮询UCI管道都会更新；不依赖info/PV输出。 */
    @Volatile private var controllerHeartbeatAt = 0L
    /** 最近收到任意UCI输出的时刻，仅用于诊断，不单独触发恢复。 */
    @Volatile private var lastOutputAt = 0L

    private fun touchControllerHeartbeat() {
        controllerHeartbeatAt = System.currentTimeMillis()
    }

    fun activitySnapshot(includeProcessCpu: Boolean = false): ActivitySnapshot {
        val current = engine
        val processAlive = ready && current != null && runCatching { current.isProcessAlive() }.getOrDefault(false)
        return ActivitySnapshot(
            engineReady = ready,
            processAlive = processAlive,
            engineBroken = engineBroken,
            activeJobId = activeJob?.id,
            pendingJobId = pendingJob?.id,
            controllerAlive = controllerLoopActive,
            controllerHeartbeatAt = controllerHeartbeatAt,
            lastOutputAt = lastOutputAt,
            processCpuTimeMs = if (includeProcessCpu) {
                current?.let { runCatching { it.processCpuTimeMs() }.getOrDefault(-1L) } ?: -1L
            } else {
                -1L
            },
        )
    }

    companion object {
        /** 时间模式总预算之外只允许的协议传输抖动。 */
        const val TOTAL_TIME_GRACE_MS = 100L

        /** 允许的最大线程数（再多收益递减且更容易触发降频） */
        const val MAX_THREADS = EngineTuningPolicy.MAX_THREADS

        /**
         * 默认线程数：核心数减 1，至少 1，最多 8。
         * 比旧的"核心数减 2、上限 6"更能吃满中端机的算力；留一个核给前台游戏、
         * 识别和系统，避免"全核满载 → 发热降频 → 反而更慢"。
         */
        fun defaultThreads(): Int =
            EngineTuningPolicy.autoThreads(Runtime.getRuntime().availableProcessors())
    }

    /** 阻塞执行一个不可变搜索任务直到 bestmove 或总时间截止。 */
    private fun searchOnce(job: SearchJob) {
        val eng = engine ?: return
        touchControllerHeartbeat()
        // 新任务已经成为唯一有效任务；清掉上一任务留下的 stop 信号。
        if (job.cancellationEpoch == cancellationEpoch) interruptRequested = false
        val fen = job.fen
        val budget = job.budget
        val lines = LinkedHashMap<Int, MutableAnalysisLine>()
        // Hash / 线程 / 主变变更：仅在两次搜索之间下发（UCI 规范不建议搜索中 setoption）
        if (appliedHash != hashMb) {
            eng.setOption("Hash", hashMb)
            appliedHash = hashMb
        }
        if (appliedThreads != threads) {
            eng.setOption("Threads", threads)
            appliedThreads = threads
        }
        if (appliedMultiPv != budget.candidateCount) {
            eng.setOption("MultiPV", budget.candidateCount)
            appliedMultiPv = budget.candidateCount
        }
        eng.writeLineToEngine("position fen $fen")
        // 只发一次 go：全部 MultiPV 候选共享这一个总预算和截止时间。
        val timeMs = budget.totalTimeMs
        eng.writeLineToEngine(budget.goCommand())

        // 时间模式的截止点属于整个搜索；只留 100ms 传输抖动，随后立刻 stop。
        // 不能再额外放宽 10 秒，否则“固定总时间”在异常引擎上会名不副实。
        val deadline = if (timeMs > 0) {
            System.currentTimeMillis() + timeMs + TOTAL_TIME_GRACE_MS
        } else {
            Long.MAX_VALUE
        }
        // bestmove 行标志结束；或因新周期 stop 导致 bestmove 立即返回
        while (System.currentTimeMillis() < deadline) {
            touchControllerHeartbeat()
            val remaining = deadline - System.currentTimeMillis()
            val readWait = if (deadline == Long.MAX_VALUE) 250 else remaining.coerceIn(1L, 250L).toInt()
        val superseded = job.cancellationEpoch != cancellationEpoch
        if (superseded || interruptRequested || engineBroken || shuttingDown) {
            // stopSearch() 在别的线程直接写 stop；这里补一次最多1.5秒的协议排空，
            // 防止旧 bestmove 残留在 stdout 中被下一任务误认成新任务的结果。
            runCatching { eng.writeLineToEngine("stop") }
            if (!shuttingDown && !engineBroken) {
                if (!drainUntilBestMove(1_500L)) {
                    // 旧搜索没有给出协议终点，继续复用同一 stdout 会把迟到的
                    // bestmove 当成新局面结果；宁可重启引擎，也不允许串任务。
                    engineBroken = true
                    Log.w(tag, "interrupted search did not terminate cleanly; restart engine")
                }
            }
            return
        }
            // null = 引擎输出流已关闭（进程退出/关停）
            val line = eng.readLineFromEngine(readWait)
            touchControllerHeartbeat()
            if (!line.isNullOrEmpty()) lastOutputAt = System.currentTimeMillis()
            if (line == null) {
                if (!shuttingDown) {
                    Log.w(tag, "engine stdout closed -> mark broken for recovery")
                    engineBroken = true
                }
                return
            }
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.isEmpty()) continue
            // 新版引擎对非法局面会 CRITICAL ERROR 后自杀：记下来交给外层重启
            if (line.contains("CRITICAL ERROR")) {
                Log.e(tag, "engine rejected position: ${line.trim()}")
                engineBroken = true
                return
            }
            if (tokens[0] == "info") {
                // 引擎自述行（如 NNUE 加载信息）转发到 logcat，便于远程诊断棋力问题
                if (tokens.contains("string")) Log.i(tag, "engine: ${line.trim()}")
                parseInfo(tokens, multiPvOf(tokens, lines))?.let { ml ->
                    lines[ml.multiPv] = ml
                    val redGo = try {
                        fen.split(" ")[1] != "b"
                    } catch (e: Exception) { true }
                    val res = AnalysisResult(
                        fen, redGo, null, toRedPerspective(lines.values, redGo), job.id
                    )
                    listener.onSearchUpdate(res)
                }
            } else if (tokens[0] == "bestmove") {
                val bm = if (tokens.size > 1) tokens[1] else null
                val redGo = try { fen.split(" ")[1] != "b" } catch (e: Exception) { true }
                val res = AnalysisResult(
                    fen, redGo, bm, toRedPerspective(lines.values, redGo), job.id
                )
                listener.onSearchDone(res)
                return
            }
        }
        // 超过用户给定总预算仍未收到 bestmove：只给引擎很短的协议收尾时间。
        // 这个收尾时间不重新给任何候选分配搜索预算。
        eng.writeLineToEngine("stop")
        val dl2 = System.currentTimeMillis() + TOTAL_TIME_GRACE_MS
        while (System.currentTimeMillis() < dl2) {
            if (interruptRequested || engineBroken || shuttingDown) return
            val remaining = dl2 - System.currentTimeMillis()
            val line = eng.readLineFromEngine(remaining.coerceIn(1L, 100L).toInt())
            touchControllerHeartbeat()
            if (!line.isNullOrEmpty()) lastOutputAt = System.currentTimeMillis()
            if (line == null) {
                if (!shuttingDown) engineBroken = true
                return
            }
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.isEmpty()) continue
            if (tokens[0] == "bestmove") {
                val bm = if (tokens.size > 1) tokens[1] else null
                val redGo = try { fen.split(" ")[1] != "b" } catch (e: Exception) { true }
                listener.onSearchDone(AnalysisResult(
                    fen, redGo, bm, toRedPerspective(lines.values, redGo), job.id
                ))
                return
            }
        }
        engineBroken = true
        Log.w(tag, "search budget ended without bestmove; restarting engine to clear protocol state")
    }

    /** 丢弃被打断搜索的协议尾巴，不向服务层发布结果。 */
    private fun drainUntilBestMove(timeoutMs: Long): Boolean {
        val eng = engine ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = runCatching {
                eng.readLineFromEngine((deadline - System.currentTimeMillis()).coerceIn(1L, 100L).toInt())
            }.getOrNull() ?: return false
            if (line.trim().startsWith("bestmove")) return true
        }
        return false
    }

    /**
     * 一个 MultiPV 行的累计状态。
     *
     * 关键点：**新一条 info 只覆盖它实际提供的字段**。引擎在浅层往往只报 score/pv，
     * 到一定深度才开始报 `wdl`；如果每次都整体替换，就会出现"胜率闪一下就消失"。
     * 这里保留同一行上一条有效的 WDL，直到本行给出新值。
     */
    private class MutableAnalysisLine(val multiPv: Int) {
        var depth = 0

        /** 引擎原始分数（**当前走子方**视角，centipawn） */
        var scoreCp = 0

        /** 引擎原始杀棋步数（当前走子方视角） */
        var mateIn: Int? = null
        var pv: List<String> = emptyList()

        /** 引擎原始 WDL（win/draw/loss 千分比，当前走子方视角） */
        var wdl: Triple<Int, Int, Int>? = null

        /**
         * 换算成**红方视角**。
         *
         * UCI 协议规定 `score` 是"走子方"视角的：轮到黑走时，红方占优会报成负数。
         * 直接把它当成红方分数是错的——表现就是"轮到对方走时，胜率跟着翻到对面去"。
         * 这里按走子方翻转符号，之后所有展示层拿到的都是统一的红方视角。
         */
        fun toAnalysisLine(redGo: Boolean): AnalysisLine {
            val cp = if (redGo) scoreCp else -scoreCp
            val mate = mateIn?.let { if (redGo) it else -it }
            // WDL 同理：走子方是黑方时，引擎报的"胜"是黑方胜，对红方而言是"负"
            val wdlRed = wdl?.let { AssistHud.toRedPerspectiveWdl(it.first, it.second, it.third, redGo) }
            return AnalysisLine(
                multiPv, depth, cp, mate, pv,
                wdlRed?.first, wdlRed?.second, wdlRed?.third
            )
        }
    }

    /** 把当前搜索收到的各行统一换算到红方视角（红先=不翻，黑先=整体取反） */
    private fun toRedPerspective(
        lines: Collection<MutableAnalysisLine>,
        redGo: Boolean,
    ): List<AnalysisLine> = lines.sortedBy { it.multiPv }.map { it.toAnalysisLine(redGo) }

    /**
     * 从一条 info 行里先读出 multipv 号，用来取回该行已累计的数据（行合并）。
     * 找不到时按第 1 条主变处理。
     */
    private fun multiPvOf(
        tokens: List<String>,
        lines: Map<Int, MutableAnalysisLine>,
    ): MutableAnalysisLine? {
        var i = 1
        while (i < tokens.size - 1) {
            if (tokens[i] == "multipv") {
                val mp = tokens[i + 1].toIntOrNull() ?: 1
                return lines[mp]
            }
            i++
        }
        return lines[1]
    }

    /**
     * 解析一条 info，并与该 MultiPV 行已有的数据合并。
     *
     * @param existing 同一 multiPv 行已经累积的数据（可能来自更浅的深度）
     */
    private fun parseInfo(tokens: List<String>, existing: MutableAnalysisLine? = null): MutableAnalysisLine? {
        var multiPv = 1
        var depth = 0
        var scoreCp: Int? = null
        var mateIn: Int? = null
        var pv: List<String> = emptyList()
        var wdl: Triple<Int, Int, Int>? = null
        var i = 1
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> if (i + 1 < tokens.size) { depth = tokens[i + 1].toIntOrNull() ?: depth; i++ }
                "multipv" -> if (i + 1 < tokens.size) { multiPv = tokens[i + 1].toIntOrNull() ?: multiPv; i++ }
                "score" -> {
                    if (i + 2 < tokens.size) {
                        when (tokens[i + 1]) {
                            "cp" -> { scoreCp = tokens[i + 2].toIntOrNull() ?: 0; i += 2 }
                            "mate" -> { mateIn = tokens[i + 2].toIntOrNull(); scoreCp = 0; i += 2 }
                        }
                    }
                }
                "wdl" -> {
                    if (i + 3 < tokens.size) {
                        val w = tokens[i + 1].toIntOrNull()
                        val d = tokens[i + 2].toIntOrNull()
                        val l = tokens[i + 3].toIntOrNull()
                        if (w != null && d != null && l != null) wdl = Triple(w, d, l)
                        i += 3
                    }
                }
                "pv" -> { pv = tokens.subList(i + 1, tokens.size).filter { it.length == 4 }; i = tokens.size }
                "currmove" -> i = tokens.size // 不需要
            }
            i++
        }
        if (scoreCp == null && mateIn == null && wdl == null && pv.isEmpty()) return null
        // 同一 MultiPV 行合并：新 info 没给的字段沿用旧值，不把有效 WDL/分数清空。
        // 只在同一行内部合并（每次搜索都会新建 lines，因此不会跨局面串数据）。
        val base = existing?.takeIf { it.multiPv == multiPv }
        return MutableAnalysisLine(multiPv).apply {
            this.depth = if (base != null) maxOf(depth, base.depth) else depth
            this.scoreCp = scoreCp ?: base?.scoreCp ?: 0
            this.mateIn = mateIn ?: if (scoreCp == null) base?.mateIn else null
            this.pv = if (pv.isNotEmpty()) pv else (base?.pv ?: emptyList())
            this.wdl = wdl ?: base?.wdl
        }
    }

    /**
     * 请求分析新局面。若引擎正忙于旧局面会先 stop（旧搜索的 bestmove 会很快返回并丢弃，旧结果由调用方按 FEN 比对丢弃）。
     * 调用线程任意；实际执行在串行引擎线程。
     */
    /**
     * 请求引擎重启（外部监督发现"迟迟未就绪"时调用）。
     * 走的是与"遇到非法局面自杀"同一条自愈路径。
     */
    fun requestRestart(reason: String) {
        Log.w(tag, "restart requested: $reason")
        interruptRequested = true
        engineBroken = true
        synchronized(lock) { lock.notifyAll() }
        if (!controllerLoopActive && !shuttingDown) {
            ready = false
            runCatching { engine?.shutDown() }
            engine = null
            launchController()
        }
    }

    /**
     * 请求一个不可变分析任务并返回会话编号。
     *
     * [budget.totalTimeMs] 属于整个 MultiPV 搜索：只发一次 `go`，不是每条候选各发一次。
     * 相同 FEN + 相同预算已经排队/运行时直接复用其编号，不重启计时。
     */
    @Synchronized
    fun request(fen: String, budget: AnalysisBudget): Long {
        val normalized = budget.normalized()
        var shouldStop = false
        val job = synchronized(lock) {
            pendingJob?.takeIf { it.fen == fen && it.budget == normalized }?.let { return it.id }
            activeJob?.takeIf { it.fen == fen && it.budget == normalized }?.let { return it.id }
            val created = SearchJob(++nextAnalysisId, fen, normalized, cancellationEpoch)
            pendingJob = created
            shouldStop = activeJob != null
            interruptRequested = shouldStop
            touchControllerHeartbeat()
            created
        }
        if (shouldStop && ready) {
            // 只在棋面或预算真的变化时打断；相同任务上面已经复用。
            interruptRequested = true
            runCatching { engine?.writeLineToEngine("stop") }
        }
        synchronized(lock) { lock.notifyAll() }
        return job.id
    }

    /** 兼容调用：根据深度/时间构建一份总预算。 */
    fun request(fen: String, maxDepth: Int? = searchDepth, timeMs: Int = thinkTimeMs): Long {
        val budget = AnalysisBudget(
            maxDepth = maxDepth,
            totalTimeMs = timeMs.coerceIn(0, ThinkingOptions.MAX_TIME_MS),
            candidateCount = multiPv,
        ).normalized()
        return request(fen, budget)
    }

    /**
     * 立即打断当前搜索。不能把 stop 排到同一个阻塞搜索 executor 后面，
     * 否则它只能等搜索自己结束后才执行，暂停/重置看起来就会卡死。
     */
    fun stopSearch() {
        synchronized(lock) {
            pendingJob = null
            activeJob = null
            cancellationEpoch++
        }
        interruptRequested = true
        runCatching { engine?.writeLineToEngine("stop") }
    }

    fun shutdown() {
        shuttingDown = true
        interruptRequested = true
        ready = false
        synchronized(lock) { lock.notifyAll() }
        try {
            engine?.shutDown()
        } catch (e: Exception) {
            Log.w(tag, "shutDown failed", e)
        }
        engine = null
        controllerThread?.interrupt()
        executor.shutdownNow()
    }
}
