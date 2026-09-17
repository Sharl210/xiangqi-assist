package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchWatchdogPolicyTest {
    private fun input(
        now: Long = 20_000L,
        expected: Long = 7L,
        request: Long = 10_000L,
        appSearching: Boolean = true,
        engineReady: Boolean = true,
        processAlive: Boolean = true,
        broken: Boolean = false,
        active: Long? = 7L,
        pending: Long? = null,
        heartbeat: Long = 19_500L,
        cpu: Long = -1L,
        cpuProgress: Long = 0L,
        output: Long = 0L,
        controller: Boolean = true,
        recovery: Boolean = false,
    ) = SearchWatchdogPolicy.Input(
        now, expected, request, appSearching, engineReady, processAlive, broken,
        active, pending, controller, heartbeat, cpu, cpuProgress, output, recovery
    )

    @Test
    fun `normal deep search with no recent PV is not interrupted`() {
        assertEquals(
            SearchWatchdogPolicy.Action.NONE,
            SearchWatchdogPolicy.decide(
                input(
                    now = 120_000L,
                    request = 10_000L,
                    active = 7L,
                    heartbeat = 119_800L,
                    cpu = 5_000L,
                    cpuProgress = 119_800L,
                    output = 10_000L,
                    // 模拟超过很长时间没有info，但控制线程、进程与CPU仍有生命迹象
                )
            )
        )
    }

    @Test
    fun `stalled process with no output is recoverable only when CPU time is available`() {
        assertEquals(
            SearchWatchdogPolicy.Action.RECOVER_PROCESS_STALLED,
            SearchWatchdogPolicy.decide(
                input(
                    now = 40_001L,
                    request = 10_000L,
                    heartbeat = 40_000L,
                    cpu = 500L,
                    cpuProgress = 20_000L,
                    output = 20_000L,
                )
            )
        )
        assertEquals(
            SearchWatchdogPolicy.Action.NONE,
            SearchWatchdogPolicy.decide(
                input(
                    now = 40_001L,
                    request = 10_000L,
                    heartbeat = 40_000L,
                    cpu = -1L,
                    cpuProgress = 20_000L,
                    output = 20_000L,
                )
            )
        )
    }

    @Test
    fun `fresh protocol output prevents CPU stall recovery`() {
        assertEquals(
            SearchWatchdogPolicy.Action.NONE,
            SearchWatchdogPolicy.decide(
                input(
                    now = 40_001L,
                    request = 10_000L,
                    heartbeat = 40_000L,
                    cpu = 500L,
                    cpuProgress = 20_000L,
                    output = 39_500L,
                )
            )
        )
    }
    @Test
    fun `dead engine process is recoverable even when controller heartbeat is fresh`() {
        assertEquals(
            SearchWatchdogPolicy.Action.RECOVER_PROCESS_LOST,
            SearchWatchdogPolicy.decide(input(processAlive = false, heartbeat = 19_900L))
        )
    }

    @Test
    fun `controller heartbeat loss is recoverable`() {
        assertEquals(
            SearchWatchdogPolicy.Action.RECOVER_CONTROLLER_STALLED,
            SearchWatchdogPolicy.decide(input(heartbeat = 16_000L))
        )
        assertEquals(
            SearchWatchdogPolicy.Action.RECOVER_CONTROLLER_STALLED,
            SearchWatchdogPolicy.decide(input(controller = false, heartbeat = 19_900L))
        )
    }

    @Test
    fun `job not attached after grace is recoverable`() {
        assertEquals(
            SearchWatchdogPolicy.Action.RECOVER_JOB_NOT_ATTACHED,
            SearchWatchdogPolicy.decide(input(active = null, pending = null))
        )
    }

    @Test
    fun `pending job is considered alive during attach grace and after it`() {
        assertEquals(
            SearchWatchdogPolicy.Action.NONE,
            SearchWatchdogPolicy.decide(input(active = null, pending = 7L, now = 20_000L))
        )
    }

    @Test
    fun `non-searching or already recovering state is never interrupted`() {
        assertEquals(
            SearchWatchdogPolicy.Action.NONE,
            SearchWatchdogPolicy.decide(input(appSearching = false, processAlive = false))
        )
        assertEquals(
            SearchWatchdogPolicy.Action.NONE,
            SearchWatchdogPolicy.decide(input(recovery = true, processAlive = false))
        )
    }
}
