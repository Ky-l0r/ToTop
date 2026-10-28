package com.totop.app.data.remote

import android.content.Context
import com.totop.app.data.prefs.LlmCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 陪伴模式的一次提问结果。 */
sealed class CompanionAskEvent {
    /** 增量正文。 */
    data class Delta(val text: String) : CompanionAskEvent()

    /** 完成。 */
    data class Completed(val text: String) : CompanionAskEvent()

    /** 失败（必然带可读原因）。 */
    data class Failed(val error: LlmError) : CompanionAskEvent()
}

/**
 * 陪伴模式的「随手问」。
 *
 * 与解题链路的区别：
 * - 只有一轮，没有四阶段，也没有题目上下文——学生问的是「那个公式是什么」，
 *   不是「这道题怎么解」；把拍题的上下文塞进来会让模型顺手把作业做完；
 * - 不落库、不缓存：答案是一句话的提醒，重复问同一句时重新生成反而更自然；
 * - 系统提示词外置在 `assets/prompts/companion_ask.md`（规范禁止把提示词写进 Kotlin）。
 *
 * 为什么不复用 [FollowUpGateway]：追问的上下文是「某一套标准解答」，而这里根本没有题；
 * 硬套会导致模型被要求围绕一份不存在的解答回答，输出质量反而更差。
 */
@Singleton
class CompanionAskGateway
    @Inject
    constructor(
        private val llmClient: LlmTransport,
        @ApplicationContext private val context: Context,
    ) {
        /**
         * 发起一次提问。
         *
         * @param question 学生问的那句话（已去空白，非空）
         * @param registry 页面级 Call 注册表，页面销毁时统一取消
         */
        fun ask(
            credentials: LlmCredentials,
            question: String,
            registry: LlmCallRegistry?,
            requestId: String,
        ): Flow<CompanionAskEvent> =
            channelFlow {
                if (!credentials.canCall) {
                    send(
                        CompanionAskEvent.Failed(
                            LlmError.Unknown(
                                detail = "还没配置模型（Base URL / API Key / 模型名），请到设置页填写后再问。",
                            ),
                        ),
                    )
                    return@channelFlow
                }
                val trimmed = question.trim()
                if (trimmed.isEmpty()) {
                    send(CompanionAskEvent.Failed(LlmError.Unknown(detail = "先写下要问的内容")))
                    return@channelFlow
                }
                val config =
                    ChatRequestConfig(
                        model = credentials.effectiveTextModel,
                        messages =
                            listOf(
                                ChatMessage.system(systemPrompt()),
                                ChatMessage.user(trimmed),
                            ),
                        maxTokens = ChatRequestConfig.MAX_TOKENS_CONCISE,
                        temperature = 0.4,
                        // 要的是人话，套 JSON 反而会让模型把答案包成没法直接读的结构
                        jsonObject = false,
                        includeUsage = true,
                        stream = true,
                        thinkingMode = ChatRequestConfig.THINKING_DISABLED,
                    )
                val buffer = StringBuilder()
                var streamError: LlmError? = null
                try {
                    llmClient.stream(credentials, config, requestId, registry, allowRetry = true)
                        .collect { chunk ->
                            when (chunk) {
                                is LlmChunk.Delta -> {
                                    buffer.append(chunk.text)
                                    send(CompanionAskEvent.Delta(chunk.text))
                                }
                                is LlmChunk.Finished, is LlmChunk.Reasoning -> Unit
                                is LlmChunk.Failed -> streamError = chunk.error
                            }
                        }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: LlmException) {
                    send(CompanionAskEvent.Failed(exception.error))
                    return@channelFlow
                }
                streamError?.let { error ->
                    send(CompanionAskEvent.Failed(error))
                    return@channelFlow
                }
                val text = buffer.toString().trim()
                if (text.isEmpty()) {
                    send(CompanionAskEvent.Failed(LlmError.EmptyCompletion(detail = "模型没有返回内容，可以再问一次")))
                    return@channelFlow
                }
                send(CompanionAskEvent.Completed(text))
            }

        /** 读系统提示词；读不到时用一句最简兜底，不让整个功能因为资产缺失而崩掉。 */
        private fun systemPrompt(): String =
            runCatching { AndroidAssetReader(context).read(PROMPT_PATH) }
                .getOrDefault("你在陪学生写作业。只解释他问的那个知识点或公式，简短、口语化，不要给出作业答案。")

        private companion object {
            const val PROMPT_PATH: String = "prompts/companion_ask.md"
        }
    }
