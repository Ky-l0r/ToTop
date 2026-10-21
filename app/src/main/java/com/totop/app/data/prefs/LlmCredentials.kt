package com.totop.app.data.prefs

/**
 * BYOK 三字段（开发方案 6.1：Base URL + API Key + 模型名，适配所有 OpenAI 兼容厂商）。
 *
 * 安全约定：
 * - 本对象不可序列化到日志、崩溃上报或界面；
 * - [toString] 已脱敏，任何打印都不会泄漏 API Key；
 * - 存储只经 [SecretStore]（EncryptedSharedPreferences + Android Keystore）。
 *
 * 分级路由（开发方案 3.3）需要两个模型名：[textModel] 走纯文本便宜档，
 * [visionModel] 走视觉档（含图/手写/低置信度）。[model] 为默认模型，
 * 当用户只填一个模型名时，文本与视觉都回退到它。
 */
data class LlmCredentials(
    /** 根地址，例如 https://dashscope.aliyuncs.com/compatible-mode/v1。 */
    val baseUrl: String = "",
    /** API Key（仅本机加密存储，绝不上传开发者服务器）。 */
    val apiKey: String = "",
    /** 默认模型名。 */
    val model: String = "",
    /** 文本档模型名；为空时回退到 [model]。 */
    val textModel: String = "",
    /** 视觉档模型名；为空时回退到 [model]。 */
    val visionModel: String = "",
    /** 用户已确认「仅本机使用」说明（开发方案 6.1）。 */
    val localOnlyAcknowledged: Boolean = false,
) {
    /** 文本档实际使用的模型名。 */
    val effectiveTextModel: String get() = textModel.ifBlank { model }

    /** 视觉档实际使用的模型名。 */
    val effectiveVisionModel: String get() = visionModel.ifBlank { model }

    /** 三字段是否齐全，决定是否进入「本地模式」（开发方案 6.5）。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 本地端点（Ollama 等）允许空 Key。 */
    val isLocalEndpoint: Boolean
        get() = baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost") || baseUrl.contains("10.0.2.2")

    /** 可发起调用：本地端点允许空 Key，远端端点必须填 Key。 */
    val canCall: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || isLocalEndpoint)

    /** chat/completions 完整端点。 */
    val chatCompletionsUrl: String get() = LlmEndpoints.chatCompletions(baseUrl)

    /** 分级路由使用的模型池。 */
    val modelPool: ModelPool get() = ModelPool(textModel = effectiveTextModel, visionModel = effectiveVisionModel)

    /**
     * 脱敏展示：只保留 Key 的首尾 4 个字符，供设置页回显「已保存」。
     * 长度不足 12 时一律显示全掩码，避免短 Key 被反推。
     */
    val maskedApiKey: String
        get() =
            when {
                apiKey.isEmpty() -> ""
                apiKey.length < 12 -> "*".repeat(apiKey.length)
                else -> apiKey.take(4) + "*".repeat(8) + apiKey.takeLast(4)
            }

    /** 日志安全投影：不含明文 Key。 */
    fun redacted(): String =
        "LlmCredentials(baseUrl=" + baseUrl + ", model=" + model +
            ", textModel=" + effectiveTextModel + ", visionModel=" + effectiveVisionModel +
            ", apiKey=" + maskedApiKey + ", localOnly=" + localOnlyAcknowledged + ")"

    /** 禁止 data class 自动 toString 泄漏 Key。 */
    override fun toString(): String = redacted()

    companion object {
        val EMPTY: LlmCredentials = LlmCredentials()

        /** 文本档 = 视觉档 = 默认模型的快捷构造（单模型厂商）。 */
        fun singleModel(
            baseUrl: String,
            apiKey: String,
            model: String,
        ): LlmCredentials = LlmCredentials(baseUrl = baseUrl, apiKey = apiKey, model = model)

        /** 双模型构造（文本便宜档 + 视觉档）。 */
        fun dualModel(
            baseUrl: String,
            apiKey: String,
            model: String,
            textModel: String,
            visionModel: String,
        ): LlmCredentials =
            LlmCredentials(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                textModel = textModel,
                visionModel = visionModel,
            )
    }
}

/** 分级路由使用的模型池（开发方案 3.3：纯文本便宜档 / 视觉档）。 */
data class ModelPool(
    val textModel: String,
    val visionModel: String,
) {
    init {
        require(textModel.isNotBlank()) { "文本档模型名不得为空" }
        require(visionModel.isNotBlank()) { "视觉档模型名不得为空" }
    }

    companion object {
        /** 未配置时使用的占位池，保证路由逻辑可离线单测。 */
        val PLACEHOLDER: ModelPool = ModelPool(textModel = "text-model", visionModel = "vision-model")
    }
}
