package com.totop.app.data.remote

import com.totop.app.data.prefs.LlmCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 追问结果。 */
sealed class FollowUpEvent {
    /** 增量正文（流式渲染）。 */
    data class Delta(val text: String) : FollowUpEvent()

    /** 完成。 */
    data class Completed(
        val text: String,
        val usage: TokenUsage,
    ) : FollowUpEvent()

    /** 失败（必然带可读原因）。 */
    data class Failed(val error: LlmError) : FollowUpEvent()
}

/**
 * 追问网关：在既有解题上下文上继续对话。
 *
 * 为什么必须单独一条链路，而不是再走一次四阶段：
 * 四阶段是「把一道新题从图片解析到知识点」的完整流水线，产物是结构化 JSON。
 * 用户的追问（「第二步为什么能这样移项？」「那我这样做错在哪？」）要的是
 * **一段自然语言的回答**，再跑一遍四阶段既浪费 token，也会把已有上下文丢掉。
 *
 * 上下文怎么给：
 * 把上一轮的题目、标准解答、错因结论压成一段结构化的「已知信息」文本，
 * 连同用户本次追问一起作为 user 消息发送。这样模型回答时看得到原题与原解，
 * 不会出现「答非所问」——这正是上一版最被诟病的地方（直接重跑整套流程）。
 *
 * 刻意不使用 response_format=json_object：追问的产物是给人读的话，不是结构化数据。
 */
