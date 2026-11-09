package com.totop.app.ui.study

import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.ChatMessage
import com.totop.app.data.remote.ChatRequestConfig
import com.totop.app.data.remote.LlmCallRegistry
import com.totop.app.data.remote.LlmChunk
import com.totop.app.data.remote.LlmClient
import com.totop.app.data.remote.LlmException
import com.totop.app.data.remote.TokenUsage
import com.totop.app.domain.usecase.port.TokenUsageCount
import com.totop.app.domain.usecase.port.TrendNarrator
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 学情趋势解读的实现。
 *
 * 与周报解读的区别（不是重复实现，是两套约束）：
 * - 周报要求「指出最值得补的知识点」，学情页明确**不点名知识点**——学生看「多练的知识点」页就够了，
 *   让模型点知识点只会得到看起来专业的空话；
 * - 只给正向建议，不做退步评价；
 * - 数字只读：摘要由本机算好，模型不得改写或新增数字。
 *
 * 依赖边界与 `ReportNarratorImpl` 一致：只用 data 层的 `LlmClient` 门面，
 * 不直接构造 OkHttp，也不参与任何统计计算。
 */
@Singleton
class TrendNarratorImpl
    @Inject
    constructor(
        private val secretStore: SecretStore,
        private val llmClient: LlmClient,
    ) : TrendNarrator {
        override suspend fun narrate(
            summaryJson: String,
            requestId: String,
        ): TrendNarrator.Outcome {
            val credentials = secretStore.read()
            if (!credentials.canCall) {
                return TrendNarrator.Outcome.Failure(
                    "尚未配置模型（Base URL / API Key / 模型名），本次只显示数据。",
                )
            }
            val registry = LlmCallRegistry()
            val buffer = StringBuilder()
            var usage = TokenUsage.EMPTY
            val config =
                ChatRequestConfig(
                    model = credentials.effectiveTextModel,
                    messages =
                        listOf(
                            ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user(userPrompt(summaryJson)),
                        ),
                    maxTokens = MAX_TOKENS,
                    temperature = 0.4,
                    jsonObject = false,
                    includeUsage = true,
                    stream = true,
                )
            return try {
                llmClient.stream(credentials, config, requestId, registry, allowRetry = true).collect { chunk ->
                    when (chunk) {
                        is LlmChunk.Delta -> buffer.append(chunk.text)
                        is LlmChunk.Reasoning -> Unit
                        is LlmChunk.Finished -> usage = chunk.usage
                        is LlmChunk.Failed -> throw LlmException(chunk.error)
                    }
                }
                val text = trimToBudget(buffer.toString())
                if (text.isBlank()) {
                    TrendNarrator.Outcome.Failure("模型没有返回可用文本，本次只显示数据。")
                } else {
                    TrendNarrator.Outcome.Success(
                        text = text,
                        usage = TokenUsageCount(inputTokens = usage.inputTokens, outputTokens = usage.outputTokens),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: LlmException) {
                TrendNarrator.Outcome.Failure(error.error.userMessage)
            } catch (throwable: Throwable) {
                TrendNarrator.Outcome.Failure(
                    "生成解读失败：" + (throwable.message ?: throwable.javaClass.simpleName) + "，本次只显示数据。",
                )
            } finally {
                registry.cancelAll()
            }
        }

        companion object {
            /** 硬上限：超出后按句号收敛。 */
            const val MAX_CHARS: Int = 320

            /** 输出 token 上限。 */
            const val MAX_TOKENS: Int = 600

            private const val SYSTEM_PROMPT: String =
                "你是中学学习助手，只根据给定的考试成绩摘要写一段约 150 字的中文说明。" +
                    "语气平实、具体、正向，不评价学生本人。"

            /** 用户提示词：摘要 JSON + 硬性约束。 */
            fun userPrompt(summaryJson: String): String =
                buildString {
                    append("下面是某学生最近几次考试的成绩摘要（JSON，数字全部由本机统计生成）：\n")
                    append(summaryJson)
                    append("\n\n请输出一段约 150 字的中文说明，必须遵守：\n")
                    append("1. 不得改写、重新计算或新增任何数字，只能引用 JSON 里已有的数字；\n")
                    append("2. 只描述趋势与进步之处，不做退步评价、不使用评判性词汇；\n")
                    append("3. 不要点名任何知识点或题目，不要给书单，不要说「去练某某」；\n")
                    append("4. 不要 Markdown 标题、不要列表符号、不要客套话，直接给一段连续文字。")
                }

            /** 截断到 [MAX_CHARS]，按句号收敛，避免半句话。 */
            fun trimToBudget(raw: String): String {
                val text = raw.trim().replace(Regex("\\n{2,}"), "\n")
                if (text.length <= MAX_CHARS) return text
                val cut = text.take(MAX_CHARS)
                val lastPeriod = cut.lastIndexOf('。')
                return if (lastPeriod > MAX_CHARS / 2) cut.substring(0, lastPeriod + 1) else cut + "…"
            }
        }
    }
