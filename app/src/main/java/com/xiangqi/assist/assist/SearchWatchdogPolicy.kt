package com.xiangqi.assist.assist

/**
 * 搜索看门狗只处置“控制链已经失去生命迹象”的情况。
 *
 * 重要边界：没有新的 info/PV 不等于搜索死亡。深度搜索可能在较长时间内没有输出，
 * 但只要引擎进程仍在、搜索控制线程仍在轮询协议，就不能打断它。只有进程已消失、
 * 控制线程停止心跳，或任务在允许的启动宽限期后完全没有挂到引擎控制器，才允许恢复。
 */
object SearchWatchdogPolicy {
    const val CONTROLLER_HEARTBEAT_TIMEOUT_MS = 3_000L
    const val JOB_ATTACH_GRACE_MS = 2_000L
    const val CPU_STALL_TIMEOUT_MS = 15_000L

    enum class Action {
        NONE,
        RECOVER_PROCESS_LOST,
        RECOVER_CONTROLLER_STALLED,
        RECOVER_JOB_NOT_ATTACHED,
        RECOVER_PROCESS_STALLED,
    }

    data class Input(
        val now: Long,
        val expectedAnalysisId: Long,
        val requestAt: Long,
        val appSearching: Boolean,
        val engineReady: Boolean,
        val processAlive: Boolean,
        val engineBroken: Boolean,
        val activeJobId: Long?,
        val pendingJobId: Long?,
        val controllerAlive: Boolean,
        val controllerHeartbeatAt: Long,
        val processCpuTimeMs: Long,
        val cpuProgressAt: Long,
        val lastOutputAt: Long,
        val recoveryAlreadyScheduled: Boolean,
    )

    fun decide(input: Input): Action {
        if (!input.appSearching || !input.engineReady || input.expectedAnalysisId <= 0L ||
            input.recoveryAlreadyScheduled || input.now < 0L
        ) return Action.NONE

        if (input.engineBroken || !input.processAlive) {
            return Action.RECOVER_PROCESS_LOST
        }

        if (!input.controllerAlive && input.requestAt > 0L &&
            input.now - input.requestAt > JOB_ATTACH_GRACE_MS
        ) {
            return Action.RECOVER_CONTROLLER_STALLED
        }

        val attached = input.activeJobId == input.expectedAnalysisId ||
            input.pendingJobId == input.expectedAnalysisId
        if (!attached && input.requestAt > 0L &&
            input.now - input.requestAt > JOB_ATTACH_GRACE_MS
        ) {
            return Action.RECOVER_JOB_NOT_ATTACHED
        }

        if (input.controllerHeartbeatAt <= 0L ||
            input.now - input.controllerHeartbeatAt > CONTROLLER_HEARTBEAT_TIMEOUT_MS
        ) {
            return Action.RECOVER_CONTROLLER_STALLED
        }

        // 只有拿得到进程CPU计时，且CPU计时长时间不增长、同时没有任何UCI输出时，
        // 才认为“进程活着但内部卡死”。无法读取CPU计时则保持保守：不打断正常搜索。
        if (input.processCpuTimeMs >= 0L && input.cpuProgressAt > 0L &&
            input.now - input.cpuProgressAt > CPU_STALL_TIMEOUT_MS &&
            (input.lastOutputAt <= 0L || input.now - input.lastOutputAt > CPU_STALL_TIMEOUT_MS)
        ) {
            return Action.RECOVER_PROCESS_STALLED
        }

        // 不检查“最后一条info距今多久”：输出静默但控制线程/进程健康时，
        // 可能只是正常的深度搜索，不能中断。
        return Action.NONE
    }
}
