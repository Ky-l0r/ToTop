package com.totop.app.ui.knowledge

import androidx.compose.ui.graphics.Color
import com.totop.app.domain.model.MasteryBand

/** 掌握度色阶取色（红 <40 / 黄 40-70 / 绿 >70，未练习为灰）。 */
object MasteryColors {
    /** 红：<40。 */
    val Red: Color = Color(0xFF9B2C2C)

    /** 黄：40-70。 */
    val Yellow: Color = Color(0xFFB4690E)

    /** 绿：>70。 */
    val Green: Color = Color(0xFF1F6F5C)

    /** 未练习。 */
    val Untouched: Color = Color(0xFF9AA0A6)

    fun of(band: MasteryBand): Color =
        when (band) {
            MasteryBand.RED -> Red
            MasteryBand.YELLOW -> Yellow
            MasteryBand.GREEN -> Green
        }

    /** 错因饼图的调色板（七类固定顺序，色差足够大且都对色盲友好）。 */
    val PiePalette: List<Color> =
        listOf(
            Color(0xFF2F6F4E),
            Color(0xFFB4690E),
            Color(0xFF3A5BA0),
            Color(0xFF8C3B8C),
            Color(0xFF9B2C2C),
            Color(0xFF2A7A7A),
            Color(0xFF6B6B6B),
        )

    fun pieColor(index: Int): Color = PiePalette[index % PiePalette.size]
}
