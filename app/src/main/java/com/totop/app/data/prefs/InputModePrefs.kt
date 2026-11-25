package com.totop.app.data.prefs

import android.content.Context

/**
 * 输入方式的记忆（0.9）：上次用的是语音还是键盘，下次进来直接停在那个状态。
 *
 * 为什么用 SharedPreferences 而不是 settings 表：它只影响「输入框下一次以什么状态出现」，
 * 不参与统计、不进导出、不需要迁移，与 [CapturePrefs] 属于同一类本机偏好。
 *
 * 拍题页与陪伴页共用这一项——两页的输入框长得一样，记忆却各记各的，学生就会觉得「时灵时不灵」。
 */
object InputModePrefs {
    private const val FILE: String = "input_mode"

    private const val KEY_REMEMBER: String = "remember"

    private const val KEY_LAST_VOICE: String = "lastVoice"

    /** 是否记住上次的输入方式；默认开启。 */
    fun remember(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_REMEMBER, true) }.getOrDefault(true)

    fun setRemember(
        context: Context,
        value: Boolean,
    ) {
        runCatching { prefs(context).edit().putBoolean(KEY_REMEMBER, value).apply() }
    }

    /**
     * 上次是不是语音态。
     *
     * 没记过时返回 null，由调用方按各自的默认值决定（拍题页有草稿时更该是键盘，
     * 陪伴页首次进来更该是语音）——这一处不替它们做统一默认。
     */
    fun lastVoice(context: Context): Boolean? =
        runCatching {
            if (prefs(context).contains(KEY_LAST_VOICE)) prefs(context).getBoolean(KEY_LAST_VOICE, true) else null
        }.getOrNull()

    fun setLastVoice(
        context: Context,
        voice: Boolean,
    ) {
        runCatching { prefs(context).edit().putBoolean(KEY_LAST_VOICE, voice).apply() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
