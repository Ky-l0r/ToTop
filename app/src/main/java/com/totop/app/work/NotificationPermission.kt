package com.totop.app.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 复习提醒的通知权限（开发方案 FR-4.4）。
 *
 * Android 13（API 33）起 POST_NOTIFICATIONS 是**运行时权限**，
 * 不申请就发不出通知，而且不会报错（[ReviewReminderWorker] 会静默跳过）。
 * 因此设置页/首页需要在用户打开「复习提醒」开关时主动申请。
 *
 * 低版本（API < 33）通知默认可用，[ensure] 直接返回 true，不弹系统框。
 */
object NotificationPermission {
    /** 是否需要运行时申请（仅 Android 13+）。 */
    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 当前是否已授权；低版本恒为 true。 */
    fun isGranted(context: Context): Boolean {
        if (!isRequired()) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 通知权限申请器（Compose）。
 *
 * 用法：
 * ```
 * val ensureNotifications = rememberNotificationPermissionRequester { granted -> enabled = granted }
 * Switch(checked = enabled, onCheckedChange = { want -> if (want) ensureNotifications() else enabled = false })
 * ```
 *
 * @param onResult 权限结果回调；已授权时会立即收到 true
 */
@Composable
fun rememberNotificationPermissionRequester(onResult: (granted: Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted -> currentOnResult(granted) }
    return remember(context, launcher) {
        {
            if (NotificationPermission.isGranted(context)) {
                currentOnResult(true)
            } else if (!NotificationPermission.isRequired()) {
                currentOnResult(true)
            } else {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
