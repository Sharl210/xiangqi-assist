package com.xiangqi.assist.assist

/**
 * 模型输出契约。不同架构不能只改文件名后复用旧解码器。
 */
enum class YoloModelFormat {
    YOLOV5_XQ,
    YOLO26_RAW,
}

/**
 * 移动端棋子识别模型档位。
 *
 * 顺序就是兼容性回退顺序：超大型 → 大型 → 中型 → Lite。
 * 超大型暂未取得现成中国象棋权重；大型先使用既有 Medium+universal/旋转鲁棒复合资源作为历史可用档，
 * 但它不冒充原生大型。中型优先使用原生 YOLO26-S；若后续效果验收不通过，可把中型切回已知稳定的原始 V5 Medium。
 */
enum class YoloModelTier(
    val displayName: String,
    val fileName: String,
    val format: YoloModelFormat,
    val selectionHint: String,
    /** 只用于兼容性回退的内部档位，不在辅助页作为独立选择项展示。 */
    val selectable: Boolean = true,
) {
    SUPER_LARGE(
        displayName = "超大型",
        fileName = "yoloxq_super_native_fp32.tflite",
        format = YoloModelFormat.YOLOV5_XQ,
        selectionHint = "效果优先的最高原生档；当前尚未取得合格的中国象棋专用超大型权重",
    ),
    LARGE(
        displayName = "大型",
        fileName = "yolov5l_xq_fp32.tflite",
        format = YoloModelFormat.YOLOV5_XQ,
        selectionHint = "既有 Medium+旋转鲁棒复合大型资源；可继续使用和优化，但不是原生大型权重",
    ),
    MEDIUM(
        displayName = "中型",
        fileName = "yolo26s_xq_fp32.tflite",
        format = YoloModelFormat.YOLO26_RAW,
        selectionHint = "优先修复的原生 YOLO26-S 中国象棋15类候选；输入输出契约独立于旧 V5",
    ),
    /** YOLO26-S 效果验收不通过时的此前稳定 V5 Medium，作为同一中型档的内部回退。 */
    MEDIUM_V5_FALLBACK(
        displayName = "中型（V5回退）",
        fileName = "yolov5m_xq_fp32.tflite",
        format = YoloModelFormat.YOLOV5_XQ,
        selectionHint = "此前稳定的原始 V5 Medium 回退档；不改变用户选择的中型档位",
        selectable = false,
    ),
    /** 原始 V5 Lite 只作为最终兼容保底，不能被未验证的新模型覆盖。 */
    LITE(
        displayName = "Lite（V5保底）",
        fileName = "yolov5n_xq_fp16.tflite",
        format = YoloModelFormat.YOLOV5_XQ,
        selectionHint = "原始 V5 Lite 保底档；文件更小，通常更快、更省内存",
    );

    /** 从当前选择向资源要求更低的档位回退，不跨过中间档。 */
    fun fallbackOrder(): List<YoloModelTier> = values().drop(ordinal)

    companion object {
        const val MAX_MODEL_BYTES = 200_000_000L

        /** 新安装仍优先尝试效果最高档；无原生工件时由真实探测链回退。 */
        const val DEFAULT_NAME = "SUPER_LARGE"

        fun fromStored(value: String?): YoloModelTier =
            values().firstOrNull { it.name == value } ?: SUPER_LARGE
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
