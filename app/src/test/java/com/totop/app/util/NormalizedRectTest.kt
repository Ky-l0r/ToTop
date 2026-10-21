package com.totop.app.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 框选坐标换算测试。
 *
 * 这段纯函数决定了「用户框住的区域」能否被准确裁出来，
 * 一旦方向或排序搞错，用户会拿到一张错误的裁剪图，且很难察觉。
 */
class NormalizedRectTest {
    @Test
    @DisplayName("从右下往左上拖，同样得到正确的矩形")
    fun dragReversed() {
        val rect = NormalizedRect.fromDrag(800f, 600f, 200f, 100f, 1000, 1000)
        assertEquals(0.2f, rect.left)
        assertEquals(0.1f, rect.top)
        assertEquals(0.8f, rect.right)
        assertEquals(0.6f, rect.bottom)
    }

    @Test
    @DisplayName("拖动超出边界时夹到 0..1")
    fun dragClamped() {
        val rect = NormalizedRect.fromDrag(-100f, -50f, 2000f, 3000f, 1000, 1000)
        assertEquals(0f, rect.left)
        assertEquals(0f, rect.top)
        assertEquals(1f, rect.right)
        assertEquals(1f, rect.bottom)
    }

    @Test
    @DisplayName("控件尺寸为零时退回全图，避免除零")
    fun zeroViewFallsBackToFull() {
        assertEquals(NormalizedRect.FULL, NormalizedRect.fromDrag(10f, 10f, 20f, 20f, 0, 0))
    }

    @Test
    @DisplayName("归一化坐标换算到像素不越界，且至少 1×1")
    fun toPixelRectInBounds() {
        val rect = NormalizedRect(0.5f, 0.5f, 0.5001f, 0.5001f)
        val pixel = rect.toPixelRect(100, 100)
        assertTrue(pixel.width >= 1, "宽度至少 1，实际：" + pixel.width)
        assertTrue(pixel.height >= 1)
        assertTrue(pixel.right <= 100 && pixel.bottom <= 100)
    }

    @Test
    @DisplayName("全图换算后覆盖整张图片")
    fun fullCoversImage() {
        val pixel = NormalizedRect.FULL.toPixelRect(1280, 720)
        assertEquals(0, pixel.left)
        assertEquals(0, pixel.top)
        assertEquals(1280, pixel.right)
        assertEquals(720, pixel.bottom)
    }

    @Test
    @DisplayName("非法坐标直接拒绝，不产生静默错误结果")
    fun rejectsOutOfRange() {
        assertThrows<IllegalArgumentException> { NormalizedRect(1.2f, 0f, 0.5f, 1f) }
        assertThrows<IllegalArgumentException> { NormalizedRect(0f, -0.1f, 1f, 1f) }
    }

    @Test
    @DisplayName("退化的零面积选框被识别为 isEmpty")
    fun degenerateIsEmpty() {
        assertTrue(NormalizedRect(0.3f, 0.3f, 0.3f, 0.8f).isEmpty)
        assertFalse(NormalizedRect(0.3f, 0.3f, 0.6f, 0.8f).isEmpty)
    }
}
