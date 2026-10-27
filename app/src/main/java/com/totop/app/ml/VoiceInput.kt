package com.totop.app.ml

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 语音输入（系统识别引擎）。
 *
 * 为什么用系统 [SpeechRecognizer] 而不是接云端 ASR：
 * 1. 本项目是 BYOK 直连模型厂商，没有自建后端，也不允许新增第三方 SDK；
 * 2. 系统识别在多数国产 ROM 上由厂商自带引擎完成，中文口语识别质量足够「少打字」这个目标；
 * 3. 不额外产生费用，也不需要新的合规声明。
 *
 * 已知取舍：部分机型没有可用识别引擎（`SpeechRecognizer.isRecognitionAvailable` 为 false），
 * 或识别服务需要联网。这两种情况都**明确提示**，不做静默失败。
 */
object VoiceInputSupport {
    /** 本机是否有可用的语音识别服务。 */
    fun isAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /** 是否已授予录音权限。 */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * 语音输入控制器。
 *
 * 生命周期与持有它的 Composable 绑定：`rememberVoiceInput()` +
 * `DisposableEffect` 里 `destroy()`，退出页面时一定释放麦克风与引擎。
 *
 * 使用约定：
 * - [start] 每次只开一轮识别，`onText` 可能被回调多次（部分结果），调用方需要
 *   以「本轮开始前的文本」为前缀拼接，避免把上一句反复叠加；
 * - 用户再次点击麦克风 = [stop]，已识别到的部分结果保留。
 */
@Stable
class VoiceInputController internal constructor(
    private val context: Context,
) {
    private var recognizer: SpeechRecognizer? = null

    /** 是否正在听（用于按钮态与提示）。 */
    var listening: Boolean by mutableStateOf(false)
        private set

    /**
     * 开始一轮识别。
     *
     * @param onText 识别到的文本（可能是部分结果，会多次回调）
     * @param onError 失败原因（可直接展示给用户）
     */
    fun start(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!VoiceInputSupport.isAvailable(context)) {
            onError("本机没有可用的语音识别服务，可以改用打字输入。")
            return
        }
        if (!VoiceInputSupport.hasPermission(context)) {
            onError("没有录音权限，无法使用语音输入。")
            return
        }
        if (listening) {
            stop()
            return
        }
        val engine = recognizer ?: createRecognizer().also { recognizer = it }
        if (engine == null) {
            onError("语音识别初始化失败，可以改用打字输入。")
            return
        }
        engine.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    listening = false
                }

                override fun onError(error: Int) {
                    listening = false
                    onError(messageOf(error))
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    bestOf(results)?.let(onText)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    bestOf(partialResults)?.let(onText)
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
        listening = true
        runCatching { engine.startListening(intent()) }
            .onFailure {
                listening = false
                onError("语音输入启动失败：" + (it.message ?: "未知原因"))
            }
    }

    /** 结束本轮识别，保留已识别到的文本。 */
    fun stop() {
        listening = false
        runCatching { recognizer?.stopListening() }
    }

    /** 释放引擎。页面销毁时必须调用，否则麦克风会被一直占用。 */
    fun destroy() {
        listening = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun createRecognizer(): SpeechRecognizer? =
        runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()

    private fun intent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // 固定中文：学生的解题思路与追问都是中文，跟随系统语言常常得到英文识别
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun bestOf(bundle: Bundle?): String? =
        bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 错误码 -> 可执行的中文提示。 */
    private fun messageOf(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "录音出错，请检查麦克风是否被其它应用占用。"
            SpeechRecognizer.ERROR_CLIENT -> "语音识别被中断，请再点一次麦克风。"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限，无法使用语音输入。"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "语音识别服务连不上网络。部分机型的中文识别需要联网，可以改用打字输入。"
            SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次。"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，稍等一下再试。"
            SpeechRecognizer.ERROR_SERVER -> "语音识别服务暂时不可用，可以改用打字输入。"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音，请再点一次麦克风。"
            else -> "语音识别失败（错误码 " + error + "），可以改用打字输入。"
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
            if (VoiceInputSupport.hasPermission(context)) {
                currentOnResult(true)
                onGranted()
            } else {
                pending[0] = onGranted
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
