package com.xiangqi.assist.assist

/**
 * YOLO 识别模型档位。
 *
 * 枚举顺序同时表达兼容性回退顺序：大型 → 中型 → Lite。
 * 这里的“兼容”只表示模型能被当前 TFLite 运行时创建、分配并完成一次受控推理，
 * 不把瞬时负载、温度或功耗当成回退条件。
 */
enum class YoloModelTier(
    val displayName: String,
    val fileName: String,
    val selectionHint: String,
) {
    LARGE(
        displayName = "大型",
        fileName = "yolov5l_xq_fp32.tflite",
        selectionHint = "双模型集成大型档，识别能力与复杂皮肤鲁棒性优先；模型更重，通常更慢、更耗电，适合设备兼容且重视识别稳定性的情况",
    ),
    MEDIUM(
        displayName = "中型",
        fileName = "yolov5m_xq_fp32.tflite",
        selectionHint = "识别能力与速度的平衡档",
    ),
    LITE(
        displayName = "Lite",
        fileName = "yolov5n_xq_fp16.tflite",
        selectionHint = "资源和兼容性优先；模型更小，通常更快、更省内存",
    );

    /** 从当前选择向资源要求更低的档位回退，不跨过中间档。 */
    fun fallbackOrder(): List<YoloModelTier> = values().drop(ordinal)

    companion object {
        /** 新安装且没有历史值时的默认首选。 */
        const val DEFAULT_NAME = "LARGE"

        fun fromStored(value: String?): YoloModelTier =
            values().firstOrNull { it.name == value } ?: LARGE
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
        return "${requested.displayName}模型未通过设备兼容性检查：$detail。已自动切换为${actual!!.displayName}模型。"
    }
}

/** 模型档位全部探测失败；保留结构化结果供界面显示和日志记录。 */
class YoloModelUnavailableException(
    val selection: YoloModelSelectionResult,
) : IllegalStateException(selection.userMessage())
