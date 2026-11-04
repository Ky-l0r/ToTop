package com.totop.app.ui.reviewmaterial

import com.totop.app.ui.reviewmaterial.ReviewMath.Token
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 轻数学记号解析。
 *
 * 复习资料里的物理量都长这样：`v_top`、`1/2mv²`、`√(2gh)`。
 * 解析错一处，学生看到的就是「v 下划线 top」这种纸面事故，所以这里逐条钉住。
 */
@DisplayName("复习资料轻数学记号")
class ReviewMathTest {
    @Test
    @DisplayName("花括号下标排成下标")
    fun `花括号下标`() {
        assertEquals(
            listOf(Token.Text("v"), Token.Sub("0")),
            ReviewMath.parse("v_{0}"),
        )
    }

    @Test
    @DisplayName("字母下标整词吃进来，不只吃一个字符")
    fun `字母下标`() {
        assertEquals(
            listOf(Token.Text("v"), Token.Sub("top"), Token.Text(" 是最高点速度")),
            ReviewMath.parse("v_top 是最高点速度"),
        )
    }

    @Test
    @DisplayName("只吃字母数字，不把中文拖进下标")
    fun `中文不进下标`() {
        val tokens = ReviewMath.parse("v_最大")
        assertTrue(tokens.all { it is Token.Text })
        assertEquals("v_最大", ReviewMath.flatten(tokens))
    }

    @Test
    @DisplayName("上标支持正负号")
    fun `负指数`() {
        assertEquals(
            listOf(Token.Text("10"), Token.Sup("-3")),
            ReviewMath.parse("10^{-3}"),
        )
        assertEquals(
            listOf(Token.Text("m"), Token.Sup("3")),
            ReviewMath.parse("m^3"),
        )
    }

    @Test
    @DisplayName("纯数字的斜杠写成分式")
    fun `数值分式`() {
        assertEquals(
            listOf(Token.Fraction(listOf(Token.Text("1")), listOf(Token.Text("2")))),
            ReviewMath.parse("1/2"),
        )
    }

    @Test
    @DisplayName("单位缩写的斜杠不是分式")
    fun `单位不拆`() {
        listOf("m/s", "km/h", "J/(kg·℃)", "W/t", "s/t").forEach { text ->
            val tokens = ReviewMath.parse(text)
            assertTrue(tokens.none { it is Token.Fraction }, "不应把 $text 拆成分式")
        }
    }

    @Test
    @DisplayName("日期与连比不拆成分式")
    fun `日期不拆`() {
        listOf("2024/11/05", "1/2/3").forEach { text ->
            assertTrue(ReviewMath.parse(text).none { it is Token.Fraction }, "不应拆 $text")
        }
    }

    @Test
    @DisplayName("根号吃到括号内的全部内容")
    fun `括号根号`() {
        assertEquals(
            listOf(Token.Radical(listOf(Token.Text("2gh")))),
            ReviewMath.parse("√(2gh)"),
        )
    }

    @Test
    @DisplayName("不带括号的根号吃到数字末尾")
    fun `裸根号`() {
        assertEquals(
            listOf(Token.Radical(listOf(Token.Text("3"))), Token.Text(" 倍")),
            ReviewMath.parse("√3 倍"),
        )
    }

    @Test
    @DisplayName("孤零零的根号符号按原文保留")
    fun `孤立根号`() {
        assertEquals(listOf(Token.Text("√")), ReviewMath.parse("√"))
    }

    @Test
    @DisplayName("模型漏出的 LaTeX 分式也能排")
    fun `latex 分式`() {
        assertEquals(
            listOf(Token.Fraction(listOf(Token.Text("s")), listOf(Token.Text("t")))),
            ReviewMath.parse("\\frac{s}{t}"),
        )
    }

    @Test
    @DisplayName("分式内部允许上下标，且只嵌一层")
    fun `分式内上下标`() {
        assertEquals(
            listOf(
                Token.Fraction(
                    numerator = listOf(Token.Text("v"), Token.Sup("2")),
                    denominator = listOf(Token.Text("2g")),
                ),
            ),
            ReviewMath.parse("\\frac{v^{2}}{2g}"),
        )
    }

