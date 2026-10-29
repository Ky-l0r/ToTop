package com.totop.app.ui.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.ui.components.FormulaParser
import com.totop.app.ui.components.FormulaText
import com.totop.app.ui.theme.Tokens

/** 一行讲解属于哪一类内容（决定颜色与容器）。 */
internal enum class AnswerLineKind {
    /** 小标题（【…】或「解题思路」「计算过程」这类整行标题）。 */
    HEADING,

    /** 带序号的步骤（"4. 系统机械能守恒……"）。 */
    STEP,

    /** 思路：为什么这样想、依据、判断。 */
    REASONING,

    /** 计算：代入、变形、得数这类纯算式行。 */
    CALCULATION,

    /** 结论：答案、故选、因此。 */
    CONCLUSION,

    /** 普通叙述。 */
    NORMAL,
}

/** 讲解的一行。 */
internal data class AnswerLine(
    val kind: AnswerLineKind,
    val text: String,
    /** 步骤编号（[AnswerLineKind.STEP] 才有，如 "4"）。 */
    val stepNo: String? = null,
)

/**
 * 把模型的一段讲解切成带语义的行（纯逻辑，可单测）。
 *
 * 为什么要在客户端解析：追问的回答是自然语言，模型并不会输出结构化字段；
 * 但它的写法其实相当稳定（编号步骤 + 「为什么：」+ 独立算式行）。
 * 只按行首特征归类，不做语义理解，规则可解释、出错也只影响配色不影响内容。
 *
 * 归类顺序（先命中先算）：
 * 1. 空行 -> 丢弃（只用统一行距分隔）；
 * 2. 【…】/「解题思路」「计算过程」这类整行标题 -> [AnswerLineKind.HEADING]；
 * 3. 以「为什么」「因为」「依据」开头 -> [AnswerLineKind.REASONING]；
 * 4. 以「答案」「结论」「所以选」「故选」开头 -> [AnswerLineKind.CONCLUSION]；
 * 5. 以数字编号开头 -> [AnswerLineKind.STEP]；
 * 6. 含有公式定界符或等号、且没有较多中文叙述 -> [AnswerLineKind.CALCULATION]；
 * 7. 其余 -> [AnswerLineKind.NORMAL]。
 */
internal object AnswerOutline {
    /** 可能单独成行的标题词。 */
    private val HEADINGS = setOf("解题思路", "思路", "分析", "计算过程", "计算", "解答", "小结", "总结", "结论", "答案")

    /** 思路类行首。 */
    private val REASONING_PREFIXES = listOf("为什么", "因为", "依据", "说明", "注意", "提示", "判断依据", "理由是")

    /** 结论类行首。 */
    private val CONCLUSION_PREFIXES = listOf("答案", "结论", "故选", "因此选", "综上", "最终答案")

    fun parse(raw: String): List<AnswerLine> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<AnswerLine>()
        raw.lineSequence().forEach { rawLine ->
            val line = normalize(rawLine)
            if (line.isEmpty()) return@forEach
            result += classify(line)
        }
        return result
    }

    /** 去掉 Markdown 残留（模型偶尔会加 ** 与 #），并压缩首尾空白。 */
    fun normalize(input: String): String =
        input
            .trim()
            .removePrefix("###")
            .removePrefix("##")
            .removePrefix("#")
            .trim()
            .replace("**", "")
            .trim()

    private fun classify(line: String): AnswerLine {
        // 【…】整行标题
        if (line.length <= HEADING_MAX_CHARS && line.startsWith("【") && line.endsWith("】")) {
            return AnswerLine(AnswerLineKind.HEADING, line.removeSurrounding("【", "】"))
        }
        // 「解题思路」「计算过程：」这类整行标题
        val headingCandidate = line.trimEnd('：', ':', ' ').trim()
        if (headingCandidate in HEADINGS) {
            return AnswerLine(AnswerLineKind.HEADING, headingCandidate)
        }
        stepNumberOf(line)?.let { number ->
            val body = line.substring(number.length + 1).trimStart(' ', '、', '.', ')', '）')
            return AnswerLine(AnswerLineKind.STEP, body.ifBlank { line }, stepNo = number)
        }
        if (REASONING_PREFIXES.any { line.startsWith(it) }) {
            return AnswerLine(AnswerLineKind.REASONING, line)
        }
        if (CONCLUSION_PREFIXES.any { line.startsWith(it) }) {
            return AnswerLine(AnswerLineKind.CONCLUSION, line)
        }
        if (looksLikeCalculation(line)) {
            return AnswerLine(AnswerLineKind.CALCULATION, line)
        }
        return AnswerLine(AnswerLineKind.NORMAL, line)
    }

    /**
     * 行首的步骤编号：支持 "4. " 与 "4、" 两种写法。
     *
     * 只在编号后面紧跟空格或顿号时才算——"2020 年" 这种数字开头的正文不能被当成步骤。
     */
    private fun stepNumberOf(line: String): String? {
        val digits = line.takeWhile { it in '0'..'9' }
        if (digits.isEmpty() || digits.length > 2) return null
        val rest = line.drop(digits.length)
        val marker = rest.firstOrNull() ?: return null
        if (marker != '.' && marker != '、') return null
        if (marker == '.' && rest.getOrNull(1)?.isDigit() == true) return null
        return digits
    }

    /**
     * 是否是「算式行」。
     *
     * 判据：含公式定界符或等号，且中文字符很少。
     * 纯中文长句里夹一个等号（「由上式可见，速度与时间成正比」）仍算叙述，
     * 只有当这一行主要就是算式时才归到计算色，否则整段话都会被染成青色。
     */
    private fun looksLikeCalculation(line: String): Boolean {
        val hasMath = FormulaParser.containsDelimiter(line) || line.contains('=') || line.contains('＝')
        if (!hasMath) return false
        val chinese = line.count { it.code in CJK_START..CJK_END }
        return chinese <= CALCULATION_MAX_CHINESE
    }

    /** 汉字区间起点（U+4E00），只用来数「这行有几个汉字」。 */
    private const val CJK_START = 0x4E00

    /** 汉字区间终点（U+9FFF）。 */
    private const val CJK_END = 0x9FFF

    /** 整行标题的最大长度：超过这个长度基本是一句正文，而不是标题。 */
    private const val HEADING_MAX_CHARS = 12

    /** 算式行允许出现的中文字数上限。 */
    private const val CALCULATION_MAX_CHINESE = 10
}

