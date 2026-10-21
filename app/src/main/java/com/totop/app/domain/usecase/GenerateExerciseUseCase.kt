package com.totop.app.domain.usecase

import com.totop.app.domain.model.Exercise
import com.totop.app.domain.model.ExerciseDifficulty
import com.totop.app.domain.model.KnowledgeIdConvention
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.DomainSettings
import com.totop.app.domain.usecase.port.ExerciseDraftOutcome
import com.totop.app.domain.usecase.port.ExerciseGenerator
import com.totop.app.domain.usecase.port.ExerciseRequest
import com.totop.app.domain.usecase.port.ExerciseStore
import com.totop.app.domain.usecase.port.IdGenerator
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.TokenUsageCount
import com.totop.app.domain.usecase.port.VerificationOutcome

/**
 * 一道变式题的处理结论（逐题可审计）。
 */
data class ExerciseDecision(
    val stem: String,
    val claimedAnswer: String,
    /** 独立重解得到的答案；校验调用失败时为 null。 */
    val derivedAnswer: String?,
    /** 是否通过独立重解校验。 */
    val verified: Boolean,
    /** 未通过或未校验的原因。 */
    val reason: String = "",
)

/** 生成练习题的总体结果。 */
sealed interface GenerateExerciseOutcome {
    /**
     * 生成完成（可能部分通过、部分被丢弃）。
     *
     * @param verified 已入库的题目（仅 verified=true 的）
     * @param rejected 未通过校验而被丢弃的题目（不入库，但把原因回传给用户）
     * @param usage 两次调用（生成 + 校验）的合计用量
     */
    data class Completed(
        val verified: List<Exercise>,
        val rejected: List<ExerciseDecision>,
        val usage: TokenUsageCount,
    ) : GenerateExerciseOutcome {
        /** 是否一题都没通过。 */
        val allRejected: Boolean get() = verified.isEmpty()

        /** 生成通过率文本，例如「1 / 2 道通过独立重解校验」。 */
        fun summaryText(): String = verified.size.toString() + " / " + (verified.size + rejected.size) + " 道通过独立重解校验"
    }

    /** 知识点 ID 不合规（必须是受控 ID，禁止自造）。 */
    data class InvalidKpoint(val kpointId: String) : GenerateExerciseOutcome {
        val userMessage: String get() = "知识点 ID 不合法：" + kpointId + "（必须是 KP_<学科>_<四位序号> 形式）"
    }

    /** 用户在设置里关闭了 AI 出题（FR-9.4：本次不生成，避免不必要 token 消耗）。 */
    data object DisabledBySettings : GenerateExerciseOutcome {
        val userMessage: String get() = "已在设置中关闭 AI 出题。开启后会按知识点生成变式题。"
    }

    /** 未配置 Key（本地模式）。 */
    data object LlmNotConfigured : GenerateExerciseOutcome {
        val userMessage: String get() = "还没有配置模型 API Key，暂时无法生成练习题。"
    }

    /** 生成阶段就失败了（没有可校验的题目）。 */
    data class GenerationFailed(val userMessage: String, val retryable: Boolean = false) : GenerateExerciseOutcome
}

/**
 * AI 生成练习题用例（开发方案 FR-9）。
 *
 * 核心是**二次独立重解校验**（FR-9.2）：
 * ```
 * 生成候选题 -> 另起一次独立调用让模型重解 -> 答案一致才入库（verified=true）
 *                                  \-> 不一致则丢弃并记录 rejected（verified=false，不入库）
 * ```
 *
 * 关键取舍：
 * - 校验是**另起一次调用**且只传题干（不把生成答案泄漏给重解），否则模型会「顺着答案找理由」；
 * - 校验调用失败（网络/解析）时该题**标记为未验证并丢弃**，绝不因为「校验没跑成」就当它是对的；
 * - 设置里可关闭 AI 出题（FR-9.4），关闭时不消耗任何 token；
 * - 只有 verified=true 的题写入 exercise 表，详情页与巩固练习只读 verified 题（FR-9.3）。
 */
