package com.totop.app.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.totop.app.ml.CameraCaptureController
import com.totop.app.ml.CameraPreview
import com.totop.app.ml.CameraPreviewState
import com.totop.app.ml.rememberAlbumPicker

/**
 * 全屏拍照页。
 *
 * 信息架构对齐主流拍题 App，只借鉴交互习惯，不使用任何第三方素材：
 * - 相机预览铺满全屏，沉浸式取景；
 * - 底部中央一枚大圆形快门，取代「点文字拍照」；
 * - 左下角相册入口，右下角轻提示（可拍多页）。
 *
 * 失败一定给出可执行下一步，绝不静默：
 * 无权限 / 无相机 / 被占用，分别对应不同的中文提示。
 */
@Composable
fun CaptureScreen(
    pageCount: Int,
    onCaptured: (String) -> Unit,
    onPickAlbum: (android.net.Uri) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var controller by remember { mutableStateOf<CameraCaptureController?>(null) }
    var previewState by remember { mutableStateOf(CameraPreviewState.IDLE) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableStateOf(androidx.camera.core.CameraSelector.LENS_FACING_BACK) }
    val captureOnce = rememberCaptureOnce()
    val pickAlbum = rememberAlbumPicker(onPicked = onPickAlbum)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            lensFacing = lensFacing,
            onControllerReady = { controller = it },
            onStateChanged = { previewState = it },
            onError = { error -> errorText = error.userMessage },
        )

        // 顶部：关闭 + 页数提示
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text(text = "关闭", color = Color.White)
            }
            if (pageCount > 0) {
                Box(
                    modifier =
                        Modifier
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "已拍 " + pageCount + " 页",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // 底部：相册 / 快门 / 切换镜头
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            errorText?.let { message ->
                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 24.dp)
                            .background(Color(0xFF3B0A0A).copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                ) {
                    Text(text = message, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 相册
                CircleIconButton(onClick = pickAlbum) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = "从相册选图",
                        tint = Color.White,
                    )
                }

                // 快门
                Box(
                    modifier =
                        Modifier
                            .size(76.dp)
                            .border(4.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .padding(6.dp)
                            .clickable(enabled = !capturing && previewState == CameraPreviewState.READY) {
                                val target = controller ?: return@clickable
                                capturing = true
                                errorText = null
                                captureOnce(target) { outcome ->
                                    capturing = false
                                    when (outcome) {
                                        is CaptureUiOutcome.Success -> onCaptured(outcome.path)
                                        is CaptureUiOutcome.Failure -> errorText = outcome.message
                                    }
                                }
                            }
                            .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // 切换前后摄像头
                CircleIconButton(onClick = {
                    lensFacing =
                        if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                        } else {
                            androidx.camera.core.CameraSelector.LENS_FACING_BACK
                        }
                }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "切换摄像头",
                        tint = Color.White,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text =
                    when (previewState) {
                        CameraPreviewState.READY -> "对准题目，点快门；题目跨页可以接着拍"
                        CameraPreviewState.BINDING -> "相机正在启动…"
                        CameraPreviewState.FAILED -> "相机不可用，可改用左下角相册导入"
                        CameraPreviewState.IDLE -> "轻点快门拍下题目"
                    },
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CircleIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
