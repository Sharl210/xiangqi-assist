package com.xiangqi.assist.assist

/**
 * 当前产品仅保留两份原始中国象棋 V5 权重：Medium 为默认主模型，Lite 为兼容性最终回退。
 *
 * 这里的 [selectionHint] 只描述用途，不携带任何实验评分或样本通过数：
 * 实验样本的逐张结论属于开发侧资料（见 `docs/两模型样本优化记录.md`、
 * `docs/移动端模型替换评估.md`），展示给用户的只有模型名称和用途。
 */
enum class YoloModelTier(
    val displayName: String,
    val fileName: String,
    val selectionHint: String,
) {
    MEDIUM(
        displayName = "Medium",
        fileName = "yolov5m_xq_fp32.tflite",
        selectionHint = "识别效果优先的默认模型",
    ),
    LITE(
        displayName = "Lite",
        fileName = "yolov5n_xq_fp16.tflite",
        selectionHint = "占用更低的轻量模型，仅在默认模型无法运行时使用",
    );

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