/**
 * AI 讲解正文：按行分类渲染，思路与计算用不同颜色，结论单独强调。
 *
 * 排版上的三条硬约定：
 * 1. 行与行之间统一 6dp 间距，不靠空行分隔——模型给的空行数量不稳定；
 * 2. 思路块与计算块各自带浅底，连续几行算式会被聚成一个视觉块，
 *    这正是「一屏公式堆在一起看不清」的正面修法；
 * 3. 公式本身仍交给 [FormulaText]（行内公式不换行），这里只负责颜色与分组。
 */
@Composable
internal fun AnswerBody(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val lines = remember(text) { AnswerOutline.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            when (line.kind) {
                AnswerLineKind.HEADING -> HeadingLine(line.text)
                AnswerLineKind.STEP -> StepLine(stepNo = line.stepNo, text = line.text, style = style)
                AnswerLineKind.REASONING -> ReasoningLine(text = line.text, style = style)
                AnswerLineKind.CALCULATION -> CalculationLine(text = line.text, style = style)
                AnswerLineKind.CONCLUSION -> ConclusionLine(text = line.text, style = style)
                AnswerLineKind.NORMAL -> FormulaText(text = line.text, style = style)
            }
        }
    }
}

/** 小标题：左侧一条主色竖标 + 半粗文字，扫读时能一眼找到分段。 */
@Composable
private fun HeadingLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.Primary),
        )
        Box(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.OnSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 步骤行：编号圆标 + 正文（算式照旧走公式渲染）。 */
@Composable
private fun StepLine(
    stepNo: String?,
    text: String,
    style: TextStyle,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (stepNo != null) {
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Tokens.PrimaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stepNo,
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.Primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            FormulaText(text = text, style = style)
        }
    }
}

/** 思路行：淡紫底 + 左侧竖标 + 「思路」小标，表示「为什么这样做」。 */
@Composable
private fun ReasoningLine(
    text: String,
    style: TextStyle,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.ReasoningContainer)
                .padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.Reasoning),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = REASONING_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.Reasoning,
                fontWeight = FontWeight.Medium,
            )
            FormulaText(text = stripReasoningPrefix(text), style = style.copy(color = Tokens.Reasoning))
        }
    }
}

/** 计算行：淡青底，算式独立成块，不与叙述混排。 */
@Composable
private fun CalculationLine(
    text: String,
    style: TextStyle,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.CalculationContainer)
                .padding(vertical = 6.dp, horizontal = 10.dp),
    ) {
        FormulaText(text = text, style = style.copy(color = Tokens.Calculation))
    }
}

/** 结论行：主色浅底 + 半粗，答案必须比过程更显眼。 */
@Composable
private fun ConclusionLine(
    text: String,
    style: TextStyle,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.PrimaryContainer)
                .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        FormulaText(
            text = text,
            style = style.copy(color = Tokens.Primary, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** 思路块的小标文案。 */
private const val REASONING_LABEL = "思路"

/**
 * 去掉「为什么：」这类前缀。
 *
 * 前缀已经由 [REASONING_LABEL] 承担，留着会变成「思路 / 为什么：……」的重复。
 */
private fun stripReasoningPrefix(text: String): String {
    val trimmed = text.trimStart()
    for (prefix in listOf("为什么", "因为", "依据", "理由是", "判断依据", "说明", "注意", "提示")) {
        if (!trimmed.startsWith(prefix)) continue
        return trimmed.removePrefix(prefix).trimStart('：', ':', '，', ',', ' ').ifBlank { text }
    }
    return text
}
