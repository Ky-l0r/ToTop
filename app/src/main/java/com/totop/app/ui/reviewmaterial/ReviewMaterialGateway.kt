package com.totop.app.ui.reviewmaterial

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.remote.ChatMessage
import com.totop.app.data.remote.ChatRequestConfig
import com.totop.app.data.remote.LlmChunk
import com.totop.app.data.remote.LlmException
import com.totop.app.data.remote.LlmTransport
import com.totop.app.data.remote.TokenUsage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 生成结果。 */
sealed class ReviewMaterialResult {
    data class Success(val material: ReviewMaterial, val usage: TokenUsage) : ReviewMaterialResult()

    data class Failure(val error: com.totop.app.data.remote.LlmError) : ReviewMaterialResult()

    /** 把解析后的结果补上真实 usage；失败结果原样返回。 */
    fun withUsage(usage: TokenUsage): ReviewMaterialResult =
        when (this) {
            is Success -> copy(usage = usage)
            is Failure -> this
        }
}

/**
 * 考前复习资料生成网关。
 *
 * 为什么单独一个网关而不是复用 [com.totop.app.data.remote.AiGateway]：
 * 四阶段网关的产物是「一道题」的完整解题链路（含错因定位），
 * 而复习资料的产物是「一份跨多题的总结」，两者的输入输出契约完全不同。
 * 强行复用会让四阶段提示词背上一个不属于它的模板分支，
 * 也会破坏「阶段 D 只从候选清单选 ID」这条硬约束。
 *
 * 复用点在于**传输层与错误分类**：都走 [LlmTransport] 与同一套 LlmError，
 * 因此「模型没连上」在解题页与复习资料页有完全一致的可读表达。
 */
