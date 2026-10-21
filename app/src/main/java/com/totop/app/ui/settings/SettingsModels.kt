package com.totop.app.ui.settings

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.prefs.LlmEndpoints
import com.totop.app.data.prefs.ProviderTemplate
import com.totop.app.data.prefs.ProviderTemplates
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ml.TtsSpeed

/**
 * BYOK 表单（设置页唯一的可编辑凭据状态）。
 *
 * 安全约定（开发方案 6.1）：
 * - API Key 只在内存里短暂存在，落盘只经 EncryptedSharedPreferences；
 * - 界面回显一律用 [maskedKey]，绝不留明文；
 * - 任何日志、异常消息都不得包含 Key。
 */
data class ByokForm(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val textModel: String = "",
    val visionModel: String = "",
    /** 用户已确认「仅本机使用」说明。 */
    val localOnlyAcknowledged: Boolean = false,
    /** 表单是否被修改过（决定「保存」按钮是否可用）。 */
    val dirty: Boolean = false,
) {
    /** Base URL 形态是否合法。 */
    val baseUrlValid: Boolean get() = baseUrl.isBlank() || LlmEndpoints.isPlausibleBaseUrl(baseUrl)

    /** 三字段是否齐全（本地端点允许空 Key）。 */
    val canSave: Boolean
        get() =
            baseUrl.isNotBlank() && model.isNotBlank() &&
                (apiKey.isNotBlank() || isLocalEndpoint) && baseUrlValid

    /** 是否本地端点（Ollama 等，允许空 Key）。 */
    val isLocalEndpoint: Boolean
        get() = baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost") || baseUrl.contains("10.0.2.2")

    /** 脱敏 Key。 */
    val maskedKey: String get() = maskKey(apiKey)

    /** 转成领域凭据。 */
    fun toCredentials(): LlmCredentials =
        LlmCredentials(
            baseUrl = LlmEndpoints.normalizeBaseUrl(baseUrl),
            apiKey = apiKey.trim(),
            model = model.trim(),
            textModel = textModel.trim(),
            visionModel = visionModel.trim(),
            localOnlyAcknowledged = localOnlyAcknowledged,
        )

    /** 填写校验提示；合法返回 null。 */
    val validationMessage: String?
        get() =
            when {
                baseUrl.isBlank() -> "请填写 Base URL，例如 https://api.deepseek.com/v1"
                !baseUrlValid -> "Base URL 必须是 http(s) 地址，且以 /v1 这类版本路径结尾"
                model.isBlank() -> "请填写模型名，例如 deepseek-flash 或 qwen-plus"
                apiKey.isBlank() && !isLocalEndpoint -> "请填写 API Key（本地 Ollama 可留空）"
                else -> null
            }

    companion object {
        /** 从已保存的凭据生成表单。 */
        fun from(credentials: LlmCredentials): ByokForm =
            ByokForm(
                baseUrl = credentials.baseUrl,
                apiKey = credentials.apiKey,
                model = credentials.model,
                textModel = credentials.textModel,
                visionModel = credentials.visionModel,
                localOnlyAcknowledged = credentials.localOnlyAcknowledged,
                dirty = false,
            )

        /**
         * Key 脱敏：只保留首尾 4 位，长度不足 12 时全掩码。
         *
         * 之所以不用 [LlmCredentials.maskedApiKey] 之外的实现：
         * 这里单独放在纯函数里，便于单测直接验证「短 Key 不会被反推」。
         */
        fun maskKey(raw: String): String =
            when {
                raw.isEmpty() -> ""
                raw.length < 12 -> "*".repeat(raw.length)
                else -> raw.take(4) + "*".repeat(8) + raw.takeLast(4)
            }
    }
}

/** 连通性校验状态（保存前必须校验，失败要分类提示）。 */
sealed interface ConnectionState {
    /** 未校验。 */
    data object Idle : ConnectionState

