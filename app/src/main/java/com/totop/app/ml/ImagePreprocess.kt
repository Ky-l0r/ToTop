package com.totop.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.totop.app.util.AndroidImageEncoder
import com.totop.app.util.EncodedImage
import com.totop.app.util.ImageEncodeError
import com.totop.app.util.ImageEncodeResult
import com.totop.app.util.ImageEncoder
import com.totop.app.util.ImageEncoderConfig
import com.totop.app.util.ImageMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 图片命名与目录规则（开发方案 3.2 第 [2] 步）。纯 Kotlin，无 Android 依赖，可被 JVM 单测覆盖。
 *
 * 目录约定与 res/xml/file_paths.xml 中的 FileProvider 配置一致：
 * - 主图：`filesDir/images/<sha256>.jpg`
 * - 缩略图：`filesDir/images/thumbs/<sha256>_thumb.jpg`
 *
 * 用 sha256 而不是自增 id 命名，是为了「同一张图重复导入只占一份空间」，
 * 也直接对上 question.imageSha256 这个字段。
 */
object PreprocessPaths {
    /** 主图子目录（相对 filesDir）。 */
    const val IMAGE_DIR: String = "images"

    /** 缩略图子目录（相对 filesDir）。 */
    const val THUMBNAIL_DIR: String = "images/thumbs"

    /** 主图后缀：统一 JPEG，不用 WebP（厂商兼容性更稳，见开发方案 3.4）。 */
    const val JPEG_SUFFIX: String = ".jpg"

    /** 缩略图后缀（与主图区分，便于清理策略单独处理）。 */
    const val THUMBNAIL_SUFFIX: String = "_thumb.jpg"

    /** sha256 十六进制串长度。 */
    const val SHA256_LENGTH: Int = 64

    /** 是否为合法的 sha256 小写十六进制串。 */
    fun isSha256(value: String): Boolean =
        value.length == SHA256_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * 主图文件名：`<sha256>.jpg`。
     * @throws IllegalArgumentException 当 [sha256] 不是合法小写十六进制串时（防止路径穿越）
     */
    fun fileNameFor(sha256: String): String {
        require(isSha256(sha256)) { "sha256 必须是 64 位小写十六进制，实际为：" + sha256 }
        return sha256 + JPEG_SUFFIX
    }

    /** 缩略图文件名：`<sha256>_thumb.jpg`。 */
    fun thumbnailNameFor(sha256: String): String = sha256 + THUMBNAIL_SUFFIX

    /** 主图相对路径：`images/<sha256>.jpg`。 */
    fun relativeImagePathFor(sha256: String): String = IMAGE_DIR + "/" + fileNameFor(sha256)

    /** 缩略图相对路径：`images/thumbs/<sha256>_thumb.jpg`。 */
    fun relativeThumbnailPathFor(sha256: String): String = THUMBNAIL_DIR + "/" + thumbnailNameFor(sha256)

    /** 从绝对路径推导缩略图路径（不访问文件系统）。 */
    fun thumbnailSiblingOf(imageFile: File): File =
        File(File(imageFile.parentFile, "thumbs"), imageFile.name.substringBeforeLast('.') + "_thumb.jpg")
}

/**
 * 预处理产物。
 *
 * @param imageFile 主图（filesDir/images/&lt;sha256&gt;.jpg），已做旋转纠正、长边 &lt;= 1280、JPEG q=0.78
 * @param thumbnailFile 列表用缩略图，可能为 null（缩略图失败不影响主流程）
 * @param encoded 编码结果，含 base64 与 sha256，直接供 LlmClient / 缓存使用
 */
data class PreprocessedImage(
    val imageFile: File,
    val thumbnailFile: File?,
    val encoded: EncodedImage,
) {
    /** 主图绝对路径，对应 question.imagePath。 */
    val imagePath: String get() = imageFile.absolutePath

    /** 缩略图绝对路径，列表展示用。 */
    val thumbnailPath: String? get() = thumbnailFile?.absolutePath

    /** 图片 sha256，对应 question.imageSha256。 */
    val sha256: String get() = encoded.sha256

    /** 宽（已按 EXIF 纠正后）。 */
    val width: Int get() = encoded.width

    /** 高（已按 EXIF 纠正后）。 */
    val height: Int get() = encoded.height

    /** 长边（<= 1280）。 */
    val longSide: Int get() = encoded.longSide

    /** 文件字节数。 */
    val byteCount: Int get() = encoded.byteCount
}

/** 预处理结果（失败不抛异常，返回可读中文原因）。 */
sealed class PreprocessResult {
    data class Success(val image: PreprocessedImage) : PreprocessResult()

    data class Failure(val error: ImageEncodeError) : PreprocessResult()
}

/**
 * 图片预处理接口。
 *
 * 抽成接口便于 UI 层在 Compose 预览与测试中注入假实现；
 * 生产环境由 Hilt 绑定到 [ImagePreprocess]（见 [MlModule]）。
 */
interface ImagePreprocessor {
    /** 相册导入（ActivityResultContracts.PickVisualMedia 的返回 Uri）。 */
    suspend fun preprocessFromUri(uri: Uri): PreprocessResult

    /** CameraX ImageCapture 输出的临时文件。 */
    suspend fun preprocessFromFile(path: String): PreprocessResult

    /** 已在内存中的图片字节。 */
    suspend fun preprocessFromBytes(bytes: ByteArray): PreprocessResult
}

