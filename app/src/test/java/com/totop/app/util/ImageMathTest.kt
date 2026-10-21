package com.totop.app.util

import com.totop.app.data.remote.IdempotencyGuard
import com.totop.app.data.remote.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** 图片预处理的纯计算部分测试（开发方案 3.4：长边 1280、JPEG 78）。 */
class ImageMathTest {
    @ParameterizedTest(name = "{0}x{1} 长边上限 {2} -> {3}x{4}")
    @CsvSource(
        "4000, 3000, 1280, 1280, 960",
        "3000, 4000, 1280, 960, 1280",
        "1920, 1080, 1280, 1280, 720",
        "1280, 1280, 1280, 1280, 1280",
        "800, 600, 1280, 800, 600",
        "1, 5000, 1280, 1, 1280",
    )
    @DisplayName("目标尺寸：保持比例，长边压到上限，小图不放大")
    fun targetSize(
        srcW: Int,
        srcH: Int,
        maxLong: Int,
        expW: Int,
        expH: Int,
    ) {
        val (w, h) = ImageMath.computeTargetSize(srcW, srcH, maxLong)
        assertEquals(expW, w)
        assertEquals(expH, h)
        assertTrue(maxOf(w, h) <= maxLong)
    }

    @Test
    @DisplayName("允许放大时小图也会放大到长边上限")
    fun upscaleAllowed() {
        val (w, h) = ImageMath.computeTargetSize(640, 480, 1280, upscaleSmall = true)
        assertEquals(1280, w)
        assertEquals(960, h)
    }

    @Test
    @DisplayName("非法尺寸抛异常，避免除零")
    fun invalidSize() {
        assertThrows(IllegalArgumentException::class.java) { ImageMath.computeTargetSize(0, 100, 1280) }
        assertThrows(IllegalArgumentException::class.java) { ImageMath.computeTargetSize(100, 100, 0) }
    }

    @ParameterizedTest(name = "{0}x{1} -> 采样率 {2}")
    @CsvSource(
        "4000, 3000, 2",
        "8000, 6000, 4",
        "1280, 960, 1",
        "2000, 1000, 1",
        "2560, 1920, 2",
    )
    @DisplayName("BitmapFactory 采样率：2 的幂，解码后长边仍不小于目标")
    fun inSampleSize(
        srcW: Int,
        srcH: Int,
        expected: Int,
    ) {
        assertEquals(expected, ImageMath.computeInSampleSize(srcW, srcH, 1280))
    }

    @Test
    @DisplayName("采样率必须是 2 的幂")
    fun sampleSizeIsPowerOfTwo() {
        for (width in listOf(1300, 2600, 5200, 9000)) {
            val sample = ImageMath.computeInSampleSize(width, width, 1280)
            assertTrue(sample > 0 && (sample and (sample - 1)) == 0, sample.toString())
        }
    }

    @ParameterizedTest(name = "EXIF {0} -> 旋转 {1} 度")
    @CsvSource(
        "1, 0",
        "2, 0",
        "3, 180",
        "4, 0",
        "5, 0",
        "6, 90",
        "7, 0",
        "8, 270",
        "0, 0",
    )
    @DisplayName("EXIF orientation 到旋转角度的映射")
    fun exifRotation(
        orientation: Int,
        degrees: Int,
    ) {
        assertEquals(degrees, ImageMath.rotationDegreesOf(orientation))
    }

    @Test
    @DisplayName("水平翻转只对 2 与 5 生效")
    fun exifFlip() {
        assertTrue(ImageMath.flipHorizontalOf(ExifOrientation.FLIP_HORIZONTAL))
        assertTrue(ImageMath.flipHorizontalOf(ExifOrientation.TRANSPOSE))
        (1..8).filter { it != 2 && it != 5 }.forEach { assertFalse(ImageMath.flipHorizontalOf(it), it.toString()) }
    }

    @Test
    @DisplayName("旋转 90/270 度后宽高互换")
    fun swapIfRotated() {
        assertEquals(960 to 1280, ImageMath.swappedIfRotated(1280, 960, 90))
        assertEquals(1280 to 960, ImageMath.swappedIfRotated(1280, 960, 0))
        assertEquals(960 to 1280, ImageMath.swappedIfRotated(1280, 960, 270))
        assertEquals(1280 to 960, ImageMath.swappedIfRotated(1280, 960, 180))
    }

    @Test
    @DisplayName("sha256 与 JDK 标准结果一致，且同内容得同哈希")
    fun sha256KnownVector() {
        // "abc" 的 SHA-256 是公开测试向量
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ImageMath.sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            ImageMath.sha256Hex(byteArrayOf(1, 2, 3)),
            ImageMath.sha256Hex(byteArrayOf(1, 2, 3)),
        )
        assertNotEquals(
            ImageMath.sha256Hex(byteArrayOf(1, 2, 3)),
            ImageMath.sha256Hex(byteArrayOf(1, 2, 4)),
        )
    }

    @Test
    @DisplayName("十六进制输出为小写且定宽 64 位")
    fun hexFormat() {
        val hex = ImageMath.sha256Hex(ByteArray(0))
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" })
    }
}

