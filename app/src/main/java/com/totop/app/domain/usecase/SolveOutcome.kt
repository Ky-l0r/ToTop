package com.totop.app.domain.usecase

import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.TeachingMode
import com.totop.app.domain.usecase.port.TokenUsageCount

/**
 * 解题结果。
 *
 * 三种终态互斥，UI 按类型分派即可，不需要再判断一堆布尔标志。
 */
sealed interface SolveOutcome {
    /**
     * 成功（已落库）。
     *
     * @param view 按 [TeachingMode] 裁剪后的下发视图（GUIDED 未揭示时无步骤、无答案）
     * @param question 已落库的题目
     * @param record 已落库的错题记录；学生全对时为 null（题目入库但不进错题本）
     * @param cacheHit 是否命中本地缓存（true 表示本次 0 token）
     * @param usage 本次 token 用量（缓存命中为 0）
     * @param warnings 非致命告警，必须落日志
     */
    data class Success(
        val view: SolvedView,
        val question: Question,
        val record: MistakeRecord?,
        val cacheHit: Boolean,
        val usage: TokenUsageCount = TokenUsageCount.EMPTY,
        val warnings: List<String> = emptyList(),
    ) : SolveOutcome {
        /** 是否没有发现错误（学生全对或未提供过程）。 */
        val noErrorFound: Boolean get() = record == null
    }

    /**
     * 失败：**绝不写库**（开发方案 3.2 第 [12] 步、FR-1.10）。
     *
     * @param userMessage 面向用户的中文原因，禁止「请求失败」这类无语义文案
     */
    data class Failed(
        val userMessage: String,
        val retryable: Boolean = false,
        val warnings: List<String> = emptyList(),
    ) : SolveOutcome

    /** 未配置 API Key（本地模式，开发方案 6.5：一次性引导到设置页，不静默失败）。 */
    data class LlmNotConfigured(
        val userMessage: String = "还没有配置模型 API Key。配置后可使用 AI 解题；现在仍可拍照、记录笔记并手动归入错题本。",
    ) : SolveOutcome

    /** 图片压缩失败（不进入任何模型调用）。 */
    data class ImageRejected(
        val userMessage: String,
        val detail: String? = null,
    ) : SolveOutcome
}

/** 供 UI 展示的缓存键（调试面板 / 成本说明用）。 */
data class SolveDiagnostics(
    val cacheKey: SolveCacheKey,
    val routeLabel: String? = null,
    val routeReason: String? = null,
    val ocrConfidence: Double = 0.0,
    val ocrHasFigure: Boolean = false,
)
