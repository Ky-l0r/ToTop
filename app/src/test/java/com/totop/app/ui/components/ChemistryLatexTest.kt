package com.totop.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `\ce{}` 展开的契约测试。
 *
 * 背景：项目里没有 mhchem 扩展，`\ce{H2SO4}` 原先只能降级成纯文本 `H2SO4`，
 * 下标、箭头、电荷全丢。这里钉住展开结果，避免以后改解析规则时悄悄退回原状。
 */
@DisplayName("化学式展开")
class ChemistryLatexTest {
    private fun expand(raw: String): String = ChemistryLatex.expand(raw)

    @Test
    @DisplayName("不含 \\ce 的公式原样返回")
    fun `无关公式不动`() {
        assertEquals("\\frac{a}{b}", expand("\\frac{a}{b}"))
        assertEquals("x^2+y^2", expand("x^2+y^2"))
    }

    @Test
    @DisplayName("元素与下标")
    fun `元素下标`() {
        assertEquals(
            "\\mathrm{H}_{2}\\mathrm{S}\\mathrm{O}_{4}",
            expand("\\ce{H2SO4}"),
        )
        assertEquals("\\mathrm{Fe}", expand("\\ce{Fe}"))
    }

    @Test
    @DisplayName("完整反应式：系数、加号、箭头")
    fun `反应式`() {
        assertEquals(
            "2\\mathrm{H}_{2} + \\mathrm{O}_{2} → 2\\mathrm{H}_{2}\\mathrm{O}",
            expand("\\ce{2H2 + O2 -> 2H2O}"),
        )
    }

    @Test
    @DisplayName("括号与括号外下标")
    fun `括号下标`() {
        assertEquals(
            "\\mathrm{Ca}(\\mathrm{O}\\mathrm{H})_{2}",
            expand("\\ce{Ca(OH)2}"),
        )
        assertEquals(
            "(\\mathrm{N}\\mathrm{H}_{4})_{2}\\mathrm{S}\\mathrm{O}_{4}",
            expand("\\ce{(NH4)2SO4}"),
        )
    }

    @Test
    @DisplayName("离子电荷")
    fun `电荷`() {
        assertEquals(
            "\\mathrm{S}\\mathrm{O}_{4}^{2-}",
            expand("\\ce{SO4^2-}"),
        )
        assertEquals("\\mathrm{Fe}^{3+}", expand("\\ce{Fe^3+}"))
        assertEquals("\\mathrm{S}\\mathrm{O}_{4}^{2-}", expand("\\ce{SO4^{2-}}"))
    }

    @Test
    @DisplayName("结晶水与状态符号")
    fun `结晶水与状态`() {
        assertEquals(
            "\\mathrm{Cu}\\mathrm{S}\\mathrm{O}_{4} \\cdot 5\\mathrm{H}_{2}\\mathrm{O}",
            expand("\\ce{CuSO4·5H2O}"),
        )
        assertEquals("2\\mathrm{H}_{2}\\mathrm{O}\\text{(l)}", expand("\\ce{2H2O(l)}"))
        assertEquals("\\mathrm{Na}\\mathrm{Cl}\\text{(aq)}", expand("\\ce{NaCl(aq)}"))
    }

    @Test
    @DisplayName("箭头与条件")
    fun `箭头条件`() {
        assertEquals(
            "\\mathrm{A} → \\text{点燃} \\mathrm{B}",
            expand("\\ce{A ->[点燃] B}"),
        )
        assertEquals(
            "\\mathrm{A} ⇌ \\mathrm{B}",
            expand("\\ce{A <=> B}"),
        )
        // 模型常把条件写成 \text{…}：先剥壳再排，避免嵌套成 \text{\text{点燃}}
        assertEquals(
            "\\mathrm{A} → \\text{点燃} \\mathrm{B}",
            expand("\\ce{A ->[\\text{点燃}] B}"),
        )
    }

    @Test
    @DisplayName("混排文本只替换公式片段")
    fun `混排`() {
        assertEquals(
            "反应式：\\mathrm{H}_{2} + \\mathrm{Cl}_{2} → 2\\mathrm{H}\\mathrm{Cl}，条件是点燃",
            expand("反应式：\\ce{H2 + Cl2 -> 2HCl}，条件是点燃"),
        )
    }

    @Test
    @DisplayName("超出支持范围时整条保留原文")
    fun `保守回退`() {
        // 花括号不配对
        assertEquals("\\ce{H2O", expand("\\ce{H2O"))
        // 公式里混进别的 LaTeX 命令：不猜含义，整条交回老路径降级显示
        assertEquals("\\ce{H2O + \\Delta}", expand("\\ce{H2O + \\Delta}"))
    }
}
