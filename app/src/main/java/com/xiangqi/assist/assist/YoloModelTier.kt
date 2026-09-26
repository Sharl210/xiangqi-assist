package com.xiangqi.assist.assist

/**
 * 模型输出契约。不同架构不能只改文件名后复用旧解码器。
 */
enum class YoloModelFormat {
    YOLOV5_XQ,
    YOLO26_RAW,
}

/**
 * 移动端识别档位按可用效果路线从低到高排列。
 * Lite 固定为原始 V5 最终兜底；Low 为此前稳定的 V5 Medium；Medium 为 YOLO26-S 候选；
 * High 暂用历史 Medium+universal 复合资源，名称只表示当前最高优先档，不宣称已证明效果胜过其他档，也不称原生大型。
 */
enum class YoloModelTier(
    val displayName: String,
    val fileName: String,
    val format: YoloModelFormat,
    val selectionHint: String,
) {
    LITE(
        displayName = "Lite",
        fileName = "yolov5n_xq_fp16.tflite",
        format = YoloModelFormat.YOLOV5_XQ,
        selectionHint = "原始 V5 Lite 最终兜底；模型最小，优先兼容与资源占用",
    ),
    LOW(
        displayName = "Low",
        fileName = "yolov5m_xq_fp32.tflite",
        format = YoloModelFormat.YOLOV5_XQ,
        selectionHint = "此前稳定的原始 V5 Medium；用作较低效果优先档和 YOLO26-S 运行时回退",
    ),
    MEDIUM(
        displayName = "Medium",
        fileName = "yolo26s_xq_fp32.tflite",
        format = YoloModelFormat.YOLO26_RAW,
        selectionHint = "原生 YOLO26-S 中国象棋15类候选；逐格效果仍在验证",
    ),
    HIGH(
        displayName = "High",
        fileName = "yolov5l_xq_fp32.tflite",
        format = YoloModelFormat.YOLOV5_XQ,
        selectionHint = "当前最高优先档，使用历史 Medium+universal 复合资源；不是原生大型权重，实际效果需用固定样本验证",
    );

    /** 从所选档位向兼容性要求更低的档位回退；枚举本身按 Lite→High 排列。 */
    fun fallbackOrder(): List<YoloModelTier> = values().take(ordinal + 1).asReversed()

    companion object {
        const val MAX_MODEL_BYTES = 200_000_000L
        const val DEFAULT_NAME = "HIGH"

        /** v1.3.2 的旧枚举值继续读取；旧超大型/大型选择迁移至当前 High 档。 */
        fun fromStored(value: String?): YoloModelTier = when (value) {
            null, "", "old-value" -> HIGH
            "LITE" -> LITE
            "LOW", "MEDIUM_V5_FALLBACK" -> LOW
            "MEDIUM" -> MEDIUM
            "HIGH", "LARGE", "SUPER_LARGE" -> HIGH
            else -> HIGH
        }
    }
}

/** 一次模型选择或自动回退的可观察结果。 */
data class YoloModelSelectionResult(
    val requested: YoloModelTier,
    val actual: YoloModelTier?,
    val modelFile: String?,
    val failureReasons: List<String> = emptyList(),
    val fatalReason: String? = null,
) {
    val success: Boolean
        get() = actual != null

    val wasFallback: Boolean
        get() = success && actual != requested

    /** 供设置页弹窗和状态栏使用的人话结果；不暴露堆栈。 */
    fun userMessage(): String {
        if (!success) {
            val detail = fatalReason
                ?: failureReasons.joinToString("；").ifBlank { "运行时未返回具体原因" }
            return "设备无法加载任何象棋识别模型。$detail"
        }
        if (!wasFallback) {
            return "已启用${actual!!.displayName}模型。${actual.selectionHint}。"
        }
        val detail = failureReasons.joinToString("；")
            .ifBlank { "目标档位未通过兼容性检查" }
        return "${requested.displayName}模型未通过设备兼容性检查：$detail。已自动切换为${actual!!.displayName}模型。${actual.selectionHint}。"
    }
}

/** 模型档位全部探测失败；保留结构化结果供界面显示和日志记录。 */
class YoloModelUnavailableException(
    val selection: YoloModelSelectionResult,
) : IllegalStateException(selection.userMessage())