class GenerateExerciseUseCase(
    private val generator: ExerciseGenerator,
    private val exercises: ExerciseStore,
    private val kpoints: KpointDirectory,
    private val settings: DomainSettings = DomainSettings.DEFAULTS,
    private val clock: DomainClock = DomainClock.SYSTEM,
    private val ids: IdGenerator = IdGenerator.UUID_GENERATOR,
) {
    /**
     * 按知识点生成变式题。
     *
     * @param kpointId 受控知识点 ID
     * @param difficulty 难度档位
     * @param count 期望条数（会被裁剪到 1..[MAX_PER_REQUEST]）
     * @param sourceStem 参考原型题干（通常是该知识点下最近的一道错题）
     */
    suspend fun generate(
        kpointId: String,
        difficulty: ExerciseDifficulty = ExerciseDifficulty.MEDIUM,
        count: Int = 2,
        sourceStem: String = "",
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): GenerateExerciseOutcome {
        if (!KnowledgeIdConvention.isValid(kpointId)) return GenerateExerciseOutcome.InvalidKpoint(kpointId)
        if (!settings.llmConfigured()) return GenerateExerciseOutcome.LlmNotConfigured
        if (!settings.exerciseAiEnabled()) return GenerateExerciseOutcome.DisabledBySettings

        val point =
            runCatching { kpoints.knowledgePoint(kpointId) }.getOrNull()
                ?: return GenerateExerciseOutcome.InvalidKpoint(kpointId)
        val wanted = count.coerceIn(1, MAX_PER_REQUEST)
        val requestId = ids.newId()

        val draft =
            generator.generate(
                ExerciseRequest(
                    kpointId = kpointId,
                    kpointName = point.name,
                    difficulty = difficulty,
                    count = wanted,
                    sourceStem = sourceStem,
                    maxTokens = maxTokens,
                ),
                requestId = requestId,
            )
        val success =
            when (draft) {
                is ExerciseDraftOutcome.Failure -> return GenerateExerciseOutcome.GenerationFailed(
                    userMessage = draft.userMessage,
                    retryable = draft.retryable,
                )

                is ExerciseDraftOutcome.Success -> draft
            }
        val drafts = success.drafts.take(wanted)
        if (drafts.isEmpty()) {
            return GenerateExerciseOutcome.GenerationFailed(userMessage = "模型没有给出可用的题目，请稍后重试。", retryable = true)
        }

        var usage = success.usage
        val nowUtc = clock.nowUtcMillis()
        val verified = mutableListOf<Exercise>()
        val rejected = mutableListOf<ExerciseDecision>()

        for ((index, item) in drafts.withIndex()) {
            val check = generator.verify(item.stem, item.answer, requestId + "-verify-" + index)
            when (check) {
                is VerificationOutcome.Failure -> {
                    rejected +=
                        ExerciseDecision(
                            stem = item.stem,
                            claimedAnswer = item.answer,
                            derivedAnswer = null,
                            verified = false,
                            reason = "独立重解校验未能完成：" + check.userMessage,
                        )
                }

                is VerificationOutcome.Success -> {
                    usage = usage.plus(check.usage)
                    val consistent = check.consistent
                    if (!consistent) {
                        rejected +=
                            ExerciseDecision(
                                stem = item.stem,
                                claimedAnswer = item.answer,
                                derivedAnswer = check.derivedAnswer,
                                verified = false,
                                reason = "独立重解答案与生成答案不一致，已丢弃",
                            )
                    } else {
                        val exercise =
                            Exercise(
                                id = ids.newId(),
                                kpointId = kpointId,
                                stem = item.stem,
                                answer = item.answer,
                                difficulty = difficulty,
                                verified = true,
                                createdAtUtc = nowUtc,
                            )
                        verified += exercise
                    }
                }
            }
        }

        if (verified.isNotEmpty()) {
            runCatching { exercises.saveAll(verified) }
        }
        return GenerateExerciseOutcome.Completed(
            verified = verified,
            rejected = rejected,
            usage = usage,
        )
    }

    /**
     * 判断「两个答案是否视为一致」（可单测；实现方也可直接复用）。
     *
     * 归一化：去空白、去美元符号、全角转半角、去末尾句号、统一 ± 与负号、忽略大小写。
     */
    fun answersMatch(
        left: String,
        right: String,
    ): Boolean = normalizeAnswer(left) == normalizeAnswer(right)

    /** 答案归一化（不改变语义，只吸收排版差异）。 */
    fun normalizeAnswer(answer: String): String {
        var value = answer.trim()
        value = value.replace("$", "").replace("\\(", "").replace("\\)", "")
        value = value.replace("\\[", "").replace("\\]", "")
        value = value.replace("\u00A0", " ").replace("\u3000", " ")
        value = value.replace("−", "-").replace("–", "-").replace("—", "-")
        value = value.replace("＋", "+").replace("－", "-").replace("＝", "=")
        value = value.replace("（", "(").replace("）", ")").replace("，", ",")
        value = value.replace(" ", "")
        value = value.trimEnd('。', '.', '；', ';')
        return value.lowercase()
    }

    companion object {
        /** 单次请求最多生成几道（成本控制：避免一次烧掉大量 token）。 */
        const val MAX_PER_REQUEST: Int = 5

        /** 单次生成调用的输出上限。 */
        const val DEFAULT_MAX_TOKENS: Int = 2048
    }
}
