package com.xiangqi.assist.assist

/**
 * 当前产品仅保留两份原始中国象棋 V5 权重：Medium 为默认主模型，Lite 为兼容性最终回退。
 * 分数是当前10张实验样本的安全门基线，不是逐格准确率或通用 mAP；逐格标注前不得宣称无误。
 */
enum class YoloModelTier(
    val displayName: String,
    val fileName: String,
    val selectionHint: String,
    /** 当前10张样本上的安全门基线，不是通用准确率。 */
    val sampleScore: Double,
) {
    MEDIUM(
        displayName = "Medium",
        fileName = "yolov5m_xq_fp32.tflite",
        selectionHint = "原始 V5 Medium，中国象棋主模型；样本评测7/10通过安全门",
        sampleScore = 78.1,
    ),
    LITE(
        displayName = "Lite",
        fileName = "yolov5n_xq_fp16.tflite",
        selectionHint = "原始 V5 Lite，Medium兼容性失败时的最终回退；样本评测4/10通过安全门",
        sampleScore = 67.2,
    );

    fun sampleScoreText(): String = "${sampleScore}/100"

    /** 仅在模型运行时兼容性检查失败时按 Medium→Lite 回退；Lite 不再反向切回主模型。 */
    fun fallbackOrder(): List<YoloModelTier> = when (this) {
        MEDIUM -> listOf(MEDIUM, LITE)
        LITE -> listOf(LITE)
    }

    companion object {
        const val MAX_MODEL_BYTES = 200_000_000L
        const val DEFAULT_NAME = "MEDIUM"

        /** 历史四档配置统一映射到现有主模型；明确的 Lite 配置继续保留。 */
        fun fromStored(value: String?): YoloModelTier = when (value) {
            "LITE" -> LITE
            null, "", "old-value", "LOW", "MEDIUM", "MEDIUM_V5_FALLBACK",
            "HIGH", "LARGE", "SUPER_LARGE" -> MEDIUM
            else -> MEDIUM
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
