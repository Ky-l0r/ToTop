package com.totop.app.ui.reviewmaterial

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.remote.ChatMessage
import com.totop.app.data.remote.ChatRequestConfig
import com.totop.app.data.remote.LlmChunk
import com.totop.app.data.remote.LlmError
import com.totop.app.data.remote.LlmException
import com.totop.app.data.remote.LlmTransport
import com.totop.app.data.remote.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 *
 * 提示词本体外置在 `assets/prompts/review_material.md`（见 [ReviewMaterialPrompt]），
 * 由调用方读好后传入：这样网关保持纯 JVM、可单测，不依赖 Android Context。
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
         * @param mistakes 本地错题摘要（题干 + 错因 + 正确思路），由调用方从 Room 读取后传入，
         *   保证「资料里讲的是你自己错过的题」而不是模型凭空编的通用题
         * @param systemPrompt 教研模板全文，见 [ReviewMaterialPrompt]
         * @param forgottenPoints 学生在陪伴模式里问过、说明容易忘的知识点名
         */
        fun generate(
            credentials: LlmCredentials,
            subjectLabel: String,
            mistakes: List<ReviewMistake>,
            requestId: String,
            systemPrompt: String,
            forgottenPoints: List<String> = emptyList(),
        ): Flow<ReviewMaterialResult> =
            channelFlow {
                if (!credentials.canCall) {
                    send(
                        ReviewMaterialResult.Failure(
                            LlmError.Unknown(
                                detail = "尚未配置模型（Base URL / API Key / 模型名），请到设置页填写",
                            ),
                        ),
                    )
                    return@channelFlow
                }
                if (systemPrompt.isBlank()) {
                    send(ReviewMaterialResult.Failure(LlmError.Unknown(detail = "复习资料提示词为空，无法生成")))
                    return@channelFlow
                }
                val userPrompt = buildUserPrompt(subjectLabel, mistakes, forgottenPoints)
                // 先按较大预算请求（目标六七页）；厂商若拒绝这个大值，退回保守预算再试一次。
                // 只降一次：再失败就是模型本身的问题，反复试只会烧钱。
                var budget = MAX_TOKENS_REVIEW
                var outcome =
                    requestOnce(
                        credentials = credentials,
                        subjectLabel = subjectLabel,
                        mistakes = mistakes,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        requestId = requestId,
                        maxTokens = budget,
                    )
                // 只降一次：厂商嫌大就换保守值，再失败就是模型本身的问题
                while (outcome is ReviewMaterialResult.Failure &&
                    outcome.error is LlmError.ParameterNotSupported &&
                    budget > FALLBACK_MAX_TOKENS
                ) {
                    budget = FALLBACK_MAX_TOKENS
                    outcome =
                        requestOnce(
                            credentials = credentials,
                            subjectLabel = subjectLabel,
                            mistakes = mistakes,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            requestId = requestId,
                            maxTokens = budget,
                        )
                }
                send(outcome)
            }

        /**
         * 发一次请求：流式收集 → 截断判断 → 解析。
         *
         * 失败一律作为返回值而不是直接 send：调用方要据此决定「换个小一点的预算再试」，
         * 早退就把这个机会堵死了。
         */
        @Suppress("LongParameterList")
        private suspend fun requestOnce(
            credentials: LlmCredentials,
            subjectLabel: String,
            mistakes: List<ReviewMistake>,
            systemPrompt: String,
            userPrompt: String,
            requestId: String,
            maxTokens: Int,
        ): ReviewMaterialResult {
            val config =
                ChatRequestConfig(
                    model = credentials.effectiveTextModel,
                    messages =
                        listOf(
                            ChatMessage.system(systemPrompt),
                            ChatMessage.user(userPrompt),
                        ),
                    maxTokens = maxTokens,
                    // 略高于解题链路：作文命题预测与素材库需要一点发散空间，
                    // 但错因与知识点必须稳定，因此不再往上调。
                    temperature = 0.35,
                    jsonObject = true,
                    stream = true,
                    thinkingMode = ChatRequestConfig.THINKING_DISABLED,
                )
            val buffer = StringBuilder()
            var usage = TokenUsage.EMPTY
            var finishReason = ""
            var streamError: LlmError? = null
            try {
                llmClient.stream(credentials, config, requestId, registry = null, allowRetry = true)
                    .collect { chunk ->
                        when (chunk) {
                            is LlmChunk.Delta -> buffer.append(chunk.text)
                            is LlmChunk.Finished -> {
                                usage = chunk.usage
                                if (chunk.finishReason.isNotBlank()) finishReason = chunk.finishReason
                            }
                            is LlmChunk.Failed -> streamError = chunk.error
                            is LlmChunk.Reasoning -> Unit
                        }
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: LlmException) {
                return ReviewMaterialResult.Failure(exception.error)
            }
            streamError?.let { return ReviewMaterialResult.Failure(it) }
            // 被 max_tokens 截断时 JSON 必然不完整，继续解析只会报一个
            // 「不是合法 JSON」的假原因，掩盖真正的问题。
            if (finishReason == FINISH_REASON_LENGTH) {
                return ReviewMaterialResult.Failure(
                    LlmError.Unknown(
                        detail = "模型输出超出单次上限被截断（finish_reason=length），这次资料不完整，请重试一次",
                    ),
                )
            }
            val raw = buffer.toString()
            if (raw.isBlank()) {
                return ReviewMaterialResult.Failure(
                    LlmError.EmptyCompletion(detail = "模型没有返回内容（复习资料生成）"),
                )
            }
            // 把真实 usage 传下去：旧实现固定返回 TokenUsage.EMPTY，
            // 导致界面上的 token 统计永远是 0，用户无法判断这次花了多少。
            return parse(raw, subjectLabel, mistakes).withUsage(usage)
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
                        return failureOf("复习资料不是合法 JSON：" + (failure.message ?: "未知原因"))
                    }
            val summaryText = (json["summary"] as? JsonPrimitive)?.content.orEmpty().trim()
            val summary = ReviewText.formatChemical(summaryText)
            val sections =
                (json["sections"] as? JsonArray).orEmpty()
                    .mapNotNull { element -> parseSection(element) }
                    .take(MAX_SECTIONS)
            if (sections.isEmpty()) {
                return failureOf("复习资料缺少可用的 sections（章节为空或字段名不对）")
            }
            return ReviewMaterialResult.Success(
                material =
                    ReviewMaterial(
                        subjectLabel = subjectLabel,
                        examDate = EXAM_DATE_LABEL,
                        summary = summary.ifBlank { "这份资料按本科目的高频考点与你的错题整理。" },
                        sections = sections,
                        mistakesUsed = mistakes.size,
                        createdAtUtc = System.currentTimeMillis(),
                    ),
                usage = TokenUsage.EMPTY,
            )
        }

        /** 解析一个章节；标题为空或没有可用内容时丢弃。 */
        private fun parseSection(element: JsonElement): ReviewSection? {
            val obj = element as? JsonObject ?: return null
            val rawTitle = (obj["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (rawTitle.isEmpty()) return null
            val title = ReviewText.formatChemical(rawTitle)
            val blocks =
                (obj["blocks"] as? JsonArray).orEmpty().mapNotNull { block -> parseBlock(block) }
            if (blocks.isEmpty()) {
                // 容忍模型把章节直接写成 lines 数组的简化形态
                val fallback = parseLines(obj["lines"], ReviewBlockStyle.PARAGRAPH)
                if (fallback.isEmpty()) return null
                return ReviewSection(
                    title = title,
                    blocks = listOf(ReviewBlock(style = ReviewBlockStyle.PARAGRAPH, lines = fallback)),
                )
            }
            return ReviewSection(title = title, blocks = blocks)
        }

        /**
         * 解析一个内容块。
         *
         * 宽容三种写法：标准对象、纯字符串（当段落）、只有 text 字段。
         * 模型的输出形态在小语种模型上并不稳定，与其整份失败，不如归一后照排。
         */
        private fun parseBlock(element: JsonElement): ReviewBlock? {
            if (element is JsonPrimitive) {
                val single = parseLines(JsonArray(listOf(element)), ReviewBlockStyle.PARAGRAPH)
                return single.takeIf { it.isNotEmpty() }?.let {
                    ReviewBlock(style = ReviewBlockStyle.PARAGRAPH, lines = it)
                }
            }
            val obj = element as? JsonObject ?: return null
            val style = ReviewBlockStyle.parse((obj["style"] as? JsonPrimitive)?.content)
            val lines =
                parseLines(obj["lines"], style).ifEmpty {
                    val single = (obj["text"] as? JsonPrimitive)?.content.orEmpty()
                    parseLines(JsonArray(listOf(JsonPrimitive(single))), style)
                }
            if (lines.isEmpty()) return null
            return ReviewBlock(style = style, lines = lines)
        }

        /** 把 lines 数组清洗成可排版的短行；空行与超长行在这里被处理掉。 */
        private fun parseLines(
            element: JsonElement?,
            style: ReviewBlockStyle,
        ): List<String> {
            val array = element as? JsonArray ?: return emptyList()
            return array
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .flatMap { text -> text.split('\n') }
                .map { line -> ReviewText.formatChemical(ReviewText.sanitizeLine(line, style)) }
                .filter { it.isNotEmpty() }
                .take(MAX_LINES_PER_BLOCK)
        }

        private fun failureOf(detail: String): ReviewMaterialResult =
            ReviewMaterialResult.Failure(
                com.totop.app.data.remote.LlmError.MalformedResponse(detail = detail),
            )

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
            const val MAX_MISTAKES: Int = 8

            /** 单条题干最多带多少字：够模型判断题型，又不会把上下文撑满。 */
            const val MAX_STEM_CHARS: Int = 200

            /** 章节上限：与提示词里的「最多 10 个章节」对齐。 */
            private const val MAX_SECTIONS: Int = 10

            /** 单个内容块的行数上限，防止一段话占掉整页。 */
            private const val MAX_LINES_PER_BLOCK: Int = 14

            /**
             * 目标六七页（约 9000-10500 字）需要的输出预算。
             *
             * 比「详细」档再高一档：字数目标翻倍，8192 会在最后两章被截断，
             * 而截断后整份 JSON 作废，等于白花一次全额调用。
             */
            private const val MAX_TOKENS_REVIEW: Int = 16384

            /** 厂商拒绝大 max_tokens 时退回到的保守值（与「详细」档一致）。 */
            private const val FALLBACK_MAX_TOKENS: Int = ChatRequestConfig.MAX_TOKENS_DETAILED

            /** PDF 副标题上的固定说明。 */
            private const val EXAM_DATE_LABEL: String = "考前复习"

            /** 厂商表示「被 max_tokens 截断」的 finish_reason。 */
            private const val FINISH_REASON_LENGTH: String = "length"

            /**
             * 组装用户消息：把真实错题与易忘点交给模型。
             *
             * 只给事实，不给结论——归因与归纳是模型的工作，
             * 本地若先算好一份结论塞进去，模型会照着抄，串科的错因也会被原样放大。
             */
            private fun buildUserPrompt(
                subjectLabel: String,
                mistakes: List<ReviewMistake>,
                forgottenPoints: List<String>,
            ): String {
                val builder = StringBuilder()
                builder.append("科目：").append(subjectLabel).append('\n')
                builder.append("学段：高中（新高考）\n")
                builder.append("当前年月：").append(currentYearMonth())
                    .append("（用于挑选时政热点，不要写尚未发生的事件）\n")
                if (forgottenPoints.isNotEmpty()) {
                    builder.append("学生写作业时问过下面这些点（说明他自己容易忘），请优先覆盖：\n")
                    forgottenPoints.forEach { name -> builder.append("- ").append(name).append('\n') }
                }
                if (mistakes.isEmpty()) {
                    builder.append("这位学生在该科目下还没有错题记录。\n")
                    builder.append("请把第一章改成「本科目常见失分点速查」，其余章节按该学科的骨架完整生成。\n")
                } else {
                    builder.append("本份资料依据的真实错题共 ").append(mistakes.size).append(" 道：\n")
                    mistakes.forEachIndexed { index, mistake ->
                        builder.append(index + 1).append(". ")
                        if (mistake.errorType.isNotBlank()) {
                            builder.append("错因类型：").append(mistake.errorType).append("；")
                        }
                        builder.append("题干：").append(truncate(mistake.stem, MAX_STEM_CHARS)).append('\n')
                        builder.append("   错在哪：").append(mistake.errorSummary.ifBlank { "未记录具体错因" })
                            .append('\n')
                        builder.append("   正确思路：")
                            .append(mistake.correctIdea.ifBlank { "未记录标准解答" })
                            .append('\n')
                    }
                }
                builder.append('\n')
                builder.append("请严格按 system 中的 JSON 结构与内容要求输出：")
                builder.append("错题只做归类归因，写成不引用原题的通用解法；补齐本科目高频易错点；")
                builder.append("方法类章节附 1 到 2 道同类例题（最后一行写答案）；")
                builder.append("篇幅 9000 到 10500 字（约六七页 A4），关键结论用成对的 ** 加粗，其余不出现星号。")
                return builder.toString()
            }

            /** 超长题干截断保留前缀，末尾加省略号表明不是原题全部。 */
            private fun truncate(
                text: String,
                limit: Int,
            ): String {
                val trimmed = text.trim()
                return if (trimmed.length <= limit) trimmed else trimmed.substring(0, limit) + "…"
            }

            /** 当前年月（本地时区），给时政热点一个时间锚点。 */
            private fun currentYearMonth(): String = SimpleDateFormat("yyyy 年 M 月", Locale.CHINA).format(Date())
        }
    }
