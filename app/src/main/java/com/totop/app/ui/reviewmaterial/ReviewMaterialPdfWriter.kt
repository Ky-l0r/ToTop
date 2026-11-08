package com.totop.app.ui.reviewmaterial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 例题题干的标记；外层与排版器都要用，因此放在文件级。 */
private const val EXAMPLE_PREFIX: String = "例："

/** 根号符号；绘制单元与排版器都要用，因此放在文件级。 */
private const val RADICAL_SIGN_TEXT: String = "√"

/**
 * 把复习资料排成可打印的 A4 PDF。
 *
 * 为什么用原生 [PdfDocument] 而不是 WebView 打印：
 * 1. 项目硬性约束「不引入第三方 SDK」「离线可用」，WebView 方案需要额外 HTML 模板资产；
 * 2. 原生方案在低端机上内存占用可控，且不依赖系统 WebView 版本；
 * 3. 打印与分享都能直接拿到 PDF 文件，链路最短。
 *
 * 版面约定（A4 纵向，72 dpi 的 PostScript 点）：
 * - 页面 595 × 842 pt，左右边距 46pt、上下 52pt；
 * - **层级靠字号与色阶拉开**：文档标题 19pt 主色、章节标题 13pt 加粗带主色序号、
 *   正文 11.5pt 墨色、附注 9.5pt 灰色；重点不再靠「全部加粗」，而是主色 + 字重 + 前置色块标签；
 * - 颜色都取中等明度（黑白打印后落在 30%-60% 灰度），不用浅色也不刺眼；
 * - **页数硬上限 [MAX_PAGES]**：先按最舒适的档位排，超页就换更紧的字号档重排，
 *   保证「双面打印最多 5 张纸」成立；
 * - 页脚页码；从第 2 页起加淡色书眉，散页后能对回顺序。
 */
class ReviewMaterialPdfWriter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun write(
        context: Context,
        material: ReviewMaterial,
    ): PdfWriteResult =
        withContext(ioDispatcher) {
            runCatching {
                val dir = File(context.cacheDir, PDF_DIR).apply { if (!exists()) mkdirs() }
                val file = File(dir, FILE_STEM + System.currentTimeMillis() + ".pdf")
                var pages = 0
                for (preset in Typography.PRESETS) {
                    val doc = PdfDocument()
                    val painter = PagePainter(doc, preset)
                    paintDocument(painter, material)
                    painter.finish()
                    pages = painter.pageCount
                    if (pages <= MAX_PAGES || preset == Typography.PRESETS.last()) {
                        file.outputStream().use { out -> doc.writeTo(out) }
                        doc.close()
                        break
                    }
                    // 超页：丢掉这一版重排。渲染只有 CPU 开销，比砍内容划算。
                    doc.close()
                }
                PdfWriteResult.Success(file, pages)
            }.getOrElse { failure ->
                PdfWriteResult.Failure(failure.message ?: failure.javaClass.simpleName)
            }
        }

    /** 按「文档头 → 总述 → 各章节」的顺序画完整份资料。 */
    private fun paintDocument(
        painter: PagePainter,
        material: ReviewMaterial,
    ) {
        painter.newPage()
        painter.docHeader(
            title = material.subjectLabel + " · 考前复习资料",
            subtitle = subtitleOf(material),
        )
        if (material.summary.isNotBlank()) {
            painter.summaryBlock(material.summary)
        }
        material.sections.forEachIndexed { index, section ->
            if (section.isEmpty) return@forEachIndexed
            painter.heading(ReviewText.sectionHeading(index + 1, section.title))
            // 章节导读先排，再进小节：导读回答「这章解决什么」，小节回答「具体怎么做」
            section.blocks.forEach { block -> paintBlock(painter, block) }
            section.subsections.forEachIndexed { subIndex, subsection ->
                painter.subheading(ReviewText.subsectionHeading(subIndex + 1, subsection.title))
                subsection.blocks.forEach { block -> paintBlock(painter, block) }
            }
        }
    }

    /** 一个内容块：段落逐行写，要点加色块符号，编号用主色数字，例题单独成块。 */
    private fun paintBlock(
        painter: PagePainter,
        block: ReviewBlock,
    ) {
        when (block.style) {
            ReviewBlockStyle.PARAGRAPH ->
                ReviewText.paragraphSegments(block.lines).forEach { text ->
                    painter.paragraph(text)
                    painter.gap(BLOCK_LINE_GAP_PT)
                }

            ReviewBlockStyle.BULLETS ->
                block.lines.forEach { line ->
                    painter.item("", line)
                    painter.gap(BLOCK_LINE_GAP_PT)
                }

            ReviewBlockStyle.NUMBERED ->
                block.lines.forEachIndexed { index, line ->
                    painter.item((index + 1).toString(), line)
                    painter.gap(BLOCK_LINE_GAP_PT)
                }

            ReviewBlockStyle.EXAMPLE -> painter.example(block.lines)
        }
        painter.gap(BLOCK_GAP_PT)
    }

    /** 卷首说明：这份资料从哪来、用了多少错题、一共几章。 */
    private fun subtitleOf(material: ReviewMaterial): String {
        val date =
            if (material.createdAtUtc > 0L) {
                SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(material.createdAtUtc))
            } else {
                ""
            }
        val origin =
            when {
                material.mistakesUsed > 0 && material.mistakesAvailable > material.mistakesUsed ->
                    "取材：本科目共 " + material.mistakesAvailable + " 道错题，纳入最近 " +
                        material.mistakesUsed + " 道"
                material.mistakesUsed > 0 -> "取材：本科目 " + material.mistakesUsed + " 道错题"
                else -> "本科目暂无错题记录，按该科目通用考点整理"
            }
        val parts =
            buildList {
                add(origin)
                add(material.sections.size.toString() + " 个章节")
                if (date.isNotEmpty()) add("生成于 " + date)
            }
        return parts.joinToString("　·　")
    }

    companion object {
        private const val PDF_DIR: String = "review_material"

        /** 导出文件名前缀。 */
        const val FILE_STEM: String = "考前复习资料_"

        /** A4 双面 5 张 = 10 页的硬上限。 */
        const val MAX_PAGES: Int = 10

        /** 同一块内相邻两行之间的小间距。 */
        private const val BLOCK_LINE_GAP_PT: Float = 2f

        /** 块与块之间的间距。 */
        private const val BLOCK_GAP_PT: Float = 5f
    }
}

