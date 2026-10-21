package com.totop.app.data.prefs

/**
 * OpenAI 兼容端点拼接（开发方案 6.2：全部走 /chat/completions）。
 *
 * 用户可能填三种形态：https://host/v1、https://host/v1/、https://host/v1/chat/completions，
 * 统一归一化，避免拼出 /v1//chat/completions 或重复路径。
 */
object LlmEndpoints {
    /** 归一化 Base URL：去首尾空白、收敛重复斜杠，去掉结尾斜杠。 */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed
            .replace(Regex("(?<!:)//+"), "/")
            .trimEnd('/')
    }

    /** 归一化后的 chat/completions 端点。 */
    fun chatCompletions(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        if (base.isEmpty()) return ""
        if (base.endsWith("/chat/completions")) return base
        return base + "/chat/completions"
    }

    /**
     * 归一化后的 /models 端点（OpenAI 兼容的模型列表接口）。
     *
     * 用途：用户填错模型名时，直接列出该 Key 实际可用的模型，
     * 把「连接失败」变成「你要的模型不存在，可用的是这些」。
     * 该接口并非所有厂商都实现，调用方必须容忍 404/405。
     */
    fun models(baseUrl: String): String {
        val base = normalizeBaseUrl(baseUrl)
        if (base.isEmpty()) return ""
        if (base.endsWith("/chat/completions")) {
            return base.removeSuffix("/chat/completions") + "/models"
        }
        return base + "/models"
    }

    /**
     * Base URL 是否形态合法。
     * 只接受 http/https 且带主机名；全部厂商端点与本地 Ollama 都满足这一形态。
     */
    fun isPlausibleBaseUrl(raw: String): Boolean {
        val normalized = normalizeBaseUrl(raw)
        if (normalized.isEmpty()) return false
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        val host = normalized.removePrefix("https://").removePrefix("http://")
        return host.isNotBlank() && !host.startsWith("/") && host.length > 1
    }

    /** 展示用主机名（不含 Key 与路径），用于设置页与错误文案。 */
    fun hostOf(raw: String): String {
        val normalized = normalizeBaseUrl(raw)
        val noScheme = normalized.removePrefix("https://").removePrefix("http://")
        return noScheme.substringBefore('/')
    }
}

/**
 * 预置厂商模板（开发方案 6.1：Qwen/DashScope、DeepSeek、Moonshot、SiliconFlow、本地 Ollama）。
 *
 * 只预置 Base URL 与推荐模型名，Key 一律由用户填写；换模型无需改代码。
 */
data class ProviderTemplate(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val textModel: String,
    val visionModel: String,
    /** 是否本地端点（允许空 Key）。 */
    val local: Boolean = false,
    /** 备注：说明该厂商的视觉模型能力，供用户选择。 */
    val note: String = "",
)

/** 内置厂商模板表，纯数据、可单测，不依赖 Android。 */
object ProviderTemplates {
    val ALL: List<ProviderTemplate> =
        listOf(
            ProviderTemplate(
                id = "dashscope",
                displayName = "阿里云百炼（通义千问）",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                textModel = "qwen-plus",
                visionModel = "qwen-vl-plus",
                note = "视觉模型按图片 tile 计费，已在前置流程压缩到长边 1280",
            ),
            ProviderTemplate(
                id = "dashscope-deepseek",
                displayName = "阿里云百炼（DeepSeek 模型）",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                // 【关键】百炼上的 DeepSeek 模型 ID 与 DeepSeek 官方不同：
                // 官方叫 deepseek-flash，百炼叫 deepseek-v4.1-flash。
                // 混用会得到 400「Model not exist.」，极易被误判为网络故障。
                textModel = "deepseek-v4.1-flash",
                visionModel = "deepseek-v4.1-flash",
                note = "百炼的模型 ID 与 DeepSeek 官方不同，请勿混用",
            ),
            ProviderTemplate(
                id = "deepseek",
                displayName = "DeepSeek 官方",
                baseUrl = "https://api.deepseek.com/v1",
                textModel = "deepseek-flash",
                // 【修正】旧模板把视觉模型写成 deepseek-chat，并标注「当前无视觉模型」，
                // 这是过期信息：deepseek-flash 官方支持图像理解（JPEG/PNG/GIF/WebP，
                // 单请求最多 600 张图、64MiB）。错误的模板会让含图题目静默走到
                // 不具备视觉能力的模型上，属于必须避免的静默失败。
                visionModel = "deepseek-flash",
                note = "deepseek-flash 支持看图解题，单请求可传多页图片",
            ),
            ProviderTemplate(
                id = "moonshot",
                displayName = "Moonshot（Kimi）",
                baseUrl = "https://api.moonshot.cn/v1",
                textModel = "moonshot-v1-8k",
                visionModel = "moonshot-v1-8k-vision-preview",
                note = "8k 上下文足够单题，禁止携带历史对话以控制成本",
            ),
            ProviderTemplate(
                id = "siliconflow",
                displayName = "SiliconFlow",
                baseUrl = "https://api.siliconflow.cn/v1",
                textModel = "Qwen/Qwen2.5-7B-Instruct",
                visionModel = "Qwen/Qwen2-VL-7B-Instruct",
                note = "同一 Key 可切换多模型，便于比较成本",
            ),
            ProviderTemplate(
                id = "ollama",
                displayName = "本地 Ollama",
                baseUrl = "http://127.0.0.1:11434/v1",
                textModel = "qwen2.5",
                visionModel = "qwen2-vl",
                local = true,
                note = "本机推理，零 api 费用；Key 可留空，数据不出设备",
            ),
        )

    fun byId(id: String): ProviderTemplate? = ALL.firstOrNull { it.id == id }

    /** 把模板套用到凭据（保留用户已填的 Key）。 */
    fun applyTo(
        template: ProviderTemplate,
        existing: LlmCredentials,
    ): LlmCredentials =
        existing.copy(
            baseUrl = template.baseUrl,
            model = template.textModel,
            textModel = template.textModel,
            visionModel = template.visionModel,
        )
}
