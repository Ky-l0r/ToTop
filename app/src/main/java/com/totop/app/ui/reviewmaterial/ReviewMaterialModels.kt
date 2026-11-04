package com.totop.app.ui.reviewmaterial

/** 复习资料生成状态。 */
sealed class ReviewMaterialState {
    /** 还没开始。 */
    data object Idle : ReviewMaterialState()

    /** 正在生成（界面显示 loading，可取消）。 */
    data object Generating : ReviewMaterialState()

    /** 生成成功。 */
    data class Success(val material: ReviewMaterial) : ReviewMaterialState()

    /** 失败：必须给出可读原因与重试入口，不能只说「失败」。 */
    data class Failed(val message: String, val retryable: Boolean) : ReviewMaterialState()
}

/**
 * 一段内容的排版样式。
 *
 * 只保留三种是刻意的：复习资料最终要打印成 A4，段落、要点、编号步骤
 * 已经能覆盖「归纳 / 易错清单 / 答题模板」的全部形态，
 * 表格、嵌套列表在固定版心内会挤成一团，反而更难读。
 */
enum class ReviewBlockStyle {
    /** 普通段落。 */
    PARAGRAPH,

    /** 无序要点，渲染时统一加「· 」。 */
    BULLETS,

    /** 有序步骤，渲染时统一加「1. 2. 3. 」。 */
    NUMBERED,

    /**
     * 同类例题：第一行题干，之后是「思路」与「答案」，答案排成小字。
     *
     * 单独一种样式而不是塞进段落，是因为答案必须**明显小于正文**：
     * 学生要的是先自己动手做，做完再看答案；字号一样会一眼扫到结果。
     */
    EXAMPLE,
    ;

    companion object {
        /** 宽容解析模型给的样式名；认不出就当段落。 */
        fun parse(raw: String?): ReviewBlockStyle =
            when (raw?.trim()?.lowercase()) {
                "bullets", "bullet", "list", "unordered" -> BULLETS
                "numbered", "ordered", "steps", "sequence" -> NUMBERED
                "example", "examples", "exercise", "sample" -> EXAMPLE
                else -> PARAGRAPH
            }
    }
}

/** 一个内容块：同一样式的一组行。 */
data class ReviewBlock(
    val style: ReviewBlockStyle,
    val lines: List<String>,
) {
    /** 空块不入库：模型偶尔会给出空数组，渲染出来只是多余留白。 */
    val isEmpty: Boolean get() = lines.all { it.isBlank() }
}

/** 一个章节：标题 + 章节导读（可选）+ 若干小节。 */
data class ReviewSection(
    val title: String,
    /**
     * 章节级内容块（导读），排在小节之前。
     *
     * 保留这一层是为了让「这章解决什么问题」有一句话的位置；
     * 没有小节的旧存档也仍然落在这里，解码与排版都不必分叉。
     */
    val blocks: List<ReviewBlock> = emptyList(),
    /** 小节，按顺序渲染，标题由渲染器统一加「（一）（二）…」。 */
    val subsections: List<ReviewSubsection> = emptyList(),
) {
    /** 是否有可打印的内容。 */
    val isEmpty: Boolean get() = blocks.all { it.isEmpty } && subsections.all { it.isEmpty }
}

/**
 * 一个小节：标题 + 若干内容块。
 *
 * 为什么要多这一层：一份六七页的资料只有七八个章节标题，
 * 学生翻到「函数与导数」之后还要在十几个要点里再找「构造函数怎么下手」。
 * 小节把这层定位成本压下来，代价是模型输出多一层 JSON——
 * 比在段落里加粗「（1）」冒充层级要可靠得多。
 */
data class ReviewSubsection(
    val title: String,
    val blocks: List<ReviewBlock>,
) {
    /** 空小节不入库：模型偶尔给出空数组，渲染出来只是多余留白。 */
    val isEmpty: Boolean get() = blocks.all { it.isEmpty }
}

/**
 * 一份考前复习资料。
 *
 * 为什么是「章节 + 内容块」而不是原来那四个固定字段
 * （weakKpoints / typicalMistakes / exercises / tips）：
 * 1. 固定字段只能罗列错题原题，装不下「归因 → 知识点 → 方法」的递进；
 * 2. 学科差异极大：语文要答题模板与作文预测，化学要盖斯定律这类通用易错点，
 *    十个科目塞进同一组字段，最后只能退化成一份谁都不够用的通用清单；
 * 3. 章节结构由模型按学科组织、由渲染器统一编号，新增内容不需要改版式代码。
 *
 * 字段仍然全部面向「打印出来看」：纯文本 + 短列表，不含任何需要交互的内容。
 */
data class ReviewMaterial(
    val subjectLabel: String,
    val examDate: String,
    /** 开篇总述：这次该重点看什么、怎么用这份资料。 */
    val summary: String,
    /** 正文章节，按顺序渲染，标题由渲染器统一加「一、二、…」。 */
    val sections: List<ReviewSection>,
    /** 本次取材的真实错题数，打印出来证明资料不是凭空生成的。 */
    val mistakesUsed: Int = 0,
    /**
     * 本科目一共有多少道历史错题（取材池大小）。
     *
     * 与 [mistakesUsed] 分开记：学生看到的应该是「本科目 32 道错题，这次用了 8 道」，
     * 只写 8 会让人以为错题本里只有 8 道；只写 32 又说不清这份资料到底依据了什么。
     */
    val mistakesAvailable: Int = 0,
    /** 生成时间（UTC 毫秒），用于历史记录排序。 */
    val createdAtUtc: Long = 0L,
) {
    /** 正文字数（不含标点统计口径的精细处理，只用于长度上限与提示）。 */
    val charCount: Int
        get() =
            summary.length +
                sections.sumOf { section ->
                    section.title.length +
                        section.blocks.sumOf { block -> block.lines.sumOf { it.length } } +
                        section.subsections.sumOf { subsection ->
                            subsection.title.length +
                                subsection.blocks.sumOf { block -> block.lines.sumOf { it.length } }
                        }
                }

    /** 是否有可打印的内容。 */
    val isRenderable: Boolean get() = sections.any { !it.isEmpty }
}

/** 参与生成的真实错题摘要（输入侧，不是展示模型）。 */
data class ReviewMistake(
    val stem: String,
    val errorSummary: String,
    val correctIdea: String,
    /** 已归一的错因类型中文名（固定七类之一），可能为空。 */
    val errorType: String = "",
)
