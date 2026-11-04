package com.totop.app.ui.reviewmaterial

import com.totop.app.ui.components.LatexPlain

/**
 * 复习资料正文里的「轻数学记号」解析（纯 Kotlin，无 Android 依赖，可 JVM 单测穷举）。
 *
 * 为什么需要它：
 * 复习资料是中文长文，物理公式只能写成 `v_top`、`1/2`、`√(2gh)` 这类平文本
 * （提示词明确禁止整段 LaTeX）。这些写法交给 [androidx.compose.material3.Text] 原样输出时，
 * 学生看到的是「v 下划线 top」「二分之一写成 1/2」「根号只剩一个符号、没有覆盖被开方内容的横线」，
 * 与课本排版差得太远。本对象把它们拆成结构化片段，界面与 PDF 各自排版：
 *
 * | 原文 | 片段 |
 * | --- | --- |
 * | `v_{0}` / `v_top` / `R_1` | `Text("v") + Sub("0"/"top"/"1")` |
 * | `10^{23}` / `m^3` / `x^(n+1)` | `Sup(...)` |
 * | `1/2`（两侧都是纯数字） | `Fraction("1", "2")` |
 * | `√(2gh)` / `√3` / `\sqrt{x}` | `Radical(...)` |
 * | `\frac{s}{t}`（模型偶尔漏出的 LaTeX） | `Fraction(...)` |
 *
 * 刻意保守的三条边界（宁可少排，也不能把正文改坏）：
 * 1. **单位不堆叠**：`m/s`、`km/h`、`J/(kg·℃)` 两侧不是纯数字，保持斜杠平排；
 *    字母分式要上下结构，得让模型写 `\frac{s}{t}`（提示词里已要求）。
 * 2. **日期与连比不拆**：`2024/11/05`、`1/2/3` 里分母后面还跟着 `/`，整段不动。
 * 3. **不吞内容**：`flatten` 后的文字重新解析必须得到同一份片段（单测保证）——
 *    解析会吃掉 `{}` 这类分组记号，逐字相等做不到，但内容一个字都不能少。
 *    没写括号的根号是唯一会「多出字符」的情形：`√3` 压平后是 `√(3)`，
 *    补括号是为了让降级文字在任何字体下都不会被读成别的意思。
 *
 * 嵌套：分式与根号内部允许再出现上下标，但只嵌一层（`\frac{v^{2}}{2g}` 可以，
 * 分式里再套分式就按原文降级），因为中学物理里没有这种写法，而多层嵌套在 PDF 里
 * 会把行高顶到不可控。
 */
object ReviewMath {
    /** 一个排版片段。 */
    sealed class Token {
        /** 正常基线的文字。 */
        data class Text(val value: String) : Token()

        /** 上标。 */
        data class Sup(val value: String) : Token()

        /** 下标。 */
        data class Sub(val value: String) : Token()

        /** 分式：分子在上、分母在下，中间一条横线。 */
        data class Fraction(
            val numerator: List<Token>,
            val denominator: List<Token>,
        ) : Token()

        /** 根号：符号 + 一条覆盖被开方内容的横线。 */
        data class Radical(val radicand: List<Token>) : Token()
    }

    /** 分式与根号最多嵌一层；再深就按原文输出。 */
    private const val MAX_DEPTH: Int = 1

    /** 根号符号（U+221A），与 `√` 同一个字符。 */
    private const val RADICAL_SIGN: Char = '\u221A'

    /**
     * 上箭头（U+2191）。
     *
     * 模型用它表示指数（`2↑(n-1)`），而中文正文里它表示「上升」（`温度↑`）。
     * 两者混在同一个字符上，所以只在 [isExponentArrow] 认定的位置按指数处理。
     */
    private const val ARROW_UP: Char = '\u2191'

    /** 分式两侧最多几位数字：再长的不像分式，更像编号或日期片段。 */
    private const val MAX_FRACTION_DIGITS: Int = 6

    private const val SUBSCRIPT_MARKER: Char = '_'

    /** 上标引导符：ASCII 的 `^` 与中文输入法常见的 U+2227。 */
    private val SUPERSCRIPT_MARKERS: CharArray = charArrayOf('^', '\u2227')

    /** 上下标类型。 */
    private enum class Script { SUP, SUB }

    /** 解析一行文本。 */
    fun parse(raw: String): List<Token> {
        if (raw.isEmpty()) return emptyList()
        return parseInto(raw, depth = 0)
    }

    /** 是否含有需要特殊排版的记号（纯文字走快速路径，不建 AnnotatedString 的额外结构）。 */
    fun hasMath(raw: String): Boolean = parse(raw).any { it !is Token.Text }

