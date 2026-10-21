package com.totop.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/**
 * 一条 LaTeX 口语化规则（与 assets/seed/latex_spoken_rules.json 一一对应）。
 *
 * @param pattern Java/Kotlin 正则字面量（JSON 中已转义）
 * @param type 替换语义，见 [LatexRuleType]
 * @param spoken 中文口语模板，用 $1、$2 引用捕获组
 * @param order 在规则表中的原始下标，决定匹配顺序（先原子命令、后结构、最后分数）
 */
data class LatexSpokenRule(
    val pattern: String,
    val type: LatexRuleType,
    val spoken: String,
    val order: Int,
)

/**
 * 规则替换语义。
 *
 * - [PREFIX]：命令是后续内容的**前缀**，替换后其后的内容继续参与后续规则。
 *   例如 `\\sin 30^\\circ` 里的 `\\sin` -> 「正弦」，30 度由后面的规则继续处理。
 * - [WHOLE]：整个匹配片段替换为口语。
 */
enum class LatexRuleType(val wire: String) {
    PREFIX("prefix"),
    WHOLE("whole"),
    ;

    companion object {
        /** 解析 JSON 中的 type 字段；未知取值按 [WHOLE] 处理，保证后续扩充资产也能加载。 */
        fun fromWire(value: String?): LatexRuleType = entries.firstOrNull { it.wire == value } ?: WHOLE
    }
}

/** 规则表加载失败原因（可读中文，直接用于日志与页面提示）。 */
enum class LatexRuleTableError(val userMessage: String) {
    /** 资产缺失。 */
    ASSET_MISSING("内置公式朗读规则表缺失，公式将按原文朗读。"),

    /** JSON 结构损坏（不是数组、缺字段、为空）。 */
    MALFORMED("内置公式朗读规则表格式错误，公式将按原文朗读。"),

    /** 正则编译失败（pattern 非法）。 */
    INVALID_PATTERN("内置公式朗读规则表存在非法正则，公式将按原文朗读。"),
}

/** 规则表加载结果。 */
sealed class LatexRuleTableLoad {
    data class Success(val table: LatexSpokenRuleTable) : LatexRuleTableLoad()

    /** 失败时不抛异常，调用方用 [fallback] 继续朗读，整条链路不断。 */
    data class Failure(val error: LatexRuleTableError, val detail: String? = null) : LatexRuleTableLoad() {
        val fallback: LatexSpokenRuleTable get() = LatexSpokenRuleTable.EMPTY
    }
}

/**
 * LaTeX 口语化规则表（开发方案 FR-6.4）。
 *
 * 职责：把 `$\\frac{1}{2}$` 这类 LaTeX 片段转成「2 分之 1」这样的中文口语句子交给 TTS 朗读。
 * 规则全部外置在 assets/seed/latex_spoken_rules.json（76 条），
 * 本类只负责**加载、编译与匹配**，不在代码里硬编码任何替换文案。
 *
 * 三条硬约束：
 * 1. **纯函数**：除构造时的正则编译外无状态、无 Android 依赖，
 *    因此可在 JVM 单测（isReturnDefaultValues = true）中直接覆盖；
 * 2. **失败不断链**：资产缺失或格式错误时退化为 [EMPTY]，朗读降级为原文；
 * 3. **规则外内容降级为原文**：未被任何规则匹配的控制序列不丢失数学内容，
 *    只去掉排版用的反斜杠与花括号，保证 TTS 念出的是可理解内容而不是乱码。
 *
 * 线程安全：构造后不可变，可跨线程共享（Hilt 单例）。
 */
class LatexSpokenRuleTable internal constructor(rules: List<LatexSpokenRule>) {
    /** 原始规则（按 JSON 数组顺序）。 */
    val rules: List<LatexSpokenRule> = rules.sortedBy { it.order }

    private val compiled: List<CompiledRule> =
        this.rules.mapNotNull { rule ->
            runCatching { CompiledRule(rule, Regex(rule.pattern)) }.getOrNull()
        }

    /** 成功编译的规则条数（资产校验与单测用）。 */
    val compiledRuleCount: Int get() = compiled.size

    /** 规则表是否为空（资产缺失时的兜底表）。 */
    val isEmpty: Boolean get() = compiled.isEmpty()

    /**
     * 把一段**纯 LaTeX** 转成中文口语句子。
     *
     * @param latex LaTeX 源码，可带或不带数学分隔符
     * @param stripDelimiters 是否先剥掉整体的数学分隔符（默认 true）
     */
    fun speak(
        latex: String,
        stripDelimiters: Boolean = true,
    ): String {
        if (latex.isEmpty()) return latex
        val text = if (stripDelimiters) stripMathDelimiters(latex) else latex
        var current = text
        for (rule in compiled) {
            current = rule.apply(current)
            if (current.isEmpty()) return current
        }
        return cleanResidue(current)
    }

