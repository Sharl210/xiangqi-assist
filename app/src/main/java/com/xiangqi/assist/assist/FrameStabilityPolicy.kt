package com.xiangqi.assist.assist

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 连续录屏流的轻量画面稳定性判断（纯 JVM）。
 *
 * 录屏本身由 MediaProjection 持续输出；本策略只在流中每 250ms 取一个样本，
 * 用低分辨率颜色签名比较相邻样本。连续八个样本稳定后才放行一个关键帧给棋盘模型。
 * 这里不识别棋子，也不读取上一局面，因此不会用历史棋面替换当前字形。
 */
object FrameStabilityPolicy {
    const val SAMPLE_FPS = 4
    const val SAMPLE_PERIOD_MS = 1_000L / SAMPLE_FPS
    const val REQUIRED_STABLE_FRAMES = 8

    /** 颜色签名的平均通道差阈值；动画中的移动棋子会明显超过此值。 */
    const val MAX_MEAN_CHANNEL_DELTA = 0.035
    /** 低分辨率采样点中允许变化的比例；避免单个棋子运动被平均值稀释。 */
    const val MAX_CHANGED_SAMPLE_FRACTION = 0.015
    private const val CHANGED_SAMPLE_DELTA = 0.10
    private const val SIGNATURE_COLUMNS = 32
    private const val SIGNATURE_ROWS = 48
    private const val TOP_MARGIN = 0.04
    private const val BOTTOM_MARGIN = 0.96

    class Signature internal constructor(
        internal val rgb: IntArray,
    )

    fun shouldSample(now: Long, previousSampleAt: Long): Boolean =
        previousSampleAt == Long.MIN_VALUE || now - previousSampleAt >= SAMPLE_PERIOD_MS

    /**
     * 生成低分辨率颜色签名。忽略屏幕最窄的上下状态边缘，降低时钟/导航栏变化对判断的影响。
     */
    fun signature(
        frame: Frame,
        columns: Int = SIGNATURE_COLUMNS,
        rows: Int = SIGNATURE_ROWS,
    ): Signature {
        require(columns > 0 && rows > 0)
        val result = IntArray(columns * rows)
        val y0 = (frame.height * TOP_MARGIN).toInt()
        val y1 = max(y0 + 1, (frame.height * BOTTOM_MARGIN).toInt())
        for (sy in 0 until rows) {
            val y = ((y0 + (y1 - y0) * (sy + 0.5) / rows).toInt())
                .coerceIn(0, frame.height - 1)
            for (sx in 0 until columns) {
                val x = ((frame.width * (sx + 0.5) / columns).toInt())
                    .coerceIn(0, frame.width - 1)
                result[sy * columns + sx] = frame.argb[y * frame.width + x] and 0x00FFFFFF
            }
        }
        return Signature(result)
    }

    /** 采样频率与稳定窗口：每秒4个录屏样本，连续8个相邻样本稳定后才推理。 */
    fun samplingContract(): String = "${SAMPLE_FPS}fps/${REQUIRED_STABLE_FRAMES} stable frames"


    fun meanChannelDelta(a: Signature, b: Signature): Double {
        if (a.rgb.size != b.rgb.size || a.rgb.isEmpty()) return 1.0
        var sum = 0.0
        for (i in a.rgb.indices) {
            val pa = a.rgb[i]
            val pb = b.rgb[i]
            sum += abs(((pa ushr 16) and 0xFF) - ((pb ushr 16) and 0xFF)) / 255.0
            sum += abs(((pa ushr 8) and 0xFF) - ((pb ushr 8) and 0xFF)) / 255.0
            sum += abs((pa and 0xFF) - (pb and 0xFF)) / 255.0
        }
        return sum / (a.rgb.size * 3.0)
    }

    /** 采样点中通道差超过阈值的比例。 */
    fun changedSampleFraction(a: Signature, b: Signature): Double {
        if (a.rgb.size != b.rgb.size || a.rgb.isEmpty()) return 1.0
        var changed = 0
        for (i in a.rgb.indices) {
            val pa = a.rgb[i]
            val pb = b.rgb[i]
            val delta = (
                abs(((pa ushr 16) and 0xFF) - ((pb ushr 16) and 0xFF)) +
                    abs(((pa ushr 8) and 0xFF) - ((pb ushr 8) and 0xFF)) +
                    abs((pa and 0xFF) - (pb and 0xFF))
                ) / (255.0 * 3.0)
            if (delta > CHANGED_SAMPLE_DELTA) changed++
        }
        return changed.toDouble() / a.rgb.size.toDouble()
    }

    fun isStable(a: Signature?, b: Signature?): Boolean {
        if (a == null || b == null) return false
        return meanChannelDelta(a, b) <= MAX_MEAN_CHANNEL_DELTA &&
            changedSampleFraction(a, b) <= MAX_CHANGED_SAMPLE_FRACTION
    }

    /**
     * 低分辨率清晰度近似：相邻采样点的亮度边缘越丰富，分数越高。
     * 只用于从已经连续稳定的八帧中选较清晰的一帧，不参与类别判断。
     */
    fun clarityScore(frame: Frame, columns: Int = 32, rows: Int = 48): Double {
        val sig = signature(frame, columns, rows).rgb
        if (sig.size < columns * rows) return 0.0
        fun y(p: Int): Double {
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        }
        var sum = 0.0
        var count = 0
        for (row in 0 until rows) for (col in 0 until columns) {
            val at = y(sig[row * columns + col])
            if (col + 1 < columns) {
                sum += abs(at - y(sig[row * columns + col + 1]))
                count++
            }
            if (row + 1 < rows) {
                sum += abs(at - y(sig[(row + 1) * columns + col]))
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }
}
