package com.xiangqi.assist.assist

import java.io.File
import java.util.Locale

/**
 * 单次“一键准备”会话的连线运行日志。
 *
 * 路径为应用私有目录 `<app filesDir>/logs/assist.log`，不申请存储权限。
 * 只有成功建立准备会话时才清理上一份日志；暂停、继续、重置、授权续接和内部恢复
 * 都复用同一文件。一键关闭只写结束标记并停止写入，保留日志供用户反馈；下次成功
 * 准备时才清理旧文件。进程被强制结束时保留已落盘内容。
 */
class AssistRuntimeLogger(private val logsDir: File) {
    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var logFile: File? = null

    /** 开启一次准备会话；同一会话内重复调用只记复用标记，绝不删日志。 */
    fun startSession(reason: String) {
        synchronized(lock) {
            if (active && logFile != null) {
                appendLocked(logFile!!, "SESSION_REUSED|reason=${clean(reason)}")
                return
            }
            generation++
            active = false
            logFile = null
            if (!logsDir.exists()) logsDir.mkdirs()
            logsDir.listFiles()?.forEach { child ->
                runCatching { child.deleteRecursively() }
            }
            val file = File(logsDir, "assist.log")
            logFile = file
            active = true
            appendLocked(file, "SESSION|generation=$generation|reason=${clean(reason)}")
        }
    }

    /** 结束写入但保留已完成日志，供用户关闭辅助后导出/反馈。 */
    fun endSession(reason: String) {
        synchronized(lock) {
            if (!active) return
            val file = logFile ?: return
            appendLocked(file, "SESSION_END|reason=${clean(reason)}")
            active = false
            logFile = null
        }
    }

    fun isActive(): Boolean = synchronized(lock) { active && logFile != null }

    fun log(event: String, message: String = "") {
        synchronized(lock) {
            if (!active) return
            val file = logFile ?: return
            appendLocked(file, "${now()}|${clean(event)}|${clean(message)}")
        }
    }

    /** 记录用户主动操作；调用者须在执行操作副作用之前记录。 */
    fun logUserAction(surface: String, action: String, details: String = "") {
        val suffix = details.takeIf { it.isNotBlank() }?.let { " details=${clean(it)}" }.orEmpty()
        log("USER_ACTION", "surface=${clean(surface)} action=${clean(action)}$suffix")
    }

    fun logException(event: String, error: Throwable) {
        log(event, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
    }

    fun directory(): File = logsDir

    private fun appendLocked(file: File, line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    private fun clean(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', '/')

    private fun now(): String = String.format(Locale.US, "%d", System.currentTimeMillis())
}