/** Base64 编码测试（OpenAI data URL 要求单行、标准字母表）。 */
class Base64CodecTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "a, YQ==",
        "ab, YWI=",
        "abc, YWJj",
        "abcd, YWJjZA==",
        "hello, aGVsbG8=",
    )
    @DisplayName("标准测试向量（RFC 4648）")
    fun knownVectors(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, Base64Codec.encode(input.toByteArray(Charsets.UTF_8)))
    }

    @Test
    @DisplayName("与 JDK Base64 逐字节一致（随机数据）")
    fun matchesJdk() {
        val random = java.util.Random(42)
        repeat(50) {
            val bytes = ByteArray(random.nextInt(511) + 1)
            random.nextBytes(bytes)
            assertEquals(
                java.util.Base64.getEncoder().encodeToString(bytes),
                Base64Codec.encode(bytes),
            )
        }
    }

    @Test
    @DisplayName("空数组编码为空串，输出无换行")
    fun emptyAndNoNewline() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        val encoded = Base64Codec.encode(ByteArray(1000) { it.toByte() })
        assertFalse(encoded.contains("\n"))
        assertFalse(encoded.contains("\r"))
    }

    @Test
    @DisplayName("二进制照片字节（含 0xFF 与 0x00）不丢字节")
    fun binarySafe() {
        val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x10, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(jpegMagic),
            Base64Codec.encode(jpegMagic),
        )
    }
}

/** EncodedImage 值语义测试（ByteArray 参与 equals）。 */
class EncodedImageTest {
    private fun image(bytes: ByteArray) =
        EncodedImage(
            jpegBytes = bytes,
            base64 = Base64Codec.encode(bytes),
            width = 1280,
            height = 960,
            sha256 = ImageMath.sha256Hex(bytes),
            sourceWidth = 4000,
            sourceHeight = 3000,
            rotationDegrees = 90,
        )

    @Test
    @DisplayName("内容相同的两个实例相等（ByteArray 用 contentEquals）")
    fun valueEquality() {
        val a = image(byteArrayOf(1, 2, 3))
        val b = image(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, image(byteArrayOf(1, 2, 4)))
    }

    @Test
    @DisplayName("data URL 形如 data:image/jpeg;base64,...")
    fun dataUrl() {
        val encoded = image(byteArrayOf(1, 2, 3))
        assertTrue(encoded.dataUrl().startsWith("data:image/jpeg;base64,"))
        assertTrue(encoded.dataUrl().endsWith(encoded.base64))
    }

    @Test
    @DisplayName("toString 不打印 base64，避免日志膨胀与内容泄漏")
    fun toStringSafe() {
        val encoded = image(ByteArray(2000) { 7 })
        val text = encoded.toString()
        assertFalse(text.contains(encoded.base64))
        assertTrue(text.contains("sha256="))
        assertEquals(2000, encoded.byteCount)
        assertEquals(1280, encoded.longSide)
    }

    @Test
    @DisplayName("编码参数校验：质量与长边范围")
    fun configValidation() {
        assertEquals(0.78f, ImageEncoderConfig.DEFAULT.qualityFraction, 0.0001f)
        assertEquals(1280, ImageEncoderConfig.DEFAULT.maxLongSide)
        assertThrows(IllegalArgumentException::class.java) { ImageEncoderConfig(maxLongSide = 10) }
        assertThrows(IllegalArgumentException::class.java) { ImageEncoderConfig(quality = 0) }
    }
}

/** 幂等守卫与请求 ID 测试。 */
class RequestIdTest {
    @Test
    @DisplayName("UUID 格式且每次不同")
    fun uuidFormat() {
        val a = RequestId.newId()
        val b = RequestId.newId()
        assertNotEquals(a, b)
        assertTrue(RequestId.isValid(a))
        assertTrue(a.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    @DisplayName("同一种子派生同一 ID，便于跨进程幂等")
    fun deriveDeterministic() {
        assertEquals(RequestId.derive("sha256-abc"), RequestId.derive("sha256-abc"))
        assertNotEquals(RequestId.derive("abc"), RequestId.derive("abd"))
    }

    @Test
    @DisplayName("非法 ID 判定")
    fun invalidIds() {
        assertFalse(RequestId.isValid(""))
        assertFalse(RequestId.isValid(null))
        assertFalse(RequestId.isValid("short"))
    }

    @Test
    @DisplayName("守卫：进行中与已完成都拒绝重复；abort 后可重来")
    fun guardLifecycle() {
        val guard = IdempotencyGuard()
        assertTrue(guard.begin("r1"))
        assertFalse(guard.begin("r1"), "进行中不得重复进入")
        assertTrue(guard.isInFlight("r1"))

        guard.abort("r1")
        assertFalse(guard.isInFlight("r1"))
        assertTrue(guard.begin("r1"), "abort 后允许重来")

        guard.complete("r1")
        assertFalse(guard.begin("r1"), "已完成的 requestId 不得再次调用")
    }

    @Test
    @DisplayName("空 requestId 一律拒绝")
    fun guardRejectsBlank() {
        val guard = IdempotencyGuard()
        assertFalse(guard.begin(""))
        assertFalse(guard.begin("   "))
    }

    @Test
    @DisplayName("已完成集合有上限，长时间使用不会无限增长")
    fun guardBounded() {
        val guard = IdempotencyGuard()
        repeat(300) { index ->
            val id = "req-" + index
            assertTrue(guard.begin(id))
            guard.complete(id)
        }
        // 最早的一批已被挤出，可再次进入
        assertTrue(guard.begin("req-0"))
    }
}