    /**
     * 把**中英混排题干**中包裹在数学分隔符里的片段口语化，分隔符之外原文保持不变。
     *
     * 这是 TTS 朗读题干的默认入口：题干里的中文说明、标点、编号不会被公式规则破坏。
     * 若整段不含任何数学分隔符，则原样返回，避免误伤普通文本。
     */
    fun speakRichText(text: String): String {
        if (text.isEmpty()) return text
        val segments = splitMathSegments(text) ?: return text
        val out = StringBuilder(text.length + 16)
        for (segment in segments) {
            when (segment) {
                is MathSegment.Plain -> out.append(segment.text)
                is MathSegment.Math -> out.append(speak(segment.latex, stripDelimiters = false))
            }
        }
        return out.toString()
    }

    /** 规则的纯文本表示，便于日志与单测断言顺序。 */
    fun describe(): List<String> = rules.map { it.order.toString() + ":" + it.type.wire + ":" + it.pattern }

    private class CompiledRule(val rule: LatexSpokenRule, val regex: Regex) {
        /**
         * 应用单条规则。
         *
         * 反复替换直到文本稳定，以支持嵌套结构（如分数里再套平方）；
         * 但设有 [MAX_APPLICATIONS] 上限，且一旦替换结果与匹配原文相同立即停止，
         * 保证任何规则表都不会把朗读线程拖进死循环。
         */
        fun apply(input: String): String {
            if (!regex.containsMatchIn(input)) return input
            var current = input
            var guard = 0
            while (guard < MAX_APPLICATIONS) {
                var changed = false
                val next =
                    regex.replace(current) { match ->
                        val replacement = expand(rule.spoken, match)
                        if (replacement != match.value) changed = true
                        replacement
                    }
                if (!changed || next == current) return next
                current = next
                guard++
            }
            return current
        }

        /** 把 spoken 模板里的 `$n` 展开为捕获组内容；越界组视为空串。 */
        private fun expand(
            template: String,
            match: MatchResult,
        ): String {
            val out = StringBuilder(template.length + 8)
            var index = 0
            while (index < template.length) {
                val char = template[index]
                if (char == '$' && index + 1 < template.length && template[index + 1].isDigit()) {
                    var cursor = index + 1
                    var group = 0
                    while (cursor < template.length && template[cursor].isDigit()) {
                        group = group * 10 + (template[cursor] - '0')
                        cursor++
                    }
                    out.append(match.groupValues.getOrElse(group) { "" })
                    index = cursor
                } else {
                    out.append(char)
                    index++
                }
            }
            return out.toString()
        }
    }

    /** 题干切分后的片段：普通文本或数学片段（不含分隔符）。 */
    internal sealed class MathSegment {
        class Plain(val text: String) : MathSegment()

        class Math(val latex: String) : MathSegment()
    }

