package com.totop.app.ui.reviewmaterial

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

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
 * - 左右边距 48pt，上下 56pt；
 * - 正文 10.5pt，标题 15/12pt，行距 1.55 倍；
 * - 超过一页自动换页，绝不截断内容。
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
                painter.title(material.subjectLabel + " · " + material.examDate)
                painter.body(material.summary)
                painter.gap(10f)

                if (material.weakKpoints.isNotEmpty()) {
                    painter.heading("还需要多练的知识点")
                    material.weakKpoints.forEach { kp ->
                        painter.body("· " + kp.name + "（错过 " + kp.mistakeCount + " 次）")
                        if (kp.reminder.isNotBlank()) painter.muted("   " + kp.reminder)
                    }
                    painter.gap(8f)
                }

                if (material.typicalMistakes.isNotEmpty()) {
                    painter.heading("典型错题")
                    material.typicalMistakes.forEachIndexed { index, item ->
                        painter.body((index + 1).toString() + ". " + item.stem)
                        painter.muted("错在哪：" + item.errorSummary)
                        painter.muted("正确思路：" + item.correctIdea)
                        painter.gap(6f)
                    }
                }

                if (material.exercises.isNotEmpty()) {
                    painter.heading("同类练习")
                    material.exercises.forEachIndexed { index, item ->
                        painter.body((index + 1).toString() + ". " + item.stem)
                        painter.muted("答案：" + item.answer)
                        painter.muted("解析：" + item.analysis)
                        painter.gap(6f)
                    }
                }

                if (material.tips.isNotEmpty()) {
                    painter.heading("考前建议")
                    material.tips.forEach { painter.body("· " + it) }
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

    companion object {
        private const val PDF_DIR: String = "review_material"

        /** 导出文件名前缀。 */
        const val FILE_STEM: String = "考前复习资料_"
    }
}

sealed class PdfWriteResult {
    data class Success(val file: File) : PdfWriteResult()

    data class Failure(val reason: String) : PdfWriteResult()
}

/**
 * 简易排版器：维护当前页与 y 坐标，写满自动换页。
 *
 * 刻意只支持「标题 / 正文 / 灰字 / 间隔」四种操作，
 * 避免为一个功能引入完整排版引擎。
 */
private class PagePainter(private val doc: PdfDocument) {
    private val pageWidth = 595
    private val pageHeight = 842
    private val marginX = 48f
    private val marginTop = 56f
    private val marginBottom = 56f

    private var page: PdfDocument.Page? = null
    private var canvas: android.graphics.Canvas? = null
    private var y: Float = marginTop
    private var pageNumber = 0

    private val titlePaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 15f
            // Compose 的 Color 不能直接给 android.graphics.Paint 用，这里写等值常量
            color = android.graphics.Color.rgb(0x6D, 0x5B, 0xD0)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        }

    private val headingPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 12f
            color = android.graphics.Color.rgb(0x6D, 0x5B, 0xD0)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        }

    private val bodyPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 10.5f
            color = android.graphics.Color.rgb(0x1C, 0x1B, 0x22)
        }

    private val mutedPaint =
        Paint().apply {
            isAntiAlias = true
            textSize = 9.5f
            color = android.graphics.Color.rgb(0x69, 0x66, 0x7A)
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
        ensureSpace(24f)
        canvas?.drawText(text, marginX, y, titlePaint)
        y += 24f
    }

    fun heading(text: String) {
        ensureSpace(28f)
        y += 6f
        canvas?.drawText(text, marginX, y, headingPaint)
        y += 18f
    }

    fun body(text: String) = writeWrapped(text, bodyPaint)

    fun muted(text: String) = writeWrapped(text, mutedPaint)

    fun gap(size: Float) {
        y += size
    }

    fun finish() {
        finishPage()
    }

    private fun finishPage() {
        page?.let { runCatching { doc.finishPage(it) } }
        page = null
        canvas = null
    }

    /** 写一段自动折行的文字；超出页面则换页继续。 */
    private fun writeWrapped(
        text: String,
        paint: Paint,
    ) {
        val maxWidth = pageWidth - marginX * 2
        val lineHeight = paint.textSize * 1.55f
        // 先按显式换行切段，再对每段做宽度折行
        text.split("\n").forEach { paragraph ->
            var rest = paragraph.ifBlank { " " }
            while (rest.isNotEmpty()) {
                ensureSpace(lineHeight)
                val count = paint.breakText(rest, true, maxWidth, null)
                val sliceEnd = if (count <= 0) rest.length.coerceAtMost(1) else count
                canvas?.drawText(rest.substring(0, sliceEnd), marginX, y, paint)
                y += lineHeight
                rest = rest.substring(sliceEnd)
            }
        }
    }

    private fun ensureSpace(needed: Float) {
        if (y + needed > pageHeight - marginBottom) newPage()
    }
}
