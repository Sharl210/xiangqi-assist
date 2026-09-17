package com.xiangqi.assist.assist

/**
 * 引擎与识别共用同一颗 CPU 时的线程/内存分配策略（纯 JVM，可单测）。
 *
 * 只用应用自己就能拿到的信息（`Runtime.availableProcessors()`、Android 的
 * `ActivityManager.memoryClass`），**不**去读 `/proc`、不去探测系统进程。
 *
 * 目标：把更多算力交给引擎搜索，同时给前台游戏和 YOLO 识别留出余量，
 * 避免“全核满载 → 发热降频 → 反而更慢”。
 */
object EngineTuningPolicy {

    /** 用户可选的线程数上限 */
    const val MAX_THREADS = 8
    const val MIN_THREADS = 1

    /** 0 = 自动 */
    const val AUTO = 0

    /** 识别（TFLite）线程数的边界 */
    const val MIN_RECOGNITION_THREADS = 2
    const val MAX_RECOGNITION_THREADS = 4

    /**
     * 自动线程数：核心数减一，至少 1，最多 [MAX_THREADS]。
     *
     * 减一是给前台游戏 + 系统留一个核；比旧的“核心数减二、上限 6”更能吃满中端机的算力，
     * 又不会把 CPU 全部占死。
     */
    fun autoThreads(cores: Int): Int {
        val c = if (cores <= 0) 1 else cores
        return (c - 1).coerceIn(MIN_THREADS, MAX_THREADS)
    }

    /** 把用户设置解析成实际线程数；非法值一律退化为自动策略。 */
    fun resolveThreads(setting: Int, cores: Int): Int =
        if (setting in MIN_THREADS..MAX_THREADS) setting else autoThreads(cores)

    /** 识别线程数：核心数的一半，夹在 [MIN_RECOGNITION_THREADS]..[MAX_RECOGNITION_THREADS]。 */
    fun recognitionThreads(cores: Int): Int {
        val c = if (cores <= 0) 1 else cores
        return (c / 2).coerceIn(MIN_RECOGNITION_THREADS, MAX_RECOGNITION_THREADS)
    }

    /** 面板/设置页显示用的档位文案 */
    fun label(setting: Int): String =
        if (setting == AUTO) "自动" else setting.toString()

    /** 一个按钮循环：自动 → 1 → 2 → … → 8 → 自动 */
    fun nextSetting(current: Int): Int = when {
        current <= AUTO -> MIN_THREADS
        current < MAX_THREADS -> current + 1
        else -> AUTO
    }

    /**
     * Hash 自动上限：按 Android 的内存级别保守取值，避免 NNUE 权重（约 50MB）
     * 加 Hash 把后台的游戏进程挤掉。请求值不超过建议上限时按请求值使用。
     */
    fun resolveHashMb(requestedMb: Int, memoryClassMb: Int): Int {
        val ceiling = when {
            memoryClassMb <= 0 -> requestedMb
            memoryClassMb < 128 -> 128
            memoryClassMb < 256 -> 256
            memoryClassMb < 512 -> 512
            else -> 1024
        }
        return requestedMb.coerceAtMost(ceiling).coerceAtLeast(16)
    }
}
