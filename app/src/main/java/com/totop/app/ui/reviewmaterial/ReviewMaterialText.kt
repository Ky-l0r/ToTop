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
     * 未闭合的标记按普通文本处理：宁可少一处加粗，也不能把 `**` 原样印在纸上。
     */
    fun splitBold(raw: String): List<ReviewTextRun> {
        if (raw.isEmpty()) return emptyList()
        val runs = ArrayList<ReviewTextRun>(4)
        var index = 0
        while (index < raw.length) {
            val start = raw.indexOf(BOLD_MARK, index)
            val end = if (start < 0) -1 else raw.indexOf(BOLD_MARK, start + BOLD_MARK.length)
            if (start < 0 || end < 0) {
                runs += ReviewTextRun(raw.substring(index), false)
                break
            }
            if (start > index) runs += ReviewTextRun(raw.substring(index, start), false)
            val inner = raw.substring(start + BOLD_MARK.length, end)
            if (inner.isNotEmpty()) runs += ReviewTextRun(inner, true)
            index = end + BOLD_MARK.length
        }
        return runs
    }

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
}
