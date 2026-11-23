package com.totop.app.data.remote

import android.content.Context
import com.totop.app.data.prefs.LlmCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 考场思维闪记（0.9）：用最短路径说一遍「考场上怎么想到」。
 *
 * 与四阶段的关系：它不参与四阶段编排，也不进缓存键——输入是阶段 A/B 已经有了的
 * 题目与答案，输出是给学生看一眼的思路提示。因此重跑一次的成本只有一次短调用。
 *
 * 提示词外置在 `assets/prompts/exam_quick_thinking.md`。
 */
@Singleton
class QuickThinkGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: LlmClient,
    ) {
        /** 闪记的输入：题目要点 + 已有答案与方法（都可为空）。 */
        data class Input(
            val subject: String,
            val stem: String,
            val ask: String,
            val options: List<String> = emptyList(),
            val questionType: String = "",
            val standardAnswer: String = "",
            val method: String = "",
        )

        /**
         * 生成闪记。
         *
         * @return 3 至 5 条短句；模型给了但全是空白时返回空列表，由调用方提示「这次没生成出来」。
         */
        suspend fun think(
            credentials: LlmCredentials,
            input: Input,
            requestId: String,
        ): List<String> {
            val system = loadAsset(ASSET)
            val raw =
                OneShotCompletion.ask(
                    client = client,
                    credentials = credentials,
                    system = system,
                    user = userPromptOf(input),
                    requestId = requestId,
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                )
            return QuickThinkParse.steps(raw)
        }

        /** 拼本次输入。纯文本而不是 JSON：字段少，模型读文本比读嵌套对象更稳。 */
        internal fun userPromptOf(input: Input): String =
            buildString {
                append("科目：").append(input.subject).append('\n')
                if (input.questionType.isNotBlank()) append("题型：").append(input.questionType).append('\n')
                append("题干：").append(input.stem).append('\n')
                if (input.ask.isNotBlank()) append("设问：").append(input.ask).append('\n')
                if (input.options.isNotEmpty()) {
                    append("选项：\n")
                    input.options.forEach { append("- ").append(it).append('\n') }
                }
                if (input.standardAnswer.isNotBlank()) append("标准答案：").append(input.standardAnswer).append('\n')
                if (input.method.isNotBlank()) append("主要方法：").append(input.method).append('\n')
            }

        private fun loadAsset(path: String): String =
            context.assets.open(path).use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            }

        private companion object {
            const val ASSET: String = "prompts/exam_quick_thinking.md"
            const val MAX_TOKENS: Int = 512

            /** 闪记要稳：同一个学生反复点，应该看到同一条路径，不该每次换一种说法。 */
            const val TEMPERATURE: Double = 0.2
        }
    }

/**
 * 闪记输出的解析（纯 Kotlin，可 JVM 单测）。
 *
 * 模型偶尔会多给一条、给一条空的、或者在 JSON 外面裹一层代码围栏——这里统一收拾：
 * 去掉空白项、最多保留 5 条、过长的句子截断，避免界面上出现一条半句话。
 */
object QuickThinkParse {
    private const val MAX_STEPS: Int = 5
    private const val MAX_CHARS: Int = 120

    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun steps(raw: String): List<String> =
        runCatching { OneShotCompletion.parseObject(raw, Wire.serializer(), json) }
            .getOrNull()
            ?.steps
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_STEPS)
            .map { if (it.length > MAX_CHARS) it.take(MAX_CHARS).trimEnd() + "…" else it }

    @Serializable
    private data class Wire(
        val steps: List<String> = emptyList(),
    )
}
