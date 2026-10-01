package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingRestartPolicyTest {

    @Test
    fun `first stall retries the analysis`() {
        assertEquals(
            ThinkingRestartPolicy.Action.RETRY,
            ThinkingRestartPolicy.decide(attempts = 0, sinceLastRetryMs = 60_000L),
        )
    }

    @Test
    fun `retries are throttled so the watchdog cannot spam every heartbeat`() {
        // 真机现象：看门狗每 400ms 一个心跳，初版每次心跳都重发一次，
        // 13 秒刷了 25 条 WATCHDOG_RESTART_ANALYSIS 却毫无作用。
        assertEquals(
            ThinkingRestartPolicy.Action.THROTTLED,
            ThinkingRestartPolicy.decide(attempts = 1, sinceLastRetryMs = 400L),
        )
        assertEquals(
            ThinkingRestartPolicy.Action.THROTTLED,
            ThinkingRestartPolicy.decide(attempts = 3, sinceLastRetryMs = 1_499L),
        )
    }

    @Test
    fun `exhausted retries give up instead of spinning forever`() {
        assertEquals(
            ThinkingRestartPolicy.Action.GIVE_UP,
            ThinkingRestartPolicy.decide(
                attempts = ThinkingRestartPolicy.MAX_RETRIES,
                sinceLastRetryMs = ThinkingRestartPolicy.MIN_RETRY_GAP_MS,
            ),
        )
    }

    @Test
    fun `give up never wins over throttling`() {
        // 次数用尽但距上次重发太近时，必须先限速，不能立刻再作废一次局面。
        assertEquals(
            ThinkingRestartPolicy.Action.THROTTLED,
            ThinkingRestartPolicy.decide(
                attempts = ThinkingRestartPolicy.MAX_RETRIES,
                sinceLastRetryMs = 100L,
            ),
        )
    }

    @Test
    fun `stall timeout matches the phase watchdog`() {
        assertEquals(
            ThinkingRestartPolicy.STALL_TIMEOUT_MS,
            AssistPhase.THINKING_STALL_TIMEOUT_MS,
        )
    }
}
