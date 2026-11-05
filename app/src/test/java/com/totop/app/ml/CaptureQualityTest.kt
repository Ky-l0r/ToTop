package com.totop.app.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 拍完即检的四项判据。
 *
 * 这些用例都喂合成灰阶图：真实照片无法进单测，而阈值一旦被改坏，
 * 「整片过曝还报模糊」这类错判会直接把用户引到错误的方向（是光线问题，不是手抖）。
 */
@DisplayName("拍完即检")
class CaptureQualityTest {
    private val size = 200

    private fun image(fill: (x: Int, y: Int) -> Int): IntArray {
        val argb = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = fill(x, y).coerceIn(0, 255)
                argb[y * size + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return argb
    }

    private fun kinds(argb: IntArray): Set<CaptureQuality.Kind> =
        CaptureQuality.inspect(size, size, argb).map { it.kind }.toSet()

    @Test
    @DisplayName("整片过曝只报光线，不报模糊与反光")
    fun `过曝`() {
        val kinds = kinds(image { _, _ -> 255 })
        assertEquals(setOf(CaptureQuality.Kind.TOO_BRIGHT), kinds)
    }

    @Test
    @DisplayName("整片欠曝只报光线，不报模糊")
    fun `过暗`() {
        val kinds = kinds(image { _, _ -> 0 })
        assertEquals(setOf(CaptureQuality.Kind.TOO_DARK), kinds)
    }

    @Test
    @DisplayName("锐利的棋盘格不会被判成糊")
    fun `清晰`() {
        val kinds = kinds(image { x, y -> if ((x / 8 + y / 8) % 2 == 0) 30 else 225 })
        assertFalse(CaptureQuality.Kind.BLURRY in kinds)
    }

    @Test
    @DisplayName("只有平滑渐变、没有边缘时报糊")
    fun `模糊`() {
        val kinds = kinds(image { x, _ -> 100 + x / 4 })
        assertTrue(CaptureQuality.Kind.BLURRY in kinds)
    }

    @Test
    @DisplayName("深色纸面上一块白斑报反光")
    fun `反光`() {
        val kinds = kinds(image { x, y -> if (x in 70..129 && y in 70..129) 252 else 120 })
        assertTrue(CaptureQuality.Kind.GLARE in kinds)
    }

    @Test
    @DisplayName("斜条纹报拍歪")
    fun `倾斜`() {
        val kinds = kinds(image { x, y -> if ((x + y) / 6 % 2 == 0) 40 else 215 })
        assertTrue(CaptureQuality.Kind.SKEWED in kinds)
    }

    @Test
    @DisplayName("横条纹是正的，不报拍歪")
    fun `水平不歪`() {
        val kinds = kinds(image { _, y -> if (y / 6 % 2 == 0) 40 else 215 })
        assertFalse(CaptureQuality.Kind.SKEWED in kinds)
    }

    @Test
    @DisplayName("尺寸过小或像素不足时什么都不说")
    fun `输入非法`() {
        assertTrue(CaptureQuality.inspect(10, 10, IntArray(100)).isEmpty())
        assertTrue(CaptureQuality.inspect(200, 200, IntArray(10)).isEmpty())
    }
}
