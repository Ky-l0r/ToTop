package com.totop.app.data.prefs

import android.content.Context
import com.totop.app.ml.ScanLevel

/**
 * 拍照增强的强度偏好。
 *
 * 为什么用 SharedPreferences 而不是 settings 表：这一项只影响「下一次上传怎么处理图片」，
 * 不参与统计、不进导出、不需要迁移；和设备上的语音引擎偏好属于同一类「本机处理偏好」。
 */
object CapturePrefs {
    private const val FILE: String = "capture"

    private const val KEY_SCAN_LEVEL: String = "scanLevel"

    /** 当前档位；没选过时用默认档。 */
    fun scanLevel(context: Context): ScanLevel = ScanLevel.fromKey(rawLevel(context))

    /** 记住选择。 */
    fun selectScanLevel(
        context: Context,
        level: ScanLevel,
    ) {
        runCatching {
            prefs(context).edit().putString(KEY_SCAN_LEVEL, level.key).apply()
        }
    }

    /** 原始的 key（供测试与诊断）。 */
    fun rawLevel(context: Context): String? = runCatching { prefs(context).getString(KEY_SCAN_LEVEL, null) }.getOrNull()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
