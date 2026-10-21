package com.totop.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 矩形（像素坐标，左上原点）。刻意不用 android.graphics.Rect，使启发式判定可在 JVM 单测覆盖。 */
data class RectBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right >= left) { "right 不得小于 left" }
        require(bottom >= top) { "bottom 不得小于 top" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()

    /** 是否为退化矩形（零面积）。 */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        /** 从 ML Kit 的 [Rect] 转换。 */
        fun from(rect: Rect): RectBox = RectBox(rect.left, rect.top, rect.right, rect.bottom)
    }
}

/** OCR 识别出的一行文字。 */
data class OcrTextLine(
    val text: String,
    val confidence: Double,
    val box: RectBox,
) {
    init {
        require(confidence.isFinite()) { "confidence 必须为有限值" }
    }

    /** 置信度裁剪到 0..1（个别机型会返回 0..100；大于 10 视为百分制，否则视为越界的小数裁剪）。 */
    val normalizedConfidence: Double
        get() =
            when {
                confidence > 10.0 -> (confidence / 100.0).coerceIn(0.0, 1.0)
                confidence > 1.0 -> 1.0
                else -> confidence.coerceIn(0.0, 1.0)
            }
}

/**
 * OCR 结果（开发方案 3.2 第 [4] 步：text + avgConfidence + hasFigure）。
 *
 * @param text 整页文本，行间以换行分隔（直接作为阶段 A 的输入）
 * @param avgConfidence 平均置信度（0..1）；识别器未给出置信度时为 0.0
 * @param hasFigure 是否疑似含图形。ML Kit 只做文字识别，不输出图形，
 *        这里由 [OcrFigureHeuristics] 依据「大块空白 / 低置信度聚集 / 图形符号与关键词」启发式判定
 * @param language 识别语言，中英混排固定为 [LANGUAGE_ZH_EN]
 */
data class OcrResult(
    val text: String,
    val avgConfidence: Double,
    val hasFigure: Boolean,
    val language: String = LANGUAGE_ZH_EN,
    val lines: List<OcrTextLine> = emptyList(),
    val analysis: FigureAnalysis = FigureAnalysis.NONE,
    val elapsedMillis: Long = 0L,
) {
    /** 文本是否可用（少于 2 个字符视为识别失败，与 [OcrSnapshot.hasUsableText] 一致）。 */
    val hasUsableText: Boolean get() = text.trim().length >= 2

    /** 转成路由层快照（开发方案 3.2 第 [5] 步的输入）。 */
    fun toSnapshot(isHandwritten: Boolean = false): OcrSnapshot =
        OcrSnapshot(
            text = text,
            avgConfidence = avgConfidence,
            hasFigure = hasFigure,
            isHandwritten = isHandwritten,
        )

    companion object {
        /** 中英混排（中文识别器自带拉丁字母与数字识别能力）。 */
        const val LANGUAGE_ZH_EN: String = "zh+en"

        /** 识别失败时的兜底结果。 */
        val EMPTY: OcrResult = OcrResult(text = "", avgConfidence = 0.0, hasFigure = false)
    }
}

/**
 * hasFigure 启发式判定的中间信号，便于日志与单测逐项断言。
 *
 * @param blankBandRatio 被大块空白覆盖的页面高度占比（0..1）
 * @param lowConfidenceRatio 低置信度行占比（0..1）
 * @param hasFigureSymbol 文本中是否出现图形符号或「如图」类关键词
 * @param score 综合得分（0..1），>= [FIGURE_SCORE_THRESHOLD] 判定为含图
 */
data class FigureAnalysis(
    val blankBandRatio: Double,
    val lowConfidenceRatio: Double,
    val hasFigureSymbol: Boolean,
    val score: Double,
) {
    /** 是否判定为含图。 */
    val hasFigure: Boolean get() = score >= OcrFigureHeuristics.FIGURE_SCORE_THRESHOLD

    companion object {
        val NONE: FigureAnalysis = FigureAnalysis(0.0, 0.0, false, 0.0)
    }
}