sealed class PdfWriteResult {
    /** @param pageCount 实际排出的页数，界面据此提示「双面打印 N 张」。 */
    data class Success(
        val file: File,
        val pageCount: Int,
    ) : PdfWriteResult()

    data class Failure(val reason: String) : PdfWriteResult()
}

/** 一套字号档位：四档递减，只在超页时才会用到后三档。 */
private data class Typography(
    val body: Float,
    val lineRatio: Float,
    val title: Float,
    val heading: Float,
) {
    companion object {
        /** 从舒适到紧凑；最后一档是兜底，宁可字小一点也不砍内容。 */
        val PRESETS: List<Typography> =
            listOf(
                Typography(body = 11.5f, lineRatio = 1.55f, title = 19f, heading = 13f),
                Typography(body = 11f, lineRatio = 1.46f, title = 18f, heading = 12.5f),
                Typography(body = 10.5f, lineRatio = 1.38f, title = 17f, heading = 12f),
                Typography(body = 10f, lineRatio = 1.32f, title = 16.5f, heading = 11.5f),
            )
    }
}

/**
 * 版面绘制器：维护当前页与 y 坐标，写满自动换页。
 *
 * 支持「文档头 / 总述块 / 章节标题 / 段落 / 要点 / 例题 / 间隔」七种操作，加上行内加粗。
 * 刻意不引入完整排版引擎：复习资料只有这几种版式，多出来的能力没人用，还得自己养。
 */
