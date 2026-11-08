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
 *
 * 两个真机踩过的坑（2026-11-08）：
 * 1. 控制台上给的是**文件转写**的 HTTPS 地址（`/api/v1/services/audio/asr/transcription`），
 *    直接粘进来连不上 WebSocket → [resolvedEndpoint] 保留主机、路径一律纠正；
 * 2. 模型填成 `...-filetrans` / `...-message` 时不会在连接时报错，只在 run-task 之后
 *    回一句「URL error, please check URL」，看不出是模型不对 → [problem] 提前拦下来。
 */
data class AsrCredentials(
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
) {
    /** 填了 Key 与模型名才算配好；否则语音继续走系统识别。 */
    val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

    /** 模型名是不是流式那一支（只有它支持按住说话边录边转）。 */
    val isStreamingModel: Boolean get() = model.trim().lowercase().contains(STREAMING_KEYWORD)

    /**
     * 实际连接的地址。
     *
     * 用户可能粘的是控制台那条文件转写接口，也可能只填域名；这里统一收敛成
     * `wss://<host>/api-ws/v1/inference`：只保留主机，路径一律换成流式识别那条。
     */
    val resolvedEndpoint: String
        get() {
            val raw = endpoint.trim()
            if (raw.isEmpty()) return DEFAULT_ENDPOINT
            val withScheme =
                when {
                    raw.startsWith("wss://", true) || raw.startsWith("ws://", true) -> raw
                    raw.startsWith("https://", true) -> "wss://" + raw.substring(HTTP_HTTPS_PREFIX)
                    raw.startsWith("http://", true) -> "ws://" + raw.substring(HTTP_PREFIX)
                    else -> "wss://" + raw
                }
            val schemeEnd = withScheme.indexOf("://") + SCHEME_SEPARATOR_LENGTH
            val slash = withScheme.indexOf('/', schemeEnd)
            val host = if (slash < 0) withScheme else withScheme.substring(0, slash)
            val path = if (slash < 0) "" else withScheme.substring(slash)
            val fixedPath = if (path == WS_PATH) path else WS_PATH
            return host + fixedPath
        }

    /**
     * 配置层面的问题：能当场说清楚的，就不让用户去猜服务端那句英文。
     *
     * 返回 null 表示没问题。
     */
    fun problem(): String? =
        when {
            apiKey.isBlank() -> "语音转写的 Key 还没填。到设置 → 模型与连接 → 语音转写里填一次。"
            model.isBlank() -> "语音转写的模型名还没填，应该是 " + DEFAULT_MODEL + "。"
            !isStreamingModel -> modelProblem()
            else -> null
        }

    private fun modelProblem(): String {
        val reason =
            when {
                model.contains("filetrans", true) ->
                    "它是**文件转写**模型：把一整段音频传上去再出结果（HTTPS + 异步任务），" +
                        "不支持边录边出字。"
                model.contains("message", true) -> "它属于 message 那一支，不是双工流式。"
                model.contains("realtime", true) ->
                    "realtime 那一支走 `/api-ws/v1/realtime` 与另一套事件名，与本应用的推流协议不通用。"
                else -> "它不在流式那一支里。"
            }
        return "模型名「" + model.trim() + "」不能用于按住说话：" + reason +
            "\n请填：" + DEFAULT_MODEL + "（用 3.0 的话填 qwen-audio-3.0-asr-flash-streaming）。"
    }

    /** 日志安全投影：不含 Key。 */
    fun redacted(): String = "AsrCredentials(endpoint=" + resolvedEndpoint + ", model=" + model + ", key=***)"

    override fun toString(): String = redacted()

    companion object {
        const val DEFAULT_ENDPOINT: String = "wss://maas.qianwenaiapi.com/api-ws/v1/inference"

        const val DEFAULT_MODEL: String = "qwen-audio-3.1-asr-flash-streaming"

        /** 流式识别在 WebSocket 上的固定路径。 */
        const val WS_PATH: String = "/api-ws/v1/inference"

        private const val STREAMING_KEYWORD: String = "streaming"

        private const val HTTP_PREFIX: Int = "http://".length

        private const val HTTP_HTTPS_PREFIX: Int = "https://".length

        private const val SCHEME_SEPARATOR_LENGTH: Int = 3

        val EMPTY: AsrCredentials = AsrCredentials()
    }
}
