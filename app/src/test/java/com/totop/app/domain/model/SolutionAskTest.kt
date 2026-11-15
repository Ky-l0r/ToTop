package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 「标准解法 / 其他解法」两个菜单提问的契约测试。
 *
 * 这里锁的是三件容易在后续改动里悄悄走样的事：
 * 1. 气泡必须是一句人话——它会被永久留在聊天记录里，不能变成指令；
 * 2. 指令里不能出现 `[[…]]` 标记，否则等于自己把机器标记请回来；
 * 3. 什么时候不能点，三种情况各要说不同的话。
 */
@DisplayName("要解法提问")
class SolutionAskTest {
    @Test
    @DisplayName("两个提问齐全，顺序为 标准解法 → 其他解法")
    fun kindsInOrder() {
        assertEquals(listOf("标准解法", "其他解法"), SolutionAsk.entries.map { it.label })
    }

    @Test
    @DisplayName("气泡是短句，不含换行也不含指令口吻")
    fun bubbleIsHumanSentence() {
        SolutionAsk.entries.forEach { kind ->
            assertTrue(kind.bubble.isNotBlank(), kind.label + " 没有气泡文案")
            assertTrue(kind.bubble.length <= MAX_BUBBLE_CHARS, kind.label + " 的气泡太长，不像人话")
            assertTrue(!kind.bubble.contains("\n"), kind.label + " 的气泡不能换行")
            assertTrue(!kind.bubble.contains("要求"), kind.label + " 的气泡混进了指令")
        }
    }

    @Test
    @DisplayName("指令各自指向不同动作，且都不要求模型写机器标记")
    fun promptsAreDistinctAndMarkerFree() {
        val standard = SolutionAsk.STANDARD.prompt
        val alternate = SolutionAsk.ALTERNATE.prompt
        assertNotEquals(standard, alternate)
        // 「其他解法」必须明确「不同」，否则模型会把原解法换个说法再讲一遍
        assertTrue(alternate.contains("不同"), "其他解法没有要求给出不同的做法")
        // 「标准解法」必须明确「完整」，否则会撞上追问系统提示里的「不要重讲整道题」
        assertTrue(standard.contains("完整"), "标准解法没有说清这次可以完整写")
        SolutionAsk.entries.forEach { kind ->
            listOf("[[", "]]", "订正", "核对").forEach { forbidden ->
                assertTrue(
                    !kind.prompt.contains(forbidden),
                    kind.label + " 的指令里出现了「" + forbidden + "」",
                )
            }
        }
    }

    @Test
    @DisplayName("正在生成时说「等这一轮」，没有解答时说「先问一问」")
    fun blockedReasons() {
        SolutionAsk.entries.forEach { kind ->
            val solving = SolutionAskPolicy.blockedReason(kind, hasSolution = true, solving = true)
            assertTrue(solving != null && solving.contains(kind.label), kind.label + " 生成中没有说明原因")

            val noSolution = SolutionAskPolicy.blockedReason(kind, hasSolution = false, solving = false)
            assertTrue(noSolution != null && noSolution.contains(kind.label), kind.label + " 无解答时没有说明原因")
            // 两条原因必须不同：一句是「等一会儿」，一句是「先去问」，混用会误导
            assertNotEquals(solving, noSolution)

            assertNull(
                SolutionAskPolicy.blockedReason(kind, hasSolution = true, solving = false),
                kind.label + " 在已有解答且空闲时不该被拦",
            )
        }
    }

    /** 气泡长度上限：超过这个长度就说明把指令写进气泡了。 */
    private companion object {
        const val MAX_BUBBLE_CHARS: Int = 20
    }
}
