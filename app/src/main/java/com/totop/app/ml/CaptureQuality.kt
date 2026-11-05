package com.totop.app.ml

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 拍完即检：在裁剪确认页给出「这张可能拍得不好」的提示。
 *
 * 三条设计约束（见 docs/需求定稿-0.6.0.md 第五节）：
 *
 * 1. **纯本地、可解释**：全部是位图指标加阈值判定，不调用模型，不产生费用，
 *    阈值可以单独调、也可以单独测；
 * 2. **只提示不拦截**：老机器在台灯下拍出来的照片误判率高，拦住会让人根本拍不了题，
 *    所以这里只返回提示文案，是否重拍由用户决定；
 * 3. **输入是缩小后的预览图**：调用方传长边 480 左右的像素，几十万像素内算完，
 *    不增加上传体积，也不会在主线程上卡顿。
 *
 * 判据刻意不追求严谨的计算机视觉：能给初二学生一句「换个角度」就够了，
 * 判不准的时候宁可不说（返回空列表），也不要每次都弹一条错的。
 */
object CaptureQuality {
    /** 灰度 Laplacian 方差低于该值判为模糊。文字边缘清晰的照片通常远高于此值。 */
    const val BLUR_VARIANCE_MIN: Double = 110.0

    /** 高亮像素（灰度 ≥ [BRIGHT_LEVEL]）占比超过该值判为过曝。 */
    const val OVEREXPOSED_RATIO_MAX: Double = 0.20

    /** 暗部像素（灰度 ≤ [DARK_LEVEL]）占比超过该值判为过暗。 */
    const val UNDEREXPOSED_RATIO_MAX: Double = 0.35

    /** 倾斜超过该角度判为拍歪。低于 8 度时框选与识别都不受影响，提示只会烦人。 */
    const val SKEW_DEGREES_MAX: Double = 8.0

    /** 单块高亮连通区域占全图比例超过该值判为反光。 */
    const val GLARE_RATIO_MAX: Double = 0.05

    private const val BRIGHT_LEVEL = 246
    private const val DARK_LEVEL = 14
    private const val GLARE_LEVEL = 250

    /**
     * 灰阶权重（0.299 / 0.587 / 0.114 各放大 1000 倍）。
     *
     * 用整数近似而不是浮点乘：阈值是按整数灰度调的，浮点误差会让同一张图
     * 在不同设备上落到阈值的两侧，出现「有的机器说糊、有的说清楚」。
     */
    private const val GRAY_RED = 299
    private const val GRAY_GREEN = 587
    private const val GRAY_BLUE = 114
    private const val GRAY_SCALE = 1000

    /** 主方向与水平/垂直轴的夹角在这个范围内时，认为照片是正的。 */
    private const val AXIS_SNAP_DEGREES = 45.0

    enum class Kind {
        BLURRY,
        TOO_BRIGHT,
        TOO_DARK,
        SKEWED,
        GLARE,
    }

    data class Issue(
        val kind: Kind,
        val message: String,
    )

    /**
     * 检查一张预览图，返回需要提示的问题（可能为空）。
     *
     * @param width 图片宽度，@param height 图片高度，@param argb 逐像素 ARGB，长度需 ≥ width*height
     */
    fun inspect(
        width: Int,
        height: Int,
        argb: IntArray,
    ): List<Issue> {
        if (width < MIN_SIDE || height < MIN_SIDE) return emptyList()
        if (argb.size < width * height) return emptyList()

        val gray = toGray(width, height, argb)
        val brightRatio = ratioOf(gray, min = BRIGHT_LEVEL, max = 255)
        val darkRatio = ratioOf(gray, min = 0, max = DARK_LEVEL)

        val issues = ArrayList<Issue>(4)
        val overexposed = brightRatio > OVEREXPOSED_RATIO_MAX
        val underexposed = darkRatio > UNDEREXPOSED_RATIO_MAX
        if (overexposed) issues += Issue(Kind.TOO_BRIGHT, "光线太强，避开直射光再拍一张")
        if (underexposed) issues += Issue(Kind.TOO_DARK, "光线有点暗，挪到亮处或开个灯再拍")

        // 整片过曝/欠曝时局部对比度必然极低，模糊判定没有意义，
        // 报「糊」只会把原因引到错的方向上，所以两种情况下都不再做模糊判定。
        if (!overexposed && !underexposed && laplacianVariance(width, height, gray) < BLUR_VARIANCE_MIN) {
            issues += Issue(Kind.BLURRY, "这张有点糊，稳住手机重拍一张更准")
        }

        // 大面积高亮在过曝时已经被解释过了，不再重复提示「有反光」。
        if (!overexposed && largestBrightBlobRatio(width, height, gray) > GLARE_RATIO_MAX) {
            issues += Issue(Kind.GLARE, "有一块反光，稍微换个角度")
        }

        val skew = skewDegrees(width, height, gray)
        if (skew != null && abs(skew) > SKEW_DEGREES_MAX) {
            issues += Issue(Kind.SKEWED, "拍歪了约 " + abs(skew).roundToInt() + " 度，尽量正对纸面")
        }
        return issues
    }

