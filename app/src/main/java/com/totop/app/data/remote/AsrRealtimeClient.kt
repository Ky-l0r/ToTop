package com.totop.app.data.remote

import com.totop.app.data.prefs.AsrCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音转写的 WebSocket 双工客户端（千问 AI「Qwen-Audio-3.x-ASR-Flash-Streaming」协议）。
 *
 * 协议要点（来自官方文档，2026-11-08）：
 * 1. 连接：`wss://maas.qianwenaiapi.com/api-ws/v1/inference`，头部 `Authorization: bearer <key>`；
 * 2. 连上先发 `run-task`（task_group/task/function/model/parameters），服务端回 `task-started` 后
 *    才开始推音频；
 * 3. 音频用**二进制帧**推，16 kHz / 单声道 / 16-bit PCM，每片 100ms（3200 字节）；
 * 4. 推完发 `finish-task`，服务端回 `task-finished`；
 * 5. 识别结果在 `payload.output.sentence.text`，`sentence_end=true` 表示这句最终定稿。
 *
 * 为什么不用 OpenAI 的 realtime 那套（`/api-ws/v1/realtime` + `input_audio_buffer.append`）：
 * 那条路对应的是 `qwen3-asr-flash-realtime`，与这里配置的模型不是同一个，
 * 事件名（`session.update` / `result-generated`）也不通用，混用会一直停在「等任务开始」。
 */
@Singleton
class AsrRealtimeClient
    @Inject
    constructor(
        private val httpClient: OkHttpClient,
    ) {
        /** 回调：全部在 OkHttp 的读线程上触发，调用方自行切回主线程。 */
        interface Listener {
            /** 服务端回了 task-started，可以开始推音频了。 */
            fun onReady()

            /** 识别文本（可能被后续中间结果修正，见 [sentenceEnd]）。 */
            fun onText(
                text: String,
                sentenceEnd: Boolean,
            )

            /** 任务失败或连接异常。 */
            fun onFailed(message: String)

            /** 服务端回了 task-finished。 */
            fun onFinished()
        }

        /** 一次识别会话。 */
        inner class Session internal constructor(
            private val socket: WebSocket,
            private val taskId: String,
        ) {
            private var finished = false

            /** 推一片 PCM（16k/单声道/16bit）。未就绪时丢弃，不抛异常。 */
            fun sendAudio(pcm: ByteArray) {
                if (finished || pcm.isEmpty()) return
                // 不用 ByteString.of(*pcm)：可变参数会先整段复制一次，
                // 每 100ms 一片的音频路径上没必要多这一份拷贝。
                socket.send(pcm.toByteString(0, pcm.size))
            }

            /** 音频推完调用：告诉服务端可以出最终结果了。 */
            fun finish() {
                if (finished) return
                finished = true
                socket.send(finishTask(taskId))
            }

            /** 用户取消（松手前就放弃）：直接关闭，不等结果。 */
            fun cancel() {
                finished = true
                runCatching { socket.close(NORMAL_CLOSURE, "cancelled") }
            }
        }

        fun start(
            credentials: AsrCredentials,
            listener: Listener,
        ): Session {
            val request =
                Request
                    .Builder()
                    .url(credentials.endpoint)
                    .header("Authorization", "bearer " + credentials.apiKey)
                    .build()
            val taskId = UUID.randomUUID().toString().replace("-", "").take(TASK_ID_LENGTH)
            // 会话在 onOpen 之前就要能拿到，但 WebSocket 要等 newWebSocket 返回；
            // 这里用一个「先占位、后填充」的可变引用：onOpen 触发时 socket 已经赋值。
            var session: Session? = null
            val socket =
                httpClient.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send(runTask(taskId, credentials.model))
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            handleMessage(text, listener)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            throwable: Throwable,
                            response: Response?,
                        ) {
                            listener.onFailed(throwable.message ?: "语音识别连接中断")
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(NORMAL_CLOSURE, "")
                        }
                    },
                )
            session = Session(socket, taskId)
            return session
        }

        private fun handleMessage(
            raw: String,
            listener: Listener,
        ) {
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
            val event = root.header()?.get("event")?.jsonPrimitive?.content ?: return
            when (event) {
                "task-started" -> listener.onReady()
                "task-finished" -> listener.onFinished()
                "task-failed" ->
                    listener.onFailed(
                        root.header()?.get("error_message")?.jsonPrimitive?.content ?: "语音识别任务失败",
                    )
                "result-generated" -> {
                    val sentence = root.sentence() ?: return
                    val text = sentence["text"]?.jsonPrimitive?.content.orEmpty()
                    val end = sentence["sentence_end"]?.jsonPrimitive?.content == "true"
                    if (text.isNotBlank()) listener.onText(text, end)
                }
                else -> Unit
            }
        }

        private fun JsonObject.header(): JsonObject? = (this["header"] as? JsonObject)

        private fun JsonObject.sentence(): JsonObject? {
            val payload = this["payload"] as? JsonObject ?: return null
            val output = payload["output"] as? JsonObject ?: return null
            return output["sentence"] as? JsonObject
        }

        private fun runTask(
            taskId: String,
            model: String,
        ): String =
            buildJsonObject {
                put(
                    "header",
                    buildJsonObject {
                        put("action", "run-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    },
                )
                put(
                    "payload",
                    buildJsonObject {
                        put("task_group", "audio")
                        put("task", "asr")
                        put("function", "recognition")
                        put("model", model)
                        put(
                            "parameters",
                            buildJsonObject {
                                put("sample_rate", SAMPLE_RATE)
                                put("format", AUDIO_FORMAT)
                            },
                        )
                        put("input", buildJsonObject {})
                    },
                )
            }.toString()

        private fun finishTask(taskId: String): String =
            buildJsonObject {
                put(
                    "header",
                    buildJsonObject {
                        put("action", "finish-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    },
                )
                put(
                    "payload",
                    buildJsonObject {
                        put("input", buildJsonObject {})
                    },
                )
            }.toString()

        private companion object {
            const val TASK_ID_LENGTH: Int = 32
            const val SAMPLE_RATE: Int = 16000
            const val AUDIO_FORMAT: String = "pcm"
            const val NORMAL_CLOSURE: Int = 1000

            val json: Json =
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
        }
    }
