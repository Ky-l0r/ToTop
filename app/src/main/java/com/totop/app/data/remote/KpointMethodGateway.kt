package com.totop.app.data.remote

import android.content.Context
import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.domain.model.KpointMethod
import com.totop.app.domain.model.MethodSkeleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 方法卡「整卡重写」：把已经叠了几道题的方法卡，重写成一份脱离具体题目的骨架。
 *
 * 为什么需要一次模型调用（这是本模块唯一一次额外调用）：
 * 合并是确定性的，它只能把同类题的步骤拼起来，拼出来的东西仍带着各题的数字与字母
 * （「把 x=2 代入 y=2x+1 得 y=5」）。要做到「这一类题该怎么想」，必须重写一次。
 *
 * 为什么只在攒够样本时调用一次：见 [com.totop.app.domain.model.KpointMethodRewrite]。
 *
 * 提示词外置在 `assets/prompts/kpoint_method_rewrite.md`（规范禁止把提示词写进 Kotlin）。
 */
@Singleton
class KpointMethodGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: LlmClient,
    ) {
        /**
         * 重写一张卡。
         *
         * @return 解析出的骨架；模型没回 JSON 或解析失败时返回 null，
         *   调用方据此放弃本次重写（卡片保持合并态），不弹提示、不让解答看起来出错
         */
        suspend fun rewrite(
            credentials: LlmCredentials,
            card: KpointMethod,
            requestId: String,
        ): MethodSkeleton? {
            val system =
                context.assets.open(ASSET).use { stream ->
                    stream.reader(Charsets.UTF_8).readText()
                }
            val raw =
                OneShotCompletion.ask(
                    client = client,
                    credentials = credentials,
                    system = system,
                    user = buildUserPrompt(card),
                    requestId = requestId,
                    maxTokens = MAX_TOKENS,
                    // 重写是「照给定材料整理」，不是创作：温度越低越不会自作主张加环节
                    temperature = LOW_TEMPERATURE,
                )
            return KpointMethodCodec.parse(raw)
        }

        private fun buildUserPrompt(card: KpointMethod): String =
            buildString {
                append("知识点：").append(card.kpointName).append('\n')
                append("方法名：").append(card.name).append('\n')
                append("这套步骤来自 ").append(card.sampleCount).append(" 道同类题，合并后的原文如下：\n")
                card.keySteps.forEachIndexed { index, step ->
                    append(index + 1).append(". ").append(step).append('\n')
                }
                if (card.applicable.isNotBlank()) {
                    append("现在写的适用条件：").append(card.applicable).append('\n')
                }
                if (card.pitfalls.isNotEmpty()) {
                    append("现在记的常见卡点：").append(card.pitfalls.joinToString("；")).append('\n')
                }
            }

        private companion object {
            const val ASSET: String = "prompts/kpoint_method_rewrite.md"

            /** 一张卡最多 8 步、每步一句，1024 足够；再多说明模型在写解析而不是骨架。 */
            const val MAX_TOKENS: Int = 1024

            const val LOW_TEMPERATURE: Double = 0.2
        }
    }

/**
 * 重写结果的反序列化（纯逻辑，可单测）。
 *
 * 与其它网关一样容忍代码围栏与前后解释；**任何异常都返回 null**——
 * 这是一次「锦上添花」的调用，解析失败不该抛到主链路上去。
 */
internal object KpointMethodCodec {
    @Serializable
    private data class Wire(
        val steps: List<String> = emptyList(),
        val applicable: String = "",
        val pitfalls: List<String> = emptyList(),
    )

    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(raw: String): MethodSkeleton? =
        runCatching {
            val wire = OneShotCompletion.parseObject(raw, Wire.serializer(), json)
            MethodSkeleton(
                steps = wire.steps,
                applicable = wire.applicable,
                pitfalls = wire.pitfalls,
            )
        }.getOrNull()
}
