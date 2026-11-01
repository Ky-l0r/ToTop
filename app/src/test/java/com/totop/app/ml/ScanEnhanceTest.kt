package com.totop.app.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 扫描式增强的像素级契约。
 *
 * 关心三件事：白底被推到接近纯白、灰字被压深、纯色块不被改成别的颜色（色相保留）。
 * 不关心具体像素值——增益与网格半径都可能调，断言用「更白 / 更黑 / 不低于」这类关系。
 */
@DisplayName("拍照件扫描式增强")
class ScanEnhanceTest {
    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(color: Int): Int = (color shr 16) and 0xFF

    private fun green(color: Int): Int = (color shr 8) and 0xFF

    private fun blue(color: Int): Int = color and 0xFF

    /** 造一张「浅灰纸 + 一行深灰字」的图。 */
    private fun pageWithText(
        width: Int = 64,
        height: Int = 32,
        paper: Int = 205,
        ink: Int = 110,
    ): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isInk = y in 12..19 && x % 8 != 0
                val value = if (isInk) ink else paper
                pixels[y * width + x] = argb(value, value, value)
            }
        }
        return pixels
    }

    @Test
    @DisplayName("纸面被推到接近纯白")
    fun `纸面变白`() {
        val width = 64
        val height = 32
        val input = pageWithText(width, height)
        val output = ScanEnhance.enhancePixels(input, width, height)
        // 取第一行（纯纸面，不受文字影响）
        val paper = output[3 * width + 5]
        assertTrue(red(paper) >= 245, "纸面亮度应接近纯白，实际 ${red(paper)}")
        assertTrue(green(paper) >= 245 && blue(paper) >= 245)
    }

    @Test
    @DisplayName("字迹被压深，与纸面拉开对比")
    fun `字迹变黑`() {
        val width = 64
        val height = 32
        val input = pageWithText(width, height)
        val output = ScanEnhance.enhancePixels(input, width, height)
        val ink = output[15 * width + 1]
        val paper = output[3 * width + 5]
        assertTrue(red(ink) <= 100, "字迹应明显变深，实际 ${red(ink)}")
        assertTrue(red(paper) - red(ink) >= 140, "纸面与字迹的差值不足：${red(paper)} - ${red(ink)}")
    }

    @Test
    @DisplayName("彩色块保留色相，只改亮度")
    fun `彩色不失真`() {
        val width = 8
        val height = 8
        // 整块深红：背景估计等于自身亮度，因此增强后仍应偏红
        val pixels = IntArray(width * height) { argb(160, 40, 40) }
        val output = ScanEnhance.enhancePixels(pixels, width, height)
        val color = output[0]
        assertTrue(red(color) > green(color), "红色块不应变成灰色：r=${red(color)} g=${green(color)}")
        assertTrue(red(color) > blue(color))
    }

    @Test
    @DisplayName("入参非法时原样返回，不抛异常")
    fun `非法入参`() {
        val pixels = intArrayOf(argb(10, 10, 10))
        assertEquals(pixels, ScanEnhance.enhancePixels(pixels, 2, 2))
        assertEquals(0, ScanEnhance.enhancePixels(IntArray(0), 0, 0).size)
    }

    @Test
    @DisplayName("背景亮度图与像素数量一致")
    fun `背景图尺寸`() {
        val width = 40
        val height = 24
        val background = ScanEnhance.backgroundLuminance(pageWithText(width, height), width, height)
        assertEquals(width * height, background.size)
    }
}
