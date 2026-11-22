package com.totop.app.ui.components

/**
 * 化学方程式的 `\ce{...}` 展开（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 为什么需要它：
 * 模型写化学方程式时常用 mhchem 的 `\ce{}` 语法，而项目里**没有** mhchem 扩展
 * （`assets/katex/` 只有自研兜底渲染器，官方 KaTeX 尚未补齐见该目录 README）。
 * 结果 `\ce{H2SO4}` 走不到渲染器，只能降级成 `H2SO4` 这样的纯文本，
 * 下标、箭头、电荷全丢——学生看到的是「H2SO4」，不是化学式。
 *
 * 本对象把 `\ce{...}` 改写成**兜底渲染器已经支持**的 LaTeX 子集：
 * 只用 `\mathrm{}` `\text{}` `\cdot` `_{}` `^{}` 与字面字符（括号、加减号、箭头、箭头符号），
 * 不引入 `\left` `\right` `\xrightarrow` 这类需要 KaTeX 才能解析的命令。
 * 这样即使将来补齐 KaTeX，同一份输出也仍然可渲染。
 *
 * 支持的范围（覆盖中学常见写法）：
 * 1. 元素与下标：`H2SO4` -> `\mathrm{H}_{2}\mathrm{S}\mathrm{O}_{4}`；
 * 2. 括号与括号外下标：`Ca(OH)2`、`(NH4)2SO4`；
 * 3. 结晶水：`CuSO4·5H2O`；
 * 4. 电荷：`SO4^2-`、`Fe^3+`、`SO4^{2-}`（只认显式 `^`，不猜 `Fe3+` 这种歧义写法）；
 * 5. 状态符号：`(s)` `(l)` `(g)` `(aq)`；
 * 6. 箭头与条件：`->`、`<=>`、`<->`、`->[点燃]`（条件排成箭头后的文字，不做上下叠排）；
 * 7. 沉淀与气体：`↓` `↑` 原样保留；
 * 8. 反应物之间的 `+` 两侧加空格；
 * 9. 中文（例如条件里的「点燃」）包成 `\text{}`。
 *
 * 保守原则：遇到反斜杠命令、括号不配对、无法判定含义的写法，**整条 `\ce{}` 原样保留**，
 * 由上层继续走「降级为纯文本」的老路径。宁可显示源码，也不要把化学式改错。
 */
object ChemistryLatex {
    private const val CE_COMMAND = "\\ce"

    /** 状态符号：括号内的缩写不当下标组处理。 */
    private val STATES = setOf("s", "l", "g", "aq")

    /** 电荷区允许出现的字符（`^` 后面、不带花括号时）。 */
    private fun isChargeChar(ch: Char): Boolean = ch.isDigit() || ch == '+' || ch == '-'

    /**
     * 展开一段公式里的所有 `\ce{...}`。
     *
     * 不含 `\ce` 时原样返回（绝大多数公式走这条路径，零开销）。
     */
    fun expand(raw: String): String {
        if (!raw.contains(CE_COMMAND)) return raw
        val out = StringBuilder(raw.length + EXPAND_HEADROOM)
        var index = 0
        while (index < raw.length) {
            val at = raw.indexOf(CE_COMMAND, index)
            if (at < 0) {
                out.append(raw, index, raw.length)
                break
            }
            out.append(raw, index, at)
            val open = skipSpaces(raw, at + CE_COMMAND.length)
            if (open >= raw.length || raw[open] != '{') {
                // 不是 \ce{...}（例如正文里恰好出现这三个字符）：原样吐出，继续往后找
                out.append(CE_COMMAND)
                index = at + CE_COMMAND.length
                continue
            }
            val close = matchingBrace(raw, open)
            if (close < 0) {
                // 花括号不配对：剩下的原文一律保留，不冒险改写
                out.append(raw, at, raw.length)
                break
            }
            val body = expandBody(raw.substring(open + 1, close))
            if (body == null) {
                out.append(raw, at, close + 1)
            } else {
                out.append(body)
            }
            index = close + 1
        }
        return out.toString()
    }

