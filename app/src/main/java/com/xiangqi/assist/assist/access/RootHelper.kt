package com.xiangqi.assist.assist.access

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * root 能力封装：只做三件事
 * 1. 探测设备是否真的能拿到 root（执行 `su -c id`）；
 * 2. 用 root 自动勾选本应用的无障碍服务（等价于用户去系统设置里手动打开）；
 * 3. 用 root 取消勾选。
 *
 * 设计约束：
 * - 所有命令都是只读探测或写入 `settings`，不改动系统分区、不安装任何东西；
 * - 写 enabled_accessibility_services 时保留列表中已有的其它服务，只做追加/移除；
 * - 无 root 时全部方法返回明确失败，不影响主流程（用户仍可手动到系统设置里勾选）。
 */
object RootHelper {

    private const val TAG = "RootHelper"
    private const val TIMEOUT_SEC = 8L

    class Result(val ok: Boolean, val output: String) {
        override fun toString(): String = (if (ok) "OK: " else "FAIL: ") + output
    }

    /** 是否具备 root（结果缓存，避免每帧调用都 forksu） */
    @Volatile private var rootProbe: Boolean? = null

    fun isRooted(forceRefresh: Boolean = false): Boolean {
        val cached = rootProbe
        if (!forceRefresh && cached != null) return cached
        val ok = runAsRoot("id").ok
        rootProbe = ok
        Log.i(TAG, "root probe: $ok")
        return ok
    }

    /** 以 root 执行一条命令（sh -c），返回输出与退出码判断的成功标志 */
    fun runAsRoot(command: String): Result = runShell(arrayOf("su", "-c", command))

    private fun runShell(cmd: Array<String>): Result {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return Result(false, "命令超时（${TIMEOUT_SEC}s）：${cmd.joinToString(" ")}")
            }
            val out = runCatching {
                proc.inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            val code = proc.exitValue()
            if (code == 0) Result(true, out) else Result(false, "exit=$code $out")
        } catch (t: Throwable) {
            // su 不存在 / 被 Magisk 拒绝：都属于「没有可用 root」
            Result(false, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { proc?.destroy() }
        }
    }

    /**
     * 内部重启无障碍通道：保留其他服务，先临时移除本服务再重新加入，
     * 用于自动落子前清理系统手势通道可能残留的卡住状态。
     * 失败只返回结果，不改变应用内自动落子开关。
     */
    fun restartAccessibility(context: Context): Result {
        if (!isRooted()) return Result(false, "未获取到 root（su 不可用）")
        val comp = AssistAccessibilityService.componentName(context)
        val short = AssistAccessibilityService.componentShortName(context)
        val current = readEnabledListAsRoot()
            ?: AssistAccessibilityService.currentEnabledList(context)
        val remaining = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(comp, true) && !it.equals(short, true) }
            .joinToString(":")
        val enabled = mergeEnabledList(remaining, comp)
        val script = buildString {
            if (remaining.isEmpty()) {
                append("settings delete secure enabled_accessibility_services")
            } else {
                append("settings put secure enabled_accessibility_services '")
                append(remaining)
                append("'")
            }
            append("; sleep 0.1; settings put secure enabled_accessibility_services '")
            append(enabled)
            append("'; settings put secure accessibility_enabled 1")
        }
        val result = runAsRoot(script)
        Log.i(TAG, "restart accessibility: $result")
        return result
    }


    /**
     * 用 root 勾选本应用的无障碍服务。
     *
     * 关键点：先读出系统当前全部已启用服务，把自己追加进去，再整体写回，
     * 不会清除用户已经启用的其他服务。
     */
    fun enableAccessibility(context: Context): Result {
        if (!isRooted()) return Result(false, "未获取到 root（su 不可用）")
        val comp = AssistAccessibilityService.componentName(context)
        val current = readEnabledListAsRoot()
            ?: AssistAccessibilityService.currentEnabledList(context)
        val merged = mergeEnabledList(current, comp)
        val script = buildString {
            append("settings put secure enabled_accessibility_services '")
            append(merged)
            append("'; settings put secure accessibility_enabled 1")
        }
        val result = runAsRoot(script)
        Log.i(TAG, "enable accessibility: $result")
        return result
    }

    /** 用 root 取消勾选本应用的无障碍服务（同样先读全量，只移除自己，保留其它服务） */
    fun disableAccessibility(context: Context): Result {
        if (!isRooted()) return Result(false, "未获取到 root（su 不可用）")
        val comp = AssistAccessibilityService.componentName(context)
        val short = AssistAccessibilityService.componentShortName(context)
        val current = readEnabledListAsRoot()
            ?: AssistAccessibilityService.currentEnabledList(context)
        val remaining = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(comp, true) && !it.equals(short, true) }
            .joinToString(":")
        val script = if (remaining.isEmpty()) {
            "settings delete secure enabled_accessibility_services"
        } else {
            "settings put secure enabled_accessibility_services '$remaining'"
        }
        val r = runAsRoot(script)
        Log.i(TAG, "disable accessibility: $r")
        return r
    }

    /** 以 root 读取当前已启用的无障碍服务列表（null 表示读取失败，调用方应退回应用内读取） */
    fun readEnabledListAsRoot(): String? {
        val r = runAsRoot("settings get secure enabled_accessibility_services")
        if (!r.ok) return null
        val v = r.output.trim()
        return if (v.isEmpty() || v == "null") "" else v
    }

    /** 把组件名并入现有启用列表（去重、保持原顺序）——**追加语义**，不会丢掉任何已有条目 */
    fun mergeEnabledList(current: String, component: String): String {
        val items = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val short = component.substringBefore('/') + "/" + component.substringAfter('/').substringAfterLast('.')
        val exists = items.any {
            it.equals(component, ignoreCase = true) || it.equals(short, ignoreCase = true)
        }
        if (!exists) items.add(component)
        return items.joinToString(":")
    }
}
