package com.totop.app.ui.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 渲染产物。 */
data class RenderedReport(
    val file: File,
    val format: ReportExportFormat,
    val sizeBytes: Long,
) {
    /** 绝对路径（分享时交给 FileProvider）。 */
    val displayPath: String get() = file.absolutePath
}

/** 报告导出格式。 */
enum class ReportExportFormat(val label: String, val extension: String, val mimeType: String) {
    PNG("长图（PNG）", "png", "image/png"),
    PDF("PDF（多页）", "pdf", "application/pdf"),
}

/**
 * 周报的本地长图 / PDF 渲染（FR-5.5）。
 *
 * 为什么要自己画：
 * - 报告是纯文本 + 六项指标，用 Android 原生 [Canvas] 与 [StaticLayout] 排版即可，
 *   不需要引入任何渲染库（包体门禁 30MB），也不联网；
 * - 长图（PNG）适合发到班级群或家长微信；PDF 适合打印或存档。
 *
 * 渲染内容就是页面上的 Markdown 正文（数字由模板渲染，AI 解读单独成段并带标识），
 * 因此导出的图与屏幕上看到的内容**完全一致**，不存在第二套排版。
 *
 * 写盘位置：`filesDir/exports/`，由 FileProvider（已在 manifest 中声明）暴露给分享。
 */