    /** 校验中。 */
    data object Checking : ConnectionState

    /** 校验通过。 */
    data class Ok(val model: String, val latencyMs: Long, val replyPreview: String) : ConnectionState

    /**
     * 校验失败。
     *
     * @param category 分类标题（Key 无效 / 余额不足 / 限流 / 模型名错 / 端点错 / 网络 / 超时 / 其他）
     * @param message 面向用户的中文原因与下一步动作
     * @param retryable 是否值得再试
     */
    data class Failed(
        val category: String,
        val message: String,
        val retryable: Boolean,
    ) : ConnectionState
}

/** 缓存统计。 */
data class CacheStats(
    /** 解题结果缓存条数。 */
    val entryCount: Int = 0,
    /** 解题结果缓存字节数。 */
    val entryBytes: Long = 0,
    /** 文件系统上的图片（含缩略图）字节数。 */
    val imageBytes: Long = 0,
) {
    val totalBytes: Long get() = entryBytes + imageBytes

    /** 展示文本。 */
    val displayText: String
        get() = "缓存条目 " + entryCount + " 条 · 结果 " + formatBytes(entryBytes) + " · 图片 " + formatBytes(imageBytes)

    companion object {
        /** 人类可读大小（复用 work 层的格式化实现，保证全应用一致）。 */
        fun formatBytes(bytes: Long): String = com.totop.app.work.CacheCleanupPlan.formatBytes(bytes)
    }
}

/** 设置页的开关集合。 */
data class SettingsToggles(
    val mode: TeachingMode = TeachingMode.DEFAULT,
    val ttsEnabled: Boolean = true,
    val ttsSpeed: TtsSpeed = TtsSpeed.NORMAL,
    val reportAiEnabled: Boolean = false,
    val generateExercise: Boolean = true,
) {
    /** 模式说明。 */
    val modeDescription: String
        get() =
            when (mode) {
                TeachingMode.DIRECT -> "直接给完整步骤与答案，适合已经想通、只想核对写法"
                TeachingMode.GUIDED -> "先给三级提示逐级解锁，答案不会提前出现（推荐）"
                TeachingMode.SOCRATIC -> "只给提问与方向，逼自己想"
            }
}

/**
 * 预置厂商模板的界面投影（不含 Key）。
 */
data class ProviderTemplateUi(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val textModel: String,
    val visionModel: String,
    val local: Boolean,
    val note: String,
) {
    companion object {
        /** 全部模板。 */
        fun all(): List<ProviderTemplateUi> = ProviderTemplates.ALL.map { it.toUi() }

        private fun ProviderTemplate.toUi(): ProviderTemplateUi =
            ProviderTemplateUi(
                id = id,
                displayName = displayName,
                baseUrl = baseUrl,
                textModel = textModel,
                visionModel = visionModel,
                local = local,
                note = note,
            )
    }
}

/**
 * 应用模板到表单：**保留用户已填的 Key**，只替换 Base URL 与模型名。
 */
fun applyTemplate(
    form: ByokForm,
    template: ProviderTemplateUi,
): ByokForm =
    form.copy(
        baseUrl = template.baseUrl,
        model = template.textModel,
        textModel = template.textModel,
        visionModel = template.visionModel,
        dirty = true,
    )

/**
 * 危险操作确认文案（一键清除全部数据）。
 *
 * 明确列出会被删除的内容与不可恢复性，避免误触。
 */
object DangerCopy {
    /** 清除全部数据的标题。 */
    const val CLEAR_ALL_TITLE: String = "清除全部本机数据"

    /** 清除全部数据的正文。 */
    const val CLEAR_ALL_BODY: String =
        "将删除：错题本、题目与图片、知识点掌握度、复习记录、周报、AI 调用记录、缓存与全部设置。" +
            "已保存的备份文件不受影响。此操作不可撤销，建议先在「备份与恢复」里导出一份。"
}
