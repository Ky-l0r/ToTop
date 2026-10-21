package com.totop.app.util

import java.security.MessageDigest

/**
 * 图片预处理结果（开发方案 3.2 第 [2] 步 / 3.4 成本控制）。
 *
 * @param jpegBytes 压缩后的 JPEG 字节
 * @param base64 供多模态接口使用的 base64（无换行）
 * @param width 压缩后宽
 * @param height 压缩后高
 * @param sha256 压缩后字节的 sha256（缓存键与去重锚点，对应 question.imageSha256）
 * @param sourceWidth 原图宽（未旋转前）
 * @param sourceHeight 原图高（未旋转前）
 * @param rotationDegrees EXIF 纠正时旋转的角度（0/90/180/270）
 * @param byteCount 压缩后字节数
 */
data class EncodedImage(
    val jpegBytes: ByteArray,
    val base64: String,
    val width: Int,
    val height: Int,
    val sha256: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int,
) {
    val byteCount: Int get() = jpegBytes.size

    /** 长边（像素）。 */
    val longSide: Int get() = maxOf(width, height)

    /** data URL，直接放进 image_url.url。 */
    fun dataUrl(mimeType: String = "image/jpeg"): String = "data:" + mimeType + ";base64," + base64

    // ByteArray 在 data class 中的 equals 需要显式实现，避免误用引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedImage) return false
        return jpegBytes.contentEquals(other.jpegBytes) &&
            base64 == other.base64 &&
            width == other.width &&
            height == other.height &&
            sha256 == other.sha256 &&
            sourceWidth == other.sourceWidth &&
            sourceHeight == other.sourceHeight &&
            rotationDegrees == other.rotationDegrees
    }

    override fun hashCode(): Int {
        var result = jpegBytes.contentHashCode()
        result = 31 * result + base64.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + sha256.hashCode()
        result = 31 * result + sourceWidth
        result = 31 * result + sourceHeight
        result = 31 * result + rotationDegrees
        return result
    }

    /** 不打印 base64，避免日志膨胀与内容泄漏。 */
    override fun toString(): String =
        "EncodedImage(w=" + width + ", h=" + height + ", bytes=" + byteCount + ", sha256=" + sha256.take(12) + ")"
}

/** 压缩参数（开发方案 3.4：长边 1280、质量 0.78、统一 JPEG）。 */
data class ImageEncoderConfig(
    /** 长边上限（像素）。 */
    val maxLongSide: Int = 1280,
    /** JPEG 质量（0..100）。 */
    val quality: Int = 78,
    /** 是否对已经小于上限的图片再次缩放；默认 false，避免无意义重编码。 */
    val upscaleSmall: Boolean = false,
) {
    init {
        require(maxLongSide in 64..8192) { "长边上限必须在 64..8192，实际为：" + maxLongSide }
        require(quality in 1..100) { "JPEG 质量必须在 1..100，实际为：" + quality }
    }

    /** 质量换算到 0..1 的浮点（Bitmap.compress 使用 0..100，此处提供换算以便测试断言）。 */
    val qualityFraction: Float get() = quality / 100f

    companion object {
        /** 默认档：长边 1280、质量 78。 */
        val DEFAULT: ImageEncoderConfig = ImageEncoderConfig()
    }
}

/**
 * 图片预处理与编码的纯计算部分。
 *
 * 真正的解码/缩放/旋转需要 android.graphics.Bitmap（见 [AndroidImageEncoder]），
 * 但尺寸计算、采样率、sha256、base64 编码全部是纯 Kotlin，
 * 既能在低端机上零额外内存运行，也能被 JVM 单测直接覆盖。
 */
object ImageMath {
    /**
     * 计算目标尺寸：保持宽高比，长边压到 [maxLongSide]。
     *
     * 规则：
     * - 长边已不超过上限且 [upscaleSmall] 为 false 时返回原尺寸；
     * - 至少保留 1 像素，避免除零；
     * - 短边按同一比例缩放并四舍五入。
     */
    fun computeTargetSize(
        srcWidth: Int,
        srcHeight: Int,
        maxLongSide: Int,
        upscaleSmall: Boolean = false,
    ): Pair<Int, Int> {
        require(srcWidth > 0 && srcHeight > 0) { "原图尺寸必须为正，实际为：" + srcWidth + "x" + srcHeight }
        require(maxLongSide > 0) { "长边上限必须为正，实际为：" + maxLongSide }
        val longSide = maxOf(srcWidth, srcHeight)
        if (longSide <= maxLongSide && !upscaleSmall) return srcWidth to srcHeight
        val scale = maxLongSide.toDouble() / longSide.toDouble()
        val width = (srcWidth * scale).toInt().coerceAtLeast(1)
        val height = (srcHeight * scale).toInt().coerceAtLeast(1)
        return width to height
    }