@Singleton
class ReviewMaterialGateway
    @Inject
    constructor(
        private val llmClient: LlmTransport,
    ) {
        /**
         * 生成一份复习资料。
         *
         * @param subjectLabel 科目中文名
         * @param mistakes 本地错题摘要（题干 + 错因），由调用方从 Room 读取后传入，
         *   保证「资料里讲的是你自己错过的题」而不是模型凭空编的通用题
         */
        fun generate(
            credentials: LlmCredentials,
            subjectLabel: String,
            mistakes: List<ReviewMistake>,
            requestId: String,
        ): Flow<ReviewMaterialResult> =
            channelFlow {
                if (!credentials.canCall) {
                    send(
                        ReviewMaterialResult.Failure(
                            com.totop.app.data.remote.LlmError.Unknown(
                                detail = "尚未配置模型（Base URL / API Key / 模型名），请到设置页填写",
                            ),
                        ),
                    )
                    return@channelFlow
                }
                val config =
                    ChatRequestConfig(
                        model = credentials.effectiveTextModel,
                        messages =
                            listOf(
                                ChatMessage.system(SYSTEM_PROMPT),
                                ChatMessage.user(buildUserPrompt(subjectLabel, mistakes)),
                            ),
                        maxTokens = ChatRequestConfig.MAX_TOKENS_DETAILED,
                        temperature = 0.3,
                        jsonObject = true,
                        stream = true,
                        thinkingMode = ChatRequestConfig.THINKING_DISABLED,
                    )
                val buffer = StringBuilder()
                var usage = TokenUsage.EMPTY
                var streamError: com.totop.app.data.remote.LlmError? = null
                try {
                    llmClient.stream(credentials, config, requestId, registry = null, allowRetry = true)
                        .collect { chunk ->
                            when (chunk) {
                                is LlmChunk.Delta -> buffer.append(chunk.text)
                                is LlmChunk.Finished -> usage = chunk.usage
                                is LlmChunk.Failed -> streamError = chunk.error
                                is LlmChunk.Reasoning -> Unit
                            }
                        }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: LlmException) {
                    send(ReviewMaterialResult.Failure(exception.error))
                    return@channelFlow
                }
                streamError?.let { error ->
                    send(ReviewMaterialResult.Failure(error))
                    return@channelFlow
                }
                val raw = buffer.toString()
                if (raw.isBlank()) {
                    send(
                        ReviewMaterialResult.Failure(
                            com.totop.app.data.remote.LlmError.EmptyCompletion(
                                detail = "模型没有返回内容（复习资料生成）",
                            ),
                        ),
                    )
                    return@channelFlow
                }
                val parsed = parse(raw, subjectLabel, mistakes)
                // 把真实 usage 传下去：旧实现固定返回 TokenUsage.EMPTY，
                // 导致界面上的 token 统计永远是 0，用户无法判断这次花了多少。
                send(parsed.withUsage(usage))
            }

        /** 解析模型输出；解析失败要给出明确原因而不是空资料。 */
        private fun parse(
            raw: String,
            subjectLabel: String,
            mistakes: List<ReviewMistake>,
        ): ReviewMaterialResult {
            val json =
                runCatching { Json.parseToJsonElement(trimToJsonObject(raw)) as JsonObject }
                    .getOrElse { failure ->
                        return ReviewMaterialResult.Failure(
                            com.totop.app.data.remote.LlmError.MalformedResponse(
                                detail = "复习资料不是合法 JSON：" + (failure.message ?: "未知原因"),
                            ),
                        )
                    }
            val summary = (json["summary"] as? JsonPrimitive)?.content.orEmpty()
            val tips = (json["tips"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            if (summary.isBlank() && tips.isEmpty()) {
                return ReviewMaterialResult.Failure(
                    com.totop.app.data.remote.LlmError.MalformedResponse(
                        detail = "复习资料缺少 summary 与 tips 两个必需字段",
                    ),
                )
            }
            val kpoints =
                (json["weakKpoints"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    ReviewKpoint(
                        name = name,
                        mistakeCount = (obj["mistakeCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                        reminder = (obj["reminder"] as? JsonPrimitive)?.content.orEmpty(),
                    )
                }
            val exercises =
                (json["exercises"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val stem = (obj["stem"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    ReviewExercise(
                        stem = stem,
                        answer = (obj["answer"] as? JsonPrimitive)?.content.orEmpty(),
                        analysis = (obj["analysis"] as? JsonPrimitive)?.content.orEmpty(),
                    )
                }
            return ReviewMaterialResult.Success(
                material =
                    ReviewMaterial(
                        subjectLabel = subjectLabel,
                        examDate = "考前复习",
                        summary = summary,
                        weakKpoints = kpoints,
                        // 典型错题直接用本地真实数据，不交给模型复述：
                        // 模型的措辞可能把题干改错，而这些题用户自己认得出来。
                        typicalMistakes = mistakes.take(MAX_MISTAKES),
                        exercises = exercises,
                        tips = tips,
                        createdAtUtc = System.currentTimeMillis(),
                    ),
                usage = TokenUsage.EMPTY,
            )
        }

        /** 从可能含 Markdown 围栏或前置说明的输出里截出 JSON 对象。 */
        private fun trimToJsonObject(raw: String): String {
            val text = raw.trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "输出中没有 JSON 对象" }
            return text.substring(start, end + 1)
        }

        companion object {
            /** 最多带几道真实错题进教材。 */
            const val MAX_MISTAKES: Int = 5

            private const val SYSTEM_PROMPT =
                "你是一名中学教研员，负责根据学生自己错过的题，生成一份考前针对性复习资料。" +
                    "只输出一个 JSON 对象，不要任何解释文字或 Markdown 代码围栏。"

            /** 组装用户消息：把真实错题摘要交给模型，避免它凭空编题。 */
            private fun buildUserPrompt(
                subjectLabel: String,
                mistakes: List<ReviewMistake>,
            ): String {
                val builder = StringBuilder()
                builder.append("科目：").append(subjectLabel).append('\n')
                if (mistakes.isEmpty()) {
                    builder.append("这位学生在该科目下还没有错题记录，请生成一份该科目的通用考前要点。\n")
                } else {
                    builder.append("以下是这位学生真实错过的题（题干 / 错在哪 / 正确思路）：\n")
                    mistakes.forEachIndexed { index, mistake ->
                        builder.append(index + 1).append(". 题干：").append(mistake.stem).append('\n')
                        builder.append("   错因：").append(mistake.errorSummary).append('\n')
                        builder.append("   正确思路：").append(mistake.correctIdea).append('\n')
                    }
                }
                builder.append(
                    """

                    |请输出这个 JSON 结构：
                    |{
                    |  "summary": "一句话总述这次该重点看什么",
                    |  "weakKpoints": [{"name":"知识点名","mistakeCount":数字,"reminder":"一句话提醒"}],
                    |  "exercises": [{"stem":"同类练习题题干","answer":"答案","analysis":"解析"}],
                    |  "tips": ["考前建议1","考前建议2","考前建议3"]
                    |}
                    |要求：
                    |1. weakKpoints 最多 4 条，必须来自上面真实错题反映的薄弱点；
                    |2. exercises 给 2 到 3 道同类题，难度与错题相当，题干完整可独立作答；
                    |3. tips 给 3 到 5 条，要具体可执行，不要说「认真复习」这种空话；
                    |4. 全部用中文，不要出现 emoji。
                    """.trimMargin(),
                )
                return builder.toString()
            }
        }
    }
