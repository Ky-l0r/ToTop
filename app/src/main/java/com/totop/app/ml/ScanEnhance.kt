package com.totop.app.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 拍照件的「扫描式」增强：背景压到接近纯白，笔画压到接近纯黑。
 *
 * 为什么需要它（真机反馈）：
 * 手机在室内拍纸面，背景是浅灰、字是深灰，对比度不足；模型看图时要同时对抗
 * 阴影、反光与纸张偏色，识别率明显下降，学生自己回头复习也看不清楚。
 * 扫描类 App 的做法是「估计背景亮度 → 把背景拉白、把比背景暗的部分压深」，
 * 这里做的正是同一件事，只保留一层，不做锐化、不做去噪、不做透视矫正。
 *
 * 算法（两段，先粗后细）：
 * 1. 在 1/[GRID_STEP] 的粗网格上采样亮度，做一次盒式模糊，得到「背景亮度图」；
 * 2. 每个像素按 `新亮度 = 255 + (亮度 - 背景亮度) * 增益` 拉伸，
 *    再按 `新亮度 / 旧亮度` 的比例缩放三个通道。
 *
 * 为什么按比例缩放通道而不是直接转灰度：红笔批注、彩色图表、化学结构里的标记
 * 在复习时仍然有区分意义；只改亮度、保留色相，比一刀切成灰度损失更小。
 * 当某一笔太深（新亮度被压到 0）时颜色自然退化为黑，这与扫描件一致。
 *
 * 纯 Kotlin 部分（[enhancePixels]）不依赖 Android，可在 JVM 单测里用合成像素断言。
 */
object ScanEnhance {
    /** 粗网格步长：每 8 个像素采样一次背景亮度。 */
    private const val GRID_STEP: Int = 8

    /** 对比增益：越大越像扫描件，也越容易把浅铅笔线吃成噪点。 */
    private const val DEFAULT_GAIN: Float = 2.2f

    /** 亮度权重（sRGB 近似），与灰度公式一致。 */
    private const val LUM_R: Float = 0.299f
    private const val LUM_G: Float = 0.587f
    private const val LUM_B: Float = 0.114f

    /** 输出长边上限：与上传分辨率保持一致，不再二次缩放。 */
    private const val MAX_LONG_SIDE: Int = 1600

    /** JPEG 质量：增强后对比度高，86 已足够，再高只增加体积。 */
    private const val JPEG_QUALITY: Int = 86

