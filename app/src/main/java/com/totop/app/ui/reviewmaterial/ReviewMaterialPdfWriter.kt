package com.totop.app.ui.reviewmaterial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/**
 * 把复习资料排成可打印的 A4 PDF。
 *
 * 为什么用原生 [PdfDocument] 而不是 WebView 打印：
 * 1. 项目硬性约束「不引入第三方 SDK」「离线可用」，WebView 方案需要额外 HTML 模板资产；
 * 2. 原生方案在低端机上内存占用可控，且不依赖系统 WebView 版本；
 * 3. 打印与分享都能直接拿到 PDF 文件，链路最短。
 *
 * 版面约定（A4 纵向，72 dpi 的 PostScript 点）：
 * - 页面 595 × 842 pt；
 * - 左右边距 46pt，上下 52pt；
 * - 正文 11.5pt、行距 1.55 倍（打印后肉眼清晰，一页约 40 行）；
 * - 标题 17pt、章节标题 13pt，均为**加粗黑字**；
 * - 强调一律用加粗，不用颜色：黑白打印机是默认场景，靠颜色区分重点会全部变成浅灰；
 * - 超过一页自动换页，绝不截断内容，页脚标页码。
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
                val doc = PdfDocument()
                val painter = PagePainter(doc)
                painter.newPage()
                painter.title(material.subjectLabel + " · 考前复习资料")
                painter.note(sourceNote(material))
                painter.rule()
                if (material.summary.isNotBlank()) {
                    painter.paragraph(material.summary)
                    painter.gap(2f)
                }
                material.sections.forEachIndexed { index, section ->
                    painter.heading(ReviewText.sectionHeading(index + 1, section.title))
                    section.blocks.forEach { block -> paintBlock(painter, block) }
                }
                painter.finish()

                // 先写 cacheDir 的临时文件：这是整个渲染过程的事务性落点，
                // 写失败时不会在用户可见目录里留下半个 PDF。
                // 真正的「发布到下载目录 + 生成可分享 Uri」由 ExportSink 负责。
                val dir = File(context.cacheDir, PDF_DIR).apply { if (!exists()) mkdirs() }
                val file = File(dir, FILE_STEM + System.currentTimeMillis() + ".pdf")
                file.outputStream().use { out -> doc.writeTo(out) }
                doc.close()
                PdfWriteResult.Success(file)
            }.getOrElse { failure ->
                PdfWriteResult.Failure(failure.message ?: failure.javaClass.simpleName)
            }
        }

    /** 一个内容块：段落逐行写，要点加「· 」，编号步骤加「1. 」，例题的答案用小字。 */
    private fun paintBlock(
        painter: PagePainter,
        block: ReviewBlock,
    ) {
        when (block.style) {
            ReviewBlockStyle.PARAGRAPH ->
                block.lines.forEach { line ->
                    painter.paragraph(line)
                    painter.gap(3f)
                }

            ReviewBlockStyle.BULLETS ->
                block.lines.forEach { line ->
                    painter.item(BULLET_PREFIX, line)
                    painter.gap(2f)
                }

            ReviewBlockStyle.NUMBERED ->
                block.lines.forEachIndexed { index, line ->
                    painter.item((index + 1).toString() + ". ", line)
                    painter.gap(2f)
                }

            // 例题：题干与思路用正文字号，答案单独缩到小字，方便做完再核对
            ReviewBlockStyle.EXAMPLE ->
                painter.exampleFigure(
                    stem = block.lines.first(),
                    notes = block.lines.drop(1),
                )
        }
        painter.gap(4f)
    }

    /** 卷首说明：这份资料到底从哪来，避免看完不知道依据。 */
    private fun sourceNote(material: ReviewMaterial): String {
        val origin =
            if (material.mistakesUsed > 0) {
                "依据本科目 " + material.mistakesUsed + " 道错题整理"
            } else {
                "本科目暂无错题记录，按该科目通用考点整理"
            }
        val date =
            if (material.createdAtUtc > 0L) {
                SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(material.createdAtUtc))
            } else {
                ""
            }
        return if (date.isEmpty()) origin else origin + " · 生成于 " + date
    }

    companion object {
        private const val PDF_DIR: String = "review_material"

        /** 导出文件名前缀。 */
        const val FILE_STEM: String = "考前复习资料_"

        /** 无序要点的行首符号。 */
        private const val BULLET_PREFIX: String = "· "
    }
}

