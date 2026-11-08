package com.totop.app.data.remote

import android.content.Context
import com.totop.app.data.prefs.LlmCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 变式出题与独立重解（0.7.0）。
 *
 * 两次**互相独立**的调用：先出题，再让模型只看题干重算一遍，
 * 答案一致才允许给学生做。这不是「多此一举」，是唯一能挡住模型自编答案的办法——
 * 一旦把出题时写的答案同时喂给校验，校验就会顺着它抄，等于没校验。
 *
 * 提示词外置在 `assets/prompts/exercise_generate.md` 与 `exercise_verify.md`，
 * 与四阶段提示词同一套约定：不许硬编码进 Kotlin。
 */
@Singleton
class ExerciseGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: LlmClient,
    ) {
        /** 一道候选题（尚未校验）。 */
        data class Draft(
            val stem: String,
            val answer: String,
            val steps: String,
        )

        /** 出题。失败抛异常，由调用方转成可读提示。 */
        suspend fun generate(
            credentials: LlmCredentials,
            kpointName: String,
            difficultyLabel: String,
            count: Int,
            sourceStem: String,
            requestId: String,
        ): List<Draft> {
            val system = loadAsset(GENERATE_ASSET)
            val user =
                buildString {
                    append("知识点：").append(kpointName).append('\n')
                    append("难度：").append(difficultyLabel).append('\n')
                    append("题数：").append(count).append('\n')
                    if (sourceStem.isNotBlank()) {
                        append("学生最近错过的一道同类题（只参考考法，不要照抄）：\n").append(sourceStem).append('\n')
                    }
                }
            val raw = complete(credentials, system, user, requestId, GENERATE_MAX_TOKENS)
            val payload = parseJson(raw, DraftResponse.serializer())
            return payload.questions
                .filter { it.stem.isNotBlank() && it.answer.isNotBlank() }
                .map { Draft(stem = it.stem.trim(), answer = it.answer.trim(), steps = it.steps.trim()) }
        }

        /**
         * 只看题干独立重解。
         *
         * 刻意不接收「出题时给出的答案」：接收了就变成让模型自己复核自己，
         * 它会倾向于确认既有答案，校验失去意义。
         */
        suspend fun solveIndependently(
            credentials: LlmCredentials,
            stem: String,
            requestId: String,
        ): String {
            val system = loadAsset(VERIFY_ASSET)
            val raw = complete(credentials, system, stem, requestId, VERIFY_MAX_TOKENS)
            return parseJson(raw, AnswerResponse.serializer()).answer.trim()
        }

        private suspend fun complete(
            credentials: LlmCredentials,
            system: String,
            user: String,
            requestId: String,
            maxTokens: Int,
        ): String {
            val text = StringBuilder()
            val flow: Flow<LlmChunk> =
                client.stream(
                    credentials = credentials,
                    config =
                        ChatRequestConfig(
                            model = credentials.effectiveTextModel,
                            messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                            maxTokens = maxTokens,
                            temperature = 0.4,
                            jsonObject = true,
                            stream = true,
                            thinkingMode = ChatRequestConfig.THINKING_DISABLED,
                        ),
                    requestId = requestId,
                    registry = null,
                    allowRetry = true,
                )
            flow.collect { chunk ->
                when (chunk) {
                    is LlmChunk.Delta -> text.append(chunk.text)
                    is LlmChunk.Failed -> throw IllegalStateException(chunk.error.userMessage)
                    // Completed / Reasoning 不影响正文；思考链不能拼进 JSON，否则解析必然失败。
                    else -> Unit
                }
            }
            return text.toString()
        }

        /** 去掉可能的代码围栏与前后解释，只留第一个完整对象。 */
        private fun <T> parseJson(
            raw: String,
            serializer: kotlinx.serialization.KSerializer<T>,
        ): T {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start in 0 until end) { "模型没有返回 JSON 对象" }
            return json.decodeFromString(serializer, raw.substring(start, end + 1))
        }

        private fun loadAsset(path: String): String =
            context.assets.open(path).use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            }

        @Serializable
        private data class DraftWire(
            val stem: String = "",
            val answer: String = "",
            val steps: String = "",
        )

        @Serializable
        private data class DraftResponse(
            val questions: List<DraftWire> = emptyList(),
        )

        @Serializable
        private data class AnswerResponse(
            val answer: String = "",
        )

        private companion object {
            const val GENERATE_ASSET: String = "prompts/exercise_generate.md"
            const val VERIFY_ASSET: String = "prompts/exercise_verify.md"
            const val GENERATE_MAX_TOKENS: Int = 2048
            const val VERIFY_MAX_TOKENS: Int = 1024

            val json: Json =
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
        }
    }
