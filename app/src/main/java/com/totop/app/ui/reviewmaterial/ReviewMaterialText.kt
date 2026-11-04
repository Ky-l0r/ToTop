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

    /**
     * 把 `paragraph` 块的多行连成一段。
     *
     * 提示词里写的是「paragraph 的 lines 会被连成一段」，模型因此把一句话拆成两三个元素
     * 交给渲染器；旧实现每行各起一段，屏幕上就是「一句话一段」的碎片，既难读又占地方。
     *
     * 中文直接相连；只有两侧都是半角字母数字时才补一个空格
     * （`v=at` + `2 m/s` 粘成 `v=at2` 会读错）。
     */
    fun joinParagraph(lines: List<String>): String {
        val kept = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (kept.size <= 1) return kept.firstOrNull().orEmpty()
        val builder = StringBuilder()
        kept.forEach { line ->
            if (builder.isNotEmpty() && needsSpace(builder.last(), line.first())) builder.append(' ')
            builder.append(line)
        }
        return builder.toString()
    }

    /** 两行相接处是否要补一个半角空格。 */
    private fun needsSpace(
        previous: Char,
        next: Char,
    ): Boolean = previous.isAsciiWordChar() && next.isAsciiWordChar()

    private fun Char.isAsciiWordChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /**
     * 把 `paragraph` 块切成若干自然段。
     *
     * 首行带「易错 / 注意 / 必背 / 陷阱」标签的行要**单独成段**：
     * 标签会被渲染成色块，混进一整段里就成了句子中间的三个字，扫不出来。
     * 其余相邻行按 [joinParagraph] 合并。
     */
    fun paragraphSegments(lines: List<String>): List<String> {
        val segments = ArrayList<String>(2)
        val buffer = ArrayList<String>(4)

        fun flush() {
            if (buffer.isEmpty()) return
            segments += joinParagraph(buffer)
            buffer.clear()
        }

        lines.forEach { line ->
            if (tagOf(line) != null) flush()
            buffer += line
        }
        flush()
        return segments
    }

    /** 章节标题行的完整文本，例如「三、化学高频易错点」。 */
    fun sectionHeading(
        index: Int,
        title: String,
    ): String = sectionNumber(index) + "、" + title

    /** 小节序号：1 -> 一，11 -> 十一，13 -> 13。 */
    fun subsectionNumber(index: Int): String = sectionNumber(index)

    /**
     * 小节标题行的完整文本，例如「（二）构造函数怎么下手」。
     *
     * 用「（一）」而不是「2.1」：章节已经是「一、二、」的汉字序号，
     * 小节再换成阿拉伯点号，两种编号混排会让「现在是第几层」变模糊。
     */
    fun subsectionHeading(
        index: Int,
        title: String,
    ): String = "（" + subsectionNumber(index) + "）" + title

    /** 例题块里以「答案」开头的行，排版时缩小字号。 */
    fun isAnswerLine(line: String): Boolean = line.trimStart().startsWith(ANSWER_PREFIX)

    /** 例题块里的「思路」行：只给入手角度，不许出现结论。 */
    fun isHintLine(line: String): Boolean = line.trimStart().startsWith(HINT_PREFIX)

    /** 例题块里的「详解」行：完整推导，排成比答案更小的附注。 */
    fun isDetailLine(line: String): Boolean {
        val text = line.trimStart()
        return text.startsWith(DETAIL_PREFIX) || text.startsWith(ANALYSIS_PREFIX)
    }

    /**
     * 选项行：`A. …`、`B、…`、`（C）…`、`D) …`。
     *
     * 为什么要单独识别：四个选项挤在同一行时，学生读题要来回扫，
     * 而打印出来又没法像手机那样点选。识别出来后一行一个，题干与选项的层级也清楚了。
     */
    fun isOptionLine(line: String): Boolean = OPTION_PATTERN.containsMatchIn(line.trimStart())

    /** 取选项字母（大小写归一为 A-D）；不是选项行时返回 null。 */
    fun optionLetter(line: String): String? {
        val match = OPTION_PATTERN.find(line.trimStart()) ?: return null
        val raw = match.groupValues[1]
        val normalized = raw.firstOrNull()?.uppercaseChar() ?: return null
        val base = 'A'.code
        val index = (normalized.code - base).coerceIn(0, 3)
        return ('A' + index).toString()
    }

    /**
     * 行首语义标签：「易错」「注意」「必背」「陷阱」「记住」。
     *
     * 复习资料的价值在于「哪里会翻车」，这些词就是模型自己标出来的重点。
     * 把它们渲染成色块标签，而不是让它们混在正文里当普通两个字。
     */
    fun tagOf(line: String): String? {
        val text = line.trimStart()
        return TAGS.firstOrNull { text.startsWith(it) }
    }

    /**
     * 把「思路」里泄露结论的半句剔掉。
     *
     * 例题的设计意图是「先自己做，做完再看答案」，思路一旦写出「所以选 B」，
     * 这道题就白出了。这里只做**确定性**的删除：按句切分，凡是同时出现
     * 「答案 / 选 / 故选 / 应选 / 正确选项」与最终答案的行内短句整句删掉。
     * 判断不出来的一律保留——宁可思路里多说一句，也不要把有用的提示误删。
     *
     * @param answer 该例题的最终答案原文（如「B」「24 L」）
     */
    fun stripAnswerLeak(
        hint: String,
        answer: String,
    ): String {
        val trimmed = hint.trim()
        if (trimmed.isEmpty() || answer.isBlank()) return trimmed
        val answerToken = answer.trim().take(MAX_LEAK_TOKEN)
        val sentences = trimmed.split('。', '；', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size <= 1) return trimmed
        val kept =
            sentences.filterNot { sentence ->
                val mentionsAnswer =
                    sentence.contains(answerToken) ||
                        sentence.contains("答案" + answerToken) ||
                        sentence.contains("选" + answerToken)
                mentionsAnswer && LEAK_HINTS.any { sentence.contains(it) }
            }
        return if (kept.isEmpty()) trimmed else kept.joinToString("；")
    }

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

    private const val HINT_PREFIX: String = "思路"

    private const val DETAIL_PREFIX: String = "详解"

    private const val ANALYSIS_PREFIX: String = "解析"

    /** 选项行首：可带括号，字母限定 A-D（全角同样识别）。 */
    private val OPTION_PATTERN = Regex("^[（(]?([A-Da-dＡ-Ｄ])[)）.、．:：]")

    /** 会被渲染成标签的行首词。顺序即优先级。 */
    private val TAGS: List<String> = listOf("易错点", "易错", "注意", "陷阱", "必背", "记住")

    /** 泄露判定时只看答案的前几个字符，避免长答案里的顿号把句子切碎。 */
    private const val MAX_LEAK_TOKEN: Int = 8

    /** 出现这些词，才认为该句是在「宣布结论」。 */
    private val LEAK_HINTS: List<String> = listOf("答案", "选", "应选", "故选", "正确选项")

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
