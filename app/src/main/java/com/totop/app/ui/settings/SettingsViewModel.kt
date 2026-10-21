package com.totop.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.ConnectivityResult
import com.totop.app.data.remote.LlmClient
import com.totop.app.data.remote.LlmError
import com.totop.app.data.remote.LlmErrorMapper
import com.totop.app.data.remote.RequestId
import com.totop.app.data.repo.CacheRepository
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ml.TtsEngine
import com.totop.app.ml.TtsSpeed
import com.totop.app.work.CacheCleanupPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 设置页状态。 */
data class SettingsUiState(
    val form: ByokForm = ByokForm(),
    /** 已保存的 Key 是否存在（决定「已保存」徽标与清除入口）。 */
    val keyStored: Boolean = false,
    /** 密钥存储后端是否可用（Keystore 损坏时为 false）。 */
    val storageAvailable: Boolean = true,
    val connection: ConnectionState = ConnectionState.Idle,
    val toggles: SettingsToggles = SettingsToggles(),
    val cacheStats: CacheStats = CacheStats(),
    val templates: List<ProviderTemplateUi> = ProviderTemplateUi.all(),
    /** TTS 可用性说明。 */
    val ttsAvailabilityText: String = "",
    val busy: Boolean = false,
    /** 一次性提示（保存成功、清理完成），页面用 Snackbar 展示。 */
    val message: String? = null,
    /** 危险操作确认弹窗。 */
    val confirmClearAll: Boolean = false,
    /** 清除全部数据的结果。 */
    val clearAllDone: Boolean = false,
) {
    /** 是否需要提示「未配置 Key 时进入本地模式」。 */
    val localMode: Boolean get() = !keyStored
}

