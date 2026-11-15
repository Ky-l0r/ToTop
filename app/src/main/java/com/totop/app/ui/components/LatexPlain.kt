package com.totop.app.ui.components

/**
 * 裸 LaTeX 命令的纯文本归一化（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 为什么需要它：
 * 模型经常把公式写成**没有定界符的 LaTeX 片段**，例如
 * 「化学式可写成 H_2SO_4 \cdot xSO_3」「所以 v=\frac{s}{t}」。
 * 这类文本不含 `$`，[FormulaParser] 认不出来，会走纯文本分支；
 * 而纯文本分支只把 `^`、`_` 排成上下标，`\cdot`、`\frac{a}{b}` 这些命令
 * 就原样留在屏幕上——学生看到的是 `\cdot` 而不是 `·`。
 *
 * 本对象只做**字符级替换**，不改变排版结构：
 * 1. 常见符号命令换成对应 Unicode 字符（`\cdot` → `·`、`\le` → `≤`、`\alpha` → `α`）；
 * 2. `\text{}` `\mathrm{}` `\ce{}` 这类「只取内容」的命令剥掉外壳；
 * 3. `\frac{a}{b}` → `a/b`（分子分母多于一个字符时加括号），`\sqrt{x}` → `√(x)`；
 * 4. `\,` `\;` `\quad` 压成一个空格，`\{` `\%` `\_` 等转义还原成字面字符；
 * 5. 认不出的命令**原样保留**（含反斜杠），宁可显示原文也不能把公式改错。
 *
 * 上下标仍交给 [MathMarkup]：本对象不处理 `^` 与 `_`，两者职责不重叠。
 */
object LatexPlain {
    /** 符号命令表。键不带反斜杠。 */
    private val SYMBOLS: Map<String, String> =
        mapOf(
            // 运算与关系
            "cdot" to "·",
            "times" to "×",
            "div" to "÷",
            "pm" to "±",
            "mp" to "∓",
            "ast" to "∗",
            "star" to "⋆",
            "le" to "≤",
            "leq" to "≤",
            "ge" to "≥",
            "geq" to "≥",
            "ne" to "≠",
            "neq" to "≠",
            "approx" to "≈",
            "equiv" to "≡",
            "sim" to "∼",
            "simeq" to "≃",
            "propto" to "∝",
            "ll" to "≪",
            "gg" to "≫",
            // 箭头
            "to" to "→",
            "rightarrow" to "→",
            "leftarrow" to "←",
            "leftrightarrow" to "↔",
            "Rightarrow" to "⇒",
            "Leftarrow" to "⇐",
            "Leftrightarrow" to "⇔",
            "rightleftharpoons" to "⇌",
            "longrightarrow" to "⟶",
            "uparrow" to "↑",
            "downarrow" to "↓",
            "implies" to "⇒",
            "iff" to "⇔",
            // 集合与逻辑
            "in" to "∈",
            "notin" to "∉",
            "ni" to "∋",
            "subset" to "⊂",
            "subseteq" to "⊆",
            "supset" to "⊃",
            "supseteq" to "⊇",
            "cup" to "∪",
            "cap" to "∩",
            "emptyset" to "∅",
            "varnothing" to "∅",
            "forall" to "∀",
            "exists" to "∃",
            "neg" to "¬",
            "land" to "∧",
            "lor" to "∨",
            "therefore" to "∴",
            "because" to "∵",
            // 几何与杂项
            "infty" to "∞",
            "circ" to "°",
            "degree" to "°",
            "angle" to "∠",
            "perp" to "⊥",
            "parallel" to "∥",
            "triangle" to "△",
            "square" to "□",
            "odot" to "⊙",
            "prime" to "′",
            "ldots" to "…",
            "dots" to "…",
            "cdots" to "⋯",
            "vdots" to "⋮",
            "partial" to "∂",
            "nabla" to "∇",
            "sum" to "∑",
            "prod" to "∏",
            "int" to "∫",
            "oint" to "∮",
            "hbar" to "ℏ",
            "ell" to "ℓ",
            "S" to "§",
            // 希腊字母
            "alpha" to "α",
            "beta" to "β",
            "gamma" to "γ",
            "delta" to "δ",
            "epsilon" to "ε",
            "varepsilon" to "ε",
            "zeta" to "ζ",
            "eta" to "η",
            "theta" to "θ",
            "iota" to "ι",
            "kappa" to "κ",
            "lambda" to "λ",
            "mu" to "μ",
            "nu" to "ν",
            "xi" to "ξ",
            "pi" to "π",
            "rho" to "ρ",
            "sigma" to "σ",
            "tau" to "τ",
            "upsilon" to "υ",
            "phi" to "φ",
            "varphi" to "φ",
            "chi" to "χ",
            "psi" to "ψ",
            "omega" to "ω",
            "Gamma" to "Γ",
            "Delta" to "Δ",
            "Theta" to "Θ",
            "Lambda" to "Λ",
            "Xi" to "Ξ",
            "Pi" to "Π",
            "Sigma" to "Σ",
            "Phi" to "Φ",
            "Psi" to "Ψ",
            "Omega" to "Ω",
        )