    private fun toGray(
        width: Int,
        height: Int,
        argb: IntArray,
    ): IntArray {
        val gray = IntArray(width * height)
        for (i in gray.indices) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 整数近似，避免浮点误差影响阈值可复现性。
            gray[i] = (r * GRAY_RED + g * GRAY_GREEN + b * GRAY_BLUE) / GRAY_SCALE
        }
        return gray
    }

    private fun ratioOf(
        gray: IntArray,
        min: Int,
        max: Int,
    ): Double {
        if (gray.isEmpty()) return 0.0
        var hit = 0
        for (v in gray) {
            if (v in min..max) hit++
        }
        return hit.toDouble() / gray.size
    }

    /** 4 邻域 Laplacian 的方差：文字边缘越多、越锐利，方差越大。 */
    private fun laplacianVariance(
        width: Int,
        height: Int,
        gray: IntArray,
    ): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val v = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]).toDouble()
                sum += v
                sumSq += v * v
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - mean * mean
    }

    /** 最大高亮连通块（反光的典型形状是一块连续的白斑，不是零散的高光）。 */
    private fun largestBrightBlobRatio(
        width: Int,
        height: Int,
        gray: IntArray,
    ): Double {
        val visited = BooleanArray(gray.size)
        val stack = IntArray(gray.size)
        var largest = 0
        for (start in gray.indices) {
            if (visited[start] || gray[start] < GLARE_LEVEL) continue
            var top = 0
            stack[top++] = start
            visited[start] = true
            var size = 0
            while (top > 0) {
                val idx = stack[--top]
                size++
                val x = idx % width
                val y = idx / width
                if (x > 0) top = pushIfBright(gray, visited, stack, top, idx - 1)
                if (x < width - 1) top = pushIfBright(gray, visited, stack, top, idx + 1)
                if (y > 0) top = pushIfBright(gray, visited, stack, top, idx - width)
                if (y < height - 1) top = pushIfBright(gray, visited, stack, top, idx + width)
            }
            if (size > largest) largest = size
        }
        return largest.toDouble() / gray.size
    }

    private fun pushIfBright(
        gray: IntArray,
        visited: BooleanArray,
        stack: IntArray,
        top: Int,
        idx: Int,
    ): Int {
        if (visited[idx] || gray[idx] < GLARE_LEVEL) return top
        visited[idx] = true
        stack[top] = idx
        return top + 1
    }

    /**
     * 用结构张量估计主边缘方向，再折算成「相对水平/垂直轴歪了多少度」。
     *
     * 为什么不直接做投影法找最平的行：投影法要枚举角度并把整幅图旋转一遍，
     * 480 长边上要跑十几次全图求和，在低端机上不值得。结构张量只扫一遍梯度，
     * 对「纸面上的横排文字」这种以水平边缘为主的内容足够准。
     */
    private fun skewDegrees(
        width: Int,
        height: Int,
        gray: IntArray,
    ): Double? {
        if (width < 3 || height < 3) return null
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val rightX = edgeSum(gray, i - width + 1, i + 1, i + width + 1)
                val leftX = edgeSum(gray, i - width - 1, i - 1, i + width - 1)
                val bottomY = edgeSum(gray, i + width - 1, i + width, i + width + 1)
                val topY = edgeSum(gray, i - width - 1, i - width, i - width + 1)
                val gx = rightX - leftX
                val gy = bottomY - topY
                sxx += (gx * gx).toDouble()
                syy += (gy * gy).toDouble()
                sxy += (gx * gy).toDouble()
            }
        }
        // 完全没有边缘（空白纸、纯色背景）时不猜角度。
        if (sxx + syy < MIN_GRADIENT_ENERGY) return null
        val angle = 0.5 * Math.toDegrees(atan2(2 * sxy, sxx - syy))
        // 折算到离最近的轴（0/90/180/-90）还差多少度，再折叠到 ±45。
        var folded = angle % 90.0
        if (folded > AXIS_SNAP_DEGREES) folded -= 90.0
        if (folded < -AXIS_SNAP_DEGREES) folded += 90.0
        return folded
    }

    /** Sobel 的一个 3 像素方向项（两端权重 1、中间权重 2）。 */
    private fun edgeSum(
        gray: IntArray,
        edge: Int,
        middle: Int,
        other: Int,
    ): Int = gray[edge] + 2 * gray[middle] + gray[other]

    private const val MIN_SIDE = 32
    private const val MIN_GRADIENT_ENERGY = 1.0
}
