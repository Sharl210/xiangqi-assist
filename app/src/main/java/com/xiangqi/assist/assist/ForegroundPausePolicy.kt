package com.xiangqi.assist.assist

/**
 * 前台应用切换时的运行状态快照规则。
 *
 * 这里只决定“切走前是否在运行、回来后是否恢复”，不保存搜索任务或手势事务：
 * 切走时在途手势必须作废以避免误触；若切走前在运行，返回后从当前屏幕重新建基线，
 * 若切走前本来暂停，则始终保持暂停。
 */
object ForegroundPausePolicy {
    /**
     * 前台观察连续不可用的样本数达到该值才进入安全暂停。
     *
     * 无障碍服务在系统重启、ROM 回收或本应用自己的通道保险过程中会短暂断开，
     * 单次不可用不代表前台未知；按稳定门同一节拍连续确认后仍不可用才判定为真未知。
     */
    const val UNAVAILABLE_PAUSE_SAMPLES = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES

    data class Snapshot(
        val wasRunning: Boolean,
        val resumePackage: String,
        /** true=因离开前台而临时暂停；用户点击暂停后主动等待返回则为 false。 */
        val suspendedByForeground: Boolean = true,
    )

    /** 前台观察可用性的连续计数；任何一次可用观察都会清零。 */
    data class AvailabilityState(val unavailableFrames: Int = 0)

    /** “快照目标应用已稳定回到前台”的连续计数。 */
    data class ReturnState(val packageName: String? = null, val frames: Int = 0)

    /**
     * 累计前台观察不可用的连续样本。
     *
     * 返回值第二项为 true 表示“连续不可用已确认”，调用方可以进入安全暂停；
     * 单次或少量瞬时不可用不会打断正在运行的会话。
     */
    fun observeAvailability(
        state: AvailabilityState,
        observationAvailable: Boolean,
        requiredSamples: Int = UNAVAILABLE_PAUSE_SAMPLES,
    ): Pair<AvailabilityState, Boolean> {
        if (observationAvailable) return AvailabilityState() to false
        val required = requiredSamples.coerceAtLeast(1)
        val frames = state.unavailableFrames + 1
        return AvailabilityState(frames) to (frames >= required)
    }

    /**
     * 确认“安全暂停期间原棋盘应用已重新稳定回到前台”。
     *
     * 这里不依赖基准包名发生变化：安全暂停期间基准包名可能仍是同一个应用，
     * 只靠切换事件永远等不到恢复，会话会一直停在暂停态。
     * 返回值第二项为 true 表示确认一次；计数随后归零，等待调用方清理快照。
     */
    fun observeReturn(
        snapshot: Snapshot?,
        state: ReturnState,
        packageName: String?,
        isFullScreen: Boolean,
        requiredStableFrames: Int = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES,
    ): Pair<ReturnState, Boolean> {
        if (snapshot == null || !snapshot.suspendedByForeground) return ReturnState() to false
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || !isFullScreen) return ReturnState() to false
        if (pkg != snapshot.resumePackage) return ReturnState() to false
        val required = requiredStableFrames.coerceAtLeast(1)
        val frames = if (state.packageName == pkg) state.frames + 1 else 1
        if (frames < required) return ReturnState(pkg, frames) to false
        return ReturnState(pkg, 0) to true
    }

    fun capture(
        paused: Boolean,
        resumePackage: String?,
        suspendedByForeground: Boolean = true,
    ): Snapshot? {
        val pkg = resumePackage?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        return Snapshot(
            wasRunning = !paused,
            resumePackage = pkg,
            suspendedByForeground = suspendedByForeground,
        )
    }

    fun shouldResume(snapshot: Snapshot?, returnedPackage: String?): Boolean =
        snapshot?.wasRunning == true &&
            snapshot.suspendedByForeground &&
            snapshot.resumePackage == returnedPackage?.trim()

    /** 授权恢复或手动继续时，只有目标应用在前台才允许启动录屏管线。 */
    fun isResumeTarget(snapshot: Snapshot?, currentPackage: String?): Boolean =
        snapshot != null &&
            snapshot.resumePackage == currentPackage?.trim()
}
