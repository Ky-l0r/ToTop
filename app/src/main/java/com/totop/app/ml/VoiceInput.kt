package com.totop.app.ml

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** 系统默认识别服务在偏好里的 ID。 */
private const val SYSTEM_ENGINE_ID = "system"

/** 本机（离线）识别服务的 ID，仅在 API 31 及以上存在。 */
private const val ON_DEVICE_ENGINE_ID = "on_device"

/** 单轮识别上限。系统识别服务普遍在 60 秒左右自己收尾，这里提前一点，避免半个字被截断。 */
private const val MAX_LISTEN_MS: Long = 55_000L

/** 松手后等待最终结果的兜底时长：部分国产引擎不回调 onResults，不设兜底会一直显示「正在整理」。 */
private const val RESULT_TIMEOUT_MS: Long = 4_000L

/** 连续几次空结果后提示换引擎。 */
private const val EMPTY_HINT_THRESHOLD: Int = 2

/**
 * 一个可选的语音识别服务。
 *
 * @param id 写进偏好的稳定标识：系统默认是 `system`，本机识别是 `on_device`，
 *   其余为 `ComponentName.flattenToString()`
 * @param component 具体服务；为空表示系统默认或本机识别
 * @param onDevice 是否需要走 [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 */
data class VoiceEngine(
    val id: String,
    val label: String,
    val component: ComponentName?,
    val onDevice: Boolean = false,
)

/**
 * 枚举本机可用的语音识别服务。
 *
 * 为什么要让用户选：国产 ROM 自带的识别服务对中文口语（尤其带口音的解题思路）
 * 明显好于 Google 服务；反过来，部分机型自带的引擎必须联网，离线时只有系统默认可用。
 * 没有统一的「谁更好」结论，所以把选择权交给用户，默认按下面的规则猜一个。
 */
object VoiceEngines {
    /** 全部可选引擎，第一项固定是系统默认。 */
    fun list(context: Context): List<VoiceEngine> {
        val engines = mutableListOf(VoiceEngine(SYSTEM_ENGINE_ID, "系统默认", null))
        if (onDeviceAvailable(context)) {
            engines += VoiceEngine(ON_DEVICE_ENGINE_ID, "本机识别（离线）", null, onDevice = true)
        }
        queryServices(context).forEach { component ->
            if (engines.none { it.component == component }) {
                engines += VoiceEngine(component.flattenToString(), labelOf(context, component), component)
            }
        }
        return engines
    }

    /**
     * 默认引擎：优先非 Google 的厂商引擎，其次系统默认。
     *
     * 判断依据只有包名——系统没有提供「这个引擎中文准不准」的任何元数据。
     * 猜错的代价很小：用户可以在设置里换，而且连续两次空结果会主动提示。
     */
    fun default(context: Context): VoiceEngine {
        val engines = list(context)
        return engines.firstOrNull { engine ->
            val pkg = engine.component?.packageName ?: return@firstOrNull false
            !pkg.contains("google", ignoreCase = true)
        } ?: engines.first()
    }

    /**
     * 按会话内的尝试顺序给出「下一个还没试过的引擎」。
     *
     * 旧实现只在网络错误时换一次「离线引擎」，等于先猜病因再决定换谁；
     * 而实际失败原因（服务端 5xx、默认服务没装、被系统拦截）各不相同，
     * 逐个试一遍比猜「该换离线还是在线」可靠得多。
     */
    fun nextUntried(
        context: Context,
        triedIds: Set<String>,
    ): VoiceEngine? = list(context).firstOrNull { it.id !in triedIds }

    /** 按 ID 取引擎，找不到时回退到默认。 */
    fun find(
        context: Context,
        id: String?,
    ): VoiceEngine {
        val engines = list(context)
        return engines.firstOrNull { it.id == id } ?: default(context)
    }

    /** 本机是否有可用的识别服务。 */
    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /** 是否已授予录音权限。 */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun onDeviceAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDeviceAvailableApi31(context)

