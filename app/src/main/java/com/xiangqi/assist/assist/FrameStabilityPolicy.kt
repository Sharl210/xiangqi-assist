package com.xiangqi.assist.assist

import kotlin.math.abs
import kotlin.math.max

/** 8fps sample clock and consecutive-stability requirement for recognition input. */
object FrameStabilityPolicy {
    const val SAMPLE_FPS = 8
    const val SAMPLE_PERIOD_MS = 1_000L / SAMPLE_FPS
    const val REQUIRED_STABLE_FRAMES = 8
    const val MAX_MEAN_CHANNEL_DELTA = 0.035
    const val MAX_CHANGED_SAMPLE_FRACTION = 0.015
    private const val CHANGED_SAMPLE_DELTA = 0.10
    private const val SIGNATURE_COLUMNS = 32
    private const val SIGNATURE_ROWS = 48
    private const val TOP_MARGIN = 0.0
    private const val BOTTOM_MARGIN = 1.0

    class Signature internal constructor(internal val rgb: IntArray)

    fun shouldSample(now: Long, previousSampleAt: Long): Boolean =
        previousSampleAt == Long.MIN_VALUE || now - previousSampleAt >= SAMPLE_PERIOD_MS

    fun signature(
        frame: Frame,
        columns: Int = SIGNATURE_COLUMNS,
        rows: Int = SIGNATURE_ROWS,
        region: DoubleArray? = null,
    ): Signature {
        require(columns > 0 && rows > 0)
        val result = IntArray(columns * rows)
        val y0: Int
        val y1: Int
        val x0: Int
        val x1: Int
        if (region != null && region.size >= 4) {
            val rx0 = region[0].coerceIn(0.0, 1.0)
            val ry0 = region[1].coerceIn(0.0, 1.0)
            val rx1 = region[2].coerceIn(rx0, 1.0)
            val ry1 = region[3].coerceIn(ry0, 1.0)
            x0 = (frame.width * rx0).toInt().coerceIn(0, frame.width - 1)
            x1 = (frame.width * rx1).toInt().coerceIn(x0 + 1, frame.width)
            y0 = (frame.height * ry0).toInt().coerceIn(0, frame.height - 1)
            y1 = (frame.height * ry1).toInt().coerceIn(y0 + 1, frame.height)
        } else {
            x0 = 0
            x1 = frame.width
            y0 = (frame.height * TOP_MARGIN).toInt().coerceIn(0, frame.height - 1)
            y1 = max(y0 + 1, (frame.height * BOTTOM_MARGIN).toInt()).coerceAtMost(frame.height)
        }
        for (sy in 0 until rows) {
            val y = (y0 + (y1 - y0) * (sy + 0.5) / rows).toInt().coerceIn(0, frame.height - 1)
            for (sx in 0 until columns) {
                val x = (x0 + (x1 - x0) * (sx + 0.5) / columns).toInt().coerceIn(0, frame.width - 1)
                result[sy * columns + sx] = frame.argb[y * frame.width + x] and 0x00FFFFFF
            }
        }
        return Signature(result)
    }

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

    fun changedSampleFraction(a: Signature, b: Signature): Double {
        if (a.rgb.size != b.rgb.size || a.rgb.isEmpty()) return 1.0
        var changed = 0
        for (i in a.rgb.indices) if (pixelDelta(a.rgb[i], b.rgb[i]) > CHANGED_SAMPLE_DELTA) changed++
        return changed.toDouble() / a.rgb.size
    }

    fun isStable(a: Signature, b: Signature): Boolean =
        meanChannelDelta(a, b) <= MAX_MEAN_CHANNEL_DELTA &&
            changedSampleFraction(a, b) <= MAX_CHANGED_SAMPLE_FRACTION

    fun clarityScore(frame: Frame): Double {
        if (frame.width < 3 || frame.height < 3) return 0.0
        var score = 0.0
        var count = 0
        val stepX = max(1, frame.width / 96)
        val stepY = max(1, frame.height / 96)
        for (y in stepY until frame.height - stepY step stepY) {
            for (x in stepX until frame.width - stepX step stepX) {
                score += pixelDelta(frame.argb[y * frame.width + x], frame.argb[y * frame.width + x + stepX])
                score += pixelDelta(frame.argb[y * frame.width + x], frame.argb[(y + stepY) * frame.width + x])
                count += 2
            }
        }
        return if (count == 0) 0.0 else score / count
    }

    private fun pixelDelta(a: Int, b: Int): Double {
        val dr = abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) / 255.0
        val dg = abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) / 255.0
        val db = abs((a and 0xFF) - (b and 0xFF)) / 255.0
        return (dr + dg + db) / 3.0
    }
}