@Singleton
class ReportImageRenderer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): ReportImageRenderer {
            this.dispatcher = dispatcher
            return this
        }

        /** 渲染长图（单张 PNG，高度随内容增长，宽度固定 [MAX_WIDTH_PX]）。 */
        suspend fun renderLongImage(
            title: String,
            markdown: String,
            fileNameStem: String,
            nowUtcMillis: Long = System.currentTimeMillis(),
        ): RenderedReport =
            withContext(dispatcher) {
                val blocks = ReportTextBlocks.parse(markdown)
                val width = MAX_WIDTH_PX
                val contentHeight = measureHeight(blocks = blocks, width = width)
                val height = contentHeight.coerceIn(MIN_HEIGHT_PX, MAX_HEIGHT_PX)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                drawBlocks(canvas = canvas, title = title, blocks = blocks, width = width, height = height)
                val file = exportFile(fileNameStem, ReportExportFormat.PNG, nowUtcMillis)
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                bitmap.recycle()
                RenderedReport(file = file, format = ReportExportFormat.PNG, sizeBytes = file.length())
            }

        /** 渲染多页 PDF（A4 纵向，按内容自动分页）。 */
        suspend fun renderPdf(
            title: String,
            markdown: String,
            fileNameStem: String,
            nowUtcMillis: Long = System.currentTimeMillis(),
        ): RenderedReport =
            withContext(dispatcher) {
                val blocks = ReportTextBlocks.parse(markdown)
                val document = PdfDocument()
                val paint = bodyPaint()
                val titlePaint = titlePaint()
                var pageNumber = 1
                var page = document.startPage(PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create())
                var canvas = page.canvas
                var y = MARGIN_PX + TITLE_SIZE_PX
                canvas.drawText(title, MARGIN_PX.toFloat(), y.toFloat(), titlePaint)
                y += TITLE_SIZE_PX + BLOCK_GAP_PX

                val maxWidth = PDF_WIDTH - MARGIN_PX * 2
                blocks.forEach { block ->
                    val paintForBlock = paintFor(block.kind, paint, titlePaint)
                    val layout = layoutOf(block.text, paintForBlock, maxWidth)
                    val blockHeight = layout.height
                    if (y + blockHeight > PDF_HEIGHT - MARGIN_PX) {
                        document.finishPage(page)
                        pageNumber++
                        page =
                            document.startPage(
                                PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create(),
                            )
                        canvas = page.canvas
                        y = MARGIN_PX + TITLE_SIZE_PX
                    }
                    canvas.save()
                    canvas.translate(MARGIN_PX.toFloat(), y.toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    y += blockHeight + BLOCK_GAP_PX
                }
                document.finishPage(page)
                val file = exportFile(fileNameStem, ReportExportFormat.PDF, nowUtcMillis)
                FileOutputStream(file).use { out -> document.writeTo(out) }
                document.close()
                RenderedReport(file = file, format = ReportExportFormat.PDF, sizeBytes = file.length())
            }

        private fun measureHeight(
            blocks: List<ReportTextBlocks.Block>,
            width: Int,
        ): Int {
            var height: Int = (MARGIN_PX + TITLE_SIZE_PX).toInt() + BLOCK_GAP_PX
            val paint = bodyPaint()
            val titleP = titlePaint()
            blocks.forEach { block ->
                val layout = layoutOf(block.text, paintFor(block.kind, paint, titleP), width - MARGIN_PX * 2)
                height += layout.height + BLOCK_GAP_PX.toInt()
            }
            return height
        }

        private fun drawBlocks(
            canvas: Canvas,
            title: String,
            blocks: List<ReportTextBlocks.Block>,
            width: Int,
            height: Int,
        ) {
            val titleP = titlePaint()
            val paint = bodyPaint()
            var y = MARGIN_PX + TITLE_SIZE_PX
            canvas.drawText(title, MARGIN_PX.toFloat(), y.toFloat(), titleP)
            y += TITLE_SIZE_PX + BLOCK_GAP_PX
            blocks.forEach { block ->
                val layout = layoutOf(block.text, paintFor(block.kind, paint, titleP), width - MARGIN_PX * 2)
                if (y + layout.height <= height - MARGIN_PX) {
                    canvas.save()
                    canvas.translate(MARGIN_PX.toFloat(), y.toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    y += layout.height + BLOCK_GAP_PX
                }
            }
            canvas.drawText(
                "由 To Top 在本机生成 · 数据未上传",
                MARGIN_PX.toFloat(),
                (height - MARGIN_PX / 2).toFloat(),
                footerPaint(),
            )
        }

        private fun layoutOf(
            text: String,
            paint: TextPaint,
            width: Int,
        ): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(LINE_SPACING_EXTRA, LINE_SPACING_MULT)
                .setIncludePad(false)
                .build()

        private fun paintFor(
            kind: ReportTextBlocks.Kind,
            body: TextPaint,
            title: TextPaint,
        ): TextPaint =
            when (kind) {
                ReportTextBlocks.Kind.TITLE -> title
                ReportTextBlocks.Kind.HEADING ->
                    TextPaint(body).apply {
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textSize = HEADING_SIZE_PX
                    }
                ReportTextBlocks.Kind.QUOTE -> TextPaint(body).apply { color = Color.rgb(0x75, 0x78, 0x73) }
                ReportTextBlocks.Kind.BODY -> body
            }

        private fun bodyPaint(): TextPaint =
            TextPaint().apply {
                isAntiAlias = true
                color = BODY_TEXT_RGB
                textSize = BODY_SIZE_PX
                typeface = Typeface.DEFAULT
            }

        private fun titlePaint(): TextPaint =
            TextPaint().apply {
                isAntiAlias = true
                color = BODY_TEXT_RGB
                textSize = TITLE_SIZE_PX
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

        private fun footerPaint(): TextPaint =
            TextPaint().apply {
                isAntiAlias = true
                color = FOOTER_TEXT_RGB
                textSize = FOOTER_SIZE_PX
            }

        private fun exportFile(
            stem: String,
            format: ReportExportFormat,
            nowUtcMillis: Long,
        ): File {
            val dir = File(context.filesDir, EXPORT_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("无法创建导出目录：" + dir.absolutePath)
            }
            val safeStem = sanitize(stem)
            return File(dir, safeStem + "_" + nowUtcMillis + "." + format.extension)
        }

        companion object {
            /** 长图宽度（px，约 1080 宽的手机屏比例，便于微信查看）。 */
            const val MAX_WIDTH_PX: Int = 1080

            /** 长图最小高度。 */
            const val MIN_HEIGHT_PX: Int = 640

            /** 长图最大高度（避免超大 Bitmap OOM；超出时内容截断，建议改用 PDF）。 */
            const val MAX_HEIGHT_PX: Int = 12000

            /** A4 纵向 @72dpi 的像素宽度。 */
            const val PDF_WIDTH: Int = 595

            /** A4 纵向 @72dpi 的像素高度。 */
            const val PDF_HEIGHT: Int = 842

            private const val MARGIN_PX = 56
            private const val BLOCK_GAP_PX = 12
            private const val TITLE_SIZE_PX = 34f

            /** 正文文字色。 */
            private const val BODY_TEXT_RGB: Int = 0x1B1C1A

            /** 页脚文字色。 */
            private const val FOOTER_TEXT_RGB: Int = 0x9AA0A6
            private const val HEADING_SIZE_PX = 26f
            private const val BODY_SIZE_PX = 22f
            private const val FOOTER_SIZE_PX = 18f
            private const val LINE_SPACING_MULT = 1.15f
            private const val LINE_SPACING_EXTRA = 4f

            /** 导出子目录（与 FileProvider 的 exports 路径一致）。 */
            const val EXPORT_DIR: String = "exports"

            /** 文件名清洗：空白回退为 report，其余非法字符替换为下划线。 */
            fun sanitize(stem: String): String {
                if (stem.isBlank()) return "report"
                val builder = StringBuilder(stem.length)
                stem.forEach { ch ->
                    val safe = ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch.code > 0x7F && ch != '/'
                    builder.append(if (safe) ch else '_')
                }
                val cleaned = builder.toString().trim('_')
                return cleaned.ifBlank { "report" }
            }
        }
    }

/**
 * Markdown 文本块解析（只处理报告模板会产出的四种块）。
 *
 * 纯函数：`# ` 标题、`## ` 小节、`> ` 引用、其余为正文；空行不产生块。
 */
object ReportTextBlocks {
    /** 块类型。 */
    enum class Kind { TITLE, HEADING, QUOTE, BODY }

    /** 文本块。 */
    data class Block(val kind: Kind, val text: String)

    /** 解析。 */
    fun parse(markdown: String): List<Block> {
        val blocks = ArrayList<Block>()
        markdown.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("# ") -> blocks += Block(Kind.TITLE, line.removePrefix("# ").trim())
                line.startsWith("## ") -> blocks += Block(Kind.HEADING, line.removePrefix("## ").trim())
                line.startsWith("> ") -> blocks += Block(Kind.QUOTE, line.removePrefix("> ").trim())
                else -> blocks += Block(Kind.BODY, line.trim())
            }
        }
        return blocks
    }

    /** 纯文本形式（分享时的文字说明）。 */
    fun toPlainText(markdown: String): String = parse(markdown).joinToString(LINE_BREAK) { it.text }

    private const val LINE_BREAK: String = "\n"
}
