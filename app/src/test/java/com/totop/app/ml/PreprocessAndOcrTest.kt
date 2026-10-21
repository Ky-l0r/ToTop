package com.totop.app.ml

import com.totop.app.util.ImageMath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 图片预处理中纯计算部分的测试（开发方案 3.2 第 [2] 步 / 3.4）。
 *
 * 只覆盖不依赖 android.graphics 的部分：尺寸计算（复用 ImageMath）、
 * sha256 命名与目录规则。真正的解码/旋转在仪器测试与人工验收中验证。
 */
class ImagePreprocessSizeTest {
    @ParameterizedTest(name = "{0}x{1} -> {2}x{3}（长边 <= 1280）")
    @CsvSource(
        "4000, 3000, 1280, 960",
        "3000, 4000, 960, 1280",
        "1920, 1080, 1280, 720",
        "1280, 1280, 1280, 1280",
        "4032, 3024, 1280, 960",
        "1, 5000, 1, 1280",
    )
    @DisplayName("预处理目标尺寸：保持比例，长边压到 1280，小图不放大")
    fun targetSize(
        srcW: Int,
        srcH: Int,
        expW: Int,
        expH: Int,
    ) {
        val (w, h) = ImageMath.computeTargetSize(srcW, srcH, MAX_LONG_SIDE)
        assertEquals(expW, w)
        assertEquals(expH, h)
        assertTrue(maxOf(w, h) <= MAX_LONG_SIDE, "长边必须 <= 1280，实际：" + maxOf(w, h))
    }

    @Test
    @DisplayName("手机原始照片（4032x3024）压到 1280 长边后仍是 4:3")
    fun aspectRatioKept() {
        val (w, h) = ImageMath.computeTargetSize(4032, 3024, MAX_LONG_SIDE)
        val sourceRatio = 4032.0 / 3024.0
        val targetRatio = w.toDouble() / h.toDouble()
        assertTrue(kotlin.math.abs(sourceRatio - targetRatio) < 0.01, "宽高比应保持，实际 " + targetRatio)
    }

    @Test
    @DisplayName("缩略图长边 320，主图长边 1280")
    fun thumbnailSize() {
        val (tw, th) = ImageMath.computeTargetSize(1280, 960, ImagePreprocess.THUMBNAIL_MAX_LONG_SIDE)
        assertEquals(320, tw)
        assertEquals(240, th)
        assertTrue(ImagePreprocess.THUMBNAIL_MAX_LONG_SIDE < MAX_LONG_SIDE)
        assertTrue(ImagePreprocess.THUMBNAIL_QUALITY in 1..100)
    }

    @Test
    @DisplayName("降采样率是 2 的幂且不会小于 1")
    fun sampleSize() {
        for (width in listOf(4000, 4032, 6000, 1280, 800)) {
            val sample = ImageMath.computeInSampleSize(width, width, MAX_LONG_SIDE)
            assertTrue(sample >= 1)
            assertTrue((sample and (sample - 1)) == 0, "必须是 2 的幂：" + sample)
        }
    }

    @Test
    @DisplayName("EXIF 旋转后的宽高互换：90/270 交换，0/180 不交换")
    fun exifSwap() {
        assertEquals(960 to 1280, ImageMath.swappedIfRotated(1280, 960, 90))
        assertEquals(1280 to 960, ImageMath.swappedIfRotated(1280, 960, 180))
        assertEquals(960 to 1280, ImageMath.swappedIfRotated(1280, 960, 270))
        assertEquals(90, ImageMath.rotationDegreesOf(6))
        assertEquals(0, ImageMath.rotationDegreesOf(1))
    }

    private companion object {
        /** 与 ImageEncoderConfig.DEFAULT.maxLongSide 保持一致（3.4 成本控制）。 */
        const val MAX_LONG_SIDE = 1280
    }
}

