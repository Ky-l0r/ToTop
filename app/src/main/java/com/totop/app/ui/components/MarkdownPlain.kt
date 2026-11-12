package com.totop.app.ui.components

/**
 * 把模型顺手写下的 Markdown 记号压成纯文本（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 为什么需要它：陪伴页回答走的是 [FormulaText]，它只认 `$…$` 与 `^`/`_`，没有任何 Markdown
 * 语义；而 `companion_ask.md` 原先只禁了 Markdown 表格，没禁加粗。模型习惯用 `**关键**`
 * 强调，星号就原样漏在屏幕上（「偶尔出现两个星号包裹强调内容」）。
 *
 * 三条保守规则，宁可少处理也不能改坏正文：
 * 1. 只吃掉**成对**的 `**` / `__`，且配对两侧不能是数字或字母——`2**3` 是幂运算，
 *    不是加粗，去掉星号会把算式改错；
 * 2. 流式输出时 `**` 会被切在两个增量之间：不闭合一律原样保留，等闭合了再处理，
 *    否则会出现「星号闪一下又消失」；
 * 3. 只处理行首的列表/标题记号，行内的 `-` `#` 不动（中文破折号、编号常用）。
 */
object MarkdownPlain {
    /** 粗体记号：两个字符一组，两两配对。 */
    private val BOLD = listOf("**", "__")

    /** 行首列表记号：`- ` / `* ` / `+ ` 换成中圆点，避免被读成负号。 */
    private val LIST_PREFIX = listOf("- ", "* ", "+ ")

    /** 归一化一段可能带 Markdown 记号的正文。 */
    fun normalize(raw: String): String {
        if (raw.isEmpty() || !needsWork(raw)) return raw
        return raw.lineSequence().joinToString("\n") { normalizeLine(it) }.let(::stripBold)
    }

    /** 快速判定：一段正文里有没有值得处理的记号（绝大多数正文没有，零开销返回原串）。 */
    private fun needsWork(raw: String): Boolean =
        raw.contains("**") ||
            raw.contains("__") ||
            raw.contains('#') ||
            raw.contains("- ") ||
            raw.contains("* ") ||
            raw.contains("+ ")

    /** 行首记号：`### 标题` → `标题`，`- 条目` → `· 条目`。 */
    private fun normalizeLine(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#")) {
            val rest = trimmed.dropWhile { it == '#' }
            return if (rest.isEmpty()) "" else line.take(line.length - trimmed.length) + rest.trimStart()
        }
        LIST_PREFIX.firstOrNull { trimmed.startsWith(it) }?.let { prefix ->
            return line.take(line.length - trimmed.length) + "· " + trimmed.removePrefix(prefix)
        }
        return line
    }

    /** 去掉成对的粗体记号；不配对的一律原样保留。 */
    private fun stripBold(text: String): String {
        if (!text.contains("**") && !text.contains("__")) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val marker = BOLD.firstOrNull { text.startsWith(it, index) }
            if (marker == null) {
                out.append(text[index])
                index++
                continue
            }
            val closeAt = findClosing(text, index, marker)
            if (closeAt < 0) {
                // 没有闭合（流式输出中途）：原样保留这两个字符，等下一个增量再说
                out.append(marker)
                index += marker.length
                continue
            }
            out.append(text, index + marker.length, closeAt)
            index = closeAt + marker.length
        }
        return out.toString()
    }

    /**
     * 找配对记号的位置。
     *
     * 返回 -1 表示「这不是一对加粗」：可能没闭合，也可能两侧紧贴字母数字（`2**3`）。
     */
    private fun findClosing(
        text: String,
        openAt: Int,
        marker: String,
    ): Int {
        // 只看 ASCII 字母数字：`2**3` 是幂运算（前后是数字，不是加粗），
        // 而中文紧邻记号是常态（`**关键**是矢量`），不能因为它是字母就放弃处理。
        val before = text.getOrNull(openAt - 1)
        if (before != null && isAsciiAlnum(before)) return -1
        val innerStart = openAt + marker.length
        val closeAt = text.indexOf(marker, innerStart)
        if (closeAt < 0) return -1
        // 空内容 `****` 不是加粗
        if (closeAt == innerStart) return -1
        val after = text.getOrNull(closeAt + marker.length)
        if (after != null && isAsciiAlnum(after)) return -1
        // 内容里还有一层记号时不处理，交给下一轮（避免吃掉别人的配对）
        if (text.substring(innerStart, closeAt).contains(marker)) return -1
        return closeAt
    }

    /** 是否是 ASCII 字母或数字（数学记号用；中文不算）。 */
    private fun isAsciiAlnum(ch: Char): Boolean = ch.code < NON_ASCII_LIMIT && ch.isLetterOrDigit()

    /** ASCII 上界。 */
    private const val NON_ASCII_LIMIT: Int = 128
}
