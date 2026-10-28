package com.totop.app.data.prefs

import android.content.Context

/**
 * 语音识别引擎的选择。
 *
 * 为什么用 SharedPreferences 而不是 settings 表：
 * 这个值只影响界面里的一个下拉项，不参与统计、不进导出、不需要迁移；
 * 为它加一行 Room 记录反而要多维护一次迁移与快照。识别引擎属于「设备本地偏好」，
 * 换手机后重新选一次即可。
 *
 * 这里只做存取，不解析引擎列表——枚举引擎要查 PackageManager，属于识别层（`ml`）的职责，
 * 数据层依赖识别层会破坏依赖方向。
 */
object VoiceEnginePrefs {
    private const val FILE: String = "voice_input"

    private const val KEY_ENGINE: String = "engineId"

    /** 用户选过的引擎 ID；没选过返回 null，由调用方决定默认值。 */
    fun selectedId(context: Context): String? = prefs(context).getString(KEY_ENGINE, null)

    /** 记住选择（传 null 表示恢复默认）。 */
    fun select(
        context: Context,
        engineId: String?,
    ) {
        runCatching {
            prefs(context).edit().apply {
                if (engineId == null) remove(KEY_ENGINE) else putString(KEY_ENGINE, engineId)
            }.apply()
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
