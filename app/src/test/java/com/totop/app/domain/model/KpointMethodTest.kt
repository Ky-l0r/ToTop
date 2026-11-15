package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 方法卡的合并与渲染契约。
 *
 * 这些断言守的是「越用越准」这句话：计数要涨、步骤要取更完整的那版、
 * 同名不同写法的方案不能分裂成两张卡，不同方法也不能被硬并成一张。
 */
class KpointMethodTest {
    private fun evidence(
        name: String = "配方法",
        steps: List<String> = listOf("配方", "求最值"),
        pitfalls: List<String> = emptyList(),
        ask: String = "",
        title: String = "已知函数求最值",
    ) = MethodEvidence(
        kpointId = "MATH-FUNC-001",
        kpointName = "二次函数",
        subject = "math",
        name = name,
        keySteps = steps,
        applicable = ask,
        pitfalls = pitfalls,
        title = title,
    )

    @Test
    @DisplayName("首次出现：新建一张卡，计数为 1")
    fun `新建`() {
        val card = KpointMethodMerge.merge(existing = null, evidence = evidence(), newId = "id-1", nowUtc = 100L)
        assertEquals("id-1", card.id)
        assertEquals(1, card.sampleCount)
        assertEquals("配方法", card.name)
        assertEquals(2, card.keySteps.size)
        assertEquals(100L, card.createdAtUtc)
    }

    @Test
    @DisplayName("同名同知识点累加计数，不新建")
    fun `累加`() {
        val first = KpointMethodMerge.merge(existing = null, evidence = evidence(), newId = "id-1", nowUtc = 100L)
        val second =
            KpointMethodMerge.merge(existing = first, evidence = evidence(), newId = "id-2", nowUtc = 200L)
        assertEquals("id-1", second.id)
        assertEquals(2, second.sampleCount)
        assertEquals(200L, second.updatedAtUtc)
        assertEquals(100L, second.createdAtUtc)
    }

    @Test
    @DisplayName("方法名归一化：空白与标点不同的写法算同一种")
    fun `名字归一化`() {
        assertEquals(KpointMethodMerge.nameKey("配方法"), KpointMethodMerge.nameKey("配 方法"))
        assertEquals(KpointMethodMerge.nameKey("配方法"), KpointMethodMerge.nameKey("配方法。"))
        assertEquals(KpointMethodMerge.nameKey("Newton法"), KpointMethodMerge.nameKey("newton法"))
        assertTrue(KpointMethodMerge.nameKey("配方法").isNotEmpty())
    }

    @Test
    @DisplayName("不同方法名各自成卡，不被合并")
    fun `不同方法不合并`() {
        val first =
            KpointMethodMerge.merge(existing = null, evidence = evidence(name = "配方法"), newId = "id-1", nowUtc = 1L)
        assertFalse(KpointMethodMerge.nameKey("配方法") == KpointMethodMerge.nameKey("导数法"))
        // 合并只由调用方按 nameKey 查到 existing 后触发，这里验证的是「查不到就不合并」的输入前提
        val other =
            KpointMethodMerge.merge(existing = null, evidence = evidence(name = "导数法"), newId = "id-2", nowUtc = 2L)
        assertEquals(1, other.sampleCount)
        assertEquals(1, first.sampleCount)
    }

    @Test
    @DisplayName("步骤取更完整的那版")
    fun `步骤取长`() {
        val short =
            KpointMethodMerge.merge(
                existing = null,
                evidence = evidence(steps = listOf("配方")),
                newId = "id",
                nowUtc = 1L,
            )
        val longer =
            KpointMethodMerge.merge(
                existing = short,
                evidence = evidence(steps = listOf("配方", "求顶点", "代回检验")),
                newId = "x",
                nowUtc = 2L,
            )
        assertEquals(3, longer.keySteps.size)
        // 反过来：新证据更短时不覆盖，避免一次草率解答把骨架改差
        val back =
            KpointMethodMerge.merge(
                existing = longer,
                evidence = evidence(steps = listOf("配方")),
                newId = "y",
                nowUtc = 3L,
            )
        assertEquals(3, back.keySteps.size)
    }

    @Test
    @DisplayName("易错点合并去重且有上限")
    fun `易错合并`() {
        val first =
            KpointMethodMerge.merge(
                existing = null,
                evidence = evidence(pitfalls = listOf("漏讨论")),
                newId = "id",
                nowUtc = 1L,
            )
        val second =
            KpointMethodMerge.merge(
                existing = first,
                evidence = evidence(pitfalls = listOf("漏讨论", "符号错", "忘记回代", "第四点")),
                newId = "x",
                nowUtc = 2L,
            )
        assertEquals(listOf("漏讨论", "符号错", "忘记回代"), second.pitfalls)
    }

    @Test
    @DisplayName("提示块写清来源与条数，且不超过字数上限")
    fun `提示块`() {
        val card =
            KpointMethodMerge.merge(
                existing = null,
                evidence = evidence(ask = "题干求「二次函数在闭区间上的最值」时", steps = listOf("配方", "看对称轴")),
                newId = "id",
                nowUtc = 1L,
            )
        val block = card.promptBlock()
        assertTrue(block.contains("配方法"))
        assertTrue(block.contains("1 道同类题"))
        assertTrue(block.contains("适用："))
        assertTrue(block.contains("通用步骤"))
        // 带上「仅供参考」：阶段 B 必须看得出这不是标准答案
        assertTrue(block.contains("仅供参考"))
        assertTrue(card.promptBlock(maxChars = 20).length <= 20)
    }
}
