package com.totop.app.ml

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.totop.app.util.LatexSpokenRuleTable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 语速档位（开发方案 FR-6.5：语速调节）。 */
enum class TtsSpeed(val rate: Float, val label: String) {
    SLOW(0.75f, "慢速"),
    NORMAL(1.0f, "正常"),
    FAST(1.3f, "快速"),
    ;

    companion object {
        /** 从 0.5..2.0 的任意倍率挑最近的档位，用于兼容旧设置值。 */
        fun nearest(rate: Float): TtsSpeed = entries.minByOrNull { kotlin.math.abs(it.rate - rate) } ?: NORMAL
    }
}

/** 朗读引擎可用性（开发方案 FR-6.3：启动前检测中文语音包）。 */
enum class TtsAvailability(val label: String) {
    /** 尚未检测。 */
    UNKNOWN("未检测"),

    /** 可用（已安装中文语音包）。 */
    AVAILABLE("可用"),

    /** 引擎存在但缺中文语音包。 */
    NO_CHINESE_PACK("系统缺少中文语音包，可前往系统设置安装，或隐藏朗读入口"),

    /** 初始化失败或设备无 TTS。 */
    UNAVAILABLE("本机语音朗读不可用"),

    /** 已释放。 */
    RELEASED("语音引擎已释放"),
    ;

    /** 是否可以直接朗读。 */
    val canSpeak: Boolean get() = this == AVAILABLE
}

/** 朗读状态，供 Compose 直接 collect 渲染。 */
sealed class TtsState {
    /** 空闲。 */
    data object Idle : TtsState()

    /** 正在朗读。 */
    data class Speaking(val utteranceId: String, val stepIndex: Int = -1) : TtsState()

    /** 已停止（用户主动或系统打断）。 */
    data class Stopped(val utteranceId: String? = null) : TtsState()

    /** 当前步骤朗读完成。 */
    data class Done(val utteranceId: String, val stepIndex: Int = -1) : TtsState()

    /** 朗读失败（已静默降级为纯文本展示，不弹错误对话框）。 */
    data class Failed(val reason: String) : TtsState()
}

/** 一步要朗读的内容（开发方案 FR-6.5「按步骤朗读」）。 */
data class TtsStep(
    val title: String,
    val content: String,
) {
    init {
        require(title.isNotBlank() || content.isNotBlank()) { "标题与内容不得同时为空" }
    }
}

/**
 * 系统 TTS 引擎（开发方案 FR-6.3 / FR-6.4 / FR-6.5，零服务器成本）。
 *
 * 必须满足的五条产品约束：
 * 1. **启动前检测中文语音包**：`isLanguageAvailable(Locale.CHINA)`，
 *    不可用时暴露 `availability = NO_CHINESE_PACK` 且 [isAvailable] 为 false，
 *    UI 层据此隐藏/置灰朗读入口（[available] StateFlow 可直接 collect）；
 * 2. **语速调节**：见 [TtsSpeed]，[setSpeed] 立即生效于下一次朗读；
 * 3. **后台播放**：音频由系统 TTS 服务输出，切到后台 / 熄屏继续念，
 *    不注册唤醒锁，也不需要前台服务（朗读题目的时长在系统限制内）；
 * 4. **按步骤朗读**：步骤之间用队列顺序播放，[readSteps] 支持从任意步开始，
 *    并校验每一步的进度（[TtsState.Done.stepIndex]）；
 * 5. **失败静默降级**：任何异常都不抛给 UI，只把状态置为 [TtsState.Failed]，
 *    页面上原文始终可读，绝不弹错误框打断学习。
 *
 * 线程约束：公开方法均在主线程调用（系统 TTS 要求），内部 IO 用 [ioDispatcher]。
 */