private class PagePainter(
    private val doc: PdfDocument,
    private val type: Typography,
) {
    private val pageWidth = 595
    private val pageHeight = 842
    private val marginX = 46f
    private val marginTop = 52f
    private val marginBottom = 52f

    private val contentRight: Float get() = pageWidth - marginX

    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y: Float = marginTop
    private var pageNumber = 0

    /** 已排出的页数（超页判断用）。 */
    val pageCount: Int get() = pageNumber

    private val titlePaint =
        Paint().apply {
            isAntiAlias = true
            textSize = type.title
            color = Palette.PRIMARY
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val headingPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = type.heading
            color = Palette.INK
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val headingNumberPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = type.heading
            color = Palette.PRIMARY
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    /**
     * 小节标题：比章节标题小 1.5 磅，不加细线。
     *
     * 层级靠字号与「有没有线」区分：章节有一条横贯版心的细线，
     * 小节只有主色序号，翻页扫读时两层的分界不会混。
     */
    private val subheadingPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = type.heading - 1.5f
            color = Palette.INK
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val subheadingNumberPaint =
        Paint(subheadingPaint).apply {
            color = Palette.PRIMARY
        }

    private val bodyPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = type.body
            color = Palette.INK
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

    /** 行内加粗：与正文同字号同字体，只换字重，避免打印后行距忽松忽紧。 */
    private val bodyBoldPaint =
        Paint(bodyPaint).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    /** 总述块正文：主色系深色，与正文一眼分得开。 */
    private val summaryPaint =
        Paint(bodyPaint).apply {
            color = Palette.PRIMARY
        }

    private val summaryBoldPaint =
        Paint(summaryPaint).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val notePaint =
        Paint().apply {
            isAntiAlias = true
            textSize = type.body - 2f
            color = Palette.MUTED
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

    /** 例题答案：比正文小 1.5 磅并改用成功色，做完题再看也不至于一眼扫到。 */
    private val answerPaint =
        Paint(bodyPaint).apply {
            textSize = type.body - 1.5f
            color = Palette.SUCCESS
        }

    private val answerBoldPaint =
        Paint(answerPaint).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val detailPaint =
        Paint(notePaint).apply {
            textSize = type.body - 1.5f
        }

    /** 例题里的「思路」：用推理色，与题干区分开。 */
    private val hintPaint =
        Paint(bodyPaint).apply {
            color = Palette.REASONING
        }

    private val rulePaint =
        Paint().apply {
            isAntiAlias = true
            strokeWidth = 0.7f
            color = Palette.RULE
            style = Paint.Style.STROKE
        }

    private val barPaint =
        Paint().apply {
            isAntiAlias = true
            color = Palette.PRIMARY
            style = Paint.Style.FILL
        }

    private val accentBarPaint =
        Paint().apply {
            isAntiAlias = true
            color = Palette.ACCENT
            style = Paint.Style.FILL
        }

    private val tagPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = type.body - 2.5f
            color = Palette.ACCENT
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val tagBoxPaint =
        Paint().apply {
            isAntiAlias = true
            color = Palette.ACCENT_TINT
            style = Paint.Style.FILL
        }

    /**
     * 这台设备的有衬线字体有没有下标字符。
     *
     * 有就直接排下标（C₈H₁₆O₄），没有就退回普通数字（C8H16O4）——
     * 缺字形时 PDF 会印成空心方块，比没有下标难看十倍。
     */
    private val subscriptSupported: Boolean by lazy { bodyPaint.hasGlyph("₈") }

    /** 同上，判定上标字符（`²`）。 */
    private val superscriptSupported: Boolean by lazy { bodyPaint.hasGlyph("²") }

    /** 小字号副本的缓存：上下标、分子分母、根号符号都要用，不能每画一个分式就 new 一支 Paint。 */
    private val brush = PaintScaler()

    /** 分式横线与根号顶横：比正文细，打印出来是一条细线而不是一道黑杠。 */
    private val mathRulePaint =
        Paint().apply {
            isAntiAlias = true
            strokeWidth = 0.6f
            color = Palette.INK
            style = Paint.Style.STROKE
        }

    /** 开启新页。 */
    fun newPage() {
        finishPage()
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create()
        val created = doc.startPage(info)
        page = created
        canvas = created.canvas
        y = marginTop
        // 第 2 页起加书眉：散页后知道这是哪一科的哪一份
        if (pageNumber > 1) {
            canvas?.drawText(RUNNING_TITLE, marginX, RUNNING_TITLE_BASELINE_Y, notePaint)
            canvas?.drawLine(marginX, RUNNING_RULE_Y, contentRight, RUNNING_RULE_Y, rulePaint)
        }
    }

    /** 文档头：主色标题 + 主色细条 + 一行灰字说明。 */
    fun docHeader(
        title: String,
        subtitle: String,
    ) {
        canvas?.drawText(title, marginX, y + type.title, titlePaint)
        y += type.title + HEADER_TITLE_GAP
        canvas?.drawRect(marginX, y, contentRight, y + HEADER_RULE_HEIGHT, barPaint)
        y += HEADER_RULE_AFTER
        writeRich(ReviewText.splitBold(subtitle), 0f, 0f, notePaint, notePaint)
        y += HEADER_SUBTITLE_AFTER
    }

    /** 章节标题：主色序号 + 墨色标题 + 一条淡线，方便打印后快速定位。 */
    fun heading(text: String) {
        // 标题后面至少留三行正文，避免「标题孤零零挂在页脚」
        ensureSpace(HEADING_BLOCK_PT + bodyPaint.textSize * type.lineRatio * 3f)
        y += HEADING_GAP_PT
        // 基线必须从「块顶」往下让出字顶，不能直接拿 y 当基线：
        // 直接当基线时字顶会向上溢出 ascent，压住上一段的最后一行（截图里的重影就是这么来的）。
        val baseline = ReviewPdfFlow.baselineOf(y, headingPaint.textSize)
        val number = text.substringBefore('、', "")
        val rest = if (number.isEmpty()) text else text.substringAfter('、', text)
        var x = marginX
        if (number.isNotEmpty()) {
            canvas?.drawText(number + "、", x, baseline, headingNumberPaint)
            x += headingNumberPaint.measureText(number + "、")
        }
        canvas?.drawText(rest, x, baseline, headingPaint)
        val ruleY = baseline + HEADING_RULE_GAP_PT
        canvas?.drawLine(marginX, ruleY, contentRight, ruleY, rulePaint)
        y = ReviewPdfFlow.nextTopAfter(baseline, headingPaint.textSize, HEADING_AFTER_PT)
    }

    /**
     * 小节标题：主色「（一）」+ 墨色标题。
     *
     * 序号与标题分色，和章节标题用的是同一套视觉语言，只是小一号、不带细线——
     * 打印出来后一眼能看出「这一条属于上面那一章」。
     */
    fun subheading(text: String) {
        ensureSpace(SUB_HEADING_BLOCK_PT + bodyPaint.textSize * type.lineRatio * 2f)
        y += SUB_HEADING_GAP_PT
        // 与章节标题同一套几何：块顶 -> 基线 -> 块底。原来只推进 5pt，
        // 下一段正文的第一行几乎与标题同基线，屏幕上看到的就是两行字叠在一起。
        val baseline = ReviewPdfFlow.baselineOf(y, subheadingPaint.textSize)
        val close = text.indexOf('）')
        val hasLabel = text.startsWith("（") && close > 0
        val label = if (hasLabel) text.substring(0, close + 1) else ""
        val rest = if (hasLabel) text.substring(close + 1) else text
        var x = marginX
        if (label.isNotEmpty()) {
            canvas?.drawText(label, x, baseline, subheadingNumberPaint)
            x += subheadingNumberPaint.measureText(label)
        }
        canvas?.drawText(rest, x, baseline, subheadingPaint)
        y = ReviewPdfFlow.nextTopAfter(baseline, subheadingPaint.textSize, SUB_HEADING_AFTER_PT)
    }

    /**
     * 卷首总述：左侧一条主色竖线，正文整体缩进。
     *
     * 竖线在正文画完之后再补：这样不需要预先估算折行高度，
     * 只要记住起止 y 就能画一条正好等高的线。
     */
    fun summaryBlock(text: String) {
        val startY = y
        writeRich(ReviewText.splitBold(text), SUMMARY_INDENT_PT, SUMMARY_INDENT_PT, summaryPaint, summaryBoldPaint)
        canvas?.drawRect(
            marginX,
            startY - type.body,
            marginX + SUMMARY_BAR_WIDTH,
            y - type.body * SUMMARY_BAR_BOTTOM_RATIO,
            barPaint,
        )
        y += SUMMARY_AFTER_PT
    }

    /** 普通段落：整段折行，行内 `**…**` 加粗；行首若是「易错/注意」先画标签。 */
    fun paragraph(text: String) {
        val tag = ReviewText.tagOf(text)
        if (tag != null) {
            val body = text.trimStart().removePrefix(tag).trimStart('：', ':', ' ')
            val startY = y
            writeRich(ReviewText.splitBold(body), TAG_WIDTH_PT, TAG_WIDTH_PT, bodyPaint)
            drawTag(tag, startY)
            return
        }
        writeRich(ReviewText.splitBold(text), 0f, 0f, bodyPaint)
    }

    /**
     * 要点：无序用主色小方块，有序用主色数字，正文悬挂缩进。
     *
     * @param prefix 有序列表的序号（"1"）；空串表示无序要点
     */
    fun item(
        prefix: String,
        text: String,
    ) {
        val tag = ReviewText.tagOf(text)
        val body = if (tag != null) text.trimStart().removePrefix(tag).trimStart('：', ':', ' ') else text
        val indent = ITEM_INDENT_PT
        val startY = y
        writeRich(ReviewText.splitBold(body), indent, indent, bodyPaint)
        if (prefix.isEmpty()) {
            canvas?.drawRect(
                marginX + ITEM_BULLET_LEFT,
                startY - type.body + ITEM_BULLET_TOP,
                marginX + ITEM_BULLET_RIGHT,
                startY - ITEM_BULLET_BOTTOM,
                barPaint,
            )
        } else {
            canvas?.drawText(prefix + ".", marginX, startY, headingNumberPaint)
        }
        if (tag != null) drawTag(tag, startY)
    }

    /**
     * 例题：题干 → 选项（一行一个）→ 思路 → 答案（小字）→ 详解（更小的灰字）。
     *
     * 为什么选项要单独占行：四个选项挤成一段时，学生要在同一行里来回找 A/B/C/D，
     * 打印在纸上更明显。答案与详解放在**一条虚分割线之后**，先做完再看。
     */
    fun example(lines: List<String>) {
        if (lines.isEmpty()) return
        val answer =
            lines.firstOrNull { ReviewText.isAnswerLine(it) }
                ?.substringAfter('：', "")
                .orEmpty()
                .trim()
        y += EXAMPLE_GAP_BEFORE_PT
        val startY = y
        val stemRuns =
            buildList {
                add(ReviewTextRun(EXAMPLE_PREFIX, true))
                addAll(ReviewText.splitBold(lines.first()))
            }
        writeRich(stemRuns, 0f, 0f, bodyPaint)
        var sawAnswer = false
        lines.drop(1).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                ReviewText.isOptionLine(line) -> {
                    val letter = ReviewText.optionLetter(line).orEmpty()
                    writeOption(letter, stripOptionPrefix(line))
                }

                ReviewText.isHintLine(line) ->
                    writeRich(
                        ReviewText.splitBold(ReviewText.stripAnswerLeak(line, answer)),
                        OPTION_INDENT_PT,
                        OPTION_INDENT_PT,
                        hintPaint,
                    )

                ReviewText.isAnswerLine(line) -> {
                    sawAnswer = true
                    dashedRule()
                    writeRich(ReviewText.splitBold(line), 0f, 0f, answerPaint, answerBoldPaint)
                }

                ReviewText.isDetailLine(line) -> {
                    if (!sawAnswer) dashedRule()
                    writeRich(ReviewText.splitBold(line), 0f, 0f, detailPaint, detailPaint)
                }

                else -> writeRich(ReviewText.splitBold(line), OPTION_INDENT_PT, OPTION_INDENT_PT, bodyPaint)
            }
        }
        y += 3f
        // 整块左侧一条赭石竖线：把「题干 + 选项 + 思路 + 答案」圈成一道题
        canvas?.drawRect(
            marginX - EXAMPLE_BAR_LEFT,
            startY - type.body,
            marginX - EXAMPLE_BAR_RIGHT,
            y - type.body * EXAMPLE_BAR_BOTTOM_RATIO,
            accentBarPaint,
        )
        y += EXAMPLE_GAP_AFTER_PT
    }

    /** 选项：字母主色加粗 + 悬挂缩进，一行一个。 */
    private fun writeOption(
        letter: String,
        body: String,
    ) {
        val label = if (letter.isEmpty()) "" else letter + "."
        val indent = OPTION_INDENT_PT + OPTION_LABEL_WIDTH_PT
        val starts = y
        writeRich(ReviewText.splitBold(body), indent, indent, bodyPaint)
        if (label.isNotEmpty()) {
            canvas?.drawText(label, marginX + OPTION_INDENT_PT, starts, headingNumberPaint)
        }
    }

    /** 去掉选项行首的「A.」「（B）」等前缀：前缀由排版单独画。 */
    private fun stripOptionPrefix(line: String): String =
        line.replaceFirst(Regex("^[（(]?[A-Da-dＡ-Ｄ][)）.、．:：]\\s*"), "").trim()

    /** 画一个浅底标签（文字底色块 + 强调色字）。 */
    private fun drawTag(
        tag: String,
        rowBaseline: Float,
    ) {
        val textWidth = tagPaint.measureText(tag)
        val top = rowBaseline - tagPaint.textSize - 2f
        val rect = RectF(marginX, top, marginX + textWidth + 6f, rowBaseline + 1.5f)
        canvas?.drawRoundRect(rect, 2f, 2f, tagBoxPaint)
        canvas?.drawText(tag, marginX + TAG_TEXT_PADDING_X, rowBaseline - TAG_TEXT_PADDING_Y, tagPaint)
    }

    /** 例题里答案与详解之间的虚线：视觉上把「题」和「解」分开。 */
    private fun dashedRule() {
        ensureSpace(DASH_BLOCK_PT)
        y += 5f
        var x = marginX
        while (x < contentRight) {
            canvas?.drawLine(x, y, minOf(x + DASH_LENGTH_PT, contentRight), y, rulePaint)
            x += DASH_LENGTH_PT * 2f
        }
        y += 6f
    }

    fun gap(size: Float) {
        y += size
    }

    fun finish() {
        finishPage()
    }

    private fun finishPage() {
        val openPage = page
        if (openPage != null) {
            // 页脚：页码 + 右侧一句「双面打印」提示，散页后能对回顺序
            canvas?.drawText("第 " + pageNumber + " 页", marginX, pageHeight - 28f, notePaint)
            canvas?.drawText(
                FOOTER_HINT,
                contentRight - notePaint.measureText(FOOTER_HINT),
                pageHeight - 28f,
                notePaint,
            )
            runCatching { doc.finishPage(openPage) }
        }
        page = null
        canvas = null
    }

    /**
     * 写一段自动折行的富文本（加粗 + 轻数学记号）。
     *
     * 分两步：先把片段排成「行」并算出每行高度，再逐行绘制。
     * 为什么不能边排边画：分式与根号比正文高，一行到底多高要等整行排完才知道；
     * 边排边画就会出现「这行画到页脚外」或「后半行被新页切断」。
     *
     * @param firstIndent 首行左缩进
     * @param restIndent 折行后左缩进（悬挂缩进）
     */
    private fun writeRich(
        runs: List<ReviewTextRun>,
        firstIndent: Float,
        restIndent: Float,
        paint: Paint,
        boldPaint: Paint = bodyBoldPaint,
    ) {
        if (runs.isEmpty()) return
        val lines = layoutLines(buildInk(runs, paint, boldPaint), firstIndent, restIndent, paint)
        lines.forEach { line ->
            ensureSpace(line.height)
            line.placements.forEach { placement ->
                when (val ink = placement.ink) {
                    is Ink.Glyphs -> canvas?.drawText(ink.text, placement.x, y + ink.offsetY, ink.paint)
                    is Ink.Fraction -> drawFraction(ink, placement.x)
                    is Ink.Radical -> drawRadical(ink, placement.x)
                }
            }
            y += line.height
        }
    }

    /** 把片段排成行：文字按剩余宽度逐字切分，分式与根号是整体，放不下就换行。 */
    private fun layoutLines(
        inks: List<Ink>,
        firstIndent: Float,
        restIndent: Float,
        paint: Paint,
    ): List<PdfLine> {
        val lines = ArrayList<PdfLine>()
        val lineHeight = paint.textSize * type.lineRatio
        var items = ArrayList<Placement>()
        var x = marginX + firstIndent
        var above = 0f
        var below = 0f

        fun flush() {
            if (items.isEmpty()) return
            lines += PdfLine(maxOf(lineHeight, above + below), items)
            items = ArrayList()
            x = marginX + restIndent
            above = 0f
            below = 0f
        }

        inks.forEach { ink ->
            if (ink is Ink.Glyphs) {
                var rest = ink.text
                while (rest.isNotEmpty()) {
                    var count = ink.paint.breakText(rest, true, contentRight - x, null)
                    if (count <= 0) {
                        if (items.isNotEmpty()) {
                            flush()
                            continue
                        }
                        // 行首也放不下（极窄残行）：至少画一个字，避免死循环
                        count = 1
                    }
                    items += Placement(Ink.Glyphs(rest.substring(0, count), ink.paint, ink.offsetY), x)
                    x += ink.paint.measureText(rest, 0, count)
                    rest = rest.substring(count)
                    above = maxOf(above, ink.above)
                    below = maxOf(below, ink.below)
                    if (rest.isNotEmpty()) flush()
                }
            } else {
                if (x + ink.width > contentRight && items.isNotEmpty()) flush()
                items += Placement(ink, x)
                x += ink.width
                above = maxOf(above, ink.above)
                below = maxOf(below, ink.below)
            }
        }
        flush()
        return lines
    }

    /** 分式：分子与分母各自居中，中间一条横线，横线宽度等于较宽的那一边。 */
    private fun drawFraction(
        ink: Ink.Fraction,
        x: Float,
    ) {
        val centerX = x + ink.width / 2f
        val numeratorX = centerX - ink.numeratorWidth / 2f
        val denominatorX = centerX - ink.denominatorWidth / 2f
        canvas?.drawText(ink.numerator, numeratorX, y + ink.numeratorBaseline, ink.numeratorPaint)
        canvas?.drawText(ink.denominator, denominatorX, y + ink.denominatorBaseline, ink.denominatorPaint)
        canvas?.drawLine(x, y + ink.ruleY, x + ink.width, y + ink.ruleY, ink.rulePaint)
    }

    /** 根号：`√` 符号 + 被开方内容 + 一条压在内容顶上的横线（就是根号的顶横）。 */
    private fun drawRadical(
        ink: Ink.Radical,
        x: Float,
    ) {
        canvas?.drawText(RADICAL_SIGN_TEXT, x, y, ink.signPaint)
        canvas?.drawText(ink.body, x + ink.signWidth, y, ink.bodyPaint)
        canvas?.drawLine(
            x + ink.signWidth * RADICAL_FLAG_RATIO,
            y + ink.vinculumY,
            x + ink.width,
            y + ink.vinculumY,
            ink.rulePaint,
        )
    }

    /** 把加粗片段与数学记号摊平成绘制单元。 */
    private fun buildInk(
        runs: List<ReviewTextRun>,
        paint: Paint,
        boldPaint: Paint,
    ): List<Ink> {
        val out = ArrayList<Ink>(runs.size * 2)
        runs.forEach { run ->
            val base = if (run.bold) boldPaint else paint
            appendInk(out, ReviewMath.parse(run.text), base)
        }
        return out
    }

    private fun appendInk(
        out: MutableList<Ink>,
        tokens: List<ReviewMath.Token>,
        base: Paint,
    ) {
        tokens.forEach { token ->
            when (token) {
                is ReviewMath.Token.Text ->
                    if (token.value.isNotEmpty()) out += Ink.Glyphs(prepareScripts(token.value), base)

                is ReviewMath.Token.Sub ->
                    out +=
                        Ink.Glyphs(
                            text = prepareScripts(token.value),
                            paint = brush.scaled(base, SCRIPT_SCALE),
                            offsetY = base.textSize * SUBSCRIPT_DROP,
                        )

                is ReviewMath.Token.Sup ->
                    out +=
                        Ink.Glyphs(
                            text = prepareScripts(token.value),
                            paint = brush.scaled(base, SCRIPT_SCALE),
                            offsetY = -base.textSize * SUPERSCRIPT_RISE,
                        )

                is ReviewMath.Token.Fraction ->
                    out +=
                        Ink.Fraction(
                            numerator = prepareScripts(ReviewMath.flattenScripted(token.numerator)),
                            denominator = prepareScripts(ReviewMath.flattenScripted(token.denominator)),
                            numeratorPaint = brush.scaled(base, FRACTION_SCALE),
                            denominatorPaint = brush.scaled(base, FRACTION_SCALE),
                            rulePaint = mathRulePaint,
                        )

                is ReviewMath.Token.Radical ->
                    out +=
                        Ink.Radical(
                            body = prepareScripts(ReviewMath.flattenScripted(token.radicand)),
                            signPaint = brush.scaled(base, RADICAL_SIGN_SCALE),
                            bodyPaint = base,
                            rulePaint = mathRulePaint,
                        )
            }
        }
    }

    /** 字体不支持下标/上标字符时退回普通数字，避免印成方块。 */
    private fun prepareScripts(text: String): String {
        if (text.isEmpty()) return text
        val needSub = !subscriptSupported && text.any { it in SUBSCRIPT_TO_DIGIT }
        val needSup = !superscriptSupported && text.any { it in SUPERSCRIPT_TO_DIGIT }
        if (!needSub && !needSup) return text
        val builder = StringBuilder(text.length)
        text.forEach { ch ->
            builder.append(SUBSCRIPT_TO_DIGIT[ch] ?: SUPERSCRIPT_TO_DIGIT[ch] ?: ch)
        }
        return builder.toString()
    }

    private fun ensureSpace(needed: Float) {
        if (y + needed > pageHeight - marginBottom) newPage()
    }

    private companion object {
        /** 下标字符 -> 普通数字，字体缺字形时用。 */
        val SUBSCRIPT_TO_DIGIT: Map<Char, Char> =
            mapOf(
                '₀' to '0',
                '₁' to '1',
                '₂' to '2',
                '₃' to '3',
                '₄' to '4',
                '₅' to '5',
                '₆' to '6',
                '₇' to '7',
                '₈' to '8',
                '₉' to '9',
            )

        /** 上标字符 -> 普通数字，字体缺字形时用。 */
        val SUPERSCRIPT_TO_DIGIT: Map<Char, Char> =
            mapOf(
                '⁰' to '0',
                '¹' to '1',
                '²' to '2',
                '³' to '3',
                '⁴' to '4',
                '⁵' to '5',
                '⁶' to '6',
                '⁷' to '7',
                '⁸' to '8',
                '⁹' to '9',
            )

        /** 第 2 页起重复的书眉文字。 */
        const val RUNNING_TITLE: String = "ToTop · 考前复习资料"

        /** 书眉文字基线与分隔线的 y 坐标。 */
        const val RUNNING_TITLE_BASELINE_Y: Float = 28f
        const val RUNNING_RULE_Y: Float = 35f

        /** 文档头：标题到主色细条、细条高度、细条到说明、说明之后的留白。 */
        const val HEADER_TITLE_GAP: Float = 10f
        const val HEADER_RULE_HEIGHT: Float = 2f
        const val HEADER_RULE_AFTER: Float = 13f
        const val HEADER_SUBTITLE_AFTER: Float = 11f

        /** 总述块左侧竖线宽度，以及竖线底端相对末行基线的比例。 */
        const val SUMMARY_BAR_WIDTH: Float = 2.5f
        const val SUMMARY_BAR_BOTTOM_RATIO: Float = 0.3f

        /** 要点小方块的四边偏移（相对版心左边界与当前行基线）。 */
        const val ITEM_BULLET_LEFT: Float = 1f
        const val ITEM_BULLET_TOP: Float = 2.5f
        const val ITEM_BULLET_RIGHT: Float = 4.5f
        const val ITEM_BULLET_BOTTOM: Float = 2.5f

        /** 例题左侧赭石竖线的左右偏移与底端比例。 */
        const val EXAMPLE_BAR_LEFT: Float = 9f
        const val EXAMPLE_BAR_RIGHT: Float = 6.5f
        const val EXAMPLE_BAR_BOTTOM_RATIO: Float = 0.4f

        /** 标签色块内文字的内边距。 */
        const val TAG_TEXT_PADDING_X: Float = 3f
        const val TAG_TEXT_PADDING_Y: Float = 2.5f

        /** 上下标相对正文的缩放比例。 */
        const val SCRIPT_SCALE: Float = 0.72f

        /** 下标基线下沉的量（相对正文字号）。 */
        const val SUBSCRIPT_DROP: Float = 0.18f

        /** 上标基线上抬的量（相对正文字号）。 */
        const val SUPERSCRIPT_RISE: Float = 0.34f

        /** 分式内部字号相对正文的比例。 */
        const val FRACTION_SCALE: Float = 0.86f

        /** 根号符号相对正文的放大倍数。 */
        const val RADICAL_SIGN_SCALE: Float = 1.18f

        /** 根号顶横的起点：落在符号斜肩之后，太靠左会压住符号本身。 */
        const val RADICAL_FLAG_RATIO: Float = 0.62f

        /** 页脚右侧提示。 */
        const val FOOTER_HINT: String = "A4 双面打印 · 5 张以内"

        /** 章节标题整体占用高度：上方留白 + 标题 + 细线 + 下方留白。 */
        const val HEADING_BLOCK_PT: Float = 38f

        /** 章节标题与上一段之间的留白。 */
        const val HEADING_GAP_PT: Float = 13f

        /** 章节标题基线到细线的距离。 */
        const val HEADING_RULE_GAP_PT: Float = 7f

        /** 细线到正文首行的距离。 */
        const val HEADING_AFTER_PT: Float = 13f

        /** 小节标题整体占用高度：上方留白 + 标题 + 下方留白。 */
        const val SUB_HEADING_BLOCK_PT: Float = 26f

        /** 小节标题与上方内容的距离（比章节标题小，层级才清楚）。 */
        const val SUB_HEADING_GAP_PT: Float = 9f

        /** 小节标题到其下第一行的距离。 */
        const val SUB_HEADING_AFTER_PT: Float = 5f

        /** 总述块的左缩进与下方留白。 */
        const val SUMMARY_INDENT_PT: Float = 11f
        const val SUMMARY_AFTER_PT: Float = 12f

        /** 要点符号占用的宽度（悬挂缩进）。 */
        const val ITEM_INDENT_PT: Float = 14f

        /** 选项行左缩进，以及字母标签占的宽度。 */
        const val OPTION_INDENT_PT: Float = 14f
        const val OPTION_LABEL_WIDTH_PT: Float = 15f

        /** 标签块宽度（标签文字 + 内边距）。 */
        const val TAG_WIDTH_PT: Float = 34f

        /** 例题上下留白。 */
        const val EXAMPLE_GAP_BEFORE_PT: Float = 4f
        const val EXAMPLE_GAP_AFTER_PT: Float = 7f

        /** 虚分割线占用高度与单段长度。 */
        const val DASH_BLOCK_PT: Float = 12f
        const val DASH_LENGTH_PT: Float = 3f
    }
}

