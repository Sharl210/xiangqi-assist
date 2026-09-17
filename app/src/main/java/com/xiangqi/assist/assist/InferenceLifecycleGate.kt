package com.xiangqi.assist.assist

/**
 * 保护原生推理对象的生命周期：关闭一旦开始，新的推理不再进入；
 * close 会等待已经进入的推理退出后才执行释放回调。
 */
class InferenceLifecycleGate {
    private val monitor = Object()
    private var accepting = true
    private var inFlight = 0

    fun <T> runIfOpen(block: () -> T): T? {
        synchronized(monitor) {
            if (!accepting) return null
            inFlight++
        }
        return try {
            block()
        } finally {
            synchronized(monitor) {
                inFlight--
                if (inFlight == 0) monitor.notifyAll()
            }
        }
    }

    fun close(release: () -> Unit) {
        var interrupted = false
        synchronized(monitor) {
            accepting = false
            while (inFlight > 0) {
                try {
                    monitor.wait()
                } catch (_: InterruptedException) {
                    // 关闭是不可跳过的安全动作；记录中断，继续等待在途推理完成。
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        release()
    }

    val isClosed: Boolean
        get() = synchronized(monitor) { !accepting }
}
