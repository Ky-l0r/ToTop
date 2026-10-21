package com.totop.app.ui.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.totop.app.ml.CameraCaptureController
import com.totop.app.ml.CaptureOutcome
import kotlinx.coroutines.launch

/** 拍照的界面层结果（已转成可读中文）。 */
sealed class CaptureUiOutcome {
    data class Success(val path: String) : CaptureUiOutcome()

    data class Failure(val message: String) : CaptureUiOutcome()
}

/**
 * 执行一次拍照并把结果交给回调。
 *
 * 单独抽成 Composable 函数，是为了让 [CaptureScreen] 的点击逻辑保持线性可读，
 * 同时保证协程作用域与组合生命周期一致（离开页面即取消）。
 */
@Composable
fun rememberCaptureOnce(): (CameraCaptureController, (CaptureUiOutcome) -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        { controller, onResult ->
            scope.launch {
                when (val outcome = controller.captureToCache()) {
                    is CaptureOutcome.Success -> onResult(CaptureUiOutcome.Success(outcome.file.absolutePath))
                    is CaptureOutcome.Failure ->
                        onResult(
                            CaptureUiOutcome.Failure(
                                outcome.error.userMessage + (outcome.detail?.let { "（" + it + "）" } ?: ""),
                            ),
                        )
                }
            }
        }
    }
}