/**
 * PDF 行内的一个排版单元。
 *
 * 三种形态就够了：一串字符（含上下标，靠 [Ink.Glyphs.offsetY] 抬落基线）、
 * 一个分式（两行小字夹一条横线）、一个根号（符号 + 被开方内容 + 顶横）。
 * 刻意不做通用公式排版引擎：复习资料里的公式只有这几种形态，多出来的能力没人用。
 */
private sealed class Ink {
    /** 宽度。 */
    abstract val width: Float

    /** 基线以上的高度。 */
    abstract val above: Float

    /** 基线以下的高度。 */
    abstract val below: Float

    /** 一串字符。 */
    class Glyphs(
        val text: String,
        val paint: Paint,
        val offsetY: Float = 0f,
    ) : Ink() {
        override val width: Float get() = paint.measureText(text)

        override val above: Float get() = paint.textSize * GLYPH_RISE - offsetY

        override val below: Float get() = paint.textSize * GLYPH_DROP + offsetY
    }

    /** 分式：分子在上、横线居中、分母在下。 */
    class Fraction(
        val numerator: String,
        val denominator: String,
        val numeratorPaint: Paint,
        val denominatorPaint: Paint,
        val rulePaint: Paint,
    ) : Ink() {
        val numeratorWidth: Float = numeratorPaint.measureText(numerator)
        val denominatorWidth: Float = denominatorPaint.measureText(denominator)

        override val width: Float get() = maxOf(numeratorWidth, denominatorWidth) + FRACTION_PADDING

        /** 分子基线的位置（相对主基线）。 */
        val numeratorBaseline: Float get() = -FRACTION_RISE

        /** 分母基线的位置。 */
        val denominatorBaseline: Float get() = denominatorPaint.textSize * FRACTION_DROP

        /** 横线的位置：压在主基线上方一点点，视觉上才是「居中」。 */
        val ruleY: Float get() = -FRACTION_RISE * 0.4f

        override val above: Float get() = FRACTION_RISE + numeratorPaint.textSize * GLYPH_RISE

        override val below: Float get() = denominatorPaint.textSize * (FRACTION_DROP + GLYPH_DROP)
    }

