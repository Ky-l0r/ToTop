package com.totop.app.domain.model

/**
 * 题型（0.9 新增，随阶段 A 输出）。
 *
 * 为什么要有它：同是「求解」，数学选择题的答案要按选项校验、语文文言文要逐句落实、
 * 英语读后续写要按情节推进写两段——一份通用模板只能写到「把步骤写清楚」这个层面。
 * 有了题型，阶段 B 才能按题挂上对应的补充要领（`assets/prompts/type_notes.md`）。
 *
 * 取值是**受控枚举**而不是自由文本：自由文本会出现「阅读题」「阅读理解」「现代文阅读」
 * 三种写法，客户端要靠猜才能对上补充要领。
 *
 * 未能判定时用 [UNKNOWN]：宁可走通用模板，也不要把语文题套进数学骨架。
 */
enum class QuestionType(val wire: String) {
    MATH_CHOICE("math_choice"),
    MATH_FILL("math_fill"),
    MATH_SOLVE("math_solve"),
    CHINESE_READING("chinese_reading"),
    CHINESE_CLASSICAL("chinese_classical"),
    CHINESE_COMPOSITION("chinese_composition"),
    ENGLISH_READING("english_reading"),
    ENGLISH_SEVEN_FIVE("english_seven_five"),
    ENGLISH_CLOZE("english_cloze"),
    ENGLISH_GRAMMAR_FILL("english_grammar_fill"),
    ENGLISH_COMPOSITION("english_composition"),
    ENGLISH_CONTINUATION("english_continuation"),
    SCIENCE_SOLVE("science_solve"),
    HUMANITIES("humanities"),
    TECHNOLOGY("technology"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(raw: String?): QuestionType =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: UNKNOWN
    }
}