/** sha256 命名与目录规则测试（同图去重的基础）。 */
class PreprocessPathsTest {
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    @DisplayName("主图与缩略图命名：<sha256>.jpg / <sha256>_thumb.jpg")
    fun naming() {
        assertEquals(sha + ".jpg", PreprocessPaths.fileNameFor(sha))
        assertEquals(sha + "_thumb.jpg", PreprocessPaths.thumbnailNameFor(sha))
        assertEquals("images/" + sha + ".jpg", PreprocessPaths.relativeImagePathFor(sha))
        assertEquals("images/thumbs/" + sha + "_thumb.jpg", PreprocessPaths.relativeThumbnailPathFor(sha))
        assertTrue(PreprocessPaths.isSha256(sha))
    }

    @Test
    @DisplayName("非法 sha256 被拒绝，防止路径穿越")
    fun rejectsInvalidSha() {
        assertThrows(IllegalArgumentException::class.java) { PreprocessPaths.fileNameFor("../evil") }
        assertThrows(IllegalArgumentException::class.java) { PreprocessPaths.fileNameFor("") }
        assertThrows(IllegalArgumentException::class.java) { PreprocessPaths.fileNameFor(sha.uppercase()) }
        assertFalse(PreprocessPaths.isSha256("abc"))
    }

    @Test
    @DisplayName("缩略图路径由主图路径推导，与目录常量一致")
    fun thumbnailSibling() {
        val image = java.io.File("/data/user/0/com.totop.app/files/images/" + sha + ".jpg")
        val thumb = PreprocessPaths.thumbnailSiblingOf(image)
        assertEquals(sha + "_thumb.jpg", thumb.name)
        assertEquals("thumbs", thumb.parentFile?.name)
        assertTrue(thumb.parentFile?.name == PreprocessPaths.THUMBNAIL_DIR.substringAfterLast('/'))
    }

    @Test
    @DisplayName("目录常量与 FileProvider 的 file_paths.xml 一致")
    fun constants() {
        assertEquals("images", PreprocessPaths.IMAGE_DIR)
        assertEquals("images/thumbs", PreprocessPaths.THUMBNAIL_DIR)
        assertEquals(".jpg", PreprocessPaths.JPEG_SUFFIX)
        assertEquals(64, PreprocessPaths.SHA256_LENGTH)
    }
}

