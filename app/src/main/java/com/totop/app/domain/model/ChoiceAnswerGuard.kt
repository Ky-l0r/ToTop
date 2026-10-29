package com.totop.app.domain.model

/**
 * 选择题的题型与「答案必须选几个」的校验（纯 Kotlin，可单测）。
 *
 * 为什么需要它：模型经常把单选讲成多选（答案是 A、C），也常把多选答成单选（只给一个字母）。
 * 判错的代价不是「看起来不严谨」——错题本会按错误答案存下来，
 * 学生复习时核对的依据就是错的。因此这里做三件事：
 * 1. 把模型给的题型归一成受控取值；
 * 2. 从答案里**只**提取真正的选项标记（不会把 NaCl 里的 A 当成选项 A）；
 * 3. 给出「与题型不符」的可读说明，供重答与界面提示共用同一套判定。
 */
object ChoiceAnswerGuard {
    /** 单选题：答案必须且只能有一个选项。 */
    const val SINGLE: String = "single"

    /** 多选题：答案必须至少两个选项。 */
    const val MULTI: String = "multi"

    /** 非选择题。 */
    const val NONE: String = "none"

    /** 无法判断：不做数量校验，避免误伤。 */
    const val UNKNOWN: String = "unknown"

    /** 把模型给的题型归一成受控取值。 */
    fun normalize(raw: String?): String =
        when (raw?.trim()?.lowercase()?.replace("-", "_")?.replace(" ", "")) {
            "single", "single_choice", "单选", "单选题", "radio" -> SINGLE
            "multi", "multiple", "multi_choice", "multiple_choice", "多选", "多选题", "checkbox" -> MULTI
            "none", "non_choice", "非选择", "解答题" -> NONE
            else -> UNKNOWN
        }

    /**
     * 从答案文本里取出选项标记。
     *
     * 两条路径：
     * 1. 整串就是字母（"AC"、"A、C" 去掉分隔符后仍是字母）——直接按字母取；
     * 2. 混着选项内容（"A. 铜丝"）——只认**后面紧跟分隔符或结尾**的字母，
     *    否则 "NaCl"、"AgNO3" 里的 A 会被当成选项 A，把单选题误判成多选。
     */
    fun lettersOf(answer: String): List<Char> {
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) return emptyList()
        // 整串只有字母与分隔符（"AC"、"A、C"、"A,C"）：直接按字母取
        if (trimmed.all { it in 'A'..'H' || it in LETTER_SEPARATORS }) {
            val letters = trimmed.filter { it in 'A'..'H' }
            if (letters.isNotEmpty()) return letters.toList().distinct()
        }
        return OPTION_LETTER.findAll(trimmed).map { it.groupValues[1][0] }.distinct().toList()
    }

    /**
     * 与题型不符时返回面向学生的说明；一致或无法判断时返回 null。
     *
     * @param options 选项原文（少于 2 个视为不是选择题）
     * @param finalAnswer 模型给的最终答案
     * @param choiceType 阶段 A 判定的题型
     */
    fun violation(
        options: List<String>,
        finalAnswer: String,
        choiceType: String,
    ): String? {
        if (options.size < 2) return null
        val letters = lettersOf(finalAnswer)
        if (letters.isEmpty()) return null
        return when (normalize(choiceType)) {
            SINGLE ->
                if (letters.size > 1) {
                    "按题型这是单选题，答案却给了 " + letters.size + " 个选项（" +
                        letters.joinToString("、") + "），只应保留一个。"
                } else {
                    null
                }
            MULTI ->
                if (letters.size == 1) {
                    "按题型这是多选题，答案却只给了 " + letters.first() + " 一个选项，需要用排除法逐项核对。"
                } else {
                    null
                }
            else -> null
        }
    }

    /** 重答时追加给模型的硬约束（只在与题型不符时生成）。 */
    fun retryInstruction(
        options: List<String>,
        finalAnswer: String,
        choiceType: String,
    ): String? {
        val problem = violation(options, finalAnswer, choiceType) ?: return null
        val type = normalize(choiceType)
        val rule =
            if (type == SINGLE) {
                "本题考查的是**单选题**：finalAnswer 只能包含一个选项字母，" +
                    "其余选项必须在 steps 里逐个说明为什么不对。"
            } else {
                "本题考查的是**多选题**：finalAnswer 必须列出**全部**正确选项（至少两个），" +
                    "并在 steps 里逐个选项判断对错。"
            }
        return "上一次的答案与题型不符：" + problem + "\n" + rule + "\n请重新给出完整解答。"
    }

    /** 选项字母后面必须跟这些分隔符（或直接结束），才认为它是「选项标记」而不是单词的一部分。 */
    private val OPTION_LETTER = Regex("(?<![A-Za-z])([A-H])(?=\\s*[、,，/／;；。.．]|\\s*$|\\)|）)")

    /** 只有字母型答案里允许出现的分隔符。 */
    private const val LETTER_SEPARATORS: String = "、,，/／;； "
}