/**
 * 「题干是否含图」的启发式判定（开发方案 3.2 第 [4] 步要求的 hasFigure）。
 *
 * ML Kit Text Recognition 只返回文字，**不提供图形检测**，所以只能用版面特征推断。
 * 判定依据与权重（顺序即权重，合计 1.0）：
 * 1. 大块空白带 0.45：相邻文字行之间的垂直空隙高度 >= 页面高度 12% 且横跨页面宽度 50% 以上，
 *    说明这块区域没有文字，极可能是几何图/函数图象/表格；
 * 2. 低置信度聚集 0.35：置信度 < 0.55 的行占比 >= 40%（图形附近的零散字符、坐标轴刻度最容易识别错）；
 * 3. 图形符号与关键词 0.20：出现 △ □ ∠ ⊙ ∽ ∴ 等几何符号，或「如图」「下图」「图1」这类表述。
 *
 * 三项相加 >= 0.50 判定含图。阈值偏高（宁可多走一次视觉模型也不要漏图），
 * 与 ModelRouter「含图 -> 视觉模型」的保守策略一致。
 *
 * 全部为纯函数，无 Android 依赖，可在 JVM 单测中穷举覆盖。
 */
object OcrFigureHeuristics {
    /** 单行视为低置信度的阈值。 */
    const val LOW_CONFIDENCE = 0.55

    /** 低置信度行占比阈值。 */
    const val LOW_CONFIDENCE_RATIO = 0.40

    /** 空白带高度占页面高度的最小比例。 */
    const val BLANK_BAND_HEIGHT_RATIO = 0.12

    /** 空白带的横向宽度占页面宽度的最小比例。 */
    const val BLANK_BAND_WIDTH_RATIO = 0.50

    /** 判定含图的综合得分阈值。 */
    const val FIGURE_SCORE_THRESHOLD = 0.50

    private const val WEIGHT_BLANK = 0.45
    private const val WEIGHT_LOW_CONFIDENCE = 0.35
    private const val WEIGHT_SYMBOL = 0.20

    /** 几何/图象相关的符号与字符。 */
    private val FIGURE_SYMBOLS =
        charArrayOf(
            '△',
            '▲',
            '□',
            '■',
            '∠',
            '⊙',
            '○',
            '●',
            '∽',
            '≌',
            '⊥',
            '∥',
            '∫',
            '∑',
            '√',
            '≈',
            '↗',
            '↘',
            '→',
            '↑',
            '↓',
            '⇀',
        )

    /** 题干里指向图形的固定表述（中英文）。 */
    private val FIGURE_KEYWORDS = listOf("如图", "下图", "上图", "图1", "图 1", "图中", "图象", "图像", "折线图", "表格", "坐标系")

    /**
     * 分析一页的文字行，输出含图判定。
     *
     * @param lines 识别出的文字行（含矩形与置信度）
     * @param pageWidth 页面宽（像素）
     * @param pageHeight 页面高（像素）
     */
    fun analyze(
        lines: List<OcrTextLine>,
        pageWidth: Int,
        pageHeight: Int,
    ): FigureAnalysis {
        if (pageWidth <= 0 || pageHeight <= 0) return FigureAnalysis.NONE
        val valid = lines.filter { !it.box.isEmpty }
        if (valid.isEmpty()) {
            // 一个字都没识别出来：既可能是纯图形题，也可能是糊片。
            // 置信度信号缺失，只按「整页空白」处理，不给满分，交给路由的「无可用文本 -> 视觉」兜底。
            return FigureAnalysis(
                blankBandRatio = 1.0,
                lowConfidenceRatio = 0.0,
                hasFigureSymbol = false,
                score = WEIGHT_BLANK,
            )
        }

        val blankBandRatio = blankBandRatio(valid, pageWidth, pageHeight)
        val lowConfidenceRatio = valid.count { it.normalizedConfidence < LOW_CONFIDENCE }.toDouble() / valid.size
        val hasFigureSymbol =
            valid.any { line ->
                line.text.any { it in FIGURE_SYMBOLS } || FIGURE_KEYWORDS.any { line.text.contains(it) }
            }

        var score = 0.0
        if (blankBandRatio >= BLANK_BAND_HEIGHT_RATIO) score += WEIGHT_BLANK
        if (lowConfidenceRatio >= LOW_CONFIDENCE_RATIO) score += WEIGHT_LOW_CONFIDENCE
        if (hasFigureSymbol) score += WEIGHT_SYMBOL

        return FigureAnalysis(
            blankBandRatio = blankBandRatio,
            lowConfidenceRatio = lowConfidenceRatio,
            hasFigureSymbol = hasFigureSymbol,
            score = score,
        )
    }

