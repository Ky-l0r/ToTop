package com.totop.app.data.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 闪记输出的解析契约。
 *
 * 这一层要挡住三种脏输出：代码围栏包着的 JSON、空白条目、条数超限。
 * 挡不住的话界面上会出现空行或第六条，而闪记本来就是「一眼看完」的东西。
 */
@DisplayName("闪记解析")
class QuickThinkParseTest {
    @Test
    @DisplayName("正常 JSON 按顺序取出")
    fun parsesPlainJson() {
        val raw = """{"steps":["看到对称轴，先算出 x=1","想到分区间内与区间外"]}"""
        assertEquals(listOf("看到对称轴，先算出 x=1", "想到分区间内与区间外"), QuickThinkParse.steps(raw))
    }

    @Test
    @DisplayName("代码围栏与前后解释都能剥掉")
    fun stripsFences() {
        val raw =
            """
            这是闪记：
            ```json
            {"steps":["先配成顶点式"]}
            ```
            """.trimIndent()
        assertEquals(listOf("先配成顶点式"), QuickThinkParse.steps(raw))
    }

    @Test
    @DisplayName("空白条目被丢弃，超过五条只留前五条")
    fun filtersBlankAndCapsCount() {
        val raw =
            """{"steps":["一条","   ","二条","三条","四条","五条","六条"]}"""
        val steps = QuickThinkParse.steps(raw)
        assertEquals(5, steps.size)
        assertEquals("一条", steps.first())
        assertTrue(steps.none { it.isBlank() })
    }

    @Test
    @DisplayName("过长的一条被截断并留下省略号")
    fun truncatesLongStep() {
        val long = "看".repeat(200)
        val steps = QuickThinkParse.steps("""{"steps":["$long"]}""")
        assertEquals(1, steps.size)
        assertTrue(steps.first().endsWith("…"))
        assertTrue(steps.first().length <= 121)
    }

    @Test
    @DisplayName("完全不是 JSON 时返回空列表，不抛异常")
    fun returnsEmptyOnGarbage() {
        assertTrue(QuickThinkParse.steps("这次没想出来").isEmpty())
        assertTrue(QuickThinkParse.steps("").isEmpty())
    }
}
