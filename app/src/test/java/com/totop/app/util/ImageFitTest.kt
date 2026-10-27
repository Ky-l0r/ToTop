package com.totop.app.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 图片在控件里的绘制区域（ContentScale.Fit 的几何关系）。
 *
 * 回归背景：框选层的归一化坐标曾经按**控件**尺寸换算，而图片是等比缩放后居中的，
 * 控件里留有空白。两者尺寸不同，归一化坐标与图片像素整体错位，
 * 表现为「框住题目，上传的却是偏移的画面」。
 */
class ImageFitTest {
    @Test
    @DisplayName("竖图放进更宽的控件：上下满高，左右留白居中")
    fun portraitInWideViewport() {
        val rect = ImageFit.contentRect(imageWidth = 900, imageHeight = 1600, viewWidth = 1080, viewHeight = 1600)
        assertEquals(0, rect.top)
        assertEquals(1600, rect.bottom)
        assertEquals(900, rect.width)
        // 居中：左右各留 90
        assertEquals(90, rect.left)
        assertEquals(990, rect.right)
    }

    @Test
    @DisplayName("横图放进更高的控件：左右满宽，上下留白居中")
    fun landscapeInTallViewport() {
        val rect = ImageFit.contentRect(imageWidth = 1440, imageHeight = 1080, viewWidth = 1080, viewHeight = 1400)
        assertEquals(0, rect.left)
        assertEquals(1080, rect.right)
        assertEquals(810, rect.height)
        // 居中：上下各留 (1400-810)/2 = 295
        assertEquals(295, rect.top)
        assertEquals(1105, rect.bottom)
    }

    @Test
    @DisplayName("同宽高比时铺满控件，不留空白")
    fun sameRatioFillsViewport() {
        val rect = ImageFit.contentRect(imageWidth = 400, imageHeight = 300, viewWidth = 800, viewHeight = 600)
        assertEquals(PixelRect(0, 0, 800, 600), rect)
    }

    @Test
    @DisplayName("尺寸非法时退化为整个控件，不抛异常")
    fun invalidSizesFallBackToViewport() {
        assertEquals(PixelRect(0, 0, 720, 1280), ImageFit.contentRect(0, 0, 720, 1280))
        assertEquals(PixelRect(0, 0, 0, 0), ImageFit.contentRect(100, 100, 0, 0))
    }

    @Test
    @DisplayName("归一化选区相对图片换算：letterbox 的留白不参与裁剪坐标")
    fun normalizedRectMapsIntoImagePixels() {
        // 1000x1000 的方图放进 1000x1600 的控件：绘制区域上下各留 300
        val content = ImageFit.contentRect(imageWidth = 1000, imageHeight = 1000, viewWidth = 1000, viewHeight = 1600)
        assertEquals(300, content.top)
        val normalized = NormalizedRect(left = 0f, top = 0.3f, right = 1f, bottom = 0.9f)
        val pixel = normalized.toPixelRect(content.width, content.height)
        // 0.3..0.9 是**图片自身**的纵向范围，与控件里的 300px 留白无关
        assertEquals(300, pixel.top)
        assertEquals(900, pixel.bottom)
        assertEquals(1000, pixel.width)
        assertEquals(600, pixel.height)
    }
}
