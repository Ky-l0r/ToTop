package com.totop.app.ui.components

/**
 * 纯文本里的数学记号解析（纯 Kotlin，无 Android 依赖，可 JVM 单测穷举）。
 *
 * 为什么需要它：
 * 模型返回的解答步骤是**中文自然语言里夹着朴素数学记号**，例如
 * 「令 2^x+x-5=x，化简得 2^x=5」「g(x^*)=x^*」。这些内容没有 LaTeX 定界符，
 * [FormulaParser] 认不出来，界面只能把它们原样当文字显示——
 * 学生看到的就是 `2^x` 与 `x^2` 这种「半个符号」的写法，与课本排版完全不同。
 *
 * 本解析器只做一件事：把 `^`（以及中文输入法常见的 U+2227）之后的内容识别为上标、
 * `_` 之后识别为下标，交给界面用 BaselineShift 真正排成上标/下标，不引入任何外部渲染依赖。
 *
 * 刻意保守（宁可不动，也不能把正文改坏）：
 * 1. 引导符后面为空、或是空白时，原样保留该字符；
 * 2. `x^2` 只吃一个字符；`x^{n+1}` / `x^(n+1)` 吃括号内的全部内容；
 * 3. 转义写法 `\\^` 视为字面量 `^`；
 * 4. 已带 $ 定界符的文本不在这里处理（那份交给 [FormulaParser] 走真 KaTeX）；
 * 5. 花括号不配对时退回「只吃一个字符」，绝不吞掉后文。
 */
object MathMarkup {
    /** 一个排版片段。 */
    sealed class Segment {
        /** 正常基线的文字。 */
        data class Text(val value: String) : Segment()

        /** 上标。 */
        data class Sup(val value: String) : Segment()

        /** 下标。 */
        data class Sub(val value: String) : Segment()
    }

    /** 上标引导符：ASCII 的 ^ 与中文输入法常见的 U+2227。 */
    private val SUPERSCRIPTS = charArrayOf('^', '\u2227')

    /** 下标引导符。 */
    private const val SUBSCRIPT = '_'

    /** 美元符号：出现即说明这段文本是给 FormulaParser 的 LaTeX，本解析器不介入。 */
    private const val LATEX_MARKER = '$'

    /**
     * 解析为片段列表。
     *
     * 返回的片段拼起来（忽略上下标语义）与原文完全一致，不会丢字符。
     */
    fun parse(raw: String): List<Segment> {
        if (raw.isEmpty()) return listOf(Segment.Text(""))
        val out = ArrayList<Segment>()
        val plain = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            // 转义：反斜杠 + 引导符 当作字面字符
            if (isEscapedMarker(raw, i)) {
                plain.append(raw[i + 1])
                i += 2
                continue
            }
            val kind =
                when {
                    ch == LATEX_MARKER -> return listOf(Segment.Text(raw))
                    isSupMarker(ch) -> Kind.SUP
                    ch == SUBSCRIPT -> Kind.SUB
                    else -> null
                }
            if (kind == null) {
                plain.append(ch)
                i++
                continue
            }
            val consumed = consumeScript(raw, i + 1)
            if (consumed == null) {
                // 引导符后面什么都没有：当作普通字符
                plain.append(ch)
                i++
                continue
            }
            if (plain.isNotEmpty()) {
                out.add(Segment.Text(plain.toString()))
                plain.setLength(0)
            }
            out.add(if (kind == Kind.SUP) Segment.Sup(consumed.text) else Segment.Sub(consumed.text))
            i = consumed.endExclusive
        }
        if (plain.isNotEmpty()) out.add(Segment.Text(plain.toString()))
        return out
    }

    /** 是否含有可排版的上标/下标记号。 */
    fun hasScript(raw: String): Boolean = parse(raw).any { it is Segment.Sup || it is Segment.Sub }

    /** 去掉记号、还原成可朗读的纯文本（上下标内容按普通字符接在后面）。 */
    fun toPlain(raw: String): String =
        parse(raw).joinToString("") { segment ->
            when (segment) {
                is Segment.Text -> segment.value
                is Segment.Sup -> segment.value
                is Segment.Sub -> segment.value
            }
        }

    private enum class Kind { SUP, SUB }

    private class Consumed(val text: String, val endExclusive: Int)

    private fun isSupMarker(ch: Char): Boolean = SUPERSCRIPTS.contains(ch)

    /** 位置 [index] 是否为「反斜杠 + 上下标引导符」的转义写法。 */
    private fun isEscapedMarker(
        raw: String,
        index: Int,
    ): Boolean {
        if (raw[index] != '\\') return false
        if (index + 1 >= raw.length) return false
        val next = raw[index + 1]
        return isSupMarker(next) || next == SUBSCRIPT
    }

    /**
     * 从 [start] 开始吃一段上下标内容。
     *
     * @return null 表示没有可吃的内容（调用方应把引导符当普通字符）
     */
    private fun consumeScript(
        raw: String,
        start: Int,
    ): Consumed? {
        if (start >= raw.length) return null
        val first = raw[start]
        if (first.isWhitespace()) return null
        // x^{n+1} / x_(n+1)：配对括号内的全部内容
        val close =
            when (first) {
                '{' -> '}'
                '(' -> ')'
                else -> null
            }
        if (close != null) {
            val closeAt = raw.indexOf(close, start + 1)
            if (closeAt > start + 1) {
                return Consumed(raw.substring(start + 1, closeAt), closeAt + 1)
            }
            // 括号没闭合：退回单字符，绝不吃到文末
            return Consumed(first.toString(), start + 1)
        }
        // x^2 / 2^x：只吃一个字符
        return Consumed(first.toString(), start + 1)
    }
}