    /**
     * 展开 `\ce{...}` 花括号里的内容。
     *
     * @return null 表示这段写法超出支持范围，调用方应保留原文
     */
    private fun expandBody(body: String): String? {
        val out = StringBuilder(body.length * 2)
        var index = 0
        while (index < body.length) {
            val next =
                when {
                    body[index].isWhitespace() -> {
                        // 只在需要时补一个空格：符号与箭头两侧已经留过空，再补会变成两格宽
                        if (out.isEmpty() || out[out.length - 1] != ' ') out.append(' ')
                        skipSpaces(body, index)
                    }
                    body[index] == '\\' -> return null
                    body[index].isCjk() -> appendCjk(body, index, out)
                    body[index] == '+' -> appendSign(out, "+", index + 1)
                    body[index] == '=' -> appendSign(out, "=", index + 1)
                    body[index] == '·' || body[index] == '*' -> appendSign(out, "\\cdot", index + 1)
                    body[index] == '↑' || body[index] == '↓' -> appendLiteral(out, body[index].toString(), index + 1)
                    body.startsWith("<=>", index) -> appendSign(out, ARROW_REVERSIBLE, index + 3)
                    body.startsWith("<->", index) -> appendSign(out, ARROW_REVERSIBLE, index + 3)
                    body.startsWith("-->", index) -> appendArrow(body, index + 3, out)
                    body.startsWith("->", index) -> appendArrow(body, index + 2, out)
                    body[index] == '(' -> appendOpenParen(body, index, out)
                    body[index] == ')' -> appendCloseParen(body, index, out)
                    body[index] == '^' -> appendSuperscript(body, index, out)
                    body[index] == '_' -> appendSubscript(body, index, out)
                    body[index].isUpperCase() -> appendElement(body, index, out)
                    body[index].isDigit() -> appendLiteral(out, body[index].toString(), index + 1)
                    else -> appendLiteral(out, "\\mathrm{" + body[index] + "}", index)
                }
            // 解析没有推进就放弃展开：这类分支一旦写漏 nextIndex 会死循环，
            // 在单测里表现为 OOM，在真机上表现为卡死。宁可退回原文。
            if (next <= index) return null
            index = next
        }
        return out.toString()
    }

    /** 中文片段：包成 `\text{}`，不参与化学语义解析。 */
    private fun appendCjk(
        body: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var end = start
        while (end < body.length && body[end].isCjk()) end++
        out.append("\\text{").append(body, start, end).append('}')
        return end
    }

    /**
     * 元素符号：大写字母 + 紧随的小写字母，包成 `\mathrm{}`；
     * 之后的下标、电荷一并处理。
     */
    private fun appendElement(
        body: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var end = start + 1
        while (end < body.length && body[end].isLowerCase()) end++
        out.append("\\mathrm{").append(body, start, end).append('}')
        var index = end
        index = appendBareDigits(body, index, out)
        if (index < body.length && body[index] == '^') {
            index = appendSuperscript(body, index, out)
        }
        return index
    }

    /** 左括号：先判状态符号，再当普通分组开括号。 */
    private fun appendOpenParen(
        body: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        val state = matchState(body, start)
        if (state != null) {
            out.append("\\text{(").append(state).append(")}")
            return start + state.length + 2
        }
        out.append('(')
        return start + 1
    }

    /** 右括号：闭合后紧跟的数字是下标（`Ca(OH)2`）。 */
    private fun appendCloseParen(
        body: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(')')
        return appendBareDigits(body, start + 1, out)
    }

    /** 元素或右括号之后裸写的阿拉伯数字，一律视为下标。 */
    private fun appendBareDigits(
        body: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var end = start
        while (end < body.length && body[end].isDigit()) end++
        if (end == start) return start
        out.append("_{").append(body, start, end).append('}')
        return end
    }

    /** 下划线：`_2` 或 `_{2}`。 */
    private fun appendSubscript(
        body: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        val script = readScript(body, start + 1) ?: return start + 1
        out.append("_{").append(script.text).append('}')
        return script.endExclusive
    }

