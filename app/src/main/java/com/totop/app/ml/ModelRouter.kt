package com.totop.app.ml

import com.totop.app.data.prefs.ModelPool

/**
 * OCR 输出摘要（开发方案 3.2 第 [4] 步：ML Kit 返回 text + avgConfidence + hasFigure）。
 *
 * 这是路由决策的唯一输入，刻意做成纯数据类，使「路由矩阵」可以在无 Android 的
 * JVM 单测中穷举覆盖。
 *
 * @param text OCR 识别出的题干文本
 * @param avgConfidence 平均置信度（0..1）；ML Kit 的 confidence 缺失时由调用方给 0
 * @param hasFigure 题干是否含几何图/函数图象/表格
 * @param isHandwritten 是否手写体。ML Kit Text Recognition v2 不直接给该标记，
 *        由 ml 成员按「笔画边缘特征 + 用户选择」判定后传入，默认 false（印刷体）
 */
data class OcrSnapshot(
    val text: String = "",
    val avgConfidence: Double = 0.0,
    val hasFigure: Boolean = false,
    val isHandwritten: Boolean = false,
) {
    init {
        require(avgConfidence.isFinite()) { "avgConfidence 必须为有限值" }
    }

    /** 置信度裁剪到 0..1，容忍个别引擎返回 0..100。 */
    val normalizedConfidence: Double get() = avgConfidence.coerceIn(0.0, 1.0)

    /** 文本是否可用（少于 2 个字符视为识别失败）。 */
    val hasUsableText: Boolean get() = text.trim().length >= 2

    /** 纯印刷体。 */
    val isPrinted: Boolean get() = !isHandwritten

    companion object {
        /** ML Kit 判定阈值（开发方案 3.2 第 [5] 步）。 */
        const val CONFIDENCE_THRESHOLD: Double = 0.80

        /** 识别失败时的兜底快照。 */
        val EMPTY: OcrSnapshot = OcrSnapshot()
    }
}

/** 路由目标（开发方案 3.3 分级调用）。 */
enum class RouteTarget(val wire: String, val label: String) {
    /** 纯文本模型（便宜档），预计 1.5K-3K token。 */
    TEXT("text", "文本模型（便宜档）"),

    /** 视觉模型，携带 base64 图片，预计 3K-8K token。 */
    VISION("vision", "视觉模型（带图）"),
}

/**
 * 一次路由决策的完整结果。
 *
 * @param target 选中的档位
 * @param model 实际模型名（来自 [ModelPool]）
 * @param reason 中文原因，直接展示给用户（开发方案：让用户看得懂为什么这次更贵）
 * @param attachImage 是否把 base64 图片放进请求
 */
data class RouteDecision(
    val target: RouteTarget,
    val model: String,
    val reason: String,
    val attachImage: Boolean,
) {
    val isVision: Boolean get() = target == RouteTarget.VISION

    /** 是否走便宜档（用于设置页「本次消耗更少」提示）。 */
    val isCheap: Boolean get() = target == RouteTarget.TEXT
}

/**
 * 分级路由（开发方案 3.2 第 [5] 步 / 3.3 分级调用与成本路由规则）。
 *
 * 判定规则（文档原文）：
 * 印刷体 且 置信度 >= 0.80 且 无图形 -> 纯文本模型（便宜档）；
 * 否则（手写 / 含图 / 置信度低） -> 视觉模型（带 base64 图片）。
 *
 * 设计要点：
 * - 决策函数可注入（[decideWith] 与构造参数 [policy]），便于单测穷举决策矩阵；
 * - 视觉关停（visionDisabled）时退化为文本路由并给出明确原因，
 *   对应「用户选了无视觉能力的厂商（如 DeepSeek）」的现实场景；
 * - 无可用文本（OCR 失败）时即使无图也必须走视觉，否则没有输入可用。
 */
class ModelRouter(
    private val modelPool: ModelPool = ModelPool.PLACEHOLDER,
    /** 可注入的自定义决策（返回 null 表示交回默认策略）。 */
    private val policy: ((OcrSnapshot, ModelPool) -> RouteDecision?)? = null,
    /** 视觉能力是否可用；false 时强制文本并说明原因。 */
    private val visionAvailable: Boolean = true,
) {
    /** 根据 OCR 快照决定档位与模型。 */
    fun route(snapshot: OcrSnapshot): RouteDecision {
        policy?.invoke(snapshot, modelPool)?.let { return it }
        return decideWith(snapshot, modelPool, visionAvailable)
    }

    /** 便捷重载：直接用散装参数路由。 */
    fun route(
        avgConfidence: Double,
        hasFigure: Boolean,
        isHandwritten: Boolean = false,
        text: String = "占位题干",
    ): RouteDecision =
        route(
            OcrSnapshot(
                text = text,
                avgConfidence = avgConfidence,
                hasFigure = hasFigure,
                isHandwritten = isHandwritten,
            ),
        )

    companion object {
        /** 默认判定阈值。 */
        const val CONFIDENCE_THRESHOLD: Double = OcrSnapshot.CONFIDENCE_THRESHOLD

        /**
         * 纯函数路由决策（无状态，可任意组合与断言）。
         *
         * 判定顺序（先命中先返回，顺序即优先级，禁止调换）：
         * 1. 手写体 -> 视觉；
         * 2. 含图 -> 视觉；
         * 3. 置信度 < 0.80 -> 视觉；
         * 4. 文本不可用（OCR 失败） -> 视觉；
         * 5. 以上都不满足 -> 文本；若此时视觉不可用，仍为文本。
         */
        fun decideWith(
            snapshot: OcrSnapshot,
            pool: ModelPool,
            visionAvailable: Boolean = true,
        ): RouteDecision {
            val visionModel = pool.visionModel.ifBlank { pool.textModel }
            val confidence = snapshot.normalizedConfidence

            val reasonIfVision =
                when {
                    snapshot.isHandwritten -> "手写体，OCR 不可靠，改用视觉模型看原图"
                    snapshot.hasFigure -> "题干含几何图/函数图，文字无法完整表达，改用视觉模型"
                    confidence < CONFIDENCE_THRESHOLD ->
                        "OCR 置信度 " + formatConfidence(confidence) + " 低于 0.80，改用视觉模型核对原图"
                    !snapshot.hasUsableText -> "OCR 未识别出可用文字，改用视觉模型直接看图"
                    else -> null
                }

            if (reasonIfVision != null) {
                return if (visionAvailable) {
                    RouteDecision(
                        target = RouteTarget.VISION,
                        model = visionModel,
                        reason = reasonIfVision,
                        attachImage = true,
                    )
                } else {
                    RouteDecision(
                        target = RouteTarget.TEXT,
                        model = pool.textModel,
                        reason = reasonIfVision + "；但当前配置的厂商无视觉模型，已退回文本模型，识别质量可能下降",
                        attachImage = false,
                    )
                }
            }

            return RouteDecision(
                target = RouteTarget.TEXT,
                model = pool.textModel,
                reason = "印刷体且置信度 " + formatConfidence(confidence) + " 不低于 0.80 且无图形，使用文本模型（更省成本）",
                attachImage = false,
            )
        }

        /** 置信度展示：两位小数，避免出现 0.8000000000000001 这类浮点噪声。 */
        fun formatConfidence(value: Double): String {
            val percent = (value * 100).toInt() / 100.0
            return percent.toString()
        }
    }
}
