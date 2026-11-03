package com.totop.app.ui.reviewmaterial

/** 一段文字里的一截：文本 + 是否加粗。 */
data class ReviewTextRun(
    val text: String,
    val bold: Boolean,
)

/**
 * 复习资料的纯文本处理：加粗标记、列表符号、章节序号。
 *
 * 为什么加粗用 `**…**` 而不是让模型另给一份「加粗字段」：
 * 排版需要的是**行内**强调（一句话里三五个字要重），
 * 拆成字段会让每一行都变成结构体，模型输出长度翻倍且更容易被截断。
 * 这里只做文本切分，PDF 与界面各自消费同一份结果，
 * 保证「屏幕上加粗的地方」和「打印出来加粗的地方」完全一致。
 */
object ReviewText {
    /** 加粗标记。 */
    const val BOLD_MARK: String = "**"

    /** 章节序号用到的汉字（最多支持 12 章，超出回落成阿拉伯数字）。 */
    private val SECTION_NUMERALS: List<String> =
        listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")

    /**
     * 把一行文本切成「普通」与「加粗」交替的片段。
     *
     * 未闭合的标记按普通文本处理；切完之后**再抹掉残留的星号**：
     * 模型偶尔会写单星号强调（`*重点*`）或句尾多一个 `*`，
     * 打印出来就是一个莫名其妙的符号，而它并不是内容的一部分。
     */
    fun splitBold(raw: String): List<ReviewTextRun> {
        if (raw.isEmpty()) return emptyList()
        val runs = ArrayList<ReviewTextRun>(4)
        var index = 0
        while (index < raw.length) {
            val start = raw.indexOf(BOLD_MARK, index)
            val end = if (start < 0) -1 else raw.indexOf(BOLD_MARK, start + BOLD_MARK.length)
            if (start < 0 || end < 0) {
                runs += ReviewTextRun(stripAsterisks(raw.substring(index)), false)
                break
            }
            if (start > index) runs += ReviewTextRun(stripAsterisks(raw.substring(index, start)), false)
            val inner = stripAsterisks(raw.substring(start + BOLD_MARK.length, end))
            if (inner.isNotEmpty()) runs += ReviewTextRun(inner, true)
            index = end + BOLD_MARK.length
        }
        return runs
    }

    /** 去掉加粗标记之外的所有星号（含全角）。 */
    private fun stripAsterisks(text: String): String = text.replace("*", "").replace("＊", "")

    /**
     * 清洗一行正文：去掉模型爱加的 Markdown 装饰与自带列表符号。
     *
     * 列表符号必须在这里删掉——渲染器会按样式统一加「· 」或「1. 」，
     * 模型再自带一个就变成「· · 」或「1. 1. 」。
     */
    fun sanitizeLine(
        raw: String,
        style: ReviewBlockStyle,
    ): String {
        var text = raw.replace("\r", "").trim()
        if (text.isEmpty()) return ""
        // 代码块反引号、Markdown 标题井号、分隔线：打印出来都是噪音
        text = text.replace("`", "")
        text = text.replace(Regex("^#{1,6}\\s*"), "")
        text = text.replace(Regex("^[-*_]{3,}$"), "")
        if (style == ReviewBlockStyle.BULLETS) {
            text = text.replace(Regex("^[·•\\-*–—]\\s*"), "")
        }
        if (style == ReviewBlockStyle.NUMBERED) {
            text = text.replace(Regex("^(\\d{1,2}|[一二三四五六七八九十]{1,2})[.、)）]\\s*"), "")
        }
        // 段首的全角空格会和白纸留白叠在一起，统一抹掉
        return text.trim()
    }

    /** 章节序号：1 -> 一，11 -> 十一，13 -> 13。 */
    fun sectionNumber(index: Int): String = SECTION_NUMERALS.getOrNull(index - 1) ?: index.toString()

    /** 章节标题行的完整文本，例如「三、化学高频易错点」。 */
    fun sectionHeading(
        index: Int,
        title: String,
    ): String = sectionNumber(index) + "、" + title

    /** 例题块里以「答案」开头的行，排版时缩小字号。 */
    fun isAnswerLine(line: String): Boolean = line.trimStart().startsWith(ANSWER_PREFIX)

    /**
     * 把化学式里的数字转成下标字符：C8H16O4 → C₈H₁₆O₄。
     *
     * 为什么不交给模型自觉：它时好时坏，而「H2SO4 印成 H2SO4」是硬伤。
     * 但也不能见数字就转——`A4 纸`、`MP3`、`K12`、`P2P` 都不是化学式，
     * 所以这里要求：连续的元素符号组 ≥ 2 且至少两种不同元素，
     * 每个符号都必须能在元素表里查到。宁可漏转，不可误转。
     */
    fun formatChemical(text: String): String {
        if (text.isEmpty() || text.none { it.isDigit() }) return text
        var result = DIATOMIC.replace(text) { match -> match.groupValues[1] + SUBSCRIPT_2 }
        result =
            FORMULA.replace(result) { match ->
                val token = match.groupValues[1]
                if (isChemicalFormula(token)) subscriptDigits(token) else token
            }
        return result
    }

    /** 判断一个「字母 + 数字」串是不是化学式（保守判定）。 */
    private fun isChemicalFormula(token: String): Boolean {
        if (token.none { it.isDigit() }) return false
        var index = 0
        var groups = 0
        val symbols = HashSet<String>(4)
        while (index < token.length) {
            if (!token[index].isUpperCase()) return false
            var symbol = token[index].toString()
            index++
            if (index < token.length && token[index].isLowerCase()) {
                symbol += token[index]
                index++
            }
            if (symbol !in ELEMENTS) return false
            symbols += symbol
            groups++
            while (index < token.length && token[index].isDigit()) index++
        }
        return groups >= 2 && symbols.size >= 2
    }

    private fun subscriptDigits(token: String): String =
        buildString(token.length) {
            token.forEach { ch -> append(SUBSCRIPT_DIGITS[ch] ?: ch) }
        }

    /** 数字 -> 下标字符。 */
    private val SUBSCRIPT_DIGITS: Map<Char, Char> =
        mapOf(
            '0' to '₀',
            '1' to '₁',
            '2' to '₂',
            '3' to '₃',
            '4' to '₄',
            '5' to '₅',
            '6' to '₆',
            '7' to '₇',
            '8' to '₈',
            '9' to '₉',
        )

    private const val ANSWER_PREFIX: String = "答案"

    private const val SUBSCRIPT_2: String = "₂"

    /** 双原子单质：O2 / H2 / N2 / Cl2 这种只有一种元素的式子。 */
    private val DIATOMIC = Regex("(?<![A-Za-z])(H|N|O|F|Cl|Br|I)2(?![A-Za-z0-9])")

    /** 由「元素符号 + 可选下标数字」重复组成的串。 */
    private val FORMULA = Regex("(?<![A-Za-z])((?:[A-Z][a-z]?\\d*){2,})(?![A-Za-z0-9])")

    /** 常见元素符号（中学化学范围内），按空格分隔写成多行，避免一长串字面量。 */
    private val ELEMENTS: Set<String> =
        (
            "H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca " +
                "Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr Rb Sr Y Zr " +
                "Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce W Pt Au Hg Tl Pb Bi"
        ).split(' ').toSet()
}