@Singleton
class TtsEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val latexTable: LatexSpokenRuleTable,
    ) {
        private val _availability = MutableStateFlow(TtsAvailability.UNKNOWN)

        /** 可用性状态流：UI 直接 collect 决定是否展示朗读入口。 */
        val availability: StateFlow<TtsAvailability> = _availability.asStateFlow()

        private val _state = MutableStateFlow<TtsState>(TtsState.Idle)

        /** 朗读状态流。 */
        val state: StateFlow<TtsState> = _state.asStateFlow()

        private var textToSpeech: TextToSpeech? = null

        /** 语速（0.5..2.0）。 */
        var speed: TtsSpeed = TtsSpeed.NORMAL
            private set

        /** 是否可朗读（中文语音包可用）。 */
        val isAvailable: Boolean get() = _availability.value.canSpeak

        /**
         * 初始化引擎并检测中文语音包。
         *
         * 幂等：重复调用只做一次初始化。初始化本身异步，最多等待 [INIT_TIMEOUT_MILLIS]。
         *
         * @return 检测后的可用性
         */
        suspend fun initialize(): TtsAvailability {
            _availability.value.takeIf { it == TtsAvailability.AVAILABLE }?.let { return it }
            val engine =
                try {
                    withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { createEngine() }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("SwallowedException") error: Exception,
                ) {
                    // 初始化失败统一视为「语音不可用」，由上层隐藏入口并提示，
                    // 不向上抛异常以免打断主流程（方案 FR-6.3）
                    null
                }
            if (engine == null) {
                _availability.value = TtsAvailability.UNAVAILABLE
                return _availability.value
            }
            // 初始化与语言检测都走主线程：系统 TTS 服务对主线程回调有要求，避免绑定异常
            return withContext(Dispatchers.Main) {
                textToSpeech = engine
                engine.setOnUtteranceProgressListener(progressListener)
                val languageResult =
                    runCatching { engine.setLanguage(Locale.CHINA) }
                        .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                val available = runCatching { engine.isLanguageAvailable(Locale.CHINA) }.getOrDefault(-1)
                _availability.value =
                    when {
                        languageResult == TextToSpeech.LANG_MISSING_DATA -> TtsAvailability.NO_CHINESE_PACK
                        languageResult == TextToSpeech.LANG_NOT_SUPPORTED -> TtsAvailability.NO_CHINESE_PACK
                        available < TextToSpeech.LANG_AVAILABLE -> TtsAvailability.NO_CHINESE_PACK
                        else -> {
                            applySpeed(engine)
                            TtsAvailability.AVAILABLE
                        }
                    }
                _availability.value
            }
        }

        /**
         * 构造引擎并等待 onInit 回调；超时或失败返回 null（由调用方判定不可用）。
         *
         * 用数组持有引用而不是局部变量：个别 ROM 的 TTS 会在构造函数内部同步回调 onInit，
         * 此时赋值还没发生，直接读局部变量会误判成「不可用」。
         * 协程被取消或超时未拿到引擎时，在 invokeOnCancellation 里释放，避免服务连接泄漏。
         */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private suspend fun createEngine(): TextToSpeech? =
            suspendCancellableCoroutine { continuation ->
                val holder = arrayOfNulls<TextToSpeech>(1)
                val resumed = booleanArrayOf(false)
                val listener =
                    TextToSpeech.OnInitListener { initStatus ->
                        if (resumed[0]) return@OnInitListener
                        resumed[0] = true
                        val engine = holder[0]
                        if (initStatus == TextToSpeech.SUCCESS && engine != null) {
                            if (continuation.isActive) {
                                continuation.resume(engine) { runCatching { engine.shutdown() } }
                            } else {
                                runCatching { engine.shutdown() }
                            }
                        } else {
                            runCatching { engine?.shutdown() }
                            holder[0] = null
                            if (continuation.isActive) {
                                continuation.resume(
                                    null,
                                ) { runCatching { holder[0]?.shutdown() } }
                            }
                        }
                    }
                holder[0] = TextToSpeech(context, listener)
                continuation.invokeOnCancellation { runCatching { holder[0]?.shutdown() } }
            }

        /**
         * 直接查询系统是否装了中文语音包（不初始化引擎，供设置页轻量探测）。
         * 返回 null 表示尚未初始化、无法判断。
         */
        fun isChineseVoiceInstalled(): Boolean? =
            when (_availability.value) {
                TtsAvailability.AVAILABLE -> true
                TtsAvailability.NO_CHINESE_PACK -> false
                else -> null
            }

        /** 设置语速；引擎已就绪时立即生效。 */
        fun setSpeed(value: TtsSpeed) {
            speed = value
            textToSpeech?.let { applySpeed(it) }
        }

        /** 设置语速倍率（0.5..2.0），内部吸附到最近的档位。 */
        fun setSpeedRate(rate: Float) {
            setSpeed(TtsSpeed.nearest(rate.coerceIn(0.5f, 2.0f)))
        }

        /**
         * 朗读一段文本（题干、解析等）。
         *
         * LaTeX 会先经 [LatexSpokenRuleTable.speakRichText] 转成中文口语，
         * 规则外内容降级为原文，保证念出来的是可理解句子而不是反斜杠乱码。
         *
         * @param flushQueue true 表示打断当前朗读并立即开始（默认），false 排到队尾
         * @return 是否成功入队；失败返回 false 并置 [TtsState.Failed]（静默降级）
         */
        fun speak(
            text: String,
            flushQueue: Boolean = true,
        ): Boolean {
            if (!isAvailable) {
                _state.value = TtsState.Failed(_availability.value.label)
                return false
            }
            val engine =
                textToSpeech ?: run {
                    _state.value = TtsState.Failed(TtsAvailability.UNAVAILABLE.label)
                    return false
                }
            val spoken = latexTable.speakRichText(text).trim()
            if (spoken.isEmpty()) {
                _state.value = TtsState.Failed("没有可朗读的内容")
                return false
            }
            val utteranceId = nextUtteranceId()
            val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result =
                runCatching {
                    engine.speak(spoken, queueMode, Bundle(), utteranceId)
                }.getOrDefault(TextToSpeech.ERROR)
            return if (result == TextToSpeech.SUCCESS) {
                _state.value = TtsState.Speaking(utteranceId)
                true
            } else {
                _state.value = TtsState.Failed("系统语音引擎拒绝朗读")
                false
            }
        }

        /**
         * 按步骤朗读（开发方案 FR-6.5）。
         *
         * 每一步的标题与内容都会先过 LaTeX 口语化规则，再按顺序入队；
         * 队列被系统打断时 [TtsState.Stopped] 会带上中断的 utteranceId，
         * UI 据此高亮「读到第几步」。
         *
         * @param startIndex 从第几步开始（0 基）
         * @return 成功入队的步数；0 表示整体失败（已静默降级）
         */
        fun readSteps(
            steps: List<TtsStep>,
            startIndex: Int = 0,
        ): Int {
            if (!isAvailable || steps.isEmpty()) {
                _state.value = TtsState.Failed(if (steps.isEmpty()) "没有可朗读的步骤" else _availability.value.label)
                return 0
            }
            val from = startIndex.coerceIn(0, steps.lastIndex)
            var queued = 0
            for (index in from until steps.size) {
                val step = steps[index]
                val text =
                    buildString {
                        if (step.title.isNotBlank()) {
                            append(latexTable.speakRichText(step.title).trim())
                            append("。")
                        }
                        if (step.content.isNotBlank()) append(latexTable.speakRichText(step.content).trim())
                    }.trim()
                if (text.isEmpty()) continue
                val engine = textToSpeech ?: continue
                val utteranceId = stepUtteranceId(index)
                val mode = if (queued == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val result =
                    runCatching { engine.speak(text, mode, Bundle(), utteranceId) }
                        .getOrDefault(TextToSpeech.ERROR)
                if (result == TextToSpeech.SUCCESS) queued++
            }
            if (queued == 0) {
                _state.value = TtsState.Failed("系统语音引擎拒绝朗读")
            }
            return queued
        }

        /** 停止朗读（页面离开时调用）。 */
        fun stop() {
            val engine = textToSpeech ?: return
            runCatching { engine.stop() }
            _state.value = TtsState.Stopped()
        }

        /** 释放引擎；App 进程结束前调用。释放后 [availability] 变为 [TtsAvailability.RELEASED]。 */
        fun release() {
            runCatching { textToSpeech?.stop() }
            runCatching { textToSpeech?.shutdown() }
            textToSpeech = null
            _availability.value = TtsAvailability.RELEASED
            _state.value = TtsState.Idle
        }

        /** 把一段 LaTeX 转成口语（UI 上「朗读这段公式」按钮的文本来源）。 */
        fun spokenTextOf(latex: String): String = latexTable.speakRichText(latex)

        private fun applySpeed(engine: TextToSpeech) {
            runCatching { engine.setSpeechRate(speed.rate) }
        }

        private val progressListener =
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId != null) {
                        _state.value = TtsState.Speaking(utteranceId, stepIndexOf(utteranceId))
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) {
                        _state.value = TtsState.Done(utteranceId, stepIndexOf(utteranceId))
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _state.value = TtsState.Failed("语音播放失败")
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) {
                    _state.value = TtsState.Failed("语音播放失败（错误码 " + errorCode + "）")
                }

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) {
                    _state.value = TtsState.Stopped(utteranceId)
                }
            }

        private fun stepIndexOf(utteranceId: String): Int {
            val marker = utteranceId.substringAfterLast(STEP_SEPARATOR, "")
            return marker.toIntOrNull() ?: -1
        }

        private fun nextUtteranceId(): String = "totop-" + System.nanoTime().toString()

        private fun stepUtteranceId(index: Int): String = "totop-step-" + index + STEP_SEPARATOR + index

        companion object {
            /** 初始化超时：低端机冷启动 TTS 服务可能需要 2-3 秒。 */
            const val INIT_TIMEOUT_MILLIS: Long = 5000L

            /** 步骤 utteranceId 的分隔符。 */
            private const val STEP_SEPARATOR = "#"
        }
    }
