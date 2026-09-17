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
    )

    fun capture(paused: Boolean, resumePackage: String?): Snapshot? {
        val pkg = resumePackage?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        return Snapshot(wasRunning = !paused, resumePackage = pkg)
    }

    fun shouldResume(snapshot: Snapshot?, returnedPackage: String?): Boolean =
        snapshot?.wasRunning == true && snapshot.resumePackage == returnedPackage?.trim()
}
