package com.totop.app.domain.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * AiSolveResult 的 JSON 编解码入口。
 *
 * 依据开发方案 FR-1.10 / 6.2：AI 输出必须经 kotlinx.serialization 严格校验，
 * 解析失败自动重试 1 次，仍失败不得入库。
 */
@OptIn(ExperimentalSerializationApi::class)
object AiSolveResultCodec {
    /**
     * 统一 Json 配置：
     * - ignoreUnknownKeys = true：厂商模型常附加额外字段，忽略而不失败；
     * - isLenient = true：容忍未加引号的键等宽松写法；
     * - explicitNulls = false：编码时省略 null 字段，解码时把显式 null 视为缺省；
     * - coerceInputValues = true：把非法值强制回落到默认值（仅对非可空字段生效）。
     */
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
            encodeDefaults = true
        }

    /** 解析 AI 原始输出；失败返回 failure，由调用方触发一次重试。 */
    fun decode(raw: String): Result<AiSolveResult> =
        runCatching { json.decodeFromString(AiSolveResult.serializer(), raw) }

    /** 序列化为契约 JSON（写缓存与 ai_trace 时使用）。 */
    fun encode(value: AiSolveResult): String = json.encodeToString(AiSolveResult.serializer(), value)
}
