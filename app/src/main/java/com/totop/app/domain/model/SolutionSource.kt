package com.totop.app.domain.model

/**
 * 标准解答的来源（0.9，落库到 `mistake_record.solutionSource`）。
 *
 * 为什么要记来源：同一道错题的解答会被复写多次，学生后来看到的那份可能是拍题时 AI 给的、
 * 也可能是自己导入的标准答案，还可能是追问里 AI 改了主意。三者可信度与适用场景不同，
 * 排查「这答案怎么是这个」时，第一步就要看它是哪来的。
 */
enum class SolutionSource(val wire: String, val label: String) {
    /** 拍题时首次给出的解答。 */
    AI("ai", "AI 解答"),

    /** 学生自己导入的标准答案（更权威，覆盖 AI 的）。 */
    IMPORT("import", "导入的答案"),

    /** 点「我核对过，一致」时认定的那一份。 */
    CHECK("check", "核对确认"),

    /** 追问中 AI 重新给出并订正的答案。 */
    FOLLOWUP("followup", "追问订正"),
    ;

    companion object {
        fun fromWire(raw: String?): SolutionSource = entries.firstOrNull { it.wire == raw?.trim() } ?: AI
    }
}