    /** 片段压平成纯文本（降级显示、朗读、复制用）。 */
    fun flatten(tokens: List<Token>): String = buildString { tokens.forEach { appendFlat(this, it) } }

    /**
     * 片段压平成「适合纸面的一行文字」：上下标尽量换成 Unicode 上下标字符。
     *
     * PDF 里的分式是「分子一行、分母一行」的独立块，块内再排 BaselineShift 需要另一套度量，
     * 收益远小于复杂度。`\frac{v^{2}}{2g}` 的分子因此写成 `v²` 而不是 `v2`——
     * 能映射就映射，映射不了的原样拼上，至少不丢字。
     */
    fun flattenScripted(tokens: List<Token>): String = buildString { tokens.forEach { appendScripted(this, it) } }

    /**
     * 片段压平成纯文本（降级显示、朗读、复制用）。
     *
     * 上下标把引导符一并写回（`v_{0}` 压平成 `v_0`）：这个函数的用途是「原文长什么样，
     * 降级后还能认出来」，丢掉 `_` 会得到 `v0`，朗读出来是另一个东西。
     */
    private fun appendFlat(
        out: StringBuilder,
        token: Token,
    ) {
        when (token) {
            is Token.Text -> out.append(token.value)
            is Token.Sup -> out.append('^').append(token.value)
            is Token.Sub -> out.append('_').append(token.value)
            is Token.Fraction -> out.append(flatten(token.numerator)).append('/').append(flatten(token.denominator))
            is Token.Radical -> out.append(RADICAL_SIGN).append('(').append(flatten(token.radicand)).append(')')
        }
    }

    private fun appendScripted(
        out: StringBuilder,
        token: Token,
    ) {
        when (token) {
            is Token.Text -> out.append(token.value)
            is Token.Sup -> out.append(mapScript(token.value, SUPERSCRIPT_CHARS))
            is Token.Sub -> out.append(mapScript(token.value, SUBSCRIPT_CHARS))
            is Token.Fraction ->
                out.append(flattenScripted(token.numerator))
                    .append('/')
                    .append(flattenScripted(token.denominator))
            is Token.Radical ->
                out.append(RADICAL_SIGN)
                    .append('(')
                    .append(flattenScripted(token.radicand))
                    .append(')')
        }
    }

    private fun mapScript(
        value: String,
        table: Map<Char, Char>,
    ): String = buildString(value.length) { value.forEach { ch -> append(table[ch] ?: ch) } }

