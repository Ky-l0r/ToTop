package com.totop.app.domain.model

/**
 * 错因字符串 → 固定七类枚举的安全映射结果。
 *
 * @param errorType 归一后的枚举（永不失败）
 * @param matched 是否成功匹配到明确类别（false 表示走了兜底）
 * @param raw 原始输入（诊断与日志用）
 */
data class ErrorTypeMapping(
    val errorType: ErrorType,
    val matched: Boolean,
    val raw: String?,
) {
    /** 是否落入兜底。 */
    val isFallback: Boolean get() = !matched
}

/**
 * 错因映射器（开发方案 FR-1.5 固定七类；6.3 阶段 C 明确禁止「新增错误类型」）。
 *
 * 支持三种输入形态：
 * 1. 机器码：`FORMULA_MISUSE`、`formula-misuse`、`formula_misuse`；
 * 2. 中文标准名：`公式误用`；
 * 3. 常见近义词：`公式用错了`、`算错`、`跳步` 等（见 [ErrorType.synonyms]）。
 *
 * 任何无法识别的输入（null / 空串 / 自造类别 / 英文或其它语种近义表达）都不会抛异常，
 * 而是归入 [ErrorType.FALLBACK]（固定七类之一），由调用方决定是否记录告警。
 */
object ErrorTypeMapper {
    /** 面向提示词与界面展示的全部合法取值（中文名）。 */
    val allowedLabels: List<String> = ErrorType.FIXED_LABELS

    /** 面向持久化与 AI 交互的全部合法机器码。 */
    val allowedCodes: List<String> = ErrorType.FIXED.map { it.code }

    /** 映射为 [ErrorTypeMapping]。 */
    fun map(raw: String?): ErrorTypeMapping {
        ErrorType.ofCodeOrLabel(raw)?.let { return ErrorTypeMapping(it, matched = true, raw = raw) }
        ErrorType.ofCodeOrLabel(sanitize(raw))?.let { return ErrorTypeMapping(it, matched = true, raw = raw) }
        return ErrorTypeMapping(ErrorType.FALLBACK, matched = false, raw = raw)
    }

    /** 只取枚举；未知值返回兜底。 */
    fun mapToType(raw: String?): ErrorType = map(raw).errorType

    /** 只取枚举；未知值返回 null（需要区分「未识别」时使用）。 */
    fun mapToTypeOrNull(raw: String?): ErrorType? =
        ErrorType.ofCodeOrLabel(raw) ?: ErrorType.ofCodeOrLabel(sanitize(raw))

    /** 是否属于固定七类之一。 */
    fun isValid(raw: String?): Boolean = mapToTypeOrNull(raw) != null

    /**
     * 清洗常见包装：去掉引号、方括号、Markdown 粗体/代码标记、序号前缀与结尾标点。
     * 例如 `"**公式误用**"` → `公式误用`，`3. 计算失误！` → `计算失误`。
     */
    fun sanitize(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var value = trimmed
        value = value.trim('"', '\'', '`', '*', '_', ' ', '\u3000', '[', ']', '（', '）', '(', ')')
        value = value.replace(Regex("^[0-9]+[.、:：)）]\\s*"), "")
        value = value.trim('"', '\'', '`', '*', '_', ' ', '\u3000', '。', '，', ',', '.', '!', '！', ';', '；')
        return value.takeIf { it.isNotEmpty() }
    }
}
