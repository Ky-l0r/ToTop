package com.totop.app.data.prefs

/**
 * 语音转写的独立凭据（0.7.0）。
 *
 * 为什么单独一套：语音转写走的是**另一条协议**（WebSocket 双工流式），
 * 端点、模型名都与主模型不同（`/api-ws/v1/inference` + `qwen-audio-3.1-asr-flash-streaming`），
 * 塞进主模型那三个字段里只会互相覆盖。
 *
 * 默认值取自千问 AI 平台文档（2026-11-08）：
 * - 端点：`wss://maas.qianwenaiapi.com/api-ws/v1/inference`
 * - 模型：`qwen-audio-3.1-asr-flash-streaming`
 * - 音频：16 kHz、单声道、16-bit PCM，每 100ms 一片（3200 字节）
 */
data class AsrCredentials(
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
) {
    /** 填了 Key 与模型名才算配好；否则语音继续走系统识别。 */
    val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

    /** 日志安全投影：不含 Key。 */
    fun redacted(): String = "AsrCredentials(endpoint=" + endpoint + ", model=" + model + ", key=***)"

    override fun toString(): String = redacted()

    companion object {
        const val DEFAULT_ENDPOINT: String = "wss://maas.qianwenaiapi.com/api-ws/v1/inference"

        const val DEFAULT_MODEL: String = "qwen-audio-3.1-asr-flash-streaming"

        val EMPTY: AsrCredentials = AsrCredentials()
    }
}