    /** 分批扫描：认得出的记号切成片段，认不出的原样进文字缓冲。 */
    private fun parseInto(
        raw: String,
        depth: Int,
    ): List<Token> {
        val out = ArrayList<Token>(4)
        val plain = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            val consumed =
                when {
                    ch == '\\' -> consumeCommand(raw, index, depth)
                    ch == RADICAL_SIGN -> consumeRadical(raw, index, depth)
                    // 上箭头只在「底数 + 箭头 + 指数」时才算指数，见 isExponentArrow
                    ch == ARROW_UP ->
                        if (isExponentArrow(raw, index)) consumeScript(raw, index, Script.SUP) else null
                    isSuperscriptMarker(ch) -> consumeScript(raw, index, Script.SUP)
                    ch == SUBSCRIPT_MARKER -> consumeScript(raw, index, Script.SUB)
                    ch.isDigit() -> consumeNumericFraction(raw, index, depth)
                    ch == '(' -> consumeParenthesizedFraction(raw, index, depth)
                    else -> null
                }
            if (consumed == null) {
                plain.append(ch)
                index++
                continue
            }
            flush(out, plain)
            out.add(consumed.token)
            index = consumed.endExclusive
        }
        flush(out, plain)
        return out
    }

    /** 把文字缓冲落成片段；顺手归一化模型漏出的裸 LaTeX 命令（`\cdot` -> `·`）。 */
    private fun flush(
        out: MutableList<Token>,
        plain: StringBuilder,
    ) {
        if (plain.isEmpty()) return
        out.add(Token.Text(LatexPlain.normalize(plain.toString())))
        plain.setLength(0)
    }

    /** 一段被消费的原文与它对应的片段。 */
    private class Consumed(
        val token: Token,
        val endExclusive: Int,
    )

    /** `\frac{a}{b}`、`\sqrt{x}`、`\sqrt[n]{x}`：只认这三个，其余交给 [LatexPlain]。 */
    private fun consumeCommand(
        raw: String,
        index: Int,
        depth: Int,
    ): Consumed? {
        if (index + 1 >= raw.length) return null
        if (!raw[index + 1].isLetter()) return null
        var end = index + 1
        while (end < raw.length && raw[end].isLetter()) end++
        val name = raw.substring(index + 1, end)
        if (depth >= MAX_DEPTH) return null
        return when (name) {
            "frac", "dfrac", "tfrac" -> {
                val numerator = groupAt(raw, end, '{', '}') ?: return null
                val denominator = groupAt(raw, numerator.endExclusive, '{', '}') ?: return null
                Consumed(
                    Token.Fraction(
                        parseInto(numerator.inner, depth + 1),
                        parseInto(denominator.inner, depth + 1),
                    ),
                    denominator.endExclusive,
                )
            }

            "sqrt" -> {
                // 可选的开方次数 \sqrt[3]{x}：次数不排，跳过它
                val afterDegree =
                    if (end < raw.length && raw[end] == '[') {
                        (groupAt(raw, end, '[', ']') ?: return null).endExclusive
                    } else {
                        end
                    }
                val body = groupAt(raw, afterDegree, '{', '}') ?: return null
                Consumed(Token.Radical(parseInto(body.inner, depth + 1)), body.endExclusive)
            }

            else -> null
        }
    }

    /** `√(2gh)`、`√{2gh}`、`√3`、`√2gh`。 */
    private fun consumeRadical(
        raw: String,
        index: Int,
        depth: Int,
    ): Consumed? {
        if (depth >= MAX_DEPTH) return null
        val start = index + 1
        if (start >= raw.length) return null
        val opener = raw[start]
        if (opener == '{' || opener == '(') {
            val closer = if (opener == '{') '}' else ')'
            val group = groupAt(raw, start, opener, closer) ?: return null
            if (group.inner.isEmpty()) return null
            return Consumed(Token.Radical(parseInto(group.inner, depth + 1)), group.endExclusive)
        }
        // 没有括号：吃到下一个不像「被开方内容」的字符为止（√2gh 整段都在根号里）
        var end = start
        while (end < raw.length && isRadicandChar(raw[end])) end++
        if (end == start) return null
        return Consumed(Token.Radical(parseInto(raw.substring(start, end), depth + 1)), end)
    }

    /** `_0`、`_top`、`_{max}`、`^2`、`^{-3}`、`^(n+1)`。 */
    private fun consumeScript(
        raw: String,
        index: Int,
        kind: Script,
    ): Consumed? {
        val start = index + 1
        if (start >= raw.length) return null
        val first = raw[start]
        if (first.isWhitespace()) return null
        if (first == '{' || first == '(') {
            val closer = if (first == '{') '}' else ')'
            val group = groupAt(raw, start, first, closer) ?: return null
            if (group.inner.isEmpty()) return null
            return Consumed(tokenOf(kind, group.inner), group.endExclusive)
        }
        if (first == '-' || first == '+') {
            var end = start + 1
            while (end < raw.length && raw[end].isDigit()) end++
            if (end == start + 1) return null
            return Consumed(tokenOf(kind, raw.substring(start, end)), end)
        }
        if (first.isDigit()) {
            var end = start
            while (end < raw.length && raw[end].isDigit()) end++
            return Consumed(tokenOf(kind, raw.substring(start, end)), end)
        }
        if (isAsciiLetter(first)) {
            var end = start
            while (end < raw.length && (isAsciiLetter(raw[end]) || raw[end].isDigit())) end++
            return Consumed(tokenOf(kind, raw.substring(start, end)), end)
        }
        // 中文不做下标：`v_最大` 这类写法按原文保留，避免把正文吃进下标
        return null
    }

    private fun tokenOf(
        kind: Script,
        value: String,
    ): Token = if (kind == Script.SUP) Token.Sup(value) else Token.Sub(value)

    /**
     * 上箭头在这个位置是否是「指数」。
     *
     * 判据只有两条，且都必须成立：底数紧贴在箭头左边（数字、字母或右括号），
     * 箭头右边紧跟内容。这样「2↑(n-1)」「a↑2」会排成上标，
     * 而「温度↑，压强↓」「产量↑」这类叙述仍然是箭头本身。
     */
    private fun isExponentArrow(
        raw: String,
        index: Int,
    ): Boolean {
        if (index <= 0 || index + 1 >= raw.length) return false
        val base = raw[index - 1]
        val baseOk = base.isDigit() || isAsciiLetter(base) || base == ')' || base == '）' || base == '}'
        if (!baseOk) return false
        return !raw[index + 1].isWhitespace()
    }

    /**
     * `(x+1)/(x-1)` 这类两侧带括号的斜杠写法。
     *
     * 只认「括号组 + 斜杠 + 括号组」：`m/s`、`km/h` 这类单位两侧没有括号，
     * 仍然按平排处理——把单位排成上下两行是实打实的错版。
     */
    private fun consumeParenthesizedFraction(
        raw: String,
        index: Int,
        depth: Int,
    ): Consumed? {
        if (depth >= MAX_DEPTH) return null
        val left = groupAt(raw, index, '(', ')') ?: return null
        if (left.endExclusive >= raw.length || raw[left.endExclusive] != '/') return null
        val right = groupAt(raw, left.endExclusive + 1, '(', ')') ?: return null
        if (left.inner.isBlank() || right.inner.isBlank()) return null
        // 后面还跟着斜杠：日期或连比，整段不动
        if (right.endExclusive < raw.length && raw[right.endExclusive] == '/') return null
        return Consumed(
            Token.Fraction(
                numerator = parseInto(left.inner, depth + 1),
                denominator = parseInto(right.inner, depth + 1),
            ),
            right.endExclusive,
        )
    }

    /** 两侧都是纯数字的 `a/b` 才算分式；单位缩写与日期一律不动。 */
    private fun consumeNumericFraction(
        raw: String,
        index: Int,
        depth: Int,
    ): Consumed? {
        if (depth >= MAX_DEPTH) return null
        // 前面紧贴字母/数字/斜杠：说明这是 m/s、x1/2 这类写法的一部分，不是独立分式
        if (index > 0 && isFractionPrefix(raw[index - 1])) return null
        var slashAt = index
        while (slashAt < raw.length && raw[slashAt].isDigit()) slashAt++
        if (slashAt - index > MAX_FRACTION_DIGITS) return null
        if (slashAt >= raw.length || raw[slashAt] != '/') return null
        val denominatorStart = slashAt + 1
        if (denominatorStart >= raw.length || !raw[denominatorStart].isDigit()) return null
        var end = denominatorStart
        while (end < raw.length && raw[end].isDigit()) end++
        if (end - denominatorStart > MAX_FRACTION_DIGITS) return null
        // 分母后面还跟着斜杠：日期 2024/11/05、连比 1/2/3，整段不动
        if (end < raw.length && raw[end] == '/') return null
        return Consumed(
            Token.Fraction(
                numerator = listOf(Token.Text(raw.substring(index, slashAt))),
                denominator = listOf(Token.Text(raw.substring(denominatorStart, end))),
            ),
            end,
        )
    }

    /** 分式前面不允许出现的字符：贴上它们就说明这是单位或更长表达式的一部分。 */
    private fun isFractionPrefix(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '/' || ch == '.' ||
            ch == SUBSCRIPT_MARKER || isSuperscriptMarker(ch)

    /** 读一对配对的括号，返回内部原文与结束位置。 */
    private fun groupAt(
        raw: String,
        start: Int,
        open: Char,
        close: Char,
    ): Group? {
        if (start >= raw.length || raw[start] != open) return null
        var depth = 0
        var index = start
        while (index < raw.length) {
            when (raw[index]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return Group(raw.substring(start + 1, index), index + 1)
                }
            }
            index++
        }
        return null
    }

    /** 一个括号组的内容与结束位置。 */
    private class Group(
        val inner: String,
        val endExclusive: Int,
    )

    private fun isSuperscriptMarker(ch: Char): Boolean = SUPERSCRIPT_MARKERS.contains(ch)

    private fun isAsciiLetter(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'

    /** 根号后面可以连续吃进来的字符：字母、数字、小数点与居中点。 */
    private fun isRadicandChar(ch: Char): Boolean = isAsciiLetter(ch) || ch.isDigit() || ch == '.' || ch == '·'

    /** 上标字符表：只收数字、正负号与 n（中学物理只用到这些）。 */
    private val SUPERSCRIPT_CHARS: Map<Char, Char> =
        mapOf(
            '0' to '\u2070',
            '1' to '\u00B9',
            '2' to '\u00B2',
            '3' to '\u00B3',
            '4' to '\u2074',
            '5' to '\u2075',
            '6' to '\u2076',
            '7' to '\u2077',
            '8' to '\u2078',
            '9' to '\u2079',
            '-' to '\u207B',
            '+' to '\u207A',
            'n' to '\u207F',
            '(' to '\u207D',
            ')' to '\u207E',
        )

    /** 下标字符表。 */
    private val SUBSCRIPT_CHARS: Map<Char, Char> =
        mapOf(
            '0' to '\u2080',
            '1' to '\u2081',
            '2' to '\u2082',
            '3' to '\u2083',
            '4' to '\u2084',
            '5' to '\u2085',
            '6' to '\u2086',
            '7' to '\u2087',
            '8' to '\u2088',
            '9' to '\u2089',
            '-' to '\u208B',
            '+' to '\u208A',
        )
}
