package com.xiangqi.assist.assist

/**
 * 落子取证（纯 JVM，可单测）。
 *
 * 按"两次截图"的协议实现，回答两个问题：
 *
 * **落子前**：现在屏幕上的盘面，是不是就是"这份建议所依据的那个盘面"？
 *   - 是 → 证明这条建议确实是对着眼前这一手棋算出来的，可以落；
 *   - 不是 → 说明算完之后盘面又变了，这条建议已经过期，**不能照落**，要重算。
 *
 * **落子后**：盘面有没有发生变化？
 *   - 窗口内任意一格变化 → 认为这一手落上去了（LANDED）；
 *   - 窗口内纹丝不动 → 没点上，重试（RETRY）或用尽次数后放弃（GIVE_UP）。
 *
 * 这里明确**没有**历史棋面关系判定。旧判定曾把一次正常的
 * 我方+对方连走、或识别抖动，判成异常并触发无限重识别，从而造成死锁；用户已要求
 * 完整删除。落子是否真的落上，由后续正常识别与轮次状态机继续跟踪即可。
 */
object AutoLanding {

    /** 落子前的判定 */
    enum class PreVerdict {
        /** 当前盘面与建议所依据的盘面一致 → 可以落 */
        MATCH,

        /** 盘面已经变了 → 建议过期，不能照落 */
        STALE,

        /** 还拿不到可用画面 → 先不落，等下一帧 */
        UNKNOWN,
    }

    /** 落子后的判定 */
    enum class Verdict {
        /** 盘面已变：这一手落下去了 */
        LANDED,

        /** 还在窗口内等盘面变化 */
        WAITING,

        /** 窗口内没变化：没点上，需要重落 */
        RETRY,

        /** 重落次数用尽：停止自动落子 */
        GIVE_UP,

        /** 缺少必要信息，本轮无法判定 */
        UNKNOWN,
    }

    /**
     * 落子前：当前的盘面，还是不是建议所依据的那个盘面。
     *
     * @param adviceBoard 提交给引擎计算时的那份盘面快照
     * @param seen 当前实际看到的盘面（最近一帧识别结果）
     * @param tolerance 允许的格差（识别偶发抖动）；0 表示要求完全一致
     */
    fun preCheck(
        adviceBoard: Array<IntArray>?,
        seen: Array<IntArray>?,
        tolerance: Int = 0,
    ): PreVerdict {
        if (adviceBoard == null || seen == null) return PreVerdict.UNKNOWN
        return if (AssistBoard.diffCount(seen, adviceBoard) <= tolerance) {
            PreVerdict.MATCH
        } else {
            PreVerdict.STALE
        }
    }

    /**
     * 落子后：盘面到底有没有动。
     *
     * @param preBoard 落子前那一张截图对应的盘面
     * @param seen 当前实际看到的盘面
     * @param elapsedMs 距点击已经过去的时间
     * @param windowMs 落子确认窗口
     * @param attempts 已经重落的次数
     * @param maxAttempts 最多重落几次
     */
    fun postCheck(
        preBoard: Array<IntArray>?,
        seen: Array<IntArray>?,
        elapsedMs: Long,
        windowMs: Long,
        attempts: Int,
        maxAttempts: Int,
    ): Verdict {
        if (preBoard == null || seen == null) return Verdict.UNKNOWN
        if (AssistBoard.diffCount(preBoard, seen) > 0) return Verdict.LANDED
        if (elapsedMs <= windowMs) return Verdict.WAITING
        return if (attempts < maxAttempts) Verdict.RETRY else Verdict.GIVE_UP
    }
}
