package com.xiangqi.assist.assist

/**
 * 预选时的棋面快照（纯 JVM，可单测）。
 *
 * 产品意图：蓝色候选箭头出现时会先按住起点做一次"预选"。此刻画面已经被识别过、
 * 落点也已经在同一套网格上算好，而中间不会有任何人动棋盘。因此正式落子时应当直接
 * 使用这份快照里的落点去点击，而不是再把棋盘识别一遍。
 *
 * 识图只服务于"算哪里"，不服务于"点哪里"：如果前面识别错了，后面再识别一次也救不回来。
 *
 * 快照带显式失效条件，任何一条不满足就作废并回退到现有完整流程，绝不放宽安全门。
 */
class PreviewBoardSnapshot {

    data class Entry(
        val fen: String,
        val analysisId: Long,
        val ucci: String,
        /** 预选时已经算好的屏幕落点（起点与终点），正式落子直接使用。 */
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val capturedAt: Long,
    )

    var entry: Entry? = null
        private set

    fun capture(
        fen: String,
        analysisId: Long,
        ucci: String,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        now: Long,
    ) {
        if (fen.isEmpty() || ucci.length < 4) return
        entry = Entry(fen, analysisId, ucci, fromX, fromY, toX, toY, now)
    }

    /**
     * 只有棋面与分析会话都与落子请求一致时，快照才可复用。
     * 落点必须落在屏幕内（0..1）之外视为脏数据，直接作废。
     */
    fun reusableFor(fen: String, analysisId: Long, maxAgeMs: Long, now: Long): Entry? {
        val e = entry ?: return null
        if (e.fen != fen) return null
        if (e.analysisId != analysisId) return null
        if (maxAgeMs > 0L && now - e.capturedAt > maxAgeMs) return null
        if (!inRange(e.fromX) || !inRange(e.fromY) || !inRange(e.toX) || !inRange(e.toY)) return null
        return e
    }

    fun clear() {
        entry = null
    }

    private fun inRange(v: Float): Boolean = v.isFinite() && v > 0f && v < 1f

    companion object {
        /** 预选快照的有效期：超过这段时间认为画面可能已经被动过，回退识别流程。 */
        const val MAX_AGE_MS = 12_000L
    }
}