    /** 尖号：`^2-` 或 `^{2-}`。 */
    private fun appendSuperscript(
        body: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        val script = readScript(body, start + 1) ?: return start + 1
        out.append("^{").append(script.text).append('}')
        return script.endExclusive
    }

    /** 读上下标的内容：优先花括号，否则读到不可能是内容的字符为止。 */
    private fun readScript(
        body: String,
        start: Int,
    ): Script? {
        if (start >= body.length) return null
        if (body[start] == '{') {
            val close = matchingBrace(body, start)
            if (close < 0) return null
            return Script(body.substring(start + 1, close), close + 1)
        }
        var end = start
        while (end < body.length && isChargeChar(body[end])) end++
        if (end == start) return null
        return Script(body.substring(start, end), end)
    }

    /**
     * 箭头：可选的 `[条件]` 排成箭头后的文字。
     *
     * 兜底渲染器不支持 `\xrightarrow` 这种上下叠排，所以条件只能跟在箭头后面；
     * 这一取舍写在 `docs/偏移记录` 的同类条目里，宁可「→ 点燃」也不要露出源码。
     */
    private fun appendArrow(
        body: String,
        afterArrow: Int,
        out: StringBuilder,
    ): Int {
        appendSign(out, ARROW_RIGHT, afterArrow)
        var index = skipSpaces(body, afterArrow)
        if (index >= body.length || body[index] != '[') return index
        val close = body.indexOf(']', index + 1)
        if (close < 0) return index
        // 常见写法是 ->[\text{点燃}]：先把内容类命令剥掉，否则会排成 \text{\text{点燃}}
        val condition = LatexPlain.normalize(body.substring(index + 1, close)).trim()
        if (condition.isNotEmpty()) {
            // 箭头已经以空格结尾，条件直接接上，否则会出现「→  点燃」两格宽
            out.append("\\text{").append(condition).append('}')
        }
        return close + 1
    }

    /** 匹配 `(...)` 形式的状态符号，返回缩写本身。 */
    private fun matchState(
        body: String,
        start: Int,
    ): String? {
        if (start + 2 >= body.length || body[start] != '(') return null
        var end = start + 1
        while (end < body.length && body[end].isLowerCase()) end++
        if (end >= body.length || body[end] != ')') return null
        val name = body.substring(start + 1, end)
        return if (name in STATES && body[end] == ')') name else null
    }

    /** 花括号配对：返回右括号下标，找不到返回 -1。 */
    private fun matchingBrace(
        raw: String,
        open: Int,
    ): Int {
        var depth = 0
        var index = open
        while (index < raw.length) {
            when (raw[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun skipSpaces(
        raw: String,
        from: Int,
    ): Int {
        var index = from
        while (index < raw.length && raw[index].isWhitespace()) index++
        return index
    }

    private fun appendLiteral(
        out: StringBuilder,
        text: String,
        nextIndex: Int,
    ): Int {
        out.append(text)
        return nextIndex
    }

    /**
     * 追加一个「两侧留空」的符号（加减号、等号、点号、箭头）。
     *
     * 先吃掉已经写在 out 末尾的空格，否则源码里 `O2 + H2` 这种本来就带空格的写法
     * 会渲染成两个空格宽。
     */
    private fun appendSign(
        out: StringBuilder,
        token: String,
        nextIndex: Int,
    ): Int {
        while (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
        out.append(' ').append(token).append(' ')
        return nextIndex
    }

    /** 一个上下标的内容与结束位置。 */
    private class Script(val text: String, val endExclusive: Int)

    /** 向右箭头；用字面字符而不是命令，兜底渲染器与 KaTeX 都能认。 */
    private const val ARROW_RIGHT = "→"

    private const val ARROW_REVERSIBLE = "⇌"

    private const val EXPAND_HEADROOM = 48

    private fun Char.isCjk(): Boolean = this.code in CJK_START..CJK_END

    private const val CJK_START = 0x4E00

    private const val CJK_END = 0x9FFF
}
