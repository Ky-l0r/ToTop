package com.totop.app.data.remote

import android.content.Context
import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.util.EncodedImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入标准答案（0.9）：把一张答案页照片整理成标准解答。
 *
 * 与四阶段的关系：不重跑识题与求解，只做「誊录」。学生手上已经有一份答案，
 * 他要的是把错题本里的旧解答换成这份**更可信的**，不是再让模型算一遍。
 *
 * 因此提示词里第一条禁令就是「禁止重新解题」——模型天生倾向于把答案改写成自己的解法，
 * 那样录进来的就不是学生手上那份了。
 */
@Singleton
class ImportSolutionGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: LlmClient,
    ) {
        /** 一次导入的结果；[warnings] 非空时调用方要把它显示出来。 */
        data class Imported(
            val solution: StandardSolution,
            val warnings: List<String>,
        ) {
            /** 至少要有答案或步骤，否则这次导入等于什么都没读到。 */
            val usable: Boolean
                get() = solution.finalAnswer.isNotBlank() || solution.steps.isNotEmpty()
        }

        /**
         * 读取答案页。
         *
         * @throws LlmException 调用失败；调用方负责转成可读提示
         */
        suspend fun read(
            credentials: LlmCredentials,
            images: List<EncodedImage>,
            stem: String,
            requestId: String,
        ): Imported {
            require(images.isNotEmpty()) { "导入标准答案至少要一张图片" }
            val system = loadAsset(ASSET)
            val user =
                buildString {
                    if (stem.isNotBlank()) {
                        append("这道题的题干（只用于确认录的是同一道题，不要据此改写答案）：\n")
                        append(stem).append('\n')
                    }
                    append("以下是答案页照片，请按系统提示誊录。")
                }
            val raw =
                OneShotCompletion.ask(
                    client = client,
                    credentials = credentials,
                    system = system,
                    user = user,
                    requestId = requestId,
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                    images = images,
                )
            val payload = OneShotCompletion.parseObject(raw, Wire.serializer(), json)
            val steps =
                payload.steps
                    .mapNotNull { step ->
                        val content = step.content.trim()
                        if (content.isEmpty()) null else SolutionStep(idx = 0, content = content, why = step.why.trim())
                    }.mapIndexed { index, step -> step.copy(idx = index + 1) }
            return Imported(
                solution =
                    StandardSolution(
                        steps = steps,
                        finalAnswer = payload.finalAnswer.trim(),
                        method = payload.method.trim(),
                    ),
                warnings = payload.warnings.map { it.trim() }.filter { it.isNotEmpty() },
            )
        }

        private fun loadAsset(path: String): String =
            context.assets.open(path).use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            }

        @Serializable
        private data class WireStep(
            val content: String = "",
            val why: String = "",
        )

        @Serializable
        private data class Wire(
            val finalAnswer: String = "",
            val steps: List<WireStep> = emptyList(),
            val method: String = "",
            val warnings: List<String> = emptyList(),
        )

        private companion object {
            const val ASSET: String = "prompts/import_solution.md"
            const val MAX_TOKENS: Int = 2048

            /** 誊录是抄写任务，不要创造性：温度压到最低。 */
            const val TEMPERATURE: Double = 0.05

            val json: Json =
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
        }
    }