    /**
     * 装饰（重音）命令 → Unicode 合成符。
     *
     * 这些命令原先一律走「认不出的命令原样保留」，学生看到的是 `\vec{a}`、`\overline{AB}`；
     * 而它们又几乎只出现在矢量、线段、均值这些基础记号上，读题时绕不过去。
     *
     * 合成符（combining mark）会**贴到前一个字符上**，所以只对单字符参数有意义：
     * `\vec{a}` → `a⃗`、`\bar{x}` → `x̄`。多字符参数（`\overline{AB}`）宁可不画线，
     * 也不把符号贴到最后一个字上——那会变成 `A` 加一个漫出来的箭头，比不画更误导。
     */
    private val DECORATIONS: Map<String, String> =
        mapOf(
            "vec" to "\u20D7",
            "overrightarrow" to "\u20D7",
            "overline" to "\u0305",
            "bar" to "\u0304",
            "hat" to "\u0302",
            "widehat" to "\u0302",
            "tilde" to "\u0303",
            "widetilde" to "\u0303",
            "dot" to "\u0307",
            "ddot" to "\u0308",
            "underline" to "\u0332",
        )

    /** 只取花括号内容的命令：字体与文本包装类。 */
    private val CONTENT_ONLY: Set<String> =
        setOf(
            "text",
            "textrm",
            "textbf",
            "textit",
            "textsf",
            "texttt",
            "mathrm",
            "mathbf",
            "mathit",
            "mathsf",
            "mathtt",
            "mathcal",
            "mathbb",
            "boldsymbol",
            "bm",
            "operatorname",
            "mbox",
            "ce",
            "pu",
            "displaystyle",
            "textstyle",
        )

    /** 间距命令：统一压成一个空格。 */
    private val SPACES: Set<String> =
        setOf("quad", "qquad", "enspace", "thinspace", "medspace", "thickspace", "space")

    /**
     * 只控制括号尺寸、不改变内容的命令。
     *
     * 真机反馈「括号直接以纯文本显示」就是它们造成的：`\left(` `\right)` 原先既不在符号表
     * 也不在包装表里，于是按「认不出的命令原样保留」处理，屏幕上出现的是
     * `\left(x+1\right)`。这里把它们整条丢掉，后面的括号字符本来就会原样留下。
     */
    private val DELIMITER_SIZERS: Set<String> =
        setOf(
            "left",
            "right",
            "middle",
            "big",
            "Big",
            "bigg",
            "Bigg",
            "bigl",
            "bigr",
            "Bigl",
            "Bigr",
            "biggl",
            "biggr",
            "Biggl",
            "Biggr",
            "bigm",
            "Bigm",
        )

    /**
     * 归一化一段纯文本。
     *
     * 不含反斜杠时**原样返回**（绝大多数正文走这条路径，零开销）。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty() || !raw.contains('\\')) return raw
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch != '\\') {
                out.append(ch)
                index++
                continue
            }
            val consumed = consumeCommand(raw, index)
            if (consumed == null) {
                out.append(ch)
                index++
                continue
            }
            out.append(consumed.text)
            index = consumed.endExclusive
        }
        return out.toString()
    }

    /** 一段被消费的原文。 */
    private class Consumed(val text: String, val endExclusive: Int)

    /**
     * 从 [start]（一定是反斜杠）开始消费一条命令。
     *
     * @return null 表示这条反斜杠后面不是可识别的命令，调用方原样输出该字符
     */
    private fun consumeCommand(
        raw: String,
        start: Int,
    ): Consumed? {
        if (start + 1 >= raw.length) return null
        val next = raw[start + 1]
        // 反斜杠 + 非字母：标点转义或换行
        if (!next.isLetter()) {
            return when (next) {
                '\\' -> Consumed("\n", start + 2)
                ',', ';', ':', '!', ' ' -> Consumed(" ", start + 2)
                '{', '}', '%', '&', '#', '$', '_' -> Consumed(next.toString(), start + 2)
                else -> Consumed(next.toString(), start + 2)
            }
        }
        var end = start + 1
        while (end < raw.length && raw[end].isLetter()) end++
        return consumeNamed(raw, end, raw.substring(start + 1, end))
    }

