package com.totop.app.ml

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** 相机相关错误（可读中文，直接展示给用户）。 */
enum class CameraCaptureError(val userMessage: String) {
    /** 未授予相机权限。 */
    PERMISSION_DENIED("未获得相机权限，可在系统设置中开启，或改用「相册导入」。"),

    /** 设备无可用相机。 */
    CAMERA_UNAVAILABLE("本机没有可用的相机，请改用「相册导入」。"),

    /** 相机初始化失败（被其他应用占用等）。 */
    BIND_FAILED("相机启动失败，可能被其他应用占用，请关闭后重试。"),

    /** 拍照失败。 */
    CAPTURE_FAILED("拍照失败，请重试或改用「相册导入」。"),
}

/** 拍照结果：[file] 为 cacheDir 下的临时原图，交给 [ImagePreprocess] 压缩后写正式目录。 */
sealed class CaptureOutcome {
    data class Success(val file: File) : CaptureOutcome()

    data class Failure(val error: CameraCaptureError, val detail: String? = null) : CaptureOutcome()
}

/** 相机硬件与权限的静态检查（纯函数式，便于 UI 层在展示入口前判断）。 */
object CameraCaptureSupport {
    /** 是否有相机硬件。 */
    fun hasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** 是否已授予相机权限。 */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** 相机入口是否可用（硬件 + 权限都满足）。 */
    fun isReady(context: Context): Boolean = hasCamera(context) && hasPermission(context)
}

/**
 * 相机控制器：由 [CameraPreview] 在绑定成功后回调给调用方持有。
 *
 * 职责边界：
 * - 只负责「拍到一张图并落到 cacheDir」，**不做压缩与识别**
 *   （压缩交给 [ImagePreprocess]，识别交给 [OcrEngine]，各自可独立测试与替换）；
 * - 生命周期跟随 Compose（[CameraPreview] 的 DisposableEffect 会 unbind）。
 *
 * 所有 suspend 方法都可在主线程调用，内部切到 [ioDispatcher]。
 */
@Stable
class CameraCaptureController internal constructor(
    private val context: Context,
    private val imageCapture: ImageCapture,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 相机是否仍处于绑定状态。 */
    var isBound: Boolean = true
        internal set

    /**
     * 拍照到 cacheDir（不压缩）。
     *
     * 返回的临时文件由调用方决定去留：走 [ImagePreprocess] 后会得到 sha256 命名的正式文件，
     * 临时文件可立即删除，避免 cache 膨胀。
     */
    suspend fun captureToCache(): CaptureOutcome =
        withContext(ioDispatcher) {
            if (!isBound) return@withContext CaptureOutcome.Failure(CameraCaptureError.BIND_FAILED, "相机未绑定")
            val dir = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }
            val target = File(dir, UUID.randomUUID().toString() + ".jpg")
            val options = ImageCapture.OutputFileOptions.Builder(target).build()
            suspendCancellableCoroutine<CaptureOutcome> { continuation ->
                imageCapture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            if (continuation.isActive) continuation.resume(CaptureOutcome.Success(target))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    CaptureOutcome.Failure(CameraCaptureError.CAPTURE_FAILED, exception.message),
                                )
                            }
                        }
                    },
                )
            }
        }

    /** 临时文件用完后清理。 */
    suspend fun discard(file: File) =
        withContext(ioDispatcher) {
            runCatching { file.delete() }
            Unit
        }

    companion object {
        /** 相机临时目录（相对 cacheDir）。 */
        const val CACHE_DIR: String = "camera"
    }
}

/** 相机预览 + 拍照的 Compose 封装状态。 */
enum class CameraPreviewState { IDLE, BINDING, READY, FAILED }

/**
 * CameraX 相机预览（Compose 版，开发方案 FR-1.1）。
 *
 * 用法：
 * ```
 * var controller by remember { mutableStateOf<CameraCaptureController?>(null) }
 * CameraPreview(
 *     modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
 *     onControllerReady = { controller = it },
 *     onError = { error -> message = error.userMessage },
 * )
 * ```
 *
 * 设计要点：
 * - 通过 [AndroidView] 承载 [PreviewView]，实现模式固定为 COMPATIBLE，
 *   牺牲一点性能换取低端机与各类国产 ROM 的兼容性；
 * - 绑定过程全部在主线程执行，[ProcessCameraProvider] 的 ListenableFuture 用
 *   addListener + 主线程 Executor，不引入额外协程适配库；
 * - 离开组合自动 unbindAll，避免相机被占用耗电；
 * - 失败走 [onError]，UI 层据此提示「改用相册导入」，绝不静默失败。
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    onControllerReady: (CameraCaptureController) -> Unit = {},
    onStateChanged: (CameraPreviewState) -> Unit = {},
    onError: (CameraCaptureError) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

    DisposableEffect(lifecycleOwner, enabled) {
        var provider: ProcessCameraProvider? = null
        var controller: CameraCaptureController? = null
        onStateChanged(CameraPreviewState.BINDING)

        if (!enabled) {
            onStateChanged(CameraPreviewState.IDLE)
            onDispose { }
        } else if (!CameraCaptureSupport.hasCamera(context)) {
            onStateChanged(CameraPreviewState.FAILED)
            onError(CameraCaptureError.CAMERA_UNAVAILABLE)
            onDispose { }
        } else if (!CameraCaptureSupport.hasPermission(context)) {
            onStateChanged(CameraPreviewState.FAILED)
            onError(CameraCaptureError.PERMISSION_DENIED)
            onDispose { }
        } else {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val bound = runCatching { future.get() }.getOrNull()
                if (bound == null) {
                    onStateChanged(CameraPreviewState.FAILED)
                    onError(CameraCaptureError.BIND_FAILED)
                } else {
                    val result =
                        runCatching {
                            bound.unbindAll()
                            val preview =
                                Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                            val capture =
                                ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .setTargetRotation(previewView.display?.rotation ?: 0)
                                    .build()
                            val selector =
                                CameraSelector.Builder()
                                    .requireLensFacing(lensFacing)
                                    .build()
                            bound.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                            capture
                        }
                    val capture = result.getOrNull()
                    if (capture == null) {
                        onStateChanged(CameraPreviewState.FAILED)
                        onError(CameraCaptureError.BIND_FAILED)
                    } else {
                        provider = bound
                        controller = CameraCaptureController(context, capture)
                        onStateChanged(CameraPreviewState.READY)
                        onControllerReady(controller!!)
                    }
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                controller?.isBound = false
                runCatching { provider?.unbindAll() }
            }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * 相册导入（开发方案 FR-1.1）。
 *
 * 使用 [ActivityResultContracts.PickVisualMedia]：Android 13+ 走系统照片选择器（无需存储权限），
 * 低版本自动回退到系统文档选择器，全程不申请 READ_EXTERNAL_STORAGE。
 *
 * @return 触发导入的 lambda，直接挂到按钮的 onClick 上
 */
@Composable
fun rememberAlbumPicker(
    onPicked: (Uri) -> Unit,
    onCancelled: () -> Unit = {},
): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri == null) onCancelled() else onPicked(uri)
        }
    return remember(launcher) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

/**
 * 相机权限申请。
 *
 * @return `request(onGranted)`：点击时若已授权则直接回调 true，
 *         否则弹出系统授权框，用户决定后回调
 */
@Composable
fun rememberCameraPermissionRequester(onResult: (granted: Boolean) -> Unit = {}): (onGranted: () -> Unit) -> Unit {
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
            if (CameraCaptureSupport.hasPermission(context)) {
                currentOnResult(true)
                onGranted()
            } else {
                pending[0] = onGranted
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