@Singleton
class FollowUpGateway
    @Inject
    constructor(
        private val llmClient: LlmTransport,
    ) {
        /**
         * 发起一次追问。
         *
         * @param history 之前的所有轮次（按时间正序，最后一条是本次追问）
         * @param context 上一轮解题的结构化上下文（由 [buildSolveContext] 生成）
         * @param images 学生这次补拍的照片（base64，页序）。非空时改走视觉模型，
         *        并把「这是对当前题的补充，不是新题」写进系统提示。
         * @param supplementNote 补拍场景下的一句说明；为空表示本轮没有图片
         */
        fun ask(
            credentials: LlmCredentials,
            context: String,
            history: List<FollowUpTurn>,
            registry: LlmCallRegistry?,
            requestId: String,
            maxTokens: Int = ChatRequestConfig.MAX_TOKENS_CONCISE,
            images: List<String> = emptyList(),
            supplementNote: String = "",
        ): Flow<FollowUpEvent> =
            channelFlow {
                if (!credentials.canCall) {
                    send(
                        FollowUpEvent.Failed(
                            LlmError.Unknown(
                                detail = "尚未配置模型（Base URL / API Key / 模型名），请到设置页填写",
                            ),
                        ),
                    )
                    return@channelFlow
                }
                val messages = ArrayList<ChatMessage>(history.size + 2)
                messages += ChatMessage.system(systemPrompt(context, supplementNote))
                history.forEach { turn ->
                    val text = turn.text.trim()
                    if (text.isEmpty()) return@forEach
                    messages +=
                        if (turn.fromStudent) {
                            ChatMessage.user(text)
                        } else {
                            ChatMessage(ROLE_ASSISTANT_TEXT, text)
                        }
                }
                // 补拍的照片放在**最后一条 user 消息**里，与本次追问的文字同一条，
                // 这样模型能明确「这张图是配合这句话的补充材料」。
                if (images.isNotEmpty()) {
                    val lastUser = messages.indexOfLast { it.role == ChatMessage.ROLE_USER }
                    val spoken = if (lastUser >= 0) messages[lastUser] else null
                    val caption =
                        spoken?.parts
                            ?.filterIsInstance<ChatPart.Text>()
                            ?.joinToString("\n") { it.text }
                            ?.trim()
                            .orEmpty()
                            .ifBlank { "请看我补拍的这张照片。" }
                    if (lastUser >= 0) messages.removeAt(lastUser)
                    messages +=
                        ChatMessage.userWithImages(
                            text = caption,
                            base64Jpegs = images,
                            pageHints =
                                images.indices.map { index ->
                                    "这是同一道题的第 " + (index + 1) + "/" + images.size +
                                        " 张补充照片，请与前面的题目信息合并理解。"
                                },
                        )
                }
                if (messages.size <= 1) {
                    send(FollowUpEvent.Failed(LlmError.Unknown(detail = "没有可发送的追问内容")))
                    return@channelFlow
                }
                val config =
                    ChatRequestConfig(
                        // 带图必须走视觉模型：文本模型会把图片整包丢掉，
                        // 学生补拍的那张等于没发。
                        model =
                            if (images.isEmpty()) {
                                credentials.effectiveTextModel
                            } else {
                                credentials.effectiveVisionModel
                            },
                        messages = messages,
                        maxTokens = maxTokens,
                        temperature = 0.3,
                        // 追问要的是人话，不是 JSON；开着 json_object 反而会让模型
                        // 把答案包成 {"answer": "..."} 这种没法直接读的东西
                        jsonObject = false,
                        includeUsage = true,
                        stream = true,
                        thinkingMode = ChatRequestConfig.THINKING_DISABLED,
                    )
                val buffer = StringBuilder()
                var usage = TokenUsage.EMPTY
                var streamError: LlmError? = null
                try {
                    llmClient.stream(credentials, config, requestId, registry, allowRetry = true)
                        .collect { chunk ->
                            when (chunk) {
                                is LlmChunk.Delta -> {
                                    buffer.append(chunk.text)
                                    send(FollowUpEvent.Delta(chunk.text))
                                }
                                is LlmChunk.Finished -> usage = chunk.usage
                                is LlmChunk.Failed -> streamError = chunk.error
                                is LlmChunk.Reasoning -> Unit
                            }
                        }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: LlmException) {
                    send(FollowUpEvent.Failed(exception.error))
                    return@channelFlow
                }
                streamError?.let { error ->
                    send(FollowUpEvent.Failed(error))
                    return@channelFlow
                }
                val text = buffer.toString().trim()
                if (text.isEmpty()) {
                    send(
                        FollowUpEvent.Failed(
                            LlmError.EmptyCompletion(detail = "模型没有返回回答内容，可以再问一次"),
                        ),
                    )
                    return@channelFlow
                }
                send(FollowUpEvent.Completed(text = text, usage = usage))
            }

        companion object {
            /**
             * assistant 角色的字面量。
             *
             * 刻意不写 `ChatMessage.ROLE_ASSISTANT`：追问链路复用同一条协议，
             * 这里显式引用常量更不容易被误改成 user。
             */
            private const val ROLE_ASSISTANT_TEXT: String = ChatMessage.ROLE_ASSISTANT

            /**
             * 追问上下文的最大字符数。
             *
             * 这个值直接决定每一次追问要付多少输入 token，因此按「够用就好」定：
             * 一道题的标准解答通常在 600 到 1500 字之间，2400 字能完整放下题干、
             * 步骤与错因；再长的题（多页大题）宁可截断末尾的方法说明，
             * 也不该让学生为了一句话付三倍的钱。
             *
             * 旧的 2400 保持不变，但历史轮次已在上层裁到 6 轮、单条 400 字，
             * 两者合起来把一次追问的输入压在 3K 字左右。
             */
            const val MAX_CONTEXT_CHARS: Int = 2400

            /** 系统提示：把「你已经有这道题的答案」这件事讲清楚。 */
            fun systemPrompt(
                context: String,
                supplementNote: String = "",
            ): String =
                buildString {
                    append("你是一名耐心的中学老师，正在微信式地一对一辅导一名学生。\n")
                    append("学生已经问过下面这道题，并且已经看到过完整的标准解答。\n")
                    append("现在学生要继续追问，请**回答这个具体问题**，不要重新讲一遍整道题。\n\n")
                    append("要求：\n")
                    append("1. 用中文口语化的讲解，像面对面说话，不要用 Markdown 标题和表格；\n")
                    append("2. 直接回答学生的疑问，必要时引用原题的条件或某一步骤；\n")
                    append("3. 如果学生问的是一道新题，就按新题独立作答；\n")
                    append("4. 不要输出 JSON，不要说「作为 AI」之类的话；\n")
                    append("5. 如果这次回答**推翻了之前的最终答案**（改选项、改结论、改得数），\n")
                    append("   必须在回答的最后单独一行给出订正，格式严格照抄：\n")
                    append("   ").append(CORRECTION_MARKER).append("最终答案：<新的最终答案>；理由：<一句话>\n")
                    append("   没有改变最终答案时，**禁止**出现这一行；不要用别的方式表达订正。\n")
                    if (supplementNote.isNotBlank()) {
                        append("\n=== 本次带图 ===\n")
                        append(supplementNote).append('\n')
                    }
                    if (context.isNotBlank()) {
                        append("\n=== 这道题的已知信息 ===\n")
                        append(context.take(MAX_CONTEXT_CHARS))
                        append("\n=== 已知信息结束 ===\n")
                    }
                }

            /**
             * 订正标记。
             *
             * 追问的回答是自然语言，客户端没法可靠地从中判断「结论改没改」，
             * 于是让模型在改变结论时多写一行机器可读的标记：
             * 只有这一行是机器可读的，其余照旧是人话，学生看到的回答不会被结构化内容切碎。
             */
            const val CORRECTION_MARKER: String = "[[订正]]"
        }
    }