    /**
     * 命名命令的分派。
     *
     * 拆出来不是因为好看：`\frac`、`\begin`、`\left` 各自要读参数，全塞进 [consumeCommand]
     * 会同时踩到「圈复杂度」与「返回点过多」两条规则；按命令族拆开后每条都好验证。
     */
    private fun consumeNamed(
        raw: String,
        end: Int,
        name: String,
    ): Consumed? =
        when (name) {
            "frac", "dfrac", "tfrac" -> consumeFraction(raw, end)
            "sqrt" -> consumeRoot(raw, end)
            "begin" -> consumeEnvironment(raw, end, opening = true)
            "end" -> consumeEnvironment(raw, end, opening = false)
            in DELIMITER_SIZERS -> consumeDelimiterSizer(raw, end)
            in DECORATIONS -> consumeDecoration(raw, end, DECORATIONS.getValue(name))
            in SPACES -> consumeSpace(raw, end)
            in CONTENT_ONLY -> consumeContentOnly(raw, end)
            // 认不出的命令：原样保留，不能把公式改坏
            else -> SYMBOLS[name]?.let { Consumed(it, end) } ?: Consumed("\\" + name, end)
        }

    /** `\frac{a}{b}`，也支持 `\frac12` 这种省花括号的写法。 */
    private fun consumeFraction(
        raw: String,
        end: Int,
    ): Consumed? {
        val numerator = readArgument(raw, end) ?: return null
        val denominator = readArgument(raw, numerator.endExclusive) ?: return null
        val up = normalize(numerator.inner)
        val down = normalize(denominator.inner)
        return Consumed(wrapFraction(up, down), denominator.endExclusive)
    }

    /** `\sqrt{a}` / `\sqrt[3]{a}` / `\sqrt2`。 */
    private fun consumeRoot(
        raw: String,
        end: Int,
    ): Consumed? {
        var cursor = end
        var degree = ""
        if (cursor < raw.length && raw[cursor] == '[') {
            val closeAt = raw.indexOf(']', cursor + 1)
            if (closeAt > cursor + 1) {
                degree = normalize(raw.substring(cursor + 1, closeAt))
                cursor = closeAt + 1
            }
        }
        val body = readArgument(raw, cursor) ?: return null
        val prefix = if (degree.isEmpty()) "√" else degree + "√"
        return Consumed(prefix + "(" + normalize(body.inner) + ")", body.endExclusive)
    }

    /**
     * `\begin{cases}…\end{cases}` 与落单的 `\end{…}`。
     *
     * 环境标记整条丢掉、内容留下；环境里的 `&` 只是对齐占位，一并去掉
     * （否则屏幕上会出现「x&=1」）。
     */
    private fun consumeEnvironment(
        raw: String,
        end: Int,
        opening: Boolean,
    ): Consumed {
        val env = readGroup(raw, skipSpaces(raw, end))
        if (!opening) {
            // 落单的 \end：只丢标记，不吞后面的正文
            return Consumed("", env?.endExclusive ?: end)
        }
        if (env == null) return Consumed("", end)
        val endToken = "\\end{" + env.inner + "}"
        val endAt = raw.indexOf(endToken, env.endExclusive)
        if (endAt < 0) return Consumed("", env.endExclusive)
        val body = raw.substring(env.endExclusive, endAt).replace("&", "")
        return Consumed(normalize(body), endAt + endToken.length)
    }

    /**
     * `\left` / `\right` / `\big…`：只丢命令，括号字符本身保留。
     *
     * `\left.`、`\right.` 里的点是隐形定界符，一起吃掉，否则屏幕上会多出一个句点。
     */
    private fun consumeDelimiterSizer(
        raw: String,
        end: Int,
    ): Consumed {
        val after = skipSpaces(raw, end)
        val endExclusive = if (after < raw.length && raw[after] == '.') after + 1 else end
        return Consumed("", endExclusive)
    }