    /**
     * 计算 [android.graphics.BitmapFactory.Options.inSampleSize]。
     *
     * 采样率只能是 2 的幂，取「解码后长边仍不小于目标长边」的最小采样率，
     * 这样既避免解码出超大位图（低端机 OOM），又保证精度足够由后续缩放补齐。
     */
    fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        targetLongSide: Int,
    ): Int {
        require(srcWidth > 0 && srcHeight > 0) { "原图尺寸必须为正" }
        require(targetLongSide > 0) { "目标长边必须为正" }
        val longSide = maxOf(srcWidth, srcHeight)
        if (longSide <= targetLongSide) return 1
        var sample = 1
        while (longSide / (sample * 2) >= targetLongSide) {
            sample *= 2
        }
        return sample
    }

    /** EXIF orientation -> 顺时针旋转角度（0/90/180/270）。 */
    fun rotationDegreesOf(exifOrientation: Int): Int =
        when (exifOrientation) {
            ExifOrientation.ROTATE_90 -> 90
            ExifOrientation.ROTATE_180 -> 180
            ExifOrientation.ROTATE_270 -> 270
            else -> 0
        }

    /** EXIF orientation 是否要求水平翻转（前置摄像头自拍常见）。 */
    fun flipHorizontalOf(exifOrientation: Int): Boolean =
        exifOrientation == ExifOrientation.FLIP_HORIZONTAL || exifOrientation == ExifOrientation.TRANSPOSE

    /**
     * 计算字节的 sha256 十六进制小写串（与 question.imageSha256 对齐）。
     * 使用 JDK 的 MessageDigest，Android 与 JVM 行为一致。
     */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(digest)
    }

    /** 字节数组 -> 小写十六进制。 */
    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            out.append(HEX_DIGITS[v shr 4])
            out.append(HEX_DIGITS[v and NIBBLE_MASK])
        }
        return out.toString()
    }

    /** 单字节掩码（0xFF）：把带符号 byte 还原为 0..255。 */
    private const val BYTE_MASK: Int = 0xFF

    /** 半字节掩码（0x0F）：取十六进制一位。 */
    private const val NIBBLE_MASK: Int = 0x0F

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /** 旋转 90/270 度后交换宽高。 */
    fun swappedIfRotated(
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): Pair<Int, Int> = if (rotationDegrees == 90 || rotationDegrees == 270) height to width else width to height
}

/**
 * EXIF orientation 常量。
 *
 * 刻意不引用 androidx.exifinterface 的常量，使纯计算部分在无 Android 依赖的
 * JVM 单测中也可断言；[AndroidImageEncoder] 内部会把 ExifInterface 的值映射到这里。
 * 数值与 EXIF 规范（以及 ExifInterface.ORIENTATION_*）完全一致。
 */
object ExifOrientation {
    const val UNDEFINED: Int = 0
    const val NORMAL: Int = 1
    const val FLIP_HORIZONTAL: Int = 2
    const val ROTATE_180: Int = 3
    const val FLIP_VERTICAL: Int = 4
    const val TRANSPOSE: Int = 5
    const val ROTATE_90: Int = 6
    const val TRANSVERSE: Int = 7
    const val ROTATE_270: Int = 8
}

/**
 * Base64 编码（RFC 4648 标准字母表，无换行）。
 *
 * 自行实现而非使用 android.util.Base64：OpenAI data URL 要求单行 base64，
 * 且 android.util.Base64 在 JVM 单测中不可用（isReturnDefaultValues 会返回 null）。
 */
object Base64Codec {
    /** Base64 六位分组掩码（0x3F = 63，即每组 6 位）。 */
    private const val SIX_BIT_MASK: Int = 0x3F

    /** 单字节掩码（0xFF）：把带符号 byte 还原为 0..255。 */
    private const val BYTE_MASK: Int = 0xFF

    /** 第二组六位的右移量。 */
    private const val SIX_BIT_SHIFT: Int = 6

    /** 第三组六位的右移量（第 12 至 17 位）。 */
    private const val THIRD_GROUP_SHIFT: Int = 12

    /** 第四组六位的右移量（第 18 至 23 位）。 */
    private const val FOURTH_GROUP_SHIFT: Int = 18

    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    /** 编码为无换行 base64（带 = 填充）。 */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val triple =
                ((bytes[i].toInt() and BYTE_MASK) shl 16) or
                    ((bytes[i + 1].toInt() and BYTE_MASK) shl 8) or
                    (bytes[i + 2].toInt() and BYTE_MASK)
            out.append(ALPHABET[(triple ushr FOURTH_GROUP_SHIFT) and SIX_BIT_MASK])
            out.append(ALPHABET[(triple ushr THIRD_GROUP_SHIFT) and SIX_BIT_MASK])
            out.append(ALPHABET[(triple ushr SIX_BIT_SHIFT) and SIX_BIT_MASK])
            out.append(ALPHABET[triple and SIX_BIT_MASK])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val b0 = bytes[i].toInt() and BYTE_MASK
                val triple = b0 shl 16
                out.append(ALPHABET[(triple ushr FOURTH_GROUP_SHIFT) and SIX_BIT_MASK])
                out.append(ALPHABET[(triple ushr THIRD_GROUP_SHIFT) and SIX_BIT_MASK])
                out.append('=')
                out.append('=')
            }
            2 -> {
                val b0 = bytes[i].toInt() and BYTE_MASK
                val b1 = bytes[i + 1].toInt() and BYTE_MASK
                val triple = (b0 shl 16) or (b1 shl 8)
                out.append(ALPHABET[(triple ushr FOURTH_GROUP_SHIFT) and SIX_BIT_MASK])
                out.append(ALPHABET[(triple ushr THIRD_GROUP_SHIFT) and SIX_BIT_MASK])
                out.append(ALPHABET[(triple ushr SIX_BIT_SHIFT) and SIX_BIT_MASK])
                out.append('=')
            }
        }
        return out.toString()
    }
}
