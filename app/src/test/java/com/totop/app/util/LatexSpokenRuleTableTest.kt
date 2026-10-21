package com.totop.app.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * LaTeX 口语化规则表测试（开发方案 FR-6.4）。
 *
 * 全部是纯函数断言：直接读取仓库里的 assets/seed/latex_spoken_rules.json，
 * 不启动 Android，因此可以随 `testDebugUnitTest` 在 CI 上秒级跑完。
 * 规则表被改坏（正则非法、条数骤减）时本测试立即失败。
 */
class LatexSpokenRuleTableTest {
    private val assetsRoot = File("src/main/assets")

    private fun loadTable(): LatexSpokenRuleTable {
        val file = File(assetsRoot, LatexSpokenRuleTable.ASSET_PATH)
        assertTrue(file.exists(), "规则表资产缺失：" + file.absolutePath)
        val load = LatexSpokenRuleTable.fromInputStream(file.inputStream())
        assertTrue(load is LatexRuleTableLoad.Success, "规则表必须能加载，实际：" + load)
        return (load as LatexRuleTableLoad.Success).table
    }

    @Test
    @DisplayName("内置规则表可加载且条数达到约 60 条的产品要求")
    fun assetLoads() {
        val table = loadTable()
        assertFalse(table.isEmpty)
        assertEquals(table.rules.size, table.compiledRuleCount, "所有规则的正则都必须能编译")
        assertTrue(table.rules.size >= 60, "规则条数应达到方案要求（约 60 条），实际：" + table.rules.size)
        assertTrue(table.rules.map { it.order } == table.rules.indices.toList(), "规则顺序必须是 JSON 数组顺序")
    }

    @Test
    @DisplayName("分数：frac{a}{b} 读作「b 分之 a」")
    fun fraction() {
        val table = loadTable()
        assertEquals("2 分之 1", table.speak("\\frac{1}{2}"))
        assertEquals("2 分之 1", table.speak("$\\frac{1}{2}$"), "带美元符号的公式也要能朗读")
        assertEquals("x 分之 1", table.speak("\\dfrac{1}{x}"), "dfrac 与 frac 等价")
    }

    @Test
    @DisplayName("根式与三角函数：sqrt / sin + 度数")
    fun sqrtAndTrig() {
        val table = loadTable()
        assertEquals("根号 2", table.speak("\\sqrt{2}"))
        assertEquals("三次根号 8", table.speak("\\sqrt[3]{8}"))
        assertEquals("正弦 30 度", table.speak("\\sin 30^\\circ"))
        assertEquals("余弦 60 度", table.speak("\\cos 60^\\circ"))
    }

    @Test
    @DisplayName("幂与下标：x^2、x^{3}、a_n")
    fun powerAndSubscript() {
        val table = loadTable()
        assertEquals("x 的平方", table.speak("x^2"))
        assertEquals("y 的立方", table.speak("y^{3}"))
        assertEquals("a 的 n 次方", table.speak("a^{n}"))
        assertEquals("a 下标 1", table.speak("a_1"))
        assertEquals("a 下标 n+1", table.speak("a_{n+1}"))
    }

    @Test
    @DisplayName("关系符号与希腊字母")
    fun relationsAndGreek() {
        val table = loadTable()
        assertEquals("阿尔法", table.speak("\\alpha"))
        assertEquals("圆周率", table.speak("\\pi"))
        assertEquals("小于等于", table.speak("\\le"))
        assertEquals("不等于", table.speak("\\neq"))
        assertEquals("垂直于", table.speak("\\perp"))
        val comparison = table.speak("3<4 且 4>2")
        assertTrue(comparison.contains("3小于4"), comparison)
        assertTrue(comparison.contains("4大于2"), comparison)
        assertTrue(table.speak("x \\in A").contains("属于"), "\\in 必须读作「属于」")
    }

    @Test
    @DisplayName("线段、向量、角等几何写法")
    fun geometry() {
        val table = loadTable()
        assertEquals("线段 AB", table.speak("\\overline{AB}"))
        assertEquals("向量 a", table.speak("\\vec{a}"))
        assertEquals("角 ABC", table.speak("\\angle ABC"))
    }

