package com.totop.app.ui.settings

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.ml.TtsSpeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 设置页的纯逻辑（FR-6 / 方案 6.1）。
 *
 * 重点覆盖安全相关约束：
 * - Key 脱敏不能让短 Key 被反推；
 * - 表单校验必须先于保存拦截；
 * - 厂商模板只替换端点与模型名，**绝不动用户已填的 Key**。
 */
class SettingsModelsTest {
    @Test
    @DisplayName("Key 脱敏：短 Key 全掩码，长 Key 只露首尾四位")
    fun maskKey() {
        assertEquals("", ByokForm.maskKey(""))
        assertEquals("****", ByokForm.maskKey("abcd"))
        assertEquals("***********", ByokForm.maskKey("abcdefghijk"))
        assertEquals("sk-a********wxyz", ByokForm.maskKey("sk-abcdefghijklwxyz"))
    }

    @Test
    @DisplayName("表单校验：Base URL 形态、模型名、Key 与本地端点豁免")
    fun validation() {
        val empty = ByokForm()
        assertNotNull(empty.validationMessage)
        assertFalse(empty.canSave)

        val badUrl = ByokForm(baseUrl = "api.deepseek.com", apiKey = "k", model = "m")
        assertNotNull(badUrl.validationMessage)
        assertFalse(badUrl.canSave)

        val missingModel = ByokForm(baseUrl = "https://api.deepseek.com/v1", apiKey = "k", model = "")
        assertNotNull(missingModel.validationMessage)

        val remoteNoKey = ByokForm(baseUrl = "https://api.deepseek.com/v1", apiKey = "", model = "deepseek-chat")
        assertNotNull(remoteNoKey.validationMessage)
        assertFalse(remoteNoKey.canSave)

        val ok = ByokForm(baseUrl = "https://api.deepseek.com/v1", apiKey = "k", model = "deepseek-chat")
        assertNull(ok.validationMessage)
        assertTrue(ok.canSave)

        val local = ByokForm(baseUrl = "http://127.0.0.1:11434/v1", apiKey = "", model = "qwen2.5")
        assertTrue(local.isLocalEndpoint)
        assertNull(local.validationMessage)
        assertTrue(local.canSave)
    }

    @Test
    @DisplayName("表单往返：已保存凭据 -> 表单 -> 凭据不丢字段")
    fun formRoundTrip() {
        val credentials =
            LlmCredentials(
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-abcdefghijkl",
                model = "deepseek-chat",
                textModel = "deepseek-chat",
                visionModel = "deepseek-vl",
                localOnlyAcknowledged = true,
            )
        val form = ByokForm.from(credentials)
        assertFalse(form.dirty)
        val back = form.toCredentials()
        assertEquals(credentials.baseUrl, back.baseUrl)
        assertEquals(credentials.apiKey, back.apiKey)
        assertEquals(credentials.model, back.model)
        assertEquals(credentials.visionModel, back.visionModel)
        assertTrue(back.localOnlyAcknowledged)
    }

    @Test
    @DisplayName("套用厂商模板：替换端点与模型名，保留用户已填的 Key")
    fun applyTemplateKeepsKey() {
        val form = ByokForm(apiKey = "sk-user-key-1234", dirty = false)
        val template = ProviderTemplateUi.all().first { it.id == "dashscope" }
        val applied = applyTemplate(form, template)
        assertEquals(template.baseUrl, applied.baseUrl)
        assertEquals(template.textModel, applied.model)
        assertEquals(template.visionModel, applied.visionModel)
        assertEquals("sk-user-key-1234", applied.apiKey)
        assertTrue(applied.dirty)
    }

    @Test
    @DisplayName("厂商模板表：至少覆盖五个预置厂商，且本地端点标记正确")
    fun providerTemplates() {
        val templates = ProviderTemplateUi.all()
        assertTrue(templates.size >= 5)
        assertTrue(templates.any { it.id == "dashscope" })
        assertTrue(templates.any { it.id == "deepseek" })
        assertTrue(templates.any { it.id == "moonshot" })
        assertTrue(templates.any { it.id == "siliconflow" })
        val ollama = templates.first { it.id == "ollama" }
        assertTrue(ollama.local)
        assertTrue(ollama.baseUrl.contains("127.0.0.1"))
        assertTrue(templates.none { it.baseUrl.isBlank() })
    }

    @Test
    @DisplayName("缓存统计文案与总字节数")
    fun cacheStats() {
        val stats = CacheStats(entryCount = 12, entryBytes = 2048L, imageBytes = 1024L * 1024L)
        assertEquals(2048L + 1024L * 1024L, stats.totalBytes)
        assertTrue(stats.displayText.contains("12"))
        assertTrue(stats.displayText.contains("KB"))
        assertTrue(stats.displayText.contains("MB"))
    }

    @Test
    @DisplayName("开关默认值：引导模式 + 语音开 + 周报 AI 关")
    fun togglesDefaults() {
        val toggles = SettingsToggles()
        assertEquals(com.totop.app.domain.model.TeachingMode.GUIDED, toggles.mode)
        assertTrue(toggles.ttsEnabled)
        assertEquals(TtsSpeed.NORMAL, toggles.ttsSpeed)
        assertFalse(toggles.reportAiEnabled)
        assertTrue(toggles.generateExercise)
        assertTrue(toggles.modeDescription.contains("提示"))
    }

    @Test
    @DisplayName("危险操作文案必须写明不可撤销与备份建议")
    fun dangerCopy() {
        assertTrue(DangerCopy.CLEAR_ALL_BODY.contains("不可撤销"))
        assertTrue(DangerCopy.CLEAR_ALL_BODY.contains("备份"))
        assertTrue(DangerCopy.CLEAR_ALL_BODY.contains("错题"))
    }

    @Test
    @DisplayName("清除顺序：子表先于主表，避免外键约束失败")
    fun clearOrderIsSafe() {
        val order = SettingsViewModel.CLEAR_ORDER
        assertTrue(order.indexOf("review_log") < order.indexOf("mistake_record"))
        assertTrue(order.indexOf("hint_log") < order.indexOf("mistake_record"))
        assertTrue(order.indexOf("mistake_kpoint") < order.indexOf("mistake_record"))
        assertTrue(order.indexOf("mistake_record") < order.indexOf("question"))
        assertTrue(order.contains("settings"))
    }
}