sealed class PdfWriteResult {
    data class Success(val file: File) : PdfWriteResult()

    data class Failure(val reason: String) : PdfWriteResult()
}

/**
 * 简易排版器：维护当前页与 y 坐标，写满自动换页。
 *
 * 支持「标题 / 章节标题 / 段落 / 要点 / 间隔 / 分隔线」六种操作，
 * 加上行内加粗（`**…**`）。刻意不引入完整排版引擎：
 * 复习资料只有这几种版式，多出来的能力没人用，还得自己养。
 */
private class PagePainter(private val doc: PdfDocument) {
    private val pageWidth = 595
    private val pageHeight = 842
    private val marginX = 46f
    private val marginTop = 52f
    private val marginBottom = 52f

    /** 版心右边界，折行时用它判断「还能放多宽」。 */
    private val contentRight: Float get() = pageWidth - marginX

    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y: Float = marginTop
    private var pageNumber = 0

    private val titlePaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 17f
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val headingPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 13f
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val bodyPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 11.5f
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

    /** 行内加粗：与正文同字号同字体，只换字重，避免打印后行距忽松忽紧。 */
    private val bodyBoldPaint =
        Paint(bodyPaint).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    private val notePaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 9.5f
            color = Color.rgb(0x55, 0x55, 0x55)
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

    /** 例题答案用的小字：比正文小 1.5 磅，做完题再看也不至于一眼扫到结果。 */
    private val smallPaint =
        Paint(bodyPaint).apply {
            textSize = 10f
        }

    private val smallBoldPaint =
        Paint(smallPaint).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

    /**
     * 这台设备的有衬线字体有没有下标字符。
     *
     * 有就直接排下标（C₈H₁₆O₄），没有就退回普通数字（C8H16O4）——
     * 缺字形时 PDF 会印成空心方块，比没有下标难看十倍。
     */
    private val subscriptSupported: Boolean by lazy { bodyPaint.hasGlyph("₈") }

    private val rulePaint =
        Paint().apply {
            isAntiAlias = true
            strokeWidth = 0.7f
            color = Color.rgb(0x66, 0x66, 0x66)
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
    }

    fun title(text: String) {
        ensureSpace(TITLE_BLOCK_PT)
        canvas?.drawText(text, marginX, y, titlePaint)
        y += TITLE_BLOCK_PT
    }

    fun note(text: String) {
        ensureSpace(NOTE_BLOCK_PT)
        canvas?.drawText(text, marginX, y, notePaint)
        y += NOTE_BLOCK_PT
    }

    /** 章节标题：加粗，下方一条细线，方便打印后快速定位。 */
    fun heading(text: String) {
        ensureSpace(HEADING_BLOCK_PT)
        y += HEADING_GAP_PT
        canvas?.drawText(text, marginX, y, headingPaint)
        y += HEADING_RULE_GAP_PT
        canvas?.drawLine(marginX, y, contentRight, y, rulePaint)
        y += HEADING_AFTER_PT
    }

    /** 分隔线（卷首用）。 */
    fun rule() {
        ensureSpace(RULE_BLOCK_PT)
        y += RULE_BEFORE_PT
        canvas?.drawLine(marginX, y, contentRight, y, rulePaint)
        y += RULE_AFTER_PT
    }

    /** 普通段落：整段折行，行内 `**…**` 加粗。 */
    fun paragraph(text: String) {
        writeRich(ReviewText.splitBold(text), firstIndent = 0f, restIndent = 0f, paint = bodyPaint)
    }

    /**
     * 例题：题干带「例：」，随后思路用正文、答案缩成小字。
     *
     * @param notes 思路与答案行；以「答案」开头的行自动用小字
     */
    fun exampleFigure(
        stem: String,
        notes: List<String>,
    ) {
        ensureSpace(EXAMPLE_BLOCK_PT)
        y += 4f
        val stemRuns =
            buildList {
                add(ReviewTextRun(EXAMPLE_PREFIX, true))
                addAll(ReviewText.splitBold(stem))
            }
        writeRich(stemRuns, firstIndent = 0f, restIndent = 0f, paint = bodyPaint)
        notes.forEach { line ->
            if (ReviewText.isAnswerLine(line)) {
                writeRich(
                    ReviewText.splitBold(line),
                    firstIndent = EXAMPLE_INDENT_PT,
                    restIndent = EXAMPLE_INDENT_PT,
                    paint = smallPaint,
                    boldPaint = smallBoldPaint,
                )
            } else {
                writeRich(
                    ReviewText.splitBold(line),
                    firstIndent = EXAMPLE_INDENT_PT,
                    restIndent = EXAMPLE_INDENT_PT,
                    paint = bodyPaint,
                )
            }
        }
        y += 4f
    }

