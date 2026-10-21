package com.totop.app.ui.components

import androidx.compose.ui.graphics.Color
import com.totop.app.domain.model.StudentStepStatus

/** 卡片语义色（与主题色保持同一套，禁止页面内私自新增色值）。 */
object SolveColors {
    /** 正确（绿）。 */
    val Correct: Color = Color(0xFF1F6F5C)

    /** 错误（红）。 */
    val Wrong: Color = Color(0xFF9B2C2C)

    /** 缺失（琥珀）。 */
    val Missing: Color = Color(0xFFB4690E)

    /** 提示（琥珀浅）。 */
    val Hint: Color = Color(0xFFB4690E)

    /** 中性次要文本。 */
    val Muted: Color = Color(0xFF757873)

    /** 三态取色。 */
    fun of(status: StudentStepStatus): Color =
        when (status) {
            StudentStepStatus.CORRECT -> Correct
            StudentStepStatus.WRONG -> Wrong
            StudentStepStatus.MISSING -> Missing
        }

    /** 三态中文标签。 */
    fun labelOf(status: StudentStepStatus): String =
        when (status) {
            StudentStepStatus.CORRECT -> "正确"
            StudentStepStatus.WRONG -> "错误"
            StudentStepStatus.MISSING -> "缺失"
        }
}
