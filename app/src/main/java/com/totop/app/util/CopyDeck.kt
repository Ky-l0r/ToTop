package com.totop.app.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CopyFile(
    // 0.9 首页去掉了每日一句，`lines` 字段不再读取；JSON 里保留它不影响解析（ignoreUnknownKeys）。
    val encouragements: List<String> = emptyList(),
)

/**
 * 文案表（答完题的鼓励语）。
 *
 * 为什么放 assets 而不是 res/strings.xml 或 Kotlin 常量：
 * - 这类文案会经常改，改一句话不该牵动任何代码；
 * - 它是**一组**文案而不是一条，塞进 strings.xml 会变成一堆同名前缀的键；
 * - 项目规范要求「文案、提示词、种子数据进 assets」，这里遵守同一条。
 *
 * 读取结果在进程内缓存一次：文件只有几 KB，但每次重组都读盘没有意义。
 */
object CopyDeck {
    private const val ASSET_PATH: String = "copy/home_lines.json"

    private val json = Json { ignoreUnknownKeys = true }

    private var cached: CopyFile? = null

    /** 答完一道题后的收束语，随机取一条。 */
    fun encouragement(context: Context): String {
        val items = load(context).encouragements
        if (items.isEmpty()) return "这题过了。下一道。"
        return items[Math.floor(Math.random() * items.size).toInt().coerceIn(0, items.lastIndex)]
    }

    private fun load(context: Context): CopyFile {
        cached?.let { return it }
        val parsed =
            runCatching {
                val raw =
                    context.assets.open(ASSET_PATH).use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                json.decodeFromString<CopyFile>(raw)
            }.getOrElse { CopyFile() }
        cached = parsed
        return parsed
    }

    /** 仅供单测使用：清掉缓存，避免用例之间互相影响。 */
    internal fun invalidate() {
        cached = null
    }
}