    /**
     * 把 JPEG 字节转成「扫描件」字节。
     *
     * @param gain 对比增益，见 [ScanLevel]；<= 0 时不做处理，直接返回 null 让调用方用原图
     * @return 增强后的 JPEG；解码或编码失败时返回 null，调用方退回原图。
     */
    fun toScannedJpeg(
        bytes: ByteArray,
        gain: Float = DEFAULT_GAIN,
    ): ByteArray? {
        if (gain <= 0f) return null
        return runCatching {
            val bounds =
                BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                }
            val decoded =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val width = decoded.width
            val height = decoded.height
            if (width <= 0 || height <= 0) {
                decoded.recycle()
                return null
            }
            val pixels = IntArray(width * height)
            decoded.getPixels(pixels, 0, width, 0, 0, width, height)
            decoded.recycle()
            val enhanced = enhancePixels(pixels, width, height, gain)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.setPixels(enhanced, 0, width, 0, 0, width, height)
            val stream = ByteArrayOutputStream()
            val ok = output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            output.recycle()
            if (!ok) null else stream.toByteArray()
        }.getOrNull()
    }

    /**
     * 像素级增强（纯函数，可单测）。
     *
     * @param argb 长度必须是 width * height 的 ARGB 数组
     * @return 同尺寸的新数组；入参不合法时原样返回
     */
    fun enhancePixels(
        argb: IntArray,
        width: Int,
        height: Int,
        gain: Float = DEFAULT_GAIN,
    ): IntArray {
        if (width <= 0 || height <= 0 || argb.size != width * height) return argb
        val background = backgroundLuminance(argb, width, height)
        val result = IntArray(argb.size)
        for (index in argb.size - 1 downTo 0) {
            val color = argb[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val lum = LUM_R * r + LUM_G * g + LUM_B * b
            val target = 255f + (lum - background[index]) * gain
            // 按比例缩放三通道：亮度被压到 0 时颜色一起变黑，与扫描件观感一致。
            val scale = if (lum < MIN_LUMINANCE) 1f else target / lum
            result[index] =
                (0xFF shl 24) or
                (channel(r * scale) shl 16) or
                (channel(g * scale) shl 8) or
                channel(b * scale)
        }
        return result
    }

    /** 逐像素的背景亮度（与 [enhancePixels] 一一对应）。 */
    fun backgroundLuminance(
        argb: IntArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val gridWidth = (width + GRID_STEP - 1) / GRID_STEP
        val gridHeight = (height + GRID_STEP - 1) / GRID_STEP
        val grid = FloatArray(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) {
            val y = minOf(gy * GRID_STEP + GRID_STEP / 2, height - 1)
            for (gx in 0 until gridWidth) {
                val x = minOf(gx * GRID_STEP + GRID_STEP / 2, width - 1)
                val color = argb[y * width + x]
                grid[gy * gridWidth + gx] =
                    LUM_R * ((color shr 16) and 0xFF) +
                    LUM_G * ((color shr 8) and 0xFF) +
                    LUM_B * (color and 0xFF)
            }
        }
        val radius = maxOf(2, minOf(gridWidth, gridHeight) / 8)
        // 先取局部**最大值**再模糊。
        //
        // 只用均值会把笔画本身算进背景：一行字占满窗口时，估计出的「背景」就是灰的，
        // 于是字压不下去、纸也白不起来，增强等于没做。
        // 纸面在一小块区域里几乎总是最亮的那一层，用最大值更接近它的真实亮度；
        // 之后再模糊一次，避免边界出现方块状的亮斑。
        val brightened = maxFilter(grid, gridWidth, gridHeight, radius)
        val blurred = blur2d(brightened, gridWidth, gridHeight, radius)
        // 双线性上采样回原尺寸
        val out = FloatArray(width * height)
        val scaleX = if (width > 1) (gridWidth - 1).toFloat() / (width - 1) else 0f
        val scaleY = if (height > 1) (gridHeight - 1).toFloat() / (height - 1) else 0f
        for (y in 0 until height) {
            val fy = y * scaleY
            val y0 = fy.toInt().coerceIn(0, gridHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(gridHeight - 1)
            val ty = fy - y0
            for (x in 0 until width) {
                val fx = x * scaleX
                val x0 = fx.toInt().coerceIn(0, gridWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(gridWidth - 1)
                val tx = fx - x0
                val top = lerp(blurred[y0 * gridWidth + x0], blurred[y0 * gridWidth + x1], tx)
                val bottom = lerp(blurred[y1 * gridWidth + x0], blurred[y1 * gridWidth + x1], tx)
                out[y * width + x] = lerp(top, bottom, ty)
            }
        }
        return out
    }

    /** 二维局部最大值（窗口 (2r+1)²），用来估计「纸面有多亮」。 */
    private fun maxFilter(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ): FloatArray {
        val out = FloatArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var best = 0f
                for (dy in -radius..radius) {
                    val sampleY = (y + dy).coerceIn(0, height - 1)
                    for (dx in -radius..radius) {
                        val sampleX = (x + dx).coerceIn(0, width - 1)
                        val value = source[sampleY * width + sampleX]
                        if (value > best) best = value
                    }
                }
                out[y * width + x] = best
            }
        }
        return out
    }

    /** 二维盒式模糊 = 横向模糊 + 转置 + 横向模糊 + 转置回来。
     *
     * 用两次一维模糊代替嵌套窗口，复杂度仍是 O(n)；
     * 只做横向会让背景估计沿着竖直方向拖出条纹，字行之间会留下灰带。
     */
    private fun blur2d(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ): FloatArray {
        val horizontal = boxBlur(source, width, height, radius)
        val transposed = transpose(horizontal, width, height)
        val vertical = boxBlur(transposed, height, width, radius)
        return transpose(vertical, height, width)
    }

    /** 矩阵转置（宽高互换）。 */
    private fun transpose(
        source: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val out = FloatArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + y] = source[y * width + x]
            }
        }
        return out
    }

    /** 沿 x 方向做一次半径为 [radius] 的盒式模糊（行内滑动窗口）。 */
    private fun boxBlur(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ): FloatArray {
        val window = radius * 2 + 1
        val out = FloatArray(source.size)
        for (y in 0 until height) {
            var sum = 0f
            for (x in -radius..radius) {
                sum += source[y * width + x.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                out[y * width + x] = sum / window
                sum -= source[y * width + (x - radius).coerceIn(0, width - 1)]
                sum += source[y * width + (x + radius + 1).coerceIn(0, width - 1)]
            }
        }
        return out
    }

    private fun lerp(
        start: Float,
        end: Float,
        fraction: Float,
    ): Float = start + (end - start) * fraction

    private fun channel(value: Float): Int = value.toInt().coerceIn(0, 255)

    /** 亮度低于该值时不按比例缩放通道，避免除零把整块区域算成纯黑。 */
    private const val MIN_LUMINANCE: Float = 8f

    private fun sampleSizeFor(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_LONG_SIDE || height / (sample * 2) >= MAX_LONG_SIDE) {
            sample *= 2
        }
        return sample
    }
}