    /**
     * 大块空白带的垂直占比：把行按 top 排序，统计相邻行之间（含首行之上、末行之下）
     * 高度 >= 页面高度 12% 且横向宽度 >= 页面宽度 50% 的空隙，累加其高度。
     */
    fun blankBandRatio(
        lines: List<OcrTextLine>,
        pageWidth: Int,
        pageHeight: Int,
    ): Double {
        if (pageHeight <= 0) return 0.0
        val sorted = lines.sortedBy { it.box.top }
        val minGapHeight = (pageHeight * BLANK_BAND_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val minGapWidth = (pageWidth * BLANK_BAND_WIDTH_RATIO).toInt().coerceAtLeast(1)
        var blankHeight = 0

        var cursor = 0
        for (line in sorted) {
            val gap = line.box.top - cursor
            if (gap >= minGapHeight && line.box.width >= minGapWidth) blankHeight += gap
            cursor = maxOf(cursor, line.box.bottom)
        }
        // 末行下方的空白同样可能藏着图形（例如选项下方的坐标图）
        val tailGap = pageHeight - cursor
        if (tailGap >= minGapHeight && sorted.last().box.width >= minGapWidth) blankHeight += tailGap

        return (blankHeight.toDouble() / pageHeight.toDouble()).coerceIn(0.0, 1.0)
    }
}

/** OCR 失败原因（可读中文，直接展示给用户；均为本地失败，与网络无关）。 */
enum class OcrError(val userMessage: String) {
    /** 识别器初始化失败（机型 ROM 异常）。 */
    RECOGNIZER_UNAVAILABLE("本机文字识别组件初始化失败，可改用手动输入题干。"),

    /** 图片无法解码。 */
    IMAGE_UNREADABLE("图片无法读取，请重新拍照或从相册选择。"),

    /** 识别超时（低端机上超大图）。 */
    TIMEOUT("文字识别超时，请靠近题目重拍一张。"),

    /** 识别过程抛出异常。 */
    RECOGNITION_FAILED("文字识别失败，可改用手动输入题干。"),
}

/** OCR 结果封装。 */
sealed class OcrOutcome {
    data class Success(val result: OcrResult) : OcrOutcome()

    data class Failure(val error: OcrError, val detail: String? = null) : OcrOutcome()
}

/**
 * 端侧文字识别接口。抽成接口便于 UI 层注入假实现做预览与测试。
 */
interface OcrRecognizer {
    /** 识别位图（调用方负责回收）。 */
    suspend fun recognize(bitmap: Bitmap): OcrOutcome

    /** 识别图片文件（内部按长边降采样解码，避免 OOM）。 */
    suspend fun recognizeFile(path: String): OcrOutcome

    /** 识别相册 Uri。 */
    suspend fun recognizeUri(uri: Uri): OcrOutcome

    /** 释放底层识别器（App 级单例，通常无需手动调用）。 */
    fun close()
}

/**
 * ML Kit Text Recognition v2 **bundled** 中文识别器（开发方案 FR-1.2、3.2 第 [4] 步）。
 *
 * 关键取舍：
 * - 依赖 `com.google.mlkit:text-recognition-chinese`（**bundled** 变体），模型随 APK 打包，
 *   **完全离线**；刻意不使用 `com.google.android.gms:play-services-mlkit-text-recognition`
 *   这类需要 Google Play Services 下载模型的变体，保证国内无 GMS 机型可用；
 * - 使用中文识别器同时识别中英混排（[OcrResult.LANGUAGE_ZH_EN]），不做二次语言切换；
 * - 图片只在本机进程内处理，**不上传任何第三方**（无网络请求，无遥测）；
 * - ML Kit 的 Task 用 [suspendCancellableCoroutine] 桥接，避免额外引入
 *   kotlinx-coroutines-play-services 依赖。
 *
 * 生命周期：[TextRecognizer] 是相对昂贵的对象，作为 Hilt 单例持有并在 [close] 中释放。
 */
@Singleton
class OcrEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : OcrRecognizer {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        private val recognizer: TextRecognizer by lazy {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }

        /** 识别器是否可用（首次调用时才真正创建，失败则返回 false 并走手动输入降级）。 */
        fun isAvailable(): Boolean = runCatching { recognizer }.isSuccess