    /**
     * `\vec{a}` / `\bar{x}` 这类装饰命令：单字符贴合成符，多字符只丢命令留内容。
     *
     * @param mark 该命令对应的 Unicode 合成符（U+0300 段，或 U+20D7 这个「上方箭头」）
     */
    private fun consumeDecoration(
        raw: String,
        end: Int,
        mark: String,
    ): Consumed? {
        val body = readArgument(raw, end) ?: return null
        val inner = normalize(body.inner)
        // 空参数（`\vec{}`）连命令一起丢掉：屏幕上不该留一个孤零零的箭头
        if (inner.isEmpty()) return Consumed("", body.endExclusive)
        // 合成符只贴得上一个字。`\overline{AB}` 贴成 A̅B̅ 在纯文本里会错位成
        // 「A 加一个漫出来的横线」，比不画更误导，所以多字时只丢命令。
        val text = if (inner.length == 1) inner + mark else inner
        return Consumed(text, body.endExclusive)
    }

    /** `\quad` 之类：压成一个空格；源文本本来就跟着空格时不再补。 */
    private fun consumeSpace(
        raw: String,
        end: Int,
    ): Consumed {
        val nextIsSpace = end < raw.length && raw[end].isWhitespace()
        return Consumed(if (nextIsSpace) "" else " ", end)
    }

    /** `\text{…}` / `\mathrm{…}`：只取内容；无参数时整条丢掉。 */
    private fun consumeContentOnly(
        raw: String,
        end: Int,
    ): Consumed? {
        var cursor = end
        if (cursor < raw.length && raw[cursor] == '[') {
            val closeAt = raw.indexOf(']', cursor + 1)
            if (closeAt > cursor) cursor = closeAt + 1
        }
        if (cursor < raw.length && raw[cursor] == '{') {
            val group = readGroup(raw, cursor) ?: return null
            return Consumed(normalize(group.inner), group.endExclusive)
        }
        return Consumed("", end)
    }

    /**
     * 读一个参数：优先 `{…}`；没有花括号时按 LaTeX 惯例只取一个字符（`\frac12`、`\sqrt2`）。
     *
     * 花括号不配对、或后面紧跟另一条命令时返回 null——交给「认不出的命令原样保留」，
     * 宁可露出原文，也不要把后文吃掉。
     */
    private fun readArgument(
        raw: String,
        start: Int,
    ): Group? {
        val cursor = skipSpaces(raw, start)
        readGroup(raw, cursor)?.let { return it }
        if (cursor >= raw.length) return null
        val ch = raw[cursor]
        // 只认 ASCII 记号：`\frac 面积` 多半是正文误写了命令，把中文当参数会改坏句子
        if (!isBareArgument(ch)) return null
        return Group(ch.toString(), cursor + 1)
    }

    /** 不带花括号的参数：ASCII 且不是结构字符（反斜杠、花括号）。 */
    private fun isBareArgument(ch: Char): Boolean = ch.code < NON_ASCII_LIMIT && !isStructural(ch)

    private fun isStructural(ch: Char): Boolean = ch == '\\' || ch == '{' || ch == '}'

    private fun skipSpaces(
        raw: String,
        from: Int,
    ): Int {
        var cursor = from
        while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
        return cursor
    }

    /** 读一个 `{...}` 分组（[start] 必须是左花括号）。 */
    private fun readGroup(
        raw: String,
        start: Int,
    ): Group? {
        if (start >= raw.length || raw[start] != '{') return null
        var depth = 0
        var index = start
        while (index < raw.length) {
            when (raw[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return Group(raw.substring(start + 1, index), index + 1)
                }
            }
            index++
        }
        return null
    }

    /** 一个花括号分组的内容与结束位置（不含右括号）。 */
    private class Group(val inner: String, val endExclusive: Int)

    /** 分式：单个字符直接并排，多字符加括号，避免 `s/t` 与 `s+t/u` 混淆。 */
    private fun wrapFraction(
        numerator: String,
        denominator: String,
    ): String = wrapOperand(numerator) + "/" + wrapOperand(denominator)

    /** 单个字符或已是括号组时不加括号。 */
    private fun wrapOperand(value: String): String {
        if (value.isEmpty()) return "()"
        if (value.length == 1) return value
        if (value.startsWith("(") && value.endsWith(")")) return value
        return "(" + value + ")"
    }

    /** ASCII 上界：不带花括号的参数只接受这之前的字符（数字、字母、运算符）。 */
    private const val NON_ASCII_LIMIT: Int = 128
}
