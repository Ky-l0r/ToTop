package com.totop.app.domain.model

/**
 * 错因分类：固定七类，顺序与中文名不可变。
 *
 * 约束来源：开发方案 FR-1.5「错因分类固定枚举：审题理解 / 概念混淆 / 公式误用 /
 * 计算失误 / 方法选择不当 / 步骤缺失 / 书写规范」。
 *
 * [code] 为稳定的机器可读标识（用于持久化与 AI 交互），[label] 为界面展示文本，
 * [synonyms] 为模型可能返回的近义写法，由 ErrorTypeMapper 做安全归一。
 */
enum class ErrorType(
    val code: String,
    val label: String,
    val synonyms: List<String> = emptyList(),
) {
    /** 审题理解：没有读懂题目条件或所求。 */
    READING_COMPREHENSION(
        code = "READING_COMPREHENSION",
        label = "审题理解",
        synonyms = listOf("审题", "审题不清", "审题不准", "理解题意有误", "题意理解错误"),
    ),

    /** 概念混淆：定义、性质、判定被张冠李戴。 */
    CONCEPT_CONFUSION(
        code = "CONCEPT_CONFUSION",
        label = "概念混淆",
        synonyms = listOf("概念不清", "概念理解错误", "定义混淆", "混淆概念"),
    ),

    /** 公式误用：公式记错或用错适用条件。 */
    FORMULA_MISUSE(
        code = "FORMULA_MISUSE",
        label = "公式误用",
        synonyms = listOf("公式使用错误", "公式套用错误", "公式记错", "乱用公式"),
    ),

    /** 计算失误：思路正确但运算出错。 */
    CALCULATION_ERROR(
        code = "CALCULATION_ERROR",
        label = "计算失误",
        synonyms = listOf("计算错误", "运算失误", "算错", "计算失误"),
    ),

    /** 方法选择不当：入口或方法选错，导致思路走不通。 */
    METHOD_MISMATCH(
        code = "METHOD_MISMATCH",
        label = "方法选择不当",
        synonyms = listOf("方法不当", "方法错误", "思路偏差", "解法选择不当"),
    ),

    /** 步骤缺失：跳步、漏证、缺少必要推导。 */
    MISSING_STEPS(
        code = "MISSING_STEPS",
        label = "步骤缺失",
        synonyms = listOf("步骤不全", "缺少步骤", "跳步", "过程缺失"),
    ),

    /** 书写规范：符号、单位、格式不合规。 */
    NOTATION_NORM(
        code = "NOTATION_NORM",
        label = "书写规范",
        synonyms = listOf("书写不规范", "格式不规范", "表达不规范", "单位不规范"),
    ),
    ;

    companion object {
        /** 固定七类。 */
        const val FIXED_COUNT: Int = 7

        /** 固定七类，按产品定义顺序排列。 */
        val FIXED: List<ErrorType> = values().toList()

        /** 固定七类中文名，按产品定义顺序排列。 */
        val FIXED_LABELS: List<String> = values().map { it.label }

        /**
         * 兜底枚举：AI 返回无法识别或缺失错因时归入此值。
         * 兜底值必须是七类之一，不允许新增「未知」类别（枚举数量固定为七）。
         */
        val FALLBACK: ErrorType = METHOD_MISMATCH

        /** 按机器码查找；大小写、空格、下划线、连字符不敏感。 */
        fun ofCode(code: String?): ErrorType? {
            val token = normalize(code) ?: return null
            return values().firstOrNull { normalize(it.code) == token }
        }

        /** 按中文名 / 近义词查找；忽略首尾空白。 */
        fun ofLabel(label: String?): ErrorType? {
            val token = label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return values().firstOrNull { it.label == token || it.synonyms.any { s -> s == token } }
        }

        /** 按机器码或中文名 / 近义词查找；均未命中返回 null。 */
        fun ofCodeOrLabel(raw: String?): ErrorType? = ofCode(raw) ?: ofLabel(raw)

        private fun normalize(raw: String?): String? =
            raw
                ?.trim()
                ?.lowercase()
                ?.replace("_", "")
                ?.replace("-", "")
                ?.replace(" ", "")
                ?.takeIf { it.isNotEmpty() }
    }
}