/**
 * 端侧图片预处理（开发方案 3.2 第 [2] 步、3.4 成本控制）。
 *
 * 一次调用完成四件事，全部在本机完成，**不访问任何网络、不上传第三方**：
 * 1. 长边压到 1280 以内、统一 JPEG 质量 0.78（不用 WebP，厂商兼容性更稳）；
 * 2. EXIF 旋转/翻转纠正（复用 [AndroidImageEncoder]，只产生一张中间位图）；
 * 3. 计算 sha256 并写盘为 `filesDir/images/&lt;sha256&gt;.jpg`（同图重复导入不重复占空间）；
 * 4. 生成列表用缩略图 `filesDir/images/thumbs/&lt;sha256&gt;_thumb.jpg`（长边 320、JPEG q=70）。
 *
 * 低端机策略：解码走 [ImageMath.computeInSampleSize] 的 2 的幂采样，
 * 全程在 [ioDispatcher] 上执行，主线程零 IO。
 */
@Singleton
class ImagePreprocess
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ImagePreprocessor {
        private val encoder: ImageEncoder = AndroidImageEncoder()

        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        override suspend fun preprocessFromUri(uri: Uri): PreprocessResult {
            val bytes =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                return PreprocessResult.Failure(ImageEncodeError.FileUnreadable(uri.toString(), "无法打开相册图片"))
            }
            return preprocessFromBytes(bytes)
        }

        override suspend fun preprocessFromFile(path: String): PreprocessResult =
            toResult(encoder.encodeFromFile(path, ImageEncoderConfig.DEFAULT))

        override suspend fun preprocessFromBytes(bytes: ByteArray): PreprocessResult =
            toResult(encoder.encodeFromBytes(bytes, ImageEncoderConfig.DEFAULT))

        /** 编码成功后落盘；编码器自身已在 IO 线程执行，这里只做文件写入。 */
        private suspend fun toResult(result: ImageEncodeResult): PreprocessResult =
            when (result) {
                is ImageEncodeResult.Success -> withContext(ioDispatcher) { persist(result.image) }
                is ImageEncodeResult.Failure -> PreprocessResult.Failure(result.error)
            }

        /** 写主图与缩略图。主图写失败视为整体失败；缩略图失败只降级为 null。 */
        private fun persist(encoded: EncodedImage): PreprocessResult {
            val imageDir = File(context.filesDir, PreprocessPaths.IMAGE_DIR)
            if (!imageDir.exists() && !imageDir.mkdirs()) {
                return PreprocessResult.Failure(ImageEncodeError.FileUnreadable(imageDir.absolutePath, "无法创建图片目录"))
            }
            val fileName = PreprocessPaths.fileNameFor(encoded.sha256)
            val imageFile = File(imageDir, fileName)
            if (!writeIfChanged(imageFile, encoded.jpegBytes)) {
                return PreprocessResult.Failure(ImageEncodeError.FileUnreadable(imageFile.absolutePath, "写入失败"))
            }
            val thumbnailFile = runCatching { writeThumbnail(encoded, imageDir) }.getOrNull()
            return PreprocessResult.Success(
                PreprocessedImage(imageFile = imageFile, thumbnailFile = thumbnailFile, encoded = encoded),
            )
        }

        /** 内容相同（同名）文件不重复写；不同内容才落盘。 */
        private fun writeIfChanged(
            target: File,
            bytes: ByteArray,
        ): Boolean {
            if (target.exists() && target.length().toInt() == bytes.size) return true
            return runCatching {
                FileOutputStream(target).use {
                    it.write(bytes)
                    it.flush()
                }
                true
            }.getOrDefault(false)
        }

        /** 生成缩略图：长边 [THUMBNAIL_MAX_LONG_SIDE]、JPEG q=[THUMBNAIL_QUALITY]。 */
        private fun writeThumbnail(
            encoded: EncodedImage,
            imageDir: File,
        ): File? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded.jpegBytes, 0, encoded.byteCount, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sampleSize = ImageMath.computeInSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_LONG_SIDE)
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            val source = BitmapFactory.decodeByteArray(encoded.jpegBytes, 0, encoded.byteCount, options) ?: return null

            val (targetWidth, targetHeight) =
                ImageMath.computeTargetSize(
                    srcWidth = source.width,
                    srcHeight = source.height,
                    maxLongSide = THUMBNAIL_MAX_LONG_SIDE,
                    upscaleSmall = false,
                )
            val scaled =
                if (targetWidth == source.width && targetHeight == source.height) {
                    source
                } else {
                    Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
                }
            if (scaled !== source) source.recycle()

            val thumbsDir = File(imageDir, "thumbs")
            if (!thumbsDir.exists() && !thumbsDir.mkdirs()) {
                scaled.recycle()
                return null
            }
            val thumbFile = File(thumbsDir, PreprocessPaths.thumbnailNameFor(encoded.sha256))
            val success =
                runCatching {
                    FileOutputStream(thumbFile).use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
                    }
                }.getOrDefault(false)
            scaled.recycle()
            return if (success && thumbFile.length() > 0L) thumbFile else null
        }

        /** 删除单张图片（主图 + 缩略图），用于「上传成功后清理本机原图」等策略。 */
        suspend fun delete(sha256: String): Boolean =
            withContext(ioDispatcher) {
                if (!PreprocessPaths.isSha256(sha256)) return@withContext false
                val imageDir = File(context.filesDir, PreprocessPaths.IMAGE_DIR)
                val image = File(imageDir, PreprocessPaths.fileNameFor(sha256))
                val thumb = File(File(imageDir, "thumbs"), PreprocessPaths.thumbnailNameFor(sha256))
                val removedImage = !image.exists() || image.delete()
                val removedThumb = !thumb.exists() || thumb.delete()
                removedImage && removedThumb
            }

        companion object {
            /** 缩略图长边。 */
            const val THUMBNAIL_MAX_LONG_SIDE: Int = 320

            /** 缩略图 JPEG 质量。 */
            const val THUMBNAIL_QUALITY: Int = 70
        }
    }