/** OCR 结果与 hasFigure 启发式判定的纯函数测试（开发方案 3.2 第 [4] 步）。 */
class OcrHeuristicsTest {
    private fun line(
        text: String,
        confidence: Double,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = OcrTextLine(text = text, confidence = confidence, box = RectBox(left, top, right, bottom))

    @Test
    @DisplayName("排版紧凑、置信度高的印刷体判定为无图")
    fun printedWithoutFigure() {
        val lines =
            (0 until 8).map { index ->
                line("已知函数 第" + index + "行内容", 0.95, 100, 100 + index * 60, 900, 140 + index * 60)
            }
        val analysis = OcrFigureHeuristics.analyze(lines, pageWidth = 1000, pageHeight = 1000)
        assertFalse(analysis.hasFigure, "紧凑排版不应判定含图，得分：" + analysis.score)
        assertEquals(0.0, analysis.lowConfidenceRatio, 0.0001)
        assertFalse(analysis.hasFigureSymbol)
    }

    @Test
    @DisplayName("中间存在大块空白带（图形位置）判定为含图")
    fun blankBandImpliesFigure() {
        val lines =
            listOf(
                line("如图，在三角形中", 0.95, 100, 100, 900, 140),
                line("求角 A 的大小", 0.95, 100, 180, 900, 220),
                // 220 -> 700 是一大块空白（可能是几何图）
                line("解：由题意得", 0.95, 100, 700, 900, 740),
            )
        val analysis = OcrFigureHeuristics.analyze(lines, pageWidth = 1000, pageHeight = 1000)
        assertTrue(analysis.blankBandRatio >= OcrFigureHeuristics.BLANK_BAND_HEIGHT_RATIO)
        assertTrue(analysis.hasFigure, "大块空白应判定含图，得分：" + analysis.score)
    }

    @Test
    @DisplayName("图形符号与「如图」关键词单独也能拉高得分")
    fun figureSymbols() {
        val lines =
            listOf(
                line("△ABC 中", 0.95, 100, 100, 900, 140),
                line("∠A = 60 度", 0.95, 100, 160, 900, 200),
            )
        val analysis = OcrFigureHeuristics.analyze(lines, pageWidth = 1000, pageHeight = 1000)
        assertTrue(analysis.hasFigureSymbol)
        assertTrue(analysis.score >= 0.2)
    }

    @Test
    @DisplayName("低置信度行占比达标也算信号")
    fun lowConfidenceSignal() {
        val lines =
            listOf(
                line("模糊文字一", 0.30, 100, 100, 900, 140),
                line("模糊文字二", 0.35, 100, 160, 900, 200),
                line("清晰文字", 0.95, 100, 220, 900, 260),
            )
        val analysis = OcrFigureHeuristics.analyze(lines, pageWidth = 1000, pageHeight = 1000)
        assertTrue(analysis.lowConfidenceRatio >= OcrFigureHeuristics.LOW_CONFIDENCE_RATIO)
        assertTrue(analysis.score > 0.0)
    }

    @Test
    @DisplayName("一个文字都没有时按整页空白处理，交给路由的视觉兜底")
    fun noLines() {
        val analysis = OcrFigureHeuristics.analyze(emptyList(), pageWidth = 1000, pageHeight = 1000)
        assertEquals(1.0, analysis.blankBandRatio, 0.0001)
        assertFalse(analysis.hasFigure, "证据不足时不强行判定含图，得分：" + analysis.score)
        assertEquals(FigureAnalysis.NONE, OcrFigureHeuristics.analyze(emptyList(), 0, 0))
    }

    @Test
    @DisplayName("OcrResult 转路由快照：文本、置信度、含图逐一传导")
    fun toSnapshot() {
        val result =
            OcrResult(
                text = "已知函数 f(x)=x^2",
                avgConfidence = 0.92,
                hasFigure = false,
                lines = emptyList(),
            )
        val snapshot = result.toSnapshot(isHandwritten = true)
        assertEquals(result.text, snapshot.text)
        assertEquals(0.92, snapshot.avgConfidence, 0.0001)
        assertFalse(snapshot.hasFigure)
        assertTrue(snapshot.isHandwritten)
        assertTrue(snapshot.hasUsableText)
        assertEquals(OcrResult.LANGUAGE_ZH_EN, result.language)
        assertTrue(OcrResult.EMPTY.text.isEmpty())
        assertFalse(OcrResult.EMPTY.hasUsableText)
    }

    @Test
    @DisplayName("置信度归一化：0..100 的返回值也能正确裁剪")
    fun confidenceNormalization() {
        assertEquals(0.9, OcrTextLine("x", 0.9, RectBox(0, 0, 1, 1)).normalizedConfidence, 0.0001)
        assertEquals(0.8, OcrTextLine("x", 80.0, RectBox(0, 0, 1, 1)).normalizedConfidence, 0.0001)
        assertEquals(1.0, OcrTextLine("x", 5.0, RectBox(0, 0, 1, 1)).normalizedConfidence, 0.0001)
        assertThrows(IllegalArgumentException::class.java) {
            OcrTextLine("x", Double.NaN, RectBox(0, 0, 1, 1))
        }
    }

    @Test
    @DisplayName("矩形：面积、退化判定与非法参数")
    fun rectBox() {
        val box = RectBox(10, 20, 110, 60)
        assertEquals(100, box.width)
        assertEquals(40, box.height)
        assertEquals(4000L, box.area)
        assertFalse(box.isEmpty)
        assertTrue(RectBox(10, 10, 10, 10).isEmpty)
        assertThrows(IllegalArgumentException::class.java) { RectBox(10, 0, 5, 10) }
        assertTrue(RectBox(0, 0, 0, 0).isEmpty)
    }
}
