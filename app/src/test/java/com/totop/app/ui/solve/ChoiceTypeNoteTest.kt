package com.totop.app.ui.solve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 题型不确定那行小字的出现条件。
 *
 * 边界很清楚，但错一次就全站错：
 * - 没有选项的题不该出现（它本来就没有单选多选之分）；
 * - 已判定为单选或多选的题不该出现（那是确定的，再提一句只会添乱）；
 * - 只有「有选项但判不出」时才出现。
 */
@DisplayName("题型不确定说明")
class ChoiceTypeNoteTest {
    @Test
    @DisplayName("有选项且题型未知时才提示")
    fun showsOnlyWhenUnknownWithOptions() {
        assertEquals(
            "题干没写明单选还是多选，这里按单选作答",
            ChoiceTypeNote.of(choiceType = "unknown", optionCount = 4),
        )
        // 老数据里根本没有题型字段，同样按未知处理
        assertEquals(
            "题干没写明单选还是多选，这里按单选作答",
            ChoiceTypeNote.of(choiceType = null, optionCount = 3),
        )
    }

    @Test
    @DisplayName("没有选项时不提示")
    fun hidesWithoutOptions() {
        assertNull(ChoiceTypeNote.of(choiceType = "unknown", optionCount = 0))
        assertNull(ChoiceTypeNote.of(choiceType = null, optionCount = 0))
    }

    @Test
    @DisplayName("题型已确定时不提示")
    fun hidesWhenDecided() {
        assertNull(ChoiceTypeNote.of(choiceType = "single", optionCount = 4))
        assertNull(ChoiceTypeNote.of(choiceType = "multi", optionCount = 4))
        assertNull(ChoiceTypeNote.of(choiceType = "none", optionCount = 0))
    }
}
