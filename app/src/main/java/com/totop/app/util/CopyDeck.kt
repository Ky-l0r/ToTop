package com.totop.app.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** assets/copy/home_lines.json 的一行。 */
@Serializable
private data class CopyLine(
    val emoji: String = "",
    val text: String = "",
)

@Serializable
private data class CopyFile(
    val lines: List<CopyLine> = emptyList(),
    val encouragements: List<String> = emptyList(),
)

/**
 * 文案表（首页每日一句、答完题的鼓励语）。
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

    private const val MILLIS_PER_DAY: Long = 86_400_000L

    private val json = Json { ignoreUnknownKeys = true }

    private var cached: CopyFile? = null

    /**
     * 今天这一句。
     *
     * 按 UTC 日序号取模，保证**同一天内反复进出首页看到的都是同一句**：
     * 每次随机会让人觉得「这句刚才不是这句」，反而像加载出了问题。
     */
    fun dailyLine(
        context: Context,
        nowUtc: Long,
    ): String {
        val lines = load(context).lines
        if (lines.isEmpty()) return ""
        val day = Math.floorDiv(nowUtc, MILLIS_PER_DAY)
        val line = lines[Math.floorMod(day, lines.size.toLong()).toInt()]
        return if (line.emoji.isBlank()) line.text else line.emoji + " " + line.text
    }

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
