package com.totop.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 像素矩形（纯值类型，不依赖 Android 框架）。
 *
 * 与 android.graphics.Rect 的区别：有 equals/hashCode，可在 JVM 单测中直接断言。
 */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left

    val height: Int get() = bottom - top

    /** 转成 Android Rect，供 Bitmap.createBitmap 使用。 */
    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

/**
 * 归一化矩形：坐标取 0..1，与图片实际像素尺寸解耦。
 *
 * 为什么用归一化而不是像素：
 * 1. 选框是在「预览控件」上拖出来的，控件尺寸与图片原始尺寸不同；
 * 2. 图片可能被压缩（长边 1280）后再裁剪，像素坐标会失效；
 * 3. 归一化坐标可以跟随原图长期保存，日后换分辨率仍能还原选区。
 *
 * 约定 left < right、top < bottom，且全部落在 0..1 内。
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f) { "left/right 必须在 0..1，实际：$left..$right" }
        require(top in 0f..1f && bottom in 0f..1f) { "top/bottom 必须在 0..1，实际：$top..$bottom" }
    }

    val isEmpty: Boolean get() = right - left <= 0f || bottom - top <= 0f

    val widthRatio: Float get() = (right - left).coerceIn(0f, 1f)

    val heightRatio: Float get() = (bottom - top).coerceIn(0f, 1f)

    /**
     * 换算成像素矩形；结果会被裁剪到图片边界内，且保证至少 1×1。
     *
     * 返回值刻意用纯数据类 [PixelRect] 而不是 android.graphics.Rect：
     * 1. 该换算是最容易出错的一段逻辑，必须能在 JVM 单测里直接断言；
     *    android.graphics.Rect 在单测环境（isReturnDefaultValues=true）下
     *    所有方法返回 0，断言会变成假绿或假红；
     * 2. 比较两个矩形是否相等需要值语义，Rect 没有 equals。
     *
     * 真正需要 Rect 的地方由调用方用 [PixelRect.toAndroidRect] 转换。
     */
    fun toPixelRect(
        imageWidth: Int,
        imageHeight: Int,
    ): PixelRect {
        val l = (left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val t = (top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val r = (right * imageWidth).toInt().coerceIn(l + 1, imageWidth)
        val b = (bottom * imageHeight).toInt().coerceIn(t + 1, imageHeight)
        return PixelRect(l, t, r, b)
    }

    companion object {
        /** 全图（不框选时的默认值）。 */
        val FULL: NormalizedRect = NormalizedRect(0f, 0f, 1f, 1f)

        /**
         * 由拖动产生的像素坐标构造，自动排序并夹到 0..1。
         *
         * 拖动时用户可能从右下往左上拉，因此必须重新排序而不能假设入参顺序。
         */
        fun fromDrag(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            viewWidth: Int,
            viewHeight: Int,
        ): NormalizedRect {
            if (viewWidth <= 0 || viewHeight <= 0) return FULL
            val l = (minOf(x1, x2) / viewWidth).coerceIn(0f, 1f)
            val r = (maxOf(x1, x2) / viewWidth).coerceIn(0f, 1f)
            val t = (minOf(y1, y2) / viewHeight).coerceIn(0f, 1f)
            val b = (maxOf(y1, y2) / viewHeight).coerceIn(0f, 1f)
            return NormalizedRect(l, t, r, b)
        }
    }
}

/**
 * 图片在控件里的实际绘制区域（纯函数，可单测）。
 *
 * 为什么必须有这一步：预览页用 `ContentScale.Fit` 把照片等比缩放后居中放进控件，
 * 控件里必然留下空白（letterbox）。框选层如果拿**控件尺寸**做归一化，
 * 归一化坐标就不对应图片像素——用户框住题目，上传的却是偏移了一大块、
 * 甚至几乎整页的画面，观感就是「框选没生效，还是发的原图」。
 *
 * 这里把「图片在控件中的绘制矩形」独立成纯函数，框选层只按这个矩形换算坐标，
 * 与 Compose 的布局细节解耦，也便于在 JVM 单测里直接断言宽高比与偏移。
 */
object ImageFit {
    /**
     * 计算等比缩放（contain）后的绘制矩形，坐标单位是控件像素。
     *
     * @param imageWidth 图片宽（像素）
     * @param imageHeight 图片高（像素）
     * @param viewWidth 控件宽（像素）
     * @param viewHeight 控件高（像素）
     */
    fun contentRect(
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): PixelRect {
        // 尺寸非法（未测量完成、解码失败）时退化为整个控件：
        // 用 any{} 表达而不是四个 || 连写，避免触发 detekt 的 ComplexCondition。
        val sizes = listOf(imageWidth, imageHeight, viewWidth, viewHeight)
        if (sizes.any { it <= 0 }) {
            return PixelRect(0, 0, viewWidth.coerceAtLeast(0), viewHeight.coerceAtLeast(0))
        }
        val scale = minOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val drawnWidth = (imageWidth * scale).toInt().coerceIn(1, viewWidth)
        val drawnHeight = (imageHeight * scale).toInt().coerceIn(1, viewHeight)
        val left = (viewWidth - drawnWidth) / 2
        val top = (viewHeight - drawnHeight) / 2
        return PixelRect(left = left, top = top, right = left + drawnWidth, bottom = top + drawnHeight)
    }
}

/** 裁剪结果。 */
sealed class CropOutcome {
    data class Success(val bitmap: Bitmap, val pixelRect: Rect) : CropOutcome()

    data class Failure(val reason: String) : CropOutcome()
}

/**
 * 本地裁剪器：按归一化选框裁出题目区域。
 *
 * 为什么选「本地裁剪后上传」而不是「上传原图 + 坐标」：
 * 1. OpenAI 兼容协议没有标准的坐标字段，各厂商互不支持，写了也不保证生效；
 * 2. 只上传裁剪图可以明显降低 token（一道小题只占整页的一小块）；
 * 3. 识别质量更高：无关的页眉、其它题目不会干扰模型。
 *
 * 全过程在 [ioDispatcher] 执行，不阻塞主线程。
 */
class ImageCropper(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 按归一化选框裁剪文件，输出 JPEG 字节。 */
    suspend fun cropToJpegBytes(
        path: String,
        rect: NormalizedRect?,
        quality: Int = DEFAULT_QUALITY,
    ): ImageCropResult =
        withContext(ioDispatcher) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                return@withContext ImageCropResult.Failure("图片文件不可读：$path")
            }
            // 不框选时直接返回原文件字节，避免无谓的解码与再压缩
            if (rect == null || rect.isEmpty) {
                val bytes =
                    runCatching { file.readBytes() }.getOrElse {
                        return@withContext ImageCropResult.Failure("读取图片失败：${it.message}")
                    }
                return@withContext ImageCropResult.Success(bytes = bytes, cropped = false)
            }
            val bounds =
                BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext ImageCropResult.Failure("无法识别图片尺寸")
            }
            val decoded =
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                    ?: return@withContext ImageCropResult.Failure("解码图片失败（可能内存不足）")
            val pixelRect = rect.toPixelRect(decoded.width, decoded.height)
            val cropped =
                runCatching {
                    Bitmap.createBitmap(decoded, pixelRect.left, pixelRect.top, pixelRect.width, pixelRect.height)
                }.getOrNull()
            if (cropped == null) {
                decoded.recycle()
                return@withContext ImageCropResult.Failure("裁剪失败，请重新框选")
            }
            val out = java.io.ByteArrayOutputStream()
            val ok = runCatching { cropped.compress(Bitmap.CompressFormat.JPEG, quality, out) }.getOrDefault(false)
            if (cropped !== decoded) cropped.recycle()
            decoded.recycle()
            if (!ok) return@withContext ImageCropResult.Failure("裁剪图压缩失败")
            ImageCropResult.Success(bytes = out.toByteArray(), cropped = true)
        }

    companion object {
        /** 与整图压缩保持一致的质量，避免二次压缩明显掉字迹。 */
        const val DEFAULT_QUALITY: Int = 88
    }
}

/** 裁剪结果。 */
sealed class ImageCropResult {
    /** @param cropped 是否为真实裁剪；false 表示未框选、返回原图 */
    data class Success(val bytes: ByteArray, val cropped: Boolean) : ImageCropResult() {
        // ByteArray 的 equals 是引用比较，data class 需要显式实现才有意义
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return cropped == other.cropped && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + cropped.hashCode()
    }

    data class Failure(val reason: String) : ImageCropResult()
}
