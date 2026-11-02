package com.totop.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 一道题里每个小问的作答进度。
 *
 * 为什么需要「逐问」而不是一个总分数：大题常分 (1)(2)(3)，学生的卡点几乎总落在
 * 某一问的起点。只给「写了 / 没写」，提示与错因仍然指向整题；
 * 逐问标出「写到 / 卡住 / 没写」，AI 才能从卷面本身看出他停在哪。
 *
 * wire 值只有三个，与阶段 A 提示词里的枚举一一对应，不允许扩写。
 */
@Serializable
enum class QuestionProgressState(
    val wire: String,
    val label: String,
) {
    /** 这一问写了（不论对错）。 */
    DONE("done", "写到"),

    /** 这一问开了头但没走完，或明显卡住。 */
    STUCK("stuck", "卡住"),

    /** 这一问一个字都没写。 */
    MISSING("missing", "没写"),
    ;

    companion object {
        /** 认不出时返回 null：宁可少显示一问，也不要把别人的进度猜成「没写」。 */
        fun ofWire(raw: String?): QuestionProgressState? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** 一个小问的进度。整题不分小问时 [no] 为 null。 */
@Serializable
data class QuestionProgress(
    val no: Int?,
    val state: QuestionProgressState,
) {
    /** 界面上的短标签：「(2) 卡住」或「卡住」。 */
    val label: String
        get() = if (no != null) "(" + no + ") " + state.label else state.label
}