    /** 根号。 */
    class Radical(
        val body: String,
        val signPaint: Paint,
        val bodyPaint: Paint,
        val rulePaint: Paint,
    ) : Ink() {
        val signWidth: Float = signPaint.measureText(RADICAL_SIGN_TEXT)

        override val width: Float get() = signWidth + bodyPaint.measureText(body) + RADICAL_PADDING

        /** 顶横的位置：贴着被开方内容的字顶。 */
        val vinculumY: Float get() = -bodyPaint.textSize * VINCULUM_RISE

        override val above: Float get() = bodyPaint.textSize * VINCULUM_RISE

        override val below: Float get() = bodyPaint.textSize * GLYPH_DROP
    }

    private companion object {
        /** 常规字形的上沿与下沿（相对字号）；与 [ReviewPdfFlow] 共用同一组比例，避免两处各调各的。 */
        val GLYPH_RISE: Float = ReviewPdfFlow.GLYPH_RISE

        val GLYPH_DROP: Float = ReviewPdfFlow.GLYPH_DROP

        /** 分子基线上抬的量。 */
        const val FRACTION_RISE: Float = 3.2f

        /** 分母基线相对字号的下沉比例。 */
        const val FRACTION_DROP: Float = 0.98f

        /** 横线比数字宽出的部分。 */
        const val FRACTION_PADDING: Float = 2f

        /** 顶横距被开方内容字顶的高度（相对字号）。 */
        const val VINCULUM_RISE: Float = 0.74f

        /** 根号右侧留一点空，避免和后一个字粘住。 */
        const val RADICAL_PADDING: Float = 1f
    }
}

