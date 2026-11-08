package com.totop.app.data.prefs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("语音转写凭据：地址归一与模型校验")
class AsrCredentialsTest {
    @Test
    @DisplayName("默认值就是流式端点与 streaming 模型")
    fun defaults() {
        val credentials = AsrCredentials(apiKey = "sk-test")
        assertEquals("wss://maas.qianwenaiapi.com/api-ws/v1/inference", credentials.resolvedEndpoint)
        assertEquals("qwen-audio-3.1-asr-flash-streaming", credentials.model)
        assertNull(credentials.problem())
    }

    @Test
    @DisplayName("粘控制台的文件转写 HTTPS 地址：保留主机，路径改成流式")
    fun normalizeFileTransUrl() {
        val credentials =
            AsrCredentials(
                endpoint = "https://cn-beijing.maas.qianwenaiapi.com/api/v1/services/audio/asr/transcription",
                apiKey = "sk-test",
            )
        assertEquals("wss://cn-beijing.maas.qianwenaiapi.com/api-ws/v1/inference", credentials.resolvedEndpoint)
    }

    @Test
    @DisplayName("只填域名或带区域主机：补 scheme 与流式路径")
    fun normalizeBareHost() {
        assertEquals(
            "wss://maas.qianwenaiapi.com/api-ws/v1/inference",
            AsrCredentials(apiKey = "k", endpoint = "maas.qianwenaiapi.com").resolvedEndpoint,
        )
        assertEquals(
            "wss://cn-beijing.maas.qianwenaiapi.com/api-ws/v1/inference",
            AsrCredentials(apiKey = "k", endpoint = "wss://cn-beijing.maas.qianwenaiapi.com").resolvedEndpoint,
        )
    }

    @Test
    @DisplayName("已经写对的地址不动它")
    fun keepCorrectEndpoint() {
        val endpoint = "wss://maas.qianwenaiapi.com/api-ws/v1/inference"
        assertEquals(endpoint, AsrCredentials(apiKey = "k", endpoint = " $endpoint ").resolvedEndpoint)
    }

    @Test
    @DisplayName("文件转写模型要被拦下，并指向 streaming")
    fun rejectFileTransModel() {
        val credentials =
            AsrCredentials(
                apiKey = "sk-test",
                model = "qwen-audio-3.1-asr-flash-filetrans",
            )
        val problem = credentials.problem().orEmpty()
        assertTrue(problem.contains("文件转写"), "实际：" + problem)
        assertTrue(problem.contains("qwen-audio-3.1-asr-flash-streaming"), "实际：" + problem)
    }

    @Test
    @DisplayName("没填 Key 时说清楚去哪儿填")
    fun missingKey() {
        val problem = AsrCredentials().problem().orEmpty()
        assertTrue(problem.contains("Key"), "实际：" + problem)
    }
}
