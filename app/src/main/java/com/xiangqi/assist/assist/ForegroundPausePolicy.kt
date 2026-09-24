package com.xiangqi.assist.assist

/**
 * 前台应用切换时的运行状态快照规则。
 *
 * 这里只决定“切走前是否在运行、回来后是否恢复”，不保存搜索任务或手势事务：
 * 切走时在途手势必须作废以避免误触；若切走前在运行，返回后从当前屏幕重新建基线，
 * 若切走前本来暂停，则始终保持暂停。
 */
object ForegroundPausePolicy {
    data class Snapshot(
        val wasRunning: Boolean,
        val resumePackage: String,
        /** true=因离开前台而临时暂停；用户点击暂停后主动等待返回则为 false。 */
        val suspendedByForeground: Boolean = true,
    )

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