/** 一个排版单元落在行内的位置。 */
private class Placement(
    val ink: Ink,
    val x: Float,
)

/** 排好的一行：行高 + 各单元的位置。 */
private class PdfLine(
    val height: Float,
    val placements: List<Placement>,
)

/**
 * 「同一支 Paint 的小号副本」缓存。
 *
 * 一支 Paint 每次 `Paint(paint)` 都要复制原生对象；一页几十个上下标、十几个分式，
 * 每个都新建会把 PDF 生成时间拉长一个量级。
 */
private class PaintScaler {
    private val cache = HashMap<Paint, HashMap<Float, Paint>>()

    fun scaled(
        paint: Paint,
        scale: Float,
    ): Paint =
        cache.getOrPut(paint) { HashMap() }
            .getOrPut(scale) { Paint(paint).apply { textSize = paint.textSize * scale } }
}

/**
 * PDF 用的墨色（与屏幕配色同色相，但取更适合打印的明度）。
 *
 * 取值原则：
 * - 全部落在 30%-60% 灰度区间——黑白打印后仍互相区分，不会糊成一片浅灰；
 * - 靠色相与位置区分「标题 / 重点 / 答案 / 附注」，黑白与色盲场景都还能认出来；
 * - 不用纯黑正文（#000 印在纸上偏刺眼），用 #23212B。
 */
