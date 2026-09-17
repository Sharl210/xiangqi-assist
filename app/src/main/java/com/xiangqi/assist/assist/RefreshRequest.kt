package com.xiangqi.assist.assist

/**
 * 「更新棋谱」请求的状态机（纯 JVM，可单测）。
 *
 * 上一版这个按钮"完全没用"，原因是一堆隐式条件各管一段：
 * 取帧节流、识别节流、取帧需求（CaptureDemand）的切换、请求标志被谁清掉……
 * 任何一处不满足，请求就被**静默吞掉**，界面上没有任何反馈。
 *
 * 现在做成一个显式的小状态机：
 * - 请求进去就有明确状态（等待取帧 → 已取到帧 → 成功 / 失败）；
 * - **优先级最高**：只要处于等待状态，取帧与识别都必须无视自己的节流让路；
 * - 有重试次数与截止时间，超时一定给出失败原因，而不是无声无息。
 */
class RefreshRequest(maxAttempts: Int = 3, timeoutMs: Long = 6_000L) {

    enum class State {
        /** 没有请求 */
        IDLE,
        /** 已请求，等待取到一帧可用画面 */
        WAITING_FRAME,
        /** 已取到帧并成功识别、应用到棋面 */
        SUCCEEDED,
        /** 用尽重试或超时 */
        FAILED,
    }

    private val maxAttempts = maxAttempts.coerceAtLeast(1)
    private val timeoutMs = timeoutMs

    var state: State = State.IDLE
        private set

    /** 已经用掉的帧数（每处理一帧 +1） */
    var attempts: Int = 0
        private set

    /** 失败原因，供界面如实显示 */
    var failReason: String? = null
        private set

    private var startedAt: Long = 0L
    private var lastNote: String? = null

    /** 最近一次的处理说明（用于状态行显示"正在干什么"） */
    val note: String? get() = lastNote

    /** 请求是否还活着（活着时取帧/识别必须让路） */
    val isActive: Boolean get() = state == State.WAITING_FRAME

    fun begin(now: Long) {
        state = State.WAITING_FRAME
        attempts = 0
        failReason = null
        lastNote = "正在取画面…"
        startedAt = now
    }

    fun cancel() {
        state = State.IDLE
        attempts = 0
        failReason = null
        lastNote = null
    }

    /** 取帧侧调用：本次这一帧应该为刷新请求让路（跳过节流） */
    fun shouldServeFrame(): Boolean = state == State.WAITING_FRAME

    /** 帧侧调用：这一帧已用于本次请求（无论成败都会消耗一次尝试） */
    fun consumeAttempt(reason: String?) {
        if (state != State.WAITING_FRAME) return
        attempts++
        if (reason != null) lastNote = reason
    }

    fun succeed() {
        state = State.SUCCEEDED
        failReason = null
        lastNote = "棋谱已更新"
        attempts = 0
    }

    /**
     * 判定是否应当放弃本次请求。
     * @return true 表示已转为失败（调用方应把原因显示出来）
     */
    fun checkGiveUp(now: Long, latestFailReason: String?): Boolean {
        if (state != State.WAITING_FRAME) return false
        val timedOut = now - startedAt > timeoutMs
        val exhausted = attempts >= maxAttempts
        if (!timedOut && !exhausted) return false
        state = State.FAILED
        failReason = when {
            latestFailReason != null -> latestFailReason
            exhausted -> "连续 $attempts 帧都没识别到棋盘"
            else -> "取画面超时"
        }
        return true
    }

    /** 供界面显示的一行说明 */
    fun statusLine(): String? = when (state) {
        State.IDLE -> null
        State.WAITING_FRAME -> "更新棋谱：${lastNote ?: "正在取画面…"}（第 ${attempts + 1}/$maxAttempts 帧）"
        State.SUCCEEDED -> "更新棋谱：已完成"
        State.FAILED -> "更新棋谱：失败（${failReason ?: "未知原因"}）"
    }
}
