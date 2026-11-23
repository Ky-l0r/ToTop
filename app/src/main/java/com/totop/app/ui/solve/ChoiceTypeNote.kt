package com.totop.app.ui.solve

import com.totop.app.domain.model.ChoiceAnswerGuard

/**
 * 题型不确定时那句小字（0.9）。
 *
 * 单独提出来是为了能被单测直接断言：这段文案会出现在每一道「题干没写单选/多选」的题上，
 * 写错一个字就是全站范围的错别字，而 Compose 里没有 UI 测试，只能把判断逻辑挪出来测。
 *
 * 只用于展示：不写进错题本、不参与统计口径。
 */
internal object ChoiceTypeNote {
    /** 有选项但判不出题型时的说明；其余情况返回 null（不占学生视线）。 */
    fun of(
        choiceType: String?,
        optionCount: Int,
    ): String? {
        if (optionCount <= 0) return null
        return when (ChoiceAnswerGuard.normalize(choiceType)) {
            ChoiceAnswerGuard.UNKNOWN -> "题干没写明单选还是多选，这里按单选作答"
            else -> null
        }
    }
}