private object Palette {
    val INK: Int = Color.rgb(0x23, 0x21, 0x2B)

    /** 主色：深紫，与 App 主题同色系。 */
    val PRIMARY: Int = Color.rgb(0x4B, 0x3F, 0x8F)

    /** 强调色：赭石，用于「易错 / 注意」标签与例题左侧竖线。 */
    val ACCENT: Int = Color.rgb(0xA8, 0x62, 0x1B)

    /** 强调色浅底（标签背景）。 */
    val ACCENT_TINT: Int = Color.rgb(0xF7, 0xEC, 0xDE)

    /** 答案色：墨绿。 */
    val SUCCESS: Int = Color.rgb(0x1F, 0x6F, 0x4B)

    /** 思路色：紫灰，和主色同系但更亮一点。 */
    val REASONING: Int = Color.rgb(0x5B, 0x4F, 0xA8)

    /** 附注与页脚。 */
    val MUTED: Int = Color.rgb(0x6B, 0x68, 0x78)

    /** 分隔线。 */
    val RULE: Int = Color.rgb(0xD8, 0xD5, 0xE2)
}

/**
 * 标题块的纵向几何：块顶 → 基线 → 下一个块顶。
 *
 * 抽成纯函数是因为「文字重叠」这类问题在 Canvas 上只能靠肉眼发现：
 * 出了 PDF 才知道两行叠在一起，改一次要重新生成一整份资料。
 * 这里把三段推进量单独拿出来，用单测守住一条不变式——
 * **标题之后的下一个块顶必须落在整行字高的下方**，否则下一段正文一定压上来。
 */
internal object ReviewPdfFlow {
    /** 字顶相对基线的比例。与 [Ink.Glyphs] 的上沿取值保持一致。 */
    const val GLYPH_RISE: Float = 0.86f

    /** 字底相对基线的比例。 */
    const val GLYPH_DROP: Float = 0.24f

    /**
     * 标题块需要的净空（相对字号）。
     *
     * 取 1.05 而不是 1.0：中文字体里「（一）」这种全角括号的实测下缘比 0.24 略深，
     * 留 5% 余量后打印出来不会出现「括号尾巴蹭到下一行」。
     */
    const val BLOCK_CLEARANCE_RATIO: Float = 1.05f

    /** 块顶 -> 基线：让出字顶的高度。 */
    fun baselineOf(
        top: Float,
        textSize: Float,
    ): Float = top + textSize * GLYPH_RISE

    /** 基线 -> 下一个块顶：先让出整行字高，再叠加段后间距。 */
    fun nextTopAfter(
        baseline: Float,
        textSize: Float,
        after: Float,
    ): Float = baseline + textSize * BLOCK_CLEARANCE_RATIO + after
}