    /** 要点：行首符号或序号 + 悬挂缩进，折行后与正文左对齐。 */
    fun item(
        prefix: String,
        text: String,
    ) {
        val runs =
            buildList {
                add(ReviewTextRun(prefix, false))
                addAll(ReviewText.splitBold(text))
            }
        val indent = bodyPaint.measureText(prefix)
        writeRich(runs, firstIndent = 0f, restIndent = indent, paint = bodyPaint)
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
            // 页脚页码：四五张纸订在一起，散页后能对回顺序
            canvas?.drawText(
                "第 " + pageNumber + " 页",
                marginX,
                pageHeight - 28f,
                notePaint,
            )
            runCatching { doc.finishPage(openPage) }
        }
        page = null
        canvas = null
    }

    /**
     * 写一段自动折行的富文本。
     *
     * 逐片段（普通/加粗）排：一个片段在当前行放不下就截断换行，
     * 段与段之间不额外换行——一句话里两处加粗，仍然是一行文字。
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
        val lineHeight = paint.textSize * LINE_HEIGHT_RATIO
        ensureSpace(lineHeight)
        var x = marginX + firstIndent
        var lineStartX = x
        runs.forEach { run ->
            val runPaint = if (run.bold) boldPaint else paint
            var rest = prepareScripts(run.text)
            while (rest.isNotEmpty()) {
                var count = runPaint.breakText(rest, true, contentRight - x, null)
                if (count <= 0) {
                    if (x > lineStartX) {
                        y += lineHeight
                        ensureSpace(lineHeight)
                        x = marginX + restIndent
                        lineStartX = x
                        continue
                    }
                    // 行首也放不下（极窄残行）：至少画一个字，避免死循环
                    count = 1
                }
                canvas?.drawText(rest, 0, count, x, y, runPaint)
                x += runPaint.measureText(rest, 0, count)
                rest = rest.drop(count)
                if (rest.isNotEmpty()) {
                    y += lineHeight
                    ensureSpace(lineHeight)
                    x = marginX + restIndent
                    lineStartX = x
                }
            }
        }
        y += lineHeight
    }

    /** 字体不支持下标时把下标字符退回普通数字，避免印成方块。 */
    private fun prepareScripts(text: String): String {
        if (subscriptSupported || text.isEmpty()) return text
        val builder = StringBuilder(text.length)
        text.forEach { ch -> builder.append(SUBSCRIPT_TO_DIGIT[ch] ?: ch) }
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

        /** 行高相对字号的倍数：1.55 在 A4 上中文不挤，行距也不松散。 */
        const val LINE_HEIGHT_RATIO: Float = 1.55f

        /** 主标题占用的竖向高度（含与下一行的间距）。 */
        const val TITLE_BLOCK_PT: Float = 30f

        /** 卷首小字说明占用的竖向高度。 */
        const val NOTE_BLOCK_PT: Float = 16f

        /** 章节标题整体占用高度：上方留白 + 标题 + 细线 + 下方留白。 */
        const val HEADING_BLOCK_PT: Float = 38f

        /** 章节标题与上一段之间的留白。 */
        const val HEADING_GAP_PT: Float = 12f

        /** 章节标题基线到细线的距离。 */
        const val HEADING_RULE_GAP_PT: Float = 7f

        /** 细线到正文首行的距离。 */
        const val HEADING_AFTER_PT: Float = 13f

        /** 卷首分隔线整体占用高度。 */
        const val RULE_BLOCK_PT: Float = 14f

        /** 分隔线之前 / 之后的留白。 */
        const val RULE_BEFORE_PT: Float = 6f
        const val RULE_AFTER_PT: Float = 8f

        /** 例题最低占用高度（题干一行 + 至少两行附注）。 */
        const val EXAMPLE_BLOCK_PT: Float = 54f

        /** 例题附注的左缩进。 */
        const val EXAMPLE_INDENT_PT: Float = 14f
    }
}