/**
 * 一轮追问消息。
 *
 * @param fromStudent true 表示学生说的，false 表示之前 AI 的回答
 */
data class FollowUpTurn(
    val text: String,
    val fromStudent: Boolean,
)

/**
 * 把一次解题结果压成追问时用得上的上下文。
 *
 * 只保留「能被引用」的部分：题干、已知、所求、标准步骤、最终答案、首个错误。
 * 不塞原始 JSON、不塞 token 统计、不塞知识点候选清单——这些对回答追问没有帮助，
 * 还会挤占上下文预算。
 */
object SolveContextBuilder {
    fun build(
        stem: String,
        known: List<String> = emptyList(),
        ask: String = "",
        steps: List<String> = emptyList(),
        finalAnswer: String = "",
        method: String = "",
        errorTypeLabel: String = "",
        errorExplain: String = "",
        correctThinking: String = "",
    ): String =
        buildString {
            if (stem.isNotBlank()) append("题目：").append(stem.trim()).append('\n')
            if (known.isNotEmpty()) {
                append("已知：").append(known.joinToString("；")).append('\n')
            }
            if (ask.isNotBlank()) append("求：").append(ask.trim()).append('\n')
            if (method.isNotBlank()) append("解题方法：").append(method.trim()).append('\n')
            if (steps.isNotEmpty()) {
                append("标准解答步骤：\n")
                steps.forEachIndexed { index, step -> append(index + 1).append(". ").append(step).append('\n') }
            }
            if (finalAnswer.isNotBlank()) append("最终答案：").append(finalAnswer.trim()).append('\n')
            if (errorTypeLabel.isNotBlank()) append("学生的错因类型：").append(errorTypeLabel).append('\n')
            if (errorExplain.isNotBlank()) append("错在哪：").append(errorExplain.trim()).append('\n')
            if (correctThinking.isNotBlank()) append("下次这样想：").append(correctThinking.trim()).append('\n')
        }
}

/** 追问正文里用于识别「这不是 JSON」的宽松小工具（保留给未来扩展）。 */
internal fun parsePlainText(raw: String): String =
    runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.content }.getOrNull() ?: raw

/**
 * 一条追问回答（去掉订正标记后的正文 + 订正出来的新答案）。
 *
 * @param text 给学生看的回答正文（已剔除机器可读的订正行）
 * @param correctedAnswer 模型本次纠正后的最终答案；没有纠正时为 null
 */
data class FollowUpAnswer(
    val text: String,
    val correctedAnswer: String?,
)

/**
 * 解析追问回答里的订正标记（纯逻辑，可单测）。
 *
 * 约定：模型改变最终答案时，会在最后单独一行写
 * `[[订正]]最终答案：C；理由：试管口朝上会把冷凝水倒流炸裂`。
 * 这一行**不能显示给学生**，也不能进历史上下文（会污染下一轮输入），
 * 因此在这里剥掉，只把答案交给上层写库。
 *
 * 容错：标记可能出现在任意一行、可能用了中英文冒号/分号、也可能根本没有「理由」部分；
 * 拿不到答案时按「没有订正」处理，绝不写入一个猜出来的答案。
 */
object FollowUpCorrection {
    fun parse(raw: String): FollowUpAnswer {
        if (!raw.contains(FollowUpGateway.CORRECTION_MARKER)) return FollowUpAnswer(raw.trim(), null)
        val lines = raw.lines()
        val markerIndex = lines.indexOfFirst { it.contains(FollowUpGateway.CORRECTION_MARKER) }
        if (markerIndex < 0) return FollowUpAnswer(raw.trim(), null)
        val markerLine = lines[markerIndex]
        val answer = extractAnswer(markerLine.substringAfter(FollowUpGateway.CORRECTION_MARKER))
        val kept =
            lines
                .filterIndexed { index, _ -> index != markerIndex }
                .joinToString("\n")
                .trim()
        return FollowUpAnswer(text = kept, correctedAnswer = answer)
    }

    /** 从「最终答案：X；理由：Y」里取出 X。 */
    private fun extractAnswer(tail: String): String? {
        val trimmed = tail.trim()
        if (trimmed.isEmpty()) return null
        val body = trimmed.removePrefix("最终答案").removePrefix("答案").trimStart('：', ':', ' ', '　')
        val answer =
            body
                .substringBefore("；")
                .substringBefore(";")
                .substringBefore("理由")
                .trim()
                .trimEnd('。', '，', ',', '.')
        return answer.takeIf { it.isNotBlank() && it.length <= MAX_ANSWER_CHARS }
    }

    /** 答案的长度上限：超过这个长度多半是模型把解释写进了答案位，宁可不同步。 */
    private const val MAX_ANSWER_CHARS: Int = 60
}