    @RequiresApi(Build.VERSION_CODES.S)
    private fun onDeviceAvailableApi31(context: Context): Boolean =
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private fun queryServices(context: Context): List<ComponentName> =
        runCatching {
            context.packageManager
                .queryIntentServices(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
                .mapNotNull { info ->
                    info.serviceInfo?.let { service -> ComponentName(service.packageName, service.name) }
                }
        }.getOrDefault(emptyList())

    private fun labelOf(
        context: Context,
        component: ComponentName,
    ): String =
        runCatching {
            val info = context.packageManager.getServiceInfo(component, 0)
            info.loadLabel(context.packageManager).toString().ifBlank { component.packageName }
        }.getOrDefault(component.packageName)
}

/**
 * 语音输入控制器（按住说话）。
 *
 * 交互约定：
 * - [press] 开始识别；[release] 松手结束——除非已 [lock]（上滑锁定），锁定后松手继续录，
 *   由 [finish] 结束；
 * - [level] 是 0..1 的音量，直接驱动声纹条；
 * - 联网识别失败时自动换离线引擎重试一次，失败原因通过 [onError] 交给界面；
 * - 连续两次空结果提示换引擎，每次进程只提示一次（[emptyHintShown]）。
 *
 * 生命周期与持有它的 Composable 绑定：`rememberVoiceInput()` + `DisposableEffect` 里
 * `destroy()`，退出页面时一定释放麦克风与引擎。
 */
@Stable
class VoiceInputController internal constructor(
    private val context: Context,
) {
    /** 正在录音（含松手后等待结果的一小段）。 */
    var listening: Boolean by mutableStateOf(false)
        private set

    /** 上滑锁定：松手不结束。 */
    var locked: Boolean by mutableStateOf(false)
        private set

    /** 已松手，等待最终结果。 */
    var finishing: Boolean by mutableStateOf(false)
        private set

    /** 当前音量 0..1，驱动声纹条。 */
    var level: Float by mutableFloatStateOf(0f)
        private set

    /**
     * 引擎是否真的在上报音量（[RecognitionListener.onRmsChanged]）。
     *
     * 国产 ROM 的识别服务里有一部分**完全不上报** RMS：此时 [level] 会一直是 0，
     * 声纹条看起来像「死的」。界面据此改用「拾音中」的呼吸指示，而不是假装有音量。
     */
    var hasVolume: Boolean by mutableStateOf(false)
        private set

    /** 是否检测到人在说话（onBeginningOfSpeech .. onEndOfSpeech）。 */
    var speaking: Boolean by mutableStateOf(false)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var engine: VoiceEngine? = null
    private var onText: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onEngineSwitched: ((VoiceEngine) -> Unit)? = null
    private var emptyCount = 0

    /** 本轮已经试过的引擎（按 id），用于「换一个引擎再试」，避免同一引擎反复重试。 */
    private val triedEngines = mutableSetOf<String>()

    /** 最近一次部分结果：引擎不回调 onResults 时用它兜底，比丢掉整句话好。 */
    private var lastPartial: String = ""

    /** 本轮是否已经把最终文本交给界面（防止部分结果与兜底重复写入）。 */
    private var delivered: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    /** 开始一轮识别。重复调用会先释放上一轮。 */
    fun press(
        engineId: String?,
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        onEngineSwitched: (VoiceEngine) -> Unit = {},
    ) {
        if (!VoiceEngines.isAvailable(context)) {
            onError("本机没有可用的语音识别服务，可以改用打字输入。")
            return
        }
        if (!VoiceEngines.hasPermission(context)) {
            onError("没有录音权限，无法使用语音输入。")
            return
        }
        this.onText = onText
        this.onError = onError
        this.onEngineSwitched = onEngineSwitched
        triedEngines.clear()
        delivered = false
        listen(VoiceEngines.find(context, engineId))
    }

    /** 上滑锁定：松手不结束。 */
    fun lock() {
        if (listening && !finishing) locked = true
    }

    /** 松手。锁定态下不结束；普通态下结束本轮并等待结果。 */
    fun release() {
        if (!listening || finishing || locked) return
        stopAndWait()
    }

    /** 明确结束（锁定态点按钮、或到达单轮上限）。 */
    fun finish() {
        if (!listening) return
        locked = false
        stopAndWait()
    }

    /** 取消本轮，丢弃结果（页面销毁、切到键盘输入时用）。 */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        listening = false
        locked = false
        finishing = false
        speaking = false
        hasVolume = false
        level = 0f
        lastPartial = ""
        runCatching { recognizer?.cancel() }
    }

    /** 释放引擎。页面销毁时必须调用，否则麦克风会被一直占用。 */
    fun destroy() {
        cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
        engine = null
    }

