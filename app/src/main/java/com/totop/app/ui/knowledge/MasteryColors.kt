package com.totop.app.ui.knowledge

import androidx.compose.ui.graphics.Color
import com.totop.app.domain.model.MasteryBand
import com.totop.app.ui.theme.Tokens

/**
 * 掌握度色阶取色（红 <40 / 黄 40-70 / 绿 >70，未练习为灰）。
 *
 * 色值只有一份，在 [Tokens]；本对象只负责把 [MasteryBand] 映射成颜色。
 *
 * 2026-11 收敛前，这里自己存了一套色值（#9B2C2C / #B4690E / #1F6F5C），
 * `Tokens` 与 `theme/Color.kt` 又各存一套（#C0392B / #C79100 / #2E7D5B 等），
 * 同一个「掌握度红」在首页与知识点页是两个值，截图对不上。
 * 现在统一取知识点页一直在用的那一套（低饱和、同明度），另外两套删除。
 */
object MasteryColors {
    /** 红：<40。 */
    val Red: Color = Tokens.MasteryLow

    /** 黄：40-70。 */
    val Yellow: Color = Tokens.MasteryMid

    /** 绿：>70。 */
    val Green: Color = Tokens.MasteryHigh

    /** 未练习。 */
    val Untouched: Color = Tokens.MasteryNone

    fun of(band: MasteryBand): Color =
        when (band) {
            MasteryBand.RED -> Red
            MasteryBand.YELLOW -> Yellow
            MasteryBand.GREEN -> Green
        }

    /** 错因饼图调色板；排序与配色理由见 [Tokens.MasteryPiePalette]。 */
    val PiePalette: List<Color> = Tokens.MasteryPiePalette

    fun pieColor(index: Int): Color = PiePalette[index % PiePalette.size]
}
