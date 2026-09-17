package com.xiangqi.assist.assist

import java.io.File
import java.util.Locale

/**
 * 连线运行日志。
 *
 * 日志放在应用私有目录的 logs 子目录中，不申请存储权限：
 * <app filesDir>/logs/assist.log
 *
 * 每次新的录屏会话或用户从暂停重新开始时，先删除 logs 下的旧文件，再创建新的日志。
 * 写入同步且短小，保证状态切换、落子事务和引擎回调即使来自不同线程也不会交叉。
 */
class AssistRuntimeLogger(private val logsDir: File) {
    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var logFile: File? = null

    fun startSession(reason: String) {
        synchronized(lock) {
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

    fun log(event: String, message: String = "") {
        synchronized(lock) {
            if (!active) return
            val file = logFile ?: return
            appendLocked(file, "${now()}|${clean(event)}|${clean(message)}")
        }
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
