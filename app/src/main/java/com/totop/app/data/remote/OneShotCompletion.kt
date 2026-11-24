package com.totop.app.data.remote

import com.totop.app.data.prefs.LlmCredentials
import kotlinx.coroutines.flow.Flow

/**
 * 「要一段文本回来」的一次性调用。
 *
 * 与 [FollowUpGateway] 的区别：那边是流式的、要边收边渲染；这里的产物只有一份 JSON，
 * 学生从头到尾看不到中间过程，所以照样走 SSE，但在内部收完再返回。
 *
 * 抽出来是因为它已经是第二次出现了（变式出题、方法卡重归纳），
 * 再复制一份就会有第三个、第四个版本各自演进。四条共同约束写在这里，改动只改一处：
 * 1. **强制 JSON 模式**：产物要被反序列化，模型在正文前后加解释就会解析失败；
 * 2. **思考链丢弃**：`Reasoning` 分片是给人看的草稿，拼进 JSON 必然解析失败；
 * 3. **带重试**：这两类调用都是幂等的（同一输入要同一结果），可以安全退避重试；
 * 4. **失败抛异常**：调用方决定这次失败是「提示学生」还是「静默放弃」。
 */
internal object OneShotCompletion {
    /**
     * @param temperature 重写骨架这类「照给定材料整理」的任务要低；出题这类要多样性才调高
     * @param jsonObject 是否要求模型只回一个 JSON 对象
     * @return 模型原始正文（可能带代码围栏，由调用方各自解析）
     */
    suspend fun ask(
        client: LlmClient,
        credentials: LlmCredentials,
        system: String,
        user: String,
        requestId: String,
        maxTokens: Int,
        temperature: Double,
        jsonObject: Boolean = true,
        /** 随消息一起发出的图片（视觉档）；为空表示纯文本调用。 */
        images: List<com.totop.app.util.EncodedImage> = emptyList(),
    ): String {
        val text = StringBuilder()
        val flow: Flow<LlmChunk> =
            client.stream(
                credentials = credentials,
                config =
                    ChatRequestConfig(
                        // 带图时必须换视觉档：文本模型看不了图，会当成空请求返回
                        model =
                            if (images.isEmpty()) {
                                credentials.effectiveTextModel
                            } else {
                                credentials.effectiveVisionModel
                            },
                        messages =
                            listOf(
                                ChatMessage("system", system),
                                ChatMessage(
                                    role = "user",
                                    parts =
                                        buildList {
                                            add(ChatPart.Text(user))
                                            images.forEach { image ->
                                                add(ChatPart.Image(base64 = image.base64, mimeType = "image/jpeg"))
                                            }
                                        },
                                ),
                            ),
                        maxTokens = maxTokens,
                        temperature = temperature,
                        jsonObject = jsonObject,
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
                is LlmChunk.Failed -> throw LlmException(chunk.error)
                // Completed / Reasoning 不影响正文
                else -> Unit
            }
        }
        return text.toString()
    }

    /**
     * 去掉可能的代码围栏与前后解释，只留第一个完整对象后反序列化。
     *
     * 不直接 `decodeFromString(raw)`：模型经常写成 ```json … ``` 或先来一句
     * 「好的，结果如下」。这两种情况都不该让整次调用白跑。
     */
    fun <T> parseObject(
        raw: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        json: kotlinx.serialization.json.Json,
    ): T {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "模型没有返回 JSON 对象" }
        return json.decodeFromString(serializer, raw.substring(start, end + 1))
    }
}
