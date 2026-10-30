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
        val name = raw.substring(start + 1, end)
        // \frac{a}{b} / \sqrt{a} / \sqrt[n]{a}：带参数的先处理
        when (name) {
            "frac", "dfrac", "tfrac" -> {
                val numerator = readGroup(raw, end) ?: return null
                val denominator = readGroup(raw, numerator.endExclusive) ?: return null
                val up = normalize(numerator.inner)
                val down = normalize(denominator.inner)
                return Consumed(wrapFraction(up, down), denominator.endExclusive)
            }
            "sqrt" -> {
                var cursor = end
                var degree = ""
                if (cursor < raw.length && raw[cursor] == '[') {
                    val closeAt = raw.indexOf(']', cursor + 1)
                    if (closeAt > cursor + 1) {
                        degree = normalize(raw.substring(cursor + 1, closeAt))
                        cursor = closeAt + 1
                    }
                }
                val body = readGroup(raw, cursor) ?: return null
                val prefix = if (degree.isEmpty()) "√" else degree + "√"
                return Consumed(prefix + "(" + normalize(body.inner) + ")", body.endExclusive)
            }
        }
        if (name in SPACES) {
            // 源文本里本来就跟着空格时不再补一个，否则 `a\quad b` 会变成两个空格
            val nextIsSpace = end < raw.length && raw[end].isWhitespace()
            return Consumed(if (nextIsSpace) "" else " ", end)
        }
        if (name in CONTENT_ONLY) {
            // 允许 \text{...} 前面带一个可选参数（如 \text[1]{...}），直接跳过
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
        SYMBOLS[name]?.let { return Consumed(it, end) }
        // 认不出的命令：原样保留，不能把公式改坏
        return Consumed("\\" + name, end)
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
}
