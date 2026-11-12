package com.totop.app.ui.components

/**
 * 把「没有 `$` 定界符」的结构性 LaTeX 命令补上定界符（纯 Kotlin，可单测）。
 *
 * 为什么需要它：模型常把公式写成 `\frac{1}{2}` 这种裸片段（没有 `$…$`），
 * [FormulaParser] 认不出来，于是走纯文本分支，[LatexPlain] 只能把它压成 `1/2`。
 * 一行文字里 `1/2` 还勉强能读，但「分子在上、分母在下」的排版信息已经丢了，
 * 学生看到的是 `sinx/2` 这类有歧义的写法。
 *
 * 这里只处理**结构性**命令（分式、根号）：它们必须靠排版才读得懂。
 * 符号类命令（`\cdot`、`\alpha`）继续交给 [LatexPlain] 换成 Unicode，
 * 没必要为它们启动一次 WebView 渲染。
 *
 * 三条保守规则，宁可不处理也不能改坏原文：
 * 1. 已经在 `$…$` / `$$…$$` 里的内容不动（避免套娃）；
 * 2. 花括号不配对、命令不完整时原样保留；
 * 3. 认不出的命令不碰。
 */
object BareLatex {
    /** 需要真实排版（分式上下结构、根号）的命令。 */
    private val STRUCTURAL: Set<String> = setOf("frac", "dfrac", "tfrac", "sqrt")

    /** 各命令需要几个花括号参数。 */
    private fun requiredGroups(command: String): Int = if (command == "sqrt") 1 else 2

    /** 把裸结构化命令包进 `$…$`；没有可处理的内容时原样返回。 */
    fun wrap(raw: String): String {
        if (!raw.contains('\\')) return raw
        val builder = StringBuilder(raw.length + 16)
        var index = 0
        var insideMath = false
        while (index < raw.length) {
            if (raw[index] == '$') {
                insideMath = !insideMath
                builder.append('$')
                index++
                continue
            }
            val consumed = consumeCommand(raw, index, insideMath, builder)
            if (consumed > index) {
                index = consumed
            } else {
                builder.append(raw[index])
                index++
            }
        }
        return builder.toString()
    }

    /**
     * 尝试从 [index] 起消费一条结构化命令。
     *
     * @return 消费到的下一个下标；没有可消费内容时原样返回 [index]（调用方按普通字符处理）
     */
    private fun consumeCommand(
        raw: String,
        index: Int,
        insideMath: Boolean,
        builder: StringBuilder,
    ): Int {
        if (insideMath || raw[index] != '\\') return index
        var nameEnd = index + 1
        while (nameEnd < raw.length && raw[nameEnd].isLetter()) nameEnd++
        val command = raw.substring(index + 1, nameEnd)
        if (command !in STRUCTURAL) return index
        val commandEnd = endOfCommand(raw, nameEnd, requiredGroups(command)) ?: return index
        builder.append('$').append(raw, index, commandEnd).append('$')
        return commandEnd
    }

    /**
     * 找到该命令的结束位置（最后一个参数右括号之后）。
     *
     * @return 结束下标；花括号不配对时返回 null，调用方按「不处理」处理
     */
    private fun endOfCommand(
        raw: String,
        from: Int,
        groups: Int,
    ): Int? {
        var cursor = skipSpaces(raw, from)
        // \sqrt 允许带一个可选的根指数：[3]{8}
        if (cursor < raw.length && raw[cursor] == '[') {
            val close = raw.indexOf(']', cursor)
            if (close < 0) return null
            cursor = skipSpaces(raw, close + 1)
        }
        for (index in 0 until groups) {
            if (cursor >= raw.length) return null
            cursor =
                when {
                    raw[cursor] == '{' -> endOfGroup(raw, cursor) ?: return null
                    // 不带花括号的单字符参数：`\sqrt2`、`\frac12`。KaTeX 同样接受这种写法，
                    // 以前这里直接判「不完整」，整条命令就退化成纯文本 `√(2)`。
                    raw[cursor] == '\\' -> return null
                    // 只认 ASCII 记号：`\frac 面积` 这种其实是正文里误写了命令，
                    // 把中文当参数包进公式会直接渲染失败，不如不动
                    raw[cursor].code >= NON_ASCII_LIMIT -> return null
                    else -> cursor + 1
                }
            // 只在「后面还有参数」时才跳过空格。
            // 最后一组之后若也跳，会把命令后面的普通文字前的空格一起包进公式里，
            // 渲染出来就是「公式 + 多余空白」，与原文的空格重了一次。
            if (index < groups - 1) cursor = skipSpaces(raw, cursor)
        }
        return cursor
    }

    /** 从 `{` 开始找到配对的 `}`，返回其后一位。 */
    private fun endOfGroup(
        raw: String,
        openAt: Int,
    ): Int? {
        var depth = 0
        var cursor = openAt
        while (cursor < raw.length) {
            when (raw[cursor]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return cursor + 1
                }
            }
            cursor++
        }
        return null
    }

    private fun skipSpaces(
        raw: String,
        from: Int,
    ): Int {
        var cursor = from
        while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
        return cursor
    }

    /** ASCII 上界：不带花括号的参数只接受这之前的字符（数字、字母、运算符）。 */
    private const val NON_ASCII_LIMIT: Int = 128
}