        override suspend fun recognize(bitmap: Bitmap): OcrOutcome =
            withContext(ioDispatcher) {
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                    return@withContext OcrOutcome.Failure(OcrError.IMAGE_UNREADABLE, "位图已回收或尺寸无效")
                }
                val startedAt = System.currentTimeMillis()
                val text =
                    try {
                        withTimeoutOrNull(RECOGNITION_TIMEOUT_MILLIS) {
                            val image = InputImage.fromBitmap(bitmap, 0)
                            suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                                recognizer.process(image)
                                    .addOnSuccessListener { value ->
                                        if (continuation.isActive) continuation.resume(value)
                                    }
                                    .addOnFailureListener { error ->
                                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                                    }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        return@withContext OcrOutcome.Failure(OcrError.RECOGNITION_FAILED, error.message)
                    }

                val recognized: com.google.mlkit.vision.text.Text =
                    text ?: return@withContext OcrOutcome.Failure(OcrError.TIMEOUT)
                OcrOutcome.Success(
                    buildResult(
                        pageWidth = bitmap.width,
                        pageHeight = bitmap.height,
                        rawText = recognized,
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                    ),
                )
            }

        override suspend fun recognizeFile(path: String): OcrOutcome {
            val file = java.io.File(path)
            if (!file.exists() || !file.canRead()) {
                return OcrOutcome.Failure(OcrError.IMAGE_UNREADABLE, path)
            }
            val bitmap = decodeSampled(file.readBytes()) ?: return OcrOutcome.Failure(OcrError.IMAGE_UNREADABLE, path)
            return try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        override suspend fun recognizeUri(uri: Uri): OcrOutcome {
            val bytes =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                return OcrOutcome.Failure(OcrError.IMAGE_UNREADABLE, uri.toString())
            }
            val bitmap = decodeSampled(bytes) ?: return OcrOutcome.Failure(OcrError.IMAGE_UNREADABLE, uri.toString())
            return try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        override fun close() {
            runCatching { recognizer.close() }
        }

        /** 按长边上限降采样解码，控制低端机内存峰值。 */
        private suspend fun decodeSampled(bytes: ByteArray): Bitmap? =
            withContext(ioDispatcher) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val sample =
                    com.totop.app.util.ImageMath.computeInSampleSize(
                        srcWidth = bounds.outWidth,
                        srcHeight = bounds.outHeight,
                        targetLongSide = MAX_DECODE_LONG_SIDE,
                    )
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            }

        /** 把 ML Kit 的 [com.google.mlkit.vision.text.Text] 汇总为 [OcrResult]。 */
        private fun buildResult(
            pageWidth: Int,
            pageHeight: Int,
            rawText: com.google.mlkit.vision.text.Text,
            elapsedMillis: Long,
        ): OcrResult {
            val lines = ArrayList<OcrTextLine>()
            var confidenceSum = 0.0
            var confidenceCount = 0
            for (block in rawText.textBlocks) {
                for (line in block.lines) {
                    val lineConfidence = averageConfidence(line.elements.mapNotNull { it.confidence })
                    // ML Kit 的 TextElement.confidence 为非空 Float（缺省为 -1），此处只需判断是否有有效值
                    if (line.elements.isNotEmpty()) {
                        confidenceSum += lineConfidence
                        confidenceCount++
                    }
                    lines +=
                        OcrTextLine(
                            text = line.text,
                            confidence = lineConfidence,
                            box = RectBox.from(line.boundingBox ?: continue),
                        )
                }
            }
            val avgConfidence = if (confidenceCount == 0) 0.0 else confidenceSum / confidenceCount
            val analysis = OcrFigureHeuristics.analyze(lines, pageWidth, pageHeight)
            return OcrResult(
                text = normalizeText(rawText.text),
                avgConfidence = avgConfidence.coerceIn(0.0, 1.0),
                hasFigure = analysis.hasFigure,
                language = OcrResult.LANGUAGE_ZH_EN,
                lines = lines,
                analysis = analysis,
                elapsedMillis = elapsedMillis,
            )
        }

        private fun averageConfidence(values: List<Float>): Double {
            if (values.isEmpty()) return 0.0
            val sum = values.sumOf { it.toDouble() }
            val avg = sum / values.size
            // 个别器件返回 0..100
            return (if (avg > 1.0) avg / 100.0 else avg).coerceIn(0.0, 1.0)
        }

        /** 归一化：去掉行尾多余空白、压缩连续空行，保证阶段 A 的输入干净。 */
        private fun normalizeText(raw: String): String =
            raw.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")

        companion object {
            /** 单次识别超时（低端机上限）。 */
            const val RECOGNITION_TIMEOUT_MILLIS: Long = 20_000L

            /** 解码长边上限：超过则降采样，识别精度仍足够。 */
            const val MAX_DECODE_LONG_SIDE: Int = 1600
        }
    }