/**
 * 设置页 ViewModel（FR-6）。
 *
 * 职责：
 * 1. BYOK 三字段的读取 / 保存 / 清除（Key 只经 [SecretStore]，明文不落库、不写日志）；
 * 2. 保存前的连通性校验（[LlmClient.probe]，max_tokens=8），失败按类别给出中文下一步；
 * 3. 偏好开关：引导模式默认值、TTS 开关与语速、AI 周报解读、是否生成练习题；
 * 4. 缓存清理（cache_entry + filesDir/images）与一键清除全部本机数据。
 *
 * 架构约束：**不直接调用 Retrofit / OkHttp**——连通性校验只经 [LlmClient] 这个门面，
 * 设置页看不到任何请求构造细节。
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val secretStore: SecretStore,
        private val llmClient: LlmClient,
        private val settingsRepository: SettingsRepository,
        private val cacheRepository: CacheRepository,
        private val ttsEngine: TtsEngine,
        private val cacheStore: com.totop.app.data.local.AppDatabase,
        private val seedRepository: com.totop.app.data.local.seed.SeedRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        /** 首次载入：读凭据、设置与缓存统计。 */
        fun bootstrap() {
            viewModelScope.launch {
                loadCredentials()
                loadToggles()
                refreshCacheStats()
                val availability = runCatching { ttsEngine.initialize() }.getOrNull()
                _uiState.value =
                    _uiState.value.copy(
                        ttsAvailabilityText = availability?.label ?: "语音引擎状态未知",
                    )
            }
        }

        private suspend fun loadCredentials() {
            val credentials =
                runCatching { secretStore.read() }
                    .getOrElse { com.totop.app.data.prefs.LlmCredentials.EMPTY }
            _uiState.value =
                _uiState.value.copy(
                    form = ByokForm.from(credentials),
                    keyStored = runCatching { secretStore.hasApiKey() }.getOrDefault(false),
                    storageAvailable = runCatching { secretStore.isAvailable() }.getOrDefault(true),
                )
        }

        private suspend fun loadToggles() {
            val mode = TeachingMode.fromWire(settingsRepository.get(SettingsRepository.Keys.MODE))
            val ttsEnabled = settingsRepository.getBoolean(SettingsRepository.Keys.TTS_ENABLED, true)
            val speedRate = settingsRepository.getDouble(SettingsRepository.Keys.TTS_SPEED, 1.0)
            val reportAi = settingsRepository.getBoolean(SettingsRepository.Keys.REPORT_AI_ENABLED, false)
            val generateExercise = settingsRepository.getBoolean(SettingsRepository.Keys.GENERATE_EXERCISE, true)
            _uiState.value =
                _uiState.value.copy(
                    toggles =
                        SettingsToggles(
                            mode = mode,
                            ttsEnabled = ttsEnabled,
                            ttsSpeed = TtsSpeed.nearest(speedRate.toFloat()),
                            reportAiEnabled = reportAi,
                            generateExercise = generateExercise,
                        ),
                )
        }

        // ---------- BYOK 表单 ----------

        fun updateBaseUrl(value: String) {
            updateForm(_uiState.value.form.copy(baseUrl = value, dirty = true))
        }

        fun updateApiKey(value: String) {
            updateForm(_uiState.value.form.copy(apiKey = value, dirty = true))
        }

        fun updateModel(value: String) {
            updateForm(_uiState.value.form.copy(model = value, dirty = true))
        }

        fun updateTextModel(value: String) {
            updateForm(_uiState.value.form.copy(textModel = value, dirty = true))
        }

        fun updateVisionModel(value: String) {
            updateForm(_uiState.value.form.copy(visionModel = value, dirty = true))
        }

        fun setLocalOnlyAcknowledged(value: Boolean) {
            updateForm(_uiState.value.form.copy(localOnlyAcknowledged = value, dirty = true))
        }

        /** 套用厂商模板（保留已填 Key）。 */
        fun applyTemplate(templateId: String) {
            val template = _uiState.value.templates.firstOrNull { it.id == templateId } ?: return
            updateForm(applyTemplate(_uiState.value.form, template))
            _uiState.value =
                _uiState.value.copy(
                    connection = ConnectionState.Idle,
                    message = "已套用「" + template.displayName + "」的端点与模型名，请填写你自己的 API Key。",
                )
        }

        private fun updateForm(form: ByokForm) {
            _uiState.value = _uiState.value.copy(form = form, connection = ConnectionState.Idle)
        }

        /**
         * 连通性校验（保存前）。
         *
         * 结果分类直接来自 [LlmError] 的中文原因，页面按类别给出下一步；
         * 校验失败**不阻止**用户保存（本地端点可能临时不可达），但会明确提示风险。
         */
        fun testConnection() {
            val form = _uiState.value.form
            form.validationMessage?.let { validation ->
                _uiState.value =
                    _uiState.value.copy(
                        connection =
                            ConnectionState.Failed(
                                category = "表单不完整",
                                message = validation,
                                retryable = false,
                            ),
                    )
                return
            }
            if (_uiState.value.connection is ConnectionState.Checking) return
            _uiState.value = _uiState.value.copy(connection = ConnectionState.Checking, busy = true)
            viewModelScope.launch {
                val requestId = RequestId.newId()
                val credentials = form.toCredentials()
                val result =
                    try {
                        llmClient.probe(credentials, requestId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        ConnectivityResult.Failed(
                            error = LlmErrorMapper.fromThrowable(throwable),
                            latencyMs = 0L,
                        )
                    }
                // 模型名不存在时，主动查一次 /models 把可用模型列出来。
                // 这样用户不用去翻厂商文档，也不会只看到「未归类问题」。
                val enriched =
                    if (result is ConnectivityResult.Failed && result.error is LlmError.ModelNameInvalid) {
                        val available = llmClient.listModels(credentials, requestId)
                        val modelFailure = result.error as LlmError.ModelNameInvalid
                        result.copy(
                            error =
                                modelFailure.copy(
                                    availableModels = available,
                                    detail =
                                        listOfNotNull(
                                            modelFailure.detail,
                                            if (available.isEmpty()) null else "该端点可用模型：" + available.take(20).joinToString("、"),
                                        ).joinToString("；"),
                                ),
                        )
                    } else {
                        result
                    }
                _uiState.value =
                    _uiState.value.copy(
                        busy = false,
                        connection = enriched.toState(),
                    )
            }
        }

        /** 保存凭据（先校验，校验不通过也允许保存，但保留下方提示）。 */
        fun saveCredentials() {
            val form = _uiState.value.form
            form.validationMessage?.let { validation ->
                _uiState.value =
                    _uiState.value.copy(
                        connection = ConnectionState.Failed("表单不完整", validation, false),
                    )
                return
            }
            viewModelScope.launch {
                try {
                    secretStore.save(form.toCredentials())
                    loadCredentials()
                    _uiState.value =
                        _uiState.value.copy(
                            message = "已保存到本机加密存储。Key 不会上传，也不会出现在备份里。",
                        )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            message = "保存失败：" + describe(throwable),
                        )
                }
            }
        }

        /** 仅清除 API Key，保留端点与模型名。 */
        fun clearApiKey() {
            viewModelScope.launch {
                runCatching { secretStore.clearApiKey() }
                loadCredentials()
                _uiState.value =
                    _uiState.value.copy(
                        connection = ConnectionState.Idle,
                        message = "已清除 API Key。未配置 Key 时应用进入本地模式，解题与 AI 解读不可用。",
                    )
            }
        }

        // ---------- 偏好开关 ----------

        /** 设置默认讲解模式。 */
        fun setMode(mode: TeachingMode) {
            viewModelScope.launch {
                settingsRepository.put(SettingsRepository.Keys.MODE, mode.wire)
                loadToggles()
            }
        }

        /** TTS 开关。 */
        fun setTtsEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.putBoolean(SettingsRepository.Keys.TTS_ENABLED, enabled)
                if (!enabled) runCatching { ttsEngine.stop() }
                loadToggles()
            }
        }

        /** TTS 语速。 */
        fun setTtsSpeed(speed: TtsSpeed) {
            viewModelScope.launch {
                settingsRepository.put(SettingsRepository.Keys.TTS_SPEED, speed.rate.toString())
                runCatching { ttsEngine.setSpeed(speed) }
                loadToggles()
            }
        }

        /** AI 周报解读开关。 */
        fun setReportAiEnabled(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.putBoolean(SettingsRepository.Keys.REPORT_AI_ENABLED, enabled)
                loadToggles()
            }
        }

        /** 是否生成练习题（关闭可省 token）。 */
        fun setGenerateExercise(enabled: Boolean) {
            viewModelScope.launch {
                settingsRepository.putBoolean(SettingsRepository.Keys.GENERATE_EXERCISE, enabled)
                loadToggles()
            }
        }

        /** 试听语速（明确会发出声音，不是静默操作）。 */
        fun previewTts() {
            viewModelScope.launch {
                val toggles = _uiState.value.toggles
                if (!toggles.ttsEnabled) {
                    _uiState.value = _uiState.value.copy(message = "朗读开关已关闭，先打开开关再试听。")
                    return@launch
                }
                runCatching { ttsEngine.initialize() }
                val spoken =
                    runCatching {
                        ttsEngine.speak("这是一次语速试听：x^2 加 1 大于 0。", flushQueue = true)
                    }.getOrDefault(false)
                _uiState.value =
                    _uiState.value.copy(
                        message =
                            if (spoken) {
                                "正在用「" + toggles.ttsSpeed.label + "」朗读试听句。"
                            } else {
                                "朗读失败：" + _uiState.value.ttsAvailabilityText + "。可到系统设置安装中文语音包。"
                            },
                    )
            }
        }

        // ---------- 缓存与数据 ----------

        /** 刷新缓存统计。 */
        fun refreshCacheStats() {
            viewModelScope.launch {
                val images =
                    withContext(Dispatchers.IO) {
                        imageByteCount(File(IMAGE_DIR_ROOT))
                    }
                val stats =
                    CacheStats(
                        entryCount = runCatching { cacheRepository.count() }.getOrDefault(0),
                        entryBytes = runCatching { cacheRepository.totalBytes() }.getOrDefault(0L),
                        imageBytes = images,
                    )
                _uiState.value = _uiState.value.copy(cacheStats = stats)
            }
        }

        /** 清理缓存（cache_entry + 图片与缩略图）。 */
        fun clearCache() {
            if (_uiState.value.busy) return
            _uiState.value = _uiState.value.copy(busy = true)
            viewModelScope.launch {
                val before = _uiState.value.cacheStats
                val removed = runCatching { cacheRepository.clear() }
                val filesRemoved = withContext(Dispatchers.IO) { deleteImageFiles(File(IMAGE_DIR_ROOT)) }
                _uiState.value =
                    _uiState.value.copy(
                        busy = false,
                        message =
                            if (removed.isSuccess) {
                                "已清理 " + before.displayText + "，删除图片 " + filesRemoved + " 个。" +
                                    "错题与掌握度不受影响。"
                            } else {
                                "缓存清理失败：" + describe(removed.exceptionOrNull())
                            },
                    )
                refreshCacheStats()
            }
        }

        /** 请求清除全部数据（先弹确认）。 */
        fun requestClearAll() {
            _uiState.value = _uiState.value.copy(confirmClearAll = true)
        }

        /** 取消清除。 */
        fun dismissClearAll() {
            _uiState.value = _uiState.value.copy(confirmClearAll = false)
        }

        /**
         * 清除全部本机数据。
         *
         * 顺序：业务表 -> 图片文件 -> 凭据 -> 重新载入内置知识点树。
         * 知识点树是安装包内的资产，清空后立即从 assets 重灌，保证界面不会变空壳。
         */
        fun clearAllData() {
            if (_uiState.value.busy) return
            _uiState.value = _uiState.value.copy(busy = true, confirmClearAll = false)
            viewModelScope.launch {
                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            for (table in CLEAR_ORDER) {
                                cacheStore.query("DELETE FROM " + table, null).close()
                            }
                            deleteImageFiles(File(IMAGE_DIR_ROOT))
                        }
                        runCatching { secretStore.clearAll() }
                        runCatching { seedRepository.loadAll() }
                    }
                _uiState.value =
                    _uiState.value.copy(
                        busy = false,
                        clearAllDone = result.isSuccess,
                        message =
                            if (result.isSuccess) {
                                "已清除全部本机数据，并重新载入内置知识点树。"
                            } else {
                                "清除失败：" + describe(result.exceptionOrNull())
                            },
                    )
                loadCredentials()
                loadToggles()
                refreshCacheStats()
            }
        }

        /** 关闭一次性提示。 */
        fun consumeMessage() {
            _uiState.value = _uiState.value.copy(message = null)
        }

        // ---------- 内部工具 ----------

        private fun ConnectivityResult.toState(): ConnectionState =
            when (this) {
                is ConnectivityResult.Ok ->
                    ConnectionState.Ok(
                        model = model,
                        latencyMs = latencyMs,
                        replyPreview = replyPreview,
                    )
                is ConnectivityResult.Failed ->
                    ConnectionState.Failed(
                        category = categoryOf(error),
                        message = error.userMessage,
                        retryable = error.isRetryable,
                    )
            }

        /** 失败分类：与学生下一步动作一一对应。 */
        private fun categoryOf(error: LlmError): String =
            when (error) {
                is LlmError.InvalidKey -> "API Key 无效"
                is LlmError.InsufficientBalance -> "余额或额度不足"
                is LlmError.RateLimited -> "被限流"
                is LlmError.ModelNotFound -> "模型名不存在"
                is LlmError.ModelNameInvalid -> "模型名不存在"
                is LlmError.BaseUrlInvalid -> "端点地址错误"
                is LlmError.NetworkUnavailable -> "网络不通"
                is LlmError.NetworkOnMainThread -> "应用内部错误"
                is LlmError.Timeout -> "响应超时"
                is LlmError.ServerError -> "厂商服务异常"
                is LlmError.MalformedResponse -> "返回格式异常"
                is LlmError.ParameterNotSupported -> "模型不支持该参数"
                is LlmError.ImageInputNotSupported -> "该模型不能看图"
                is LlmError.ImageFormatRejected -> "图片不符合要求"
                is LlmError.ContextLengthExceeded -> "内容超出长度上限"
                is LlmError.EmptyCompletion -> "模型返回空白"
                is LlmError.Cancelled -> "已取消"
                is LlmError.Unknown -> "未识别的问题"
            }

        private fun imageByteCount(root: File): Long {
            if (!root.exists()) return 0L
            return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        private fun deleteImageFiles(root: File): Int {
            if (!root.exists()) return 0
            var count = 0
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.delete()) count++
            }
            return count
        }

        private fun describe(throwable: Throwable?): String =
            throwable?.message?.takeIf { it.isNotBlank() } ?: throwable?.javaClass?.simpleName ?: "未知原因"

        companion object {
            /** 图片根目录（与 ml 层 PreprocessPaths.IMAGE_DIR 一致）。 */
            const val IMAGE_DIR_ROOT: String = "images"

            /** 清除顺序：先子表后主表，避免外键约束导致删除失败。 */
            val CLEAR_ORDER: List<String> =
                listOf(
                    "review_log",
                    "hint_log",
                    "mistake_kpoint",
                    "mistake_record",
                    "question",
                    "exercise",
                    "mastery",
                    "daily_stats",
                    "report",
                    "ai_trace",
                    "outbox",
                    "cache_entry",
                    "settings",
                )

            /** 缓存大小展示（复用 work 层格式化，保持全应用一致）。 */
            fun formatBytes(bytes: Long): String = CacheCleanupPlan.formatBytes(bytes)
        }
    }
