package com.xiangqi.assist.assist

/**
 * 连线分析的可选深度档位与统一边界。
 *
 * 深度只是搜索上限，仍受思考时间上限共同约束；选择高档位不会绕过时间限制。
 * 档位最高 64：再高的档位在手机上只会长时间占用算力，收益递减且更容易触发降频。
 */
object AssistDepth {
    const val MIN = 6
    const val MAX = 64

    val LEVELS: List<Pair<String, Int>> = listOf(
        "入门·深度6" to 6,
        "快速·深度12" to 12,
        "标准·深度20" to 20,
        "强劲·深度28" to 28,
        "极限·深度36" to 36,
        "高阶·深度50" to 50,
        "最高·深度64" to 64,
    )

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}