    @Test
    @DisplayName("中英混排题干：分隔符外的中文原样保留")
    fun richText() {
        val table = loadTable()
        val spoken = table.speakRichText("已知函数 \$f(x)=x^{2}-2x+1\$，求最小值。")
        assertTrue(spoken.startsWith("已知函数 f(x)"), spoken)
        assertTrue(spoken.contains("x 的平方"), spoken)
        assertTrue(spoken.endsWith("，求最小值。"), spoken)
        assertFalse(spoken.contains("$"), "分隔符必须被去掉，实际：" + spoken)
    }

    @Test
    @DisplayName("不含数学分隔符的纯文本原样返回，避免误伤中文")
    fun plainTextUntouched() {
        val table = loadTable()
        val text = "已知 a<b，求 a 与 b 的大小关系。"
        assertEquals(text, table.speakRichText(text))
    }

    @Test
    @DisplayName("规则外内容降级为原文：未知命令不丢内容")
    fun unknownCommandFallsBack() {
        val table = loadTable()
        // \text{已知} 不在规则表内，必须降级为「已知」而不是丢失或留反斜杠
        val spoken = table.speak("\\text{已知}")
        assertTrue(spoken.contains("已知"), spoken)
        assertFalse(spoken.contains("\\"), spoken)
        // 带参数的未知命令降级为其参数内容，排版控制符被清掉但不丢数学信息
        assertEquals("2", table.speak("\\foo{2}"))
        // 裸未知命令保留命令名，至少能念出名字
        assertEquals("foo", table.speak("\\foo"))
    }

    @Test
    @DisplayName("空表（资产缺失兜底）只做清理，不抛异常")
    fun emptyTableDegrades() {
        val empty = LatexSpokenRuleTable.EMPTY
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.compiledRuleCount)
        val spoken = empty.speak("\\frac{1}{2}")
        assertTrue(spoken.isNotEmpty(), "兜底也必须念出内容，实际为空")
        assertFalse(spoken.contains("\\"), "反斜杠应被清理，实际：" + spoken)
        assertEquals("", empty.speak(""))
    }

    @Test
    @DisplayName("非法正则与损坏 JSON 都返回 Failure，不抛异常")
    fun invalidInput() {
        val invalidPattern = LatexSpokenRuleTable.fromJson("""[{"pattern":"[","type":"whole","spoken":"x"}]""")
        assertTrue(invalidPattern is LatexRuleTableLoad.Failure)
        assertEquals(LatexRuleTableError.INVALID_PATTERN, (invalidPattern as LatexRuleTableLoad.Failure).error)

        val notArray = LatexSpokenRuleTable.fromJson("""{"pattern":"a"}""")
        assertTrue(notArray is LatexRuleTableLoad.Failure)
        assertEquals(LatexRuleTableError.MALFORMED, (notArray as LatexRuleTableLoad.Failure).error)

        val emptyArray = LatexSpokenRuleTable.fromJson("[]")
        assertTrue(emptyArray is LatexRuleTableLoad.Failure)
        assertEquals(
            LatexSpokenRuleTable.EMPTY.compiledRuleCount,
            (emptyArray as LatexRuleTableLoad.Failure).fallback.compiledRuleCount,
        )

        val broken = LatexSpokenRuleTable.fromJson("not json")
        assertTrue(broken is LatexRuleTableLoad.Failure)
    }

    @Test
    @DisplayName("自嵌套规则不会死循环，且迭代有上限")
    fun noInfiniteLoop() {
        val table =
            LatexSpokenRuleTable.fromJson(
                """[{"pattern":"a","type":"whole","spoken":"a"},{"pattern":"b","type":"whole","spoken":"a"}]""",
            )
        assertTrue(table is LatexRuleTableLoad.Success)
        val result = (table as LatexRuleTableLoad.Success).table.speak("b")
        assertNotNull(result)
        assertEquals("a", result)
    }

    @Test
    @DisplayName("题干分隔符未闭合时不吞掉内容")
    fun unclosedDelimiter() {
        val table = loadTable()
        val text = "求 \$x^2 的值"
        assertEquals(text, table.speakRichText(text), "未闭合的分隔符应保持原文")
    }

    @Test
    @DisplayName("括号与换行控制符被清理为可朗读文本")
    fun cleanupResidue() {
        assertEquals("，", LatexSpokenRuleTable.cleanResidue("\\".repeat(2)))
        assertEquals("a", LatexSpokenRuleTable.cleanResidue("{a}"))
    }
}