    private fun listen(target: VoiceEngine) {
        val created = createRecognizer(target)
        if (created == null) {
            // 创建失败不直接放弃：换下一个可用引擎，很多机型「系统默认」压根没装识别服务，
            // 但厂商自带的那一个是在的。
            if (switchToNextEngine()) return
            onError?.invoke("语音识别初始化失败，可以改用打字输入。")
            return
        }
        engine = target
        triedEngines += target.id
        listening = true
        locked = false
        finishing = false
        speaking = false
        hasVolume = false
        level = 0f
        lastPartial = ""
        delivered = false
        created.setRecognitionListener(listener)
        val started =
            runCatching { created.startListening(intent()) }.isSuccess
        if (!started) {
            listening = false
            if (switchToNextEngine()) return
            onError?.invoke("语音输入启动失败，可以改用打字输入。")
            return
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finish() }, MAX_LISTEN_MS)
    }

    /**
     * 换一个还没试过的引擎继续。
     *
     * 这是「几乎每次都识别不出来」的主修法：以前只在网络错误时换一次离线引擎，
     * 而国产 ROM 上失败原因五花八门（默认服务没装、服务端 5xx、被系统拦截），
     * 只要第一次落在坏引擎上就永远失败。现在按可用列表逐个试，最多试 [MAX_ENGINE_ATTEMPTS] 个。
     */
    private fun switchToNextEngine(): Boolean {
        if (triedEngines.size >= MAX_ENGINE_ATTEMPTS) return false
        val next = VoiceEngines.nextUntried(context, triedEngines) ?: return false
        onEngineSwitched?.invoke(next)
        listen(next)
        return true
    }

    private fun stopAndWait() {
        finishing = true
        locked = false
        speaking = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.stopListening() }
        // 引擎不回调时兜底收尾，避免界面一直停在「正在整理」。
        // 兜底时若有部分结果就把它交出去，而不是把已经听到的半句话丢掉。
        handler.postDelayed({ complete(salvage = true) }, RESULT_TIMEOUT_MS)
    }

    /**
     * 收尾。
     *
     * @param salvage 为 true 时：若引擎始终没回调最终结果，用最近一次部分结果兜底。
     *   这条兜底很重要——部分国产引擎会一路给部分结果，但松手后不回调 onResults，
     *   旧实现到这里直接清状态，用户看到的就是「说了半天一个字都没有」。
     */
    private fun complete(salvage: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        val fallback = if (salvage && !delivered) lastPartial.trim().takeIf { it.isNotEmpty() } else null
        listening = false
        finishing = false
        speaking = false
        hasVolume = false
        level = 0f
        if (fallback != null) {
            delivered = true
            emptyCount = 0
            onText?.invoke(fallback)
        }
    }

    private fun createRecognizer(target: VoiceEngine): SpeechRecognizer? {
        if (recognizer != null && engine?.id != target.id) {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        if (recognizer == null) {
            recognizer =
                runCatching {
                    when {
                        target.onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> createOnDevice(context)
                        target.component != null -> SpeechRecognizer.createSpeechRecognizer(context, target.component)
                        else -> SpeechRecognizer.createSpeechRecognizer(context)
                    }
                }.getOrNull()
        }
        return recognizer
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createOnDevice(context: Context): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    private fun intent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // 固定中文：学生的解题思路与追问都是中文，跟随系统语言常常得到英文识别
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            // 部分引擎只认 LANGUAGE_PREFERENCE，两个都给上，避免被当成系统语言
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // 一口气说完一句话通常会超过 1 秒，静音判定太短会把后半句切掉：
            // 「重力加速度取十，求落地时间」很可能只剩「重力加速度取十」。
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_COMPLETE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MAYBE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SPEECH_MS)
            // 离线时优先本机模型：部分引擎在无网时会直接把 ERROR_NETWORK 抛回来
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !isOnline())
        }

    private fun isOnline(): Boolean =
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
            val network = manager.activeNetwork ?: return@runCatching false
            val caps = manager.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)

    /** 空结果的统一处理：计数、必要时提示换引擎。 */
    private fun onEmpty() {
        emptyCount++
        if (emptyCount >= EMPTY_HINT_THRESHOLD && !emptyHintShown) {
            emptyHintShown = true
            onError?.invoke("连续两次没听清，可以到「设置 - 教学偏好」换一个识别引擎。")
        } else {
            onError?.invoke("没听清，请再说一次。")
        }
    }

    /** 识别失败：能换引擎就换一个再试，换不动才把原因交给界面。 */
    private fun onFailure(error: Int) {
        // 「没听到声音 / 没匹配上」不是引擎坏，换引擎重录只会在用户没开口时反复开麦，
        // 因此这两类只在有部分结果时兜底，否则直接给出可执行的提示。
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            val partial = lastPartial.trim()
            complete()
            if (partial.isNotEmpty()) {
                delivered = true
                emptyCount = 0
                onText?.invoke(partial)
            } else {
                onEmpty()
            }
            return
        }
        val retryable =
            error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER ||
                error == SpeechRecognizer.ERROR_CLIENT
        // 只在「用户还按着、也没锁定」时自动换引擎重来：
        // 松手后（finishing）或已锁定时重启，会凭空多开一次麦克风，
        // 界面还会从「正在整理」跳回「录音中」，用户以为按钮失灵。
        val canSwitchEngine = !finishing && !locked
        if (retryable && canSwitchEngine && switchToNextEngine()) return
        complete()
        if (isNetworkError(error)) {
            onError?.invoke("语音识别服务连不上网络，已试过本机可用的识别引擎。可以改用打字输入，或到设置里换一个引擎。")
            return
        }
        onError?.invoke(messageOf(error))
    }

    private val listener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() {
                speaking = true
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 系统给的 rmsdB 大致在 -2..10 之间。有的引擎一次都不回调这里，
                // 界面据此把声纹条换成「拾音中」的呼吸指示，而不是假装有音量。
                hasVolume = true
                val raw = ((rmsdB + RMS_FLOOR_DB) / RMS_SPAN_DB).coerceIn(0f, 1f)
                // 快起慢落：说话时立刻抬起，停顿时缓慢回落，观感上才像在「听」。
                val factor = if (raw > level) RMS_ATTACK else RMS_RELEASE
                level += (raw - level) * factor
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                speaking = false
                // 引擎自己判断说完了：等同松手，进入等待结果。
                if (!locked) {
                    finishing = true
                    // 引擎若不回调 onResults，这里不设兜底就会永远停在「正在整理」。
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ complete(salvage = true) }, RESULT_TIMEOUT_MS)
                }
            }

            override fun onError(error: Int) = onFailure(error)

            override fun onResults(results: Bundle?) {
                val text = bestOf(results)
                delivered = true
                complete()
                if (text == null) {
                    // 最终结果为空但刚才有部分结果：用部分结果，别让用户白说一遍
                    val partial = lastPartial.trim()
                    if (partial.isNotEmpty()) {
                        emptyCount = 0
                        onText?.invoke(partial)
                    } else {
                        onEmpty()
                    }
                } else {
                    emptyCount = 0
                    onText?.invoke(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                bestOf(partialResults)?.let { text ->
                    lastPartial = text
                    onText?.invoke(text)
                }
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

    private fun bestOf(bundle: Bundle?): String? =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun isNetworkError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT

    /** 错误码 -> 可执行的中文提示。 */
    private fun messageOf(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "录音出错，请检查麦克风是否被其它应用占用。"
            SpeechRecognizer.ERROR_CLIENT -> "语音识别被中断，再按住说一次。"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限，无法使用语音输入。"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，稍等一下再试。"
            SpeechRecognizer.ERROR_SERVER -> "语音识别服务暂时不可用，可以改用打字输入。"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音，再按住说一次。"
            else -> "语音识别失败（错误码 " + error + "），可以改用打字输入。"
        }

    private companion object {
        /** 换引擎提示每次进程只弹一次，避免反复打扰。 */
        var emptyHintShown: Boolean = false

        /** 单轮最多换几个引擎：再多就变成反复开麦，观感上像卡死。 */
        const val MAX_ENGINE_ATTEMPTS: Int = 3

        /** 说完后判定「已说完」的静音时长（毫秒）。 */
        const val SILENCE_COMPLETE_MS: Long = 1400L

        /** 可能说完的静音时长，略短于上者，让引擎更早给出中间结果。 */
        const val SILENCE_MAYBE_MS: Long = 900L

        /** 最短录音时长：避免刚开口就被判定说完。 */
        const val MIN_SPEECH_MS: Long = 1000L

        /** rmsdB 的下界（系统文档给的范围是 -2..10 dB）。 */
        const val RMS_FLOOR_DB: Float = 2f

        /** rmsdB 的量程。 */
        const val RMS_SPAN_DB: Float = 12f

        /** 音量上升系数：说话要立刻反应。 */
        const val RMS_ATTACK: Float = 0.65f

        /** 音量下降系数：停顿要缓一点，否则声纹条会一直闪。 */
        const val RMS_RELEASE: Float = 0.22f
    }
}

/** 创建一个与当前 Composable 同生命周期的语音输入控制器。 */
@Composable
fun rememberVoiceInput(): VoiceInputController {
    val context = LocalContext.current
    val controller = remember(context) { VoiceInputController(context) }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }
    return controller
}

/**
 * 录音权限申请（与相机权限同款用法）。
 *
 * @return `request(onGranted)`：已授权时直接回调，否则弹系统授权框
 */
@Composable
fun rememberRecordAudioPermissionRequester(onResult: (granted: Boolean) -> Unit = {}): (onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(() -> Unit)>(1) }
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            currentOnResult(granted)
            val action = pending[0]
            pending[0] = null
            if (granted) action?.invoke()
        }
    return remember(context, launcher) {
        { onGranted: () -> Unit ->
            if (VoiceEngines.hasPermission(context)) {
                currentOnResult(true)
                onGranted()
            } else {
                pending[0] = onGranted
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
