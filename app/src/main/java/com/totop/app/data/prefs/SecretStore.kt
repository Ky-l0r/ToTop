package com.totop.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * BYOK 密钥存储抽象（开发方案 6.1）。
 *
 * 生产实现为 [EncryptedSecretStore]（EncryptedSharedPreferences + Android Keystore）；
 * 单测使用内存实现，避免引入 Android 运行时。
 *
 * 安全约定（任何实现都必须遵守）：
 * - 明文 Key 不得出现在日志、崩溃上报、导出文件或界面；
 * - 失败路径不得把 Key 写进异常消息；
 * - [read] 永不抛异常，存储损坏时返回 [LlmCredentials.EMPTY]，让 App 退回本地模式（FR-6.5）。
 */
interface SecretStore {
    /** 读取三字段凭据；未配置或读取失败返回 [LlmCredentials.EMPTY]。 */
    fun read(): LlmCredentials

    /** 保存凭据。空 Key 视为「保留原 Key」以外的显式清空需调用 [clearApiKey]。 */
    fun save(credentials: LlmCredentials)

    /** 仅清除 API Key，保留 Base URL 与模型名（换 Key 不重填其他字段）。 */
    fun clearApiKey()

    /** 清除全部凭据（含「仅本机使用」标记）。 */
    fun clearAll()

    /** 是否已存有非空 Key。 */
    fun hasApiKey(): Boolean

    /** 读取「仅本机使用」确认标记。 */
    fun isLocalOnlyAcknowledged(): Boolean

    /** 写入「仅本机使用」确认标记。 */
    fun setLocalOnlyAcknowledged(acknowledged: Boolean)

    /** 存储后端是否可用（Keystore 损坏时为 false，设置页需提示）。 */
    fun isAvailable(): Boolean
}

/**
 * EncryptedSharedPreferences 实现。
 *
 * 加密方案：MasterKey（AES256_GCM，存于 Android Keystore）+
 * 键 AES256_SIV / 值 AES256_GCM。设备锁屏凭据丢失或 Keystore 条目损坏时，
 * 底层会抛 GeneralSecurityException，此处删除损坏文件并重建一次，
 * 使 App 退回「未配置」状态而不是崩溃。
 *
 * 注意：本类不写任何日志（连 debug 日志也不写），避免 Key 通过 logcat 泄漏。
 */
class EncryptedSecretStore(
    context: Context,
    private val fileName: String = FILE_NAME,
) : SecretStore {
    private val appContext: Context = context.applicationContext

    /** 加密文件损坏时置 false，设置页据此提示「密钥存储不可用，请重新填写」。 */
    @Volatile
    private var available: Boolean = true

    private val prefs: SharedPreferences? by lazy { openOrRecreate() }

    private fun openOrRecreate(): SharedPreferences? {
        createPrefs()?.let { return it }
        // 一次性恢复：删除损坏文件后重建，仍失败则整体降级
        runCatching { appContext.deleteSharedPreferences(fileName) }
        return createPrefs().also { if (it == null) available = false }
    }

    private fun createPrefs(): SharedPreferences? =
        runCatching {
            val masterKey =
                MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                appContext,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()

    override fun read(): LlmCredentials {
        val store = prefs ?: return LlmCredentials.EMPTY
        return runCatching {
            LlmCredentials(
                baseUrl = store.getString(KEY_BASE_URL, "").orEmpty(),
                apiKey = store.getString(KEY_API_KEY, "").orEmpty(),
                model = store.getString(KEY_MODEL, "").orEmpty(),
                textModel = store.getString(KEY_TEXT_MODEL, "").orEmpty(),
                visionModel = store.getString(KEY_VISION_MODEL, "").orEmpty(),
                localOnlyAcknowledged = store.getBoolean(KEY_LOCAL_ONLY, false),
            )
        }.getOrElse { LlmCredentials.EMPTY }
    }

    override fun save(credentials: LlmCredentials) {
        val store = prefs ?: return
        val normalized =
            credentials.copy(
                baseUrl = LlmEndpoints.normalizeBaseUrl(credentials.baseUrl),
                apiKey = credentials.apiKey.trim(),
                model = credentials.model.trim(),
                textModel = credentials.textModel.trim(),
                visionModel = credentials.visionModel.trim(),
            )
        // commit() 而非 apply()：密钥必须落盘后再进入连通性校验，避免进程被杀导致半成品配置
        store.edit()
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_API_KEY, normalized.apiKey)
            .putString(KEY_MODEL, normalized.model)
            .putString(KEY_TEXT_MODEL, normalized.textModel)
            .putString(KEY_VISION_MODEL, normalized.visionModel)
            .putBoolean(KEY_LOCAL_ONLY, normalized.localOnlyAcknowledged)
            .commit()
    }

    override fun clearApiKey() {
        prefs?.edit()?.remove(KEY_API_KEY)?.commit()
    }

    override fun clearAll() {
        prefs?.edit()?.clear()?.commit()
    }

    override fun hasApiKey(): Boolean = read().apiKey.isNotBlank()

    override fun isLocalOnlyAcknowledged(): Boolean = read().localOnlyAcknowledged

    override fun setLocalOnlyAcknowledged(acknowledged: Boolean) {
        prefs?.edit()?.putBoolean(KEY_LOCAL_ONLY, acknowledged)?.commit()
    }

    override fun isAvailable(): Boolean = prefs != null

    companion object {
        /** 加密文件名；与 data_extraction_rules.xml 中的排除规则对应，绝不进入备份。 */
        const val FILE_NAME: String = "totop_secrets"

        const val KEY_BASE_URL: String = "llm_base_url"
        const val KEY_API_KEY: String = "llm_api_key"
        const val KEY_MODEL: String = "llm_model"
        const val KEY_TEXT_MODEL: String = "llm_text_model"
        const val KEY_VISION_MODEL: String = "llm_vision_model"
        const val KEY_LOCAL_ONLY: String = "llm_local_only_ack"
    }
}

/** 内存实现：仅用于单测与预览，明文不落盘。 */
class InMemorySecretStore(
    initial: LlmCredentials = LlmCredentials.EMPTY,
) : SecretStore {
    private var current: LlmCredentials = initial

    /** 保存次数，便于断言「保存前先校验」的顺序。 */
    var saveCount: Int = 0
        private set

    override fun read(): LlmCredentials = current

    override fun save(credentials: LlmCredentials) {
        current =
            credentials.copy(
                baseUrl = LlmEndpoints.normalizeBaseUrl(credentials.baseUrl),
                apiKey = credentials.apiKey.trim(),
                model = credentials.model.trim(),
                textModel = credentials.textModel.trim(),
                visionModel = credentials.visionModel.trim(),
            )
        saveCount++
    }

    override fun clearApiKey() {
        current = current.copy(apiKey = "")
    }

    override fun clearAll() {
        current = LlmCredentials.EMPTY
    }

    override fun hasApiKey(): Boolean = current.apiKey.isNotBlank()

    override fun isLocalOnlyAcknowledged(): Boolean = current.localOnlyAcknowledged

    override fun setLocalOnlyAcknowledged(acknowledged: Boolean) {
        current = current.copy(localOnlyAcknowledged = acknowledged)
    }

    override fun isAvailable(): Boolean = true
}
