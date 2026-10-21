package com.totop.app.ui.report

import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.ChatMessage
import com.totop.app.data.remote.ChatRequestConfig
import com.totop.app.data.remote.LlmCallRegistry
import com.totop.app.data.remote.LlmChunk
import com.totop.app.data.remote.LlmClient
import com.totop.app.data.remote.LlmException
import com.totop.app.data.remote.TokenUsage
import com.totop.app.domain.usecase.port.ReportNarrator
import com.totop.app.domain.usecase.port.TokenUsageCount
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 周报 AI 解读的实现（FR-5.3 / FR-5.4）。
 *
 * 依赖边界：
 * - 只用 data 层的 [LlmClient] 门面（SSE + 错误分类 + 超时 + 退避），**不直接构造 OkHttp/Retrofit**；
 * - 只写文字，**不参与任何数字计算**：提示词里明确禁止改动统计数字，
 *   并且渲染模板 [com.totop.app.domain.usecase.BuildWeeklyReportUseCase.renderMarkdown]
 *   会把 AI 段落单独放在「五、AI 解读与建议（AI 生成）」小节，数字段落全部来自本地统计。
 *
 * 提示词位置说明：四阶段提示词外置在 assets/prompts 并参与缓存键；
 * 周报解读是一次性短文本生成（约 200 字），不参与 sha256 缓存，因此提示词就近定义在这里。
 * 若后续要把提示词也外置，只需在 assets/prompts 增加 report_narrate.md 并改这里读取。
 */
@Singleton
class ReportNarratorImpl
    @Inject
    constructor(
        private val secretStore: SecretStore,
        private val llmClient: LlmClient,
    ) : ReportNarrator {
        override suspend fun narrate(
            statsJson: String,
            requestId: String,
        ): ReportNarrator.NarrativeOutcome {
            val credentials = secretStore.read()
            if (!credentials.canCall) {
                return ReportNarrator.NarrativeOutcome.Failure(
                    "尚未配置模型（Base URL / API Key / 模型名），已输出纯统计版周报。",
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
                            ChatMessage.user(userPrompt(statsJson)),
                        ),
                    maxTokens = MAX_TOKENS,
                    temperature = 0.3,
                    // 解读是自然语言段落，不需要 JSON 模式（部分本地模型不支持该字段）
                    jsonObject = false,
                    includeUsage = true,
                    stream = true,
                )
            return try {
                llmClient.stream(credentials, config, requestId, registry, allowRetry = true).collect { chunk ->
                    when (chunk) {
                        is LlmChunk.Delta -> buffer.append(chunk.text)
                        // 思考链不计入解读正文，丢弃即可（周报只要结论段落）
                        is LlmChunk.Reasoning -> Unit
                        is LlmChunk.Finished -> usage = chunk.usage
                        is LlmChunk.Failed -> throw LlmException(chunk.error)
                    }
                }
                val text = trimToBudget(buffer.toString())
                if (text.isBlank()) {
                    ReportNarrator.NarrativeOutcome.Failure("模型没有返回可用的解读文本，已输出纯统计版周报。")
                } else {
                    ReportNarrator.NarrativeOutcome.Success(
                        text = text,
                        usage = TokenUsageCount(inputTokens = usage.inputTokens, outputTokens = usage.outputTokens),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: LlmException) {
                ReportNarrator.NarrativeOutcome.Failure(error.error.userMessage)
            } catch (throwable: Throwable) {
                ReportNarrator.NarrativeOutcome.Failure(
                    "生成 AI 解读失败：" + (throwable.message ?: throwable.javaClass.simpleName) + "，已输出纯统计版周报。",
                )
            } finally {
                registry.cancelAll()
            }
        }

        companion object {
            /** 解读目标长度（字）。 */
            const val TARGET_CHARS: Int = 200

            /** 硬上限：超出后截断，避免一段话占满整页。 */
            const val MAX_CHARS: Int = 420

            /** 输出 token 上限（约 420 汉字）。 */
            const val MAX_TOKENS: Int = 700

            private const val SYSTEM_PROMPT: String =
                "你是中学数学学习助手。你只根据给定统计写一段约 200 字的中文学习建议，语气平实、具体、可执行。"

            /** 用户提示词：把统计 JSON 与硬性约束一起交给模型。 */
            fun userPrompt(statsJson: String): String =
                buildString {
                    append("下面是本周的学习统计（JSON，数字全部由本机统计生成）：\n")
                    append(statsJson)
                    append("\n\n请输出一段约 200 字的中文解读与建议，必须遵守：\n")
                    append("1. 不得改写、重新计算或新增任何数字，只能引用 JSON 里已有的数字；\n")
                    append("2. 不得使用任何评判性、比率式表述（不把错题数折算成得分或比率），也不对学生做评价；\n")
                    append("3. 先指出最值得优先补的一到两个知识点，再给一条今天就能执行的具体动作；\n")
                    append("4. 不要 Markdown 标题、不要列表符号、不要客套话，直接给一段连续文字。")
                }

            /** 截断到 [MAX_CHARS] 个字符（按句号收敛，避免半句话结尾）。 */
            fun trimToBudget(raw: String): String {
                val text = raw.trim().replace(Regex("\\n{2,}"), "\n")
                if (text.length <= MAX_CHARS) return text
                val cut = text.take(MAX_CHARS)
                val lastPeriod = cut.lastIndexOf('。')
                return if (lastPeriod > MAX_CHARS / 2) cut.substring(0, lastPeriod + 1) else cut + "…"
            }
        }
    }
