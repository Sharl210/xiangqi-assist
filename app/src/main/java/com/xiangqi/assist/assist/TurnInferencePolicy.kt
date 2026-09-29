package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece

/**
 * 回合推断策略（纯 JVM）。
 *
 * 只在“棋盘确实发生变化”时决定轮到谁；任何无法证明是合法走子的变化都不得把回合推给“对方”，
 * 因为把回合推给对方的代价是整局停在“等对方走”，表现为“明明该我方走却一直不动”。
 *
 * 判定优先级：
 * 1. 没有基线（首次确认）：保持调用方给定的回合；
 * 2. 棋盘未变：保持当前回合；
 * 3. 新棋面回到标准开局而上一帧不是：新的一局开始，红先；
 * 4. 差分恰好构成一步**规则合法**的着法：按走子方切换。自动模式下“我方走子”必须由落子事务
 *    背书（[ourMoveInFlight]），否则视为识别抖动，不切回合；
 * 5. 差分解释不了（多子变化、动画残影、识别抖动、换局残局）：绝不切到“对方回合”。
 *    当前已在对方回合时保守回到我方，避免永久死等。
 */
object TurnInferencePolicy {

    enum class Reason {
        /** 首次确认，没有可比较的上一帧棋面。 */
        FRESH_BASELINE,
        /** 棋面与上一帧相同。 */
        UNCHANGED,
        /** 回到标准开局：新的一局，红先行。 */
        NEW_GAME,
        /** 差分是一步合法的我方着法，轮到对方。 */
        OUR_STEP,
        /** 差分是一步合法的对方着法，轮到我方。 */
        OPPONENT_STEP,
        /** 差分解释不了；保持当前回合（当前在我方回合时）。 */
        UNEXPLAINED_HOLD,
        /** 差分解释不了；当前在对方回合，保守回到我方，避免死等。 */
        UNEXPLAINED_RECOVER,
    }

    data class Decision(val redGo: Boolean, val reason: Reason, val changed: Boolean)

    /** 判定“新的一局”时允许的漏检棋子数（开局满盘 32 子，识别可能少认几个）。 */
    const val NEW_GAME_MAX_MISSING = 4

    fun infer(
        previous: Array<IntArray>?,
        current: Array<IntArray>,
        currentRedGo: Boolean,
        mySideRed: Boolean,
        autoMode: Boolean = true,
        ourMoveInFlight: Boolean = false,
    ): Decision {
        if (previous == null) return Decision(currentRedGo, Reason.FRESH_BASELINE, false)
        if (AssistBoard.equal(previous, current)) return Decision(currentRedGo, Reason.UNCHANGED, false)

        if (isNewGameStart(previous, current)) {
            // 新的一局固定红先；此时我方执子方由逐帧检测另行维护。
            return Decision(true, Reason.NEW_GAME, currentRedGo != true)
        }
        if (AssistBoard.isLegalSingleMove(previous, current)) {
            val moved = AssistBoard.movedSide(previous, current)
            val movedIsMine = moved != null && moved == mySideCode(mySideRed)
            return when {
                movedIsMine && currentRedGo == mySideRed -> {
                    // 自动模式下，我方的着法一定由落子事务产生；没有事务就只能是识别抖动。
                    if (!autoMode || ourMoveInFlight) {
                        Decision(!mySideRed, Reason.OUR_STEP, true)
                    } else {
                        Decision(currentRedGo, Reason.UNEXPLAINED_HOLD, false)
                    }
                }
                !movedIsMine && currentRedGo != mySideRed ->
                    Decision(mySideRed, Reason.OPPONENT_STEP, true)
                else ->
                    Decision(currentRedGo, Reason.UNEXPLAINED_HOLD, false)
            }
        }

        return if (currentRedGo != mySideRed) {
            Decision(mySideRed, Reason.UNEXPLAINED_RECOVER, true)
        } else {
            Decision(currentRedGo, Reason.UNEXPLAINED_HOLD, false)
        }
    }

    /** 新的一局：当前棋面回到（或接近）标准开局，而上一帧不是。 */
    private fun isNewGameStart(previous: Array<IntArray>, current: Array<IntArray>): Boolean {
        val start = AssistBoard.canonicalStart()
        if (AssistBoard.equal(current, start)) return !AssistBoard.equal(previous, start)
        // 识别可能漏子，严格比对会失败。开局的特征是“当前所有棋子都落在标准开局的点位上，
        // 只是可能少了几个（漏检）”；而“开局后走了一步”一定会多出一个标准开局没有的位置。
        return isSubsetOfStart(current) && !isSubsetOfStart(previous)
    }

    /** 当前棋面是否就是标准开局的子集（允许少量漏检，不允许多出或挪位的棋子）。 */
    private fun isSubsetOfStart(current: Array<IntArray>): Boolean {
        val start = AssistBoard.canonicalStart()
        var missing = 0
        for (y in 0 until AssistBoard.H) {
            for (x in 0 until AssistBoard.W) {
                val cell = current[y][x]
                if (cell == Piece.EMPTY) {
                    if (start[y][x] != Piece.EMPTY) missing++
                    if (missing > NEW_GAME_MAX_MISSING) return false
                    continue
                }
                if (start[y][x] != cell) return false
            }
        }
        return true
    }

    private fun mySideCode(mySideRed: Boolean): Int = if (mySideRed) 1 else 0
}
