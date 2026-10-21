package com.totop.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 编码失败原因，均为可读中文，直接展示给用户。 */
sealed class ImageEncodeError(val userMessage: String) {
    /** 图片不存在或不可读。 */
    data class FileUnreadable(val path: String, val detail: String? = null) : ImageEncodeError(
        "无法读取图片文件" + (detail?.let { "（" + it + "）" } ?: "") + "，请重新拍照或从相册选择。",
    )

    /** 解码失败（文件损坏或不是图片）。 */
    data class DecodeFailed(val detail: String? = null) : ImageEncodeError(
        "图片解码失败，文件可能已损坏，请重新拍照。" + (detail?.let { "（" + it + "）" } ?: ""),
    )

    /** 内存不足（超大图）。 */
    data class OutOfMemory(val detail: String? = null) : ImageEncodeError(
        "图片过大，手机内存不足，已尝试降采样仍失败。请改用「相册」导入或直接输入题干文字。",
    )

    /** JPEG 压缩失败。 */
    data class CompressFailed(val detail: String? = null) : ImageEncodeError(
        "图片压缩失败，请重试；若反复失败，可直接输入题干文字。",
    )
}

/** 编码结果。 */
sealed class ImageEncodeResult {
    data class Success(val image: EncodedImage) : ImageEncodeResult()

    data class Failure(val error: ImageEncodeError) : ImageEncodeResult()
}

/**
 * 图片编码器接口（开发方案 3.2 第 [2] 步、3.4 成本控制）。
 *
 * 约定：长边 <= 1280、JPEG 质量 78、EXIF 旋转纠正、输出 base64 与 sha256。
 * 接口化便于 ui-solve 用假实现做 Compose 预览测试。
 */
interface ImageEncoder {
    /** 从文件路径编码。 */
    suspend fun encodeFromFile(
        path: String,
        config: ImageEncoderConfig = ImageEncoderConfig.DEFAULT,
    ): ImageEncodeResult

    /** 从字节编码（相册 Uri 读取后的字节）。 */
    suspend fun encodeFromBytes(
        bytes: ByteArray,
        config: ImageEncoderConfig = ImageEncoderConfig.DEFAULT,
    ): ImageEncodeResult
}

/**
 * 基于 android.graphics 的实现。
 *
 * 内存策略（低端机友好）：
 * 1. 先用 inJustDecodeBounds 读尺寸，不分配像素内存；
 * 2. 按 [ImageMath.computeInSampleSize] 取 2 的幂采样率解码，避免 OOM；
 * 3. 再做精确缩放到长边 1280；
 * 4. EXIF 旋转/翻转在缩放后用 Matrix 一次完成，只产生一张新位图；
 * 5. 最后回收中间位图。
 *
 * 全过程在 [ioDispatcher]（默认 Dispatchers.IO）执行，不阻塞主线程。
 */
class AndroidImageEncoder(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageEncoder {
    override suspend fun encodeFromFile(
        path: String,
        config: ImageEncoderConfig,
    ): ImageEncodeResult =
        withContext(ioDispatcher) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                return@withContext ImageEncodeResult.Failure(ImageEncodeError.FileUnreadable(path))
            }
            val exifOrientation = readExifOrientation(file)
            val bytes =
                runCatching { file.readBytes() }.getOrElse {
                    return@withContext ImageEncodeResult.Failure(ImageEncodeError.FileUnreadable(path, it.message))
                }
            encodeInternal(bytes, exifOrientation, config)
        }

    override suspend fun encodeFromBytes(
        bytes: ByteArray,
        config: ImageEncoderConfig,
    ): ImageEncodeResult =
        withContext(ioDispatcher) {
            if (bytes.isEmpty()) {
                return@withContext ImageEncodeResult.Failure(ImageEncodeError.DecodeFailed("空文件"))
            }
            val exifOrientation = readExifOrientation(bytes)
            encodeInternal(bytes, exifOrientation, config)
        }

    private fun encodeInternal(
        bytes: ByteArray,
        exifOrientation: Int,
        config: ImageEncoderConfig,
    ): ImageEncodeResult {
        // 第一步：只读尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ImageEncodeResult.Failure(ImageEncodeError.DecodeFailed("图片尺寸无效"))
        }
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight

        // 第二步：按 2 的幂采样解码
        val sampleSize = ImageMath.computeInSampleSize(sourceWidth, sourceHeight, config.maxLongSide)
        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val decoded =
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            } catch (
                @Suppress("SwallowedException") outOfMemory: OutOfMemoryError,
            ) {
                // 老机型大图解码可能 OOM：返回 null 让上层降采样重试，绝不崩溃
                null
            } ?: return ImageEncodeResult.Failure(ImageEncodeError.OutOfMemory("采样率 " + sampleSize))

        val rotation = ImageMath.rotationDegreesOf(exifOrientation)
        val flipHorizontal = ImageMath.flipHorizontalOf(exifOrientation)

        // 第三步：精确缩放 + 旋转 + 翻转（一次 Matrix）
        val (targetWidth, targetHeight) =
            ImageMath.computeTargetSize(
                srcWidth = decoded.width,
                srcHeight = decoded.height,
                maxLongSide = config.maxLongSide,
                upscaleSmall = config.upscaleSmall,
            )
        val matrix = Matrix()
        val scaleX = targetWidth.toFloat() / decoded.width.toFloat()
        val scaleY = targetHeight.toFloat() / decoded.height.toFloat()
        matrix.postScale(scaleX, scaleY)
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        if (flipHorizontal) matrix.postScale(-1f, 1f)

        val transformed =
            try {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } catch (
                @Suppress("SwallowedException") outOfMemory: OutOfMemoryError,
            ) {
                // 老机型旋转大图可能 OOM：回收后返回明确失败，由界面提示重拍或改用相册
                decoded.recycle()
                return ImageEncodeResult.Failure(
                    ImageEncodeError.OutOfMemory("目标尺寸 " + targetWidth + "x" + targetHeight),
                )
            }
        if (transformed !== decoded) decoded.recycle()

        // 第四步：JPEG 压缩
        val output = ByteArrayOutputStream()
        val compressed =
            try {
                transformed.compress(Bitmap.CompressFormat.JPEG, config.quality, output)
            } catch (
                @Suppress("SwallowedException") outOfMemory: OutOfMemoryError,
            ) {
                // 压缩阶段 OOM：同样回收并返回失败，绝不崩溃
                transformed.recycle()
                return ImageEncodeResult.Failure(ImageEncodeError.OutOfMemory("JPEG 压缩"))
            }
        val jpegBytes = output.toByteArray()
        transformed.recycle()
        if (!compressed || jpegBytes.isEmpty()) {
            return ImageEncodeResult.Failure(ImageEncodeError.CompressFailed())
        }

        val (finalWidth, finalHeight) = ImageMath.swappedIfRotated(targetWidth, targetHeight, rotation)
        return ImageEncodeResult.Success(
            EncodedImage(
                jpegBytes = jpegBytes,
                base64 = Base64Codec.encode(jpegBytes),
                width = finalWidth,
                height = finalHeight,
                sha256 = ImageMath.sha256Hex(jpegBytes),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotationDegrees = rotation,
            ),
        )
    }

    private fun readExifOrientation(file: File): Int =
        runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifOrientation.NORMAL,
            )
        }.getOrDefault(ExifOrientation.NORMAL)

    private fun readExifOrientation(bytes: ByteArray): Int =
        runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifOrientation.NORMAL,
            )
        }.getOrDefault(ExifOrientation.NORMAL)
}