    @Test
    @DisplayName("漏出的符号命令换成字符，命令后的空格按原样保留")
    fun `裸命令归一化`() {
        // LatexPlain 的口径：符号命令换成 Unicode 字符，源码里写的空格不动
        // （`H_2SO_4 \cdot xSO_3` 也是这么处理的，见 LatexPlainTest）
        assertEquals(
            listOf(Token.Text("F=ma· t")),
            ReviewMath.parse("F=ma\\cdot t"),
        )
    }

    @Test
    @DisplayName("压平后的文字重新解析得到同样的片段（内容一个字都不丢）")
    fun `压平可重解析`() {
        // 逐字相等做不到（解析会丢掉 `{}` 这种分组记号），但「压平再解析仍是同一份片段」
        // 等价于「没有丢内容」，这才是降级显示要保证的东西。
        listOf(
            "v_top=1/2mv²",
            "s=v_0t+1/2at²",
            "√(2gh)",
            "E_k=1/2mv^{2}",
            "重力加速度 g=9.8 m/s²",
        ).forEach { text ->
            val tokens = ReviewMath.parse(text)
            val flattened = ReviewMath.flatten(tokens)
            assertEquals(tokens, ReviewMath.parse(flattened), "压平后重新解析应得到同样的片段：$text -> $flattened")
        }
    }

    @Test
    @DisplayName("不带括号的根号压平时补上括号，避免 √3 与 √(3) 两种写法混淆")
    fun `根号压平补括号`() {
        val tokens = ReviewMath.parse("√3")
        assertEquals(listOf(Token.Radical(listOf(Token.Text("3")))), tokens)
        assertEquals("√(3)", ReviewMath.flatten(tokens))
    }

    @Test
    @DisplayName("压平成纸面文字时把上下标换成 Unicode")
    fun `纸面压平`() {
        val tokens = ReviewMath.parse("v^{2}/2g")
        assertEquals("v²/2g", ReviewMath.flattenScripted(tokens))
    }

    @Test
    @DisplayName("纯文字不判为含记号")
    fun `纯文字`() {
        assertFalse(ReviewMath.hasMath("先定受力对象，再分解"))
        assertTrue(ReviewMath.hasMath("v_top"))
        assertTrue(ReviewMath.hasMath("1/2"))
    }

    @Test
    @DisplayName("上箭头写在底数后面时按指数排")
    fun `上箭头指数`() {
        assertEquals(
            listOf(Token.Text("2"), Token.Sup("n-1")),
            ReviewMath.parse("2↑(n-1)"),
        )
        assertEquals(
            listOf(Token.Text("a"), Token.Sup("2")),
            ReviewMath.parse("a↑2"),
        )
    }

    @Test
    @DisplayName("叙述里的「上升箭头」保持原样")
    fun `上箭头叙述`() {
        listOf("温度↑，压强↓", "产量↑ 了一倍", "先看 ↑ 这个符号").forEach { text ->
            assertFalse(ReviewMath.hasMath(text), "不该当成指数：$text")
            assertEquals(text, ReviewMath.flatten(ReviewMath.parse(text)))
        }
    }

    @Test
    @DisplayName("两侧带括号的斜杠写法排成分式")
    fun `括号分式`() {
        assertEquals(
            listOf(
                Token.Fraction(
                    numerator = listOf(Token.Text("x+1")),
                    denominator = listOf(Token.Text("x-1")),
                ),
            ),
            ReviewMath.parse("(x+1)/(x-1)"),
        )
    }

    @Test
    @DisplayName("单位缩写不排成上下两行")
    fun `单位保持平排`() {
        listOf("速度 60 km/h", "密度 1.0 g/cm3", "m/s²").forEach { text ->
            assertFalse(ReviewMath.hasMath(text), "不该当成数值分式：$text")
        }
    }
}