    companion object {
        /** 资产路径（与 data/README.md 登记一致）。 */
        const val ASSET_PATH: String = "seed/latex_spoken_rules.json"

        /** 单条规则的最大迭代次数，防止病态规则导致死循环。 */
        const val MAX_APPLICATIONS: Int = 64

        /** 兜底空表：不做任何替换，朗读降级为原文。 */
        val EMPTY: LatexSpokenRuleTable = LatexSpokenRuleTable(emptyList())

        /** 未被规则消费的带参数控制序列，例如 `\\text{已知}`。 */
        private val UNKNOWN_COMMAND_WITH_ARG = Regex("""\\[A-Za-z]+\s*\{([^{}]*)\}""")

        /** 未被规则消费的裸控制序列，例如 `\\foo`。 */
        private val UNKNOWN_COMMAND = Regex("""\\[A-Za-z]+""")

        /** 数学环境里的换行控制符（连续反斜杠），整段替换为一个逗号，避免逐字符重复替换。 */
        private val LATEX_LINEBREAK = Regex("\\\\+")

        private val BRACES = Regex("""[{}]""")

        private val SPACES = Regex("""[ \t\u00A0]+""")

        /**
         * 从 JSON 文本加载（单测与调试用）。
         *
         * 刻意使用 kotlinx.serialization 手写解析而不是 org.json：
         * 后者在 JVM 单测（isReturnDefaultValues = true）中返回 null，会把规则表测成空表。
         */
        fun fromJson(json: String): LatexRuleTableLoad {
            // 注意：这里刻意写成块体函数而不是 `= try { ... }` 表达式体，
            // 因为 Kotlin 禁止在表达式体函数中使用 return，而下面的校验需要提前返回。
            return try {
                val array =
                    Json.parseToJsonElement(json) as? JsonArray
                        ?: return LatexRuleTableLoad.Failure(LatexRuleTableError.MALFORMED, "根节点不是数组")
                val parsed = ArrayList<LatexSpokenRule>(array.size)
                for (index in array.indices) {
                    val element = array[index]
                    val obj =
                        element as? JsonObject
                            ?: return LatexRuleTableLoad.Failure(
                                LatexRuleTableError.MALFORMED,
                                "第 " + index + " 项不是对象",
                            )
                    val pattern =
                        obj["pattern"]?.jsonPrimitive?.content
                            ?: return LatexRuleTableLoad.Failure(
                                LatexRuleTableError.MALFORMED,
                                "第 " + index + " 项缺少 pattern",
                            )
                    val spoken =
                        obj["spoken"]?.jsonPrimitive?.content
                            ?: return LatexRuleTableLoad.Failure(
                                LatexRuleTableError.MALFORMED,
                                "第 " + index + " 项缺少 spoken",
                            )
                    val type = LatexRuleType.fromWire(obj["type"]?.jsonPrimitive?.content)
                    if (runCatching { Regex(pattern) }.isFailure) {
                        return LatexRuleTableLoad.Failure(
                            LatexRuleTableError.INVALID_PATTERN,
                            "第 " + index + " 项正则非法：" + pattern,
                        )
                    }
                    parsed += LatexSpokenRule(pattern = pattern, type = type, spoken = spoken, order = index)
                }
                if (parsed.isEmpty()) {
                    LatexRuleTableLoad.Failure(LatexRuleTableError.MALFORMED, "规则为空")
                } else {
                    LatexRuleTableLoad.Success(LatexSpokenRuleTable(parsed))
                }
            } catch (error: Exception) {
                LatexRuleTableLoad.Failure(LatexRuleTableError.MALFORMED, error.message)
            }
        }

        /** 从输入流加载（AssetManager.open 的结果）。 */
        fun fromInputStream(input: InputStream): LatexRuleTableLoad =
            try {
                input.use { fromJson(it.readBytes().toString(Charsets.UTF_8)) }
            } catch (error: Exception) {
                LatexRuleTableLoad.Failure(LatexRuleTableError.ASSET_MISSING, error.message)
            }

        /**
         * 按数学分隔符切分题干；不含任何数学分隔符时返回 null。
         *
         * 手写扫描而不是大正则：`$` 与 `$$` 有前缀歧义，逐字符扫描的行为完全可预期，
         * 也方便单测覆盖「分隔符未闭合」这类脏数据。
         */
        internal fun splitMathSegments(text: String): List<MathSegment>? {
            if (!text.contains('$') && !text.contains("\\(") && !text.contains("\\[")) return null
            val segments = ArrayList<MathSegment>()
            val plain = StringBuilder()
            var index = 0
            var foundAny = false
            while (index < text.length) {
                val open: String? =
                    when {
                        text.startsWith("$$", index) -> "$$"
                        text.startsWith("$", index) -> "$"
                        text.startsWith("\\[", index) -> "\\]"
                        text.startsWith("\\(", index) -> "\\)"
                        else -> null
                    }
                val openLength =
                    when {
                        text.startsWith("$$", index) -> 2
                        text.startsWith("$", index) -> 1
                        text.startsWith("\\[", index) -> 2
                        text.startsWith("\\(", index) -> 2
                        else -> 0
                    }
                if (open == null || openLength == 0) {
                    plain.append(text[index])
                    index++
                    continue
                }
                val closeIndex = text.indexOf(open, index + openLength)
                if (closeIndex < 0) {
                    // 分隔符未闭合：剩下的内容按普通文本处理，绝不吞掉学生题干
                    plain.append(text.substring(index))
                    index = text.length
                    continue
                }
                foundAny = true
                if (plain.isNotEmpty()) {
                    segments += MathSegment.Plain(plain.toString())
                    plain.setLength(0)
                }
                segments += MathSegment.Math(text.substring(index + openLength, closeIndex))
                index = closeIndex + open.length
            }
            if (plain.isNotEmpty()) segments += MathSegment.Plain(plain.toString())
            return if (foundAny) segments else null
        }

        /** 剥掉包裹整体的数学分隔符（`$$...$$`、`$...$`、`\\[...\\]`、`\\(...\\)`）。 */
        private fun stripMathDelimiters(latex: String): String {
            val trimmed = latex.trim()
            val pairs = listOf("$$" to "$$", "$" to "$", "\\[" to "\\]", "\\(" to "\\)")
            for ((open, close) in pairs) {
                if (trimmed.length > open.length + close.length &&
                    trimmed.startsWith(open) &&
                    trimmed.endsWith(close)
                ) {
                    return trimmed.substring(open.length, trimmed.length - close.length).trim()
                }
            }
            return trimmed
        }

        /**
         * 规则外内容清理（「降级为原文」的实现）。
         *
         * 顺序即语义：
         * 1. 带参数的未知命令 `\\text{已知}` -> 保留参数内容；
         * 2. 裸未知命令 `\\foo` -> 去掉反斜杠保留命令名（至少能念出名字）；
         * 3. 换行控制符 -> 逗号停顿；
         * 4. 清理残留花括号与多余空白。
         *
         * 任何情况下都只去掉排版控制符，不丢弃数学内容本身。
         */
        internal fun cleanResidue(text: String): String {
            var result = UNKNOWN_COMMAND_WITH_ARG.replace(text) { it.groupValues[1] }
            result = UNKNOWN_COMMAND.replace(result) { it.value.substring(1) }
            result = LATEX_LINEBREAK.replace(result, "，")
            result = BRACES.replace(result, "")
            result = SPACES.replace(result, " ")
            return result.trim()
        }
    }
}
