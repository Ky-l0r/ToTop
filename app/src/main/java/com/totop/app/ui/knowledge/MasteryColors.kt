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

    /**
     * 错因饼图的调色板（七类固定顺序）。
     *
     * 旧版是七个饱和度拉满的深色（墨绿、赭石、藏蓝、紫、砖红、青、灰），
     * 色相之间没有关系，同一块饼里出现三四块就非常刺眼。现在改为一组
     * **同明度、同饱和度**的柔和色，冷暖交替排列：
     * 1. 相邻扇区永远一暖一冷，不会糊成一片；
     * 2. 明度交替（深-中-深-中…），黑白打印也能靠深浅分出边界；
     * 3. 与主题的紫、语义的成功绿/警示橙同族，不破坏整页观感。
     */
    val PiePalette: List<Color> =
        listOf(
            Color(0xFFC2703A),
            Color(0xFF7B5AA6),
            Color(0xFF3F7FA6),
            Color(0xFFC9A227),
            Color(0xFF4E8C6A),
            Color(0xFF8C6A4F),
            Color(0xFF8A8794),
        )

    fun pieColor(index: Int): Color = PiePalette[index % PiePalette.size]
}
