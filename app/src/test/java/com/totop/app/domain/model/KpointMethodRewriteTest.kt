package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 「样本够了整卡重写」的触发与落地契约。
 *
 * 守三件事：
 * 1. 触发点是**唯一**的一次（第 3 道题之后），不能退化成每做一题写一次；
 * 2. 模型只能改步骤、适用条件、易错点，方法名与计数不许动；
 * 3. 模型给了半截东西时整张卡保持原样，不做半截更新。
 */
@DisplayName("方法卡重写")
class KpointMethodRewriteTest {
    private fun card(
        steps: List<String> = listOf("把 x=2 代入 y=2x+1 得 y=5"),
        applicable: String = "题干求「函数值」时",
        pitfalls: List<String> = listOf("第 1 步容易算错"),
        sampleCount: Int = 3,
    ) = KpointMethod(
        id = "card-1",
        kpointId = "KP_MATH_0001",
        kpointName = "一次函数",
        subject = "math",
        name = "代入法",
        keySteps = steps,
        applicable = applicable,
        pitfalls = pitfalls,
        sampleCount = sampleCount,
        lastTitle = "已知 y=2x+1，求 x=2 时的函数值",
        createdAtUtc = 100L,
        updatedAtUtc = 100L,
    )

    @Test
    @DisplayName("只在第 3 道题之后触发一次，第 4 道不再触发")
    fun `触发点唯一`() {
        // 前两道样本太少：重写只会把一道题的特殊做法固化成「通用路径」
        assertFalse(KpointMethodRewrite.shouldRewrite(0))
        assertFalse(KpointMethodRewrite.shouldRewrite(1))
        assertFalse(KpointMethodRewrite.shouldRewrite(2))
        assertTrue(KpointMethodRewrite.shouldRewrite(3))
        // 计数只加 1，所以「等于 3」有且只有一次；靠它当开关就不必再加一列「重写过没有」
        assertFalse(KpointMethodRewrite.shouldRewrite(4))
        assertFalse(KpointMethodRewrite.shouldRewrite(9))
    }

    @Test
    @DisplayName("重写只换步骤、适用条件、易错点，身份与计数不动")
    fun `只换骨架`() {
        val before = card()
        val after =
            KpointMethodRewrite.apply(
                card = before,
                draft =
                    MethodSkeleton(
                        steps = listOf("确认函数表达式与自变量的取值", "代入并化简", "写出结果并带单位"),
                        applicable = "给定函数式，求某点函数值时",
                        pitfalls = listOf("代入后忘记化简"),
                    ),
                nowUtc = 200L,
            )
        assertEquals(before.id, after.id)
        assertEquals(before.kpointId, after.kpointId)
        assertEquals(before.name, after.name)
        assertEquals(before.sampleCount, after.sampleCount)
        assertEquals(before.createdAtUtc, after.createdAtUtc)
        assertEquals(before.lastTitle, after.lastTitle)
        assertEquals(3, after.keySteps.size)
        assertEquals("给定函数式，求某点函数值时", after.applicable)
        assertEquals(listOf("代入后忘记化简"), after.pitfalls)
        assertEquals(200L, after.updatedAtUtc)
    }

    @Test
    @DisplayName("模型没给出步骤时整张卡原样返回，不清空也不改时间")
    fun `空骨架不动卡`() {
        val before = card()
        val after =
            KpointMethodRewrite.apply(
                card = before,
                draft = MethodSkeleton(steps = listOf("  ", "")),
                nowUtc = 999L,
            )
        assertSame(before, after, "没有可用步骤时必须原样返回，不能写进一张空卡")
    }

    @Test
    @DisplayName("易错点与适用条件为空时保留原值")
    fun `缺席的字段保留旧值`() {
        val after =
            KpointMethodRewrite.apply(
                card = card(),
                draft = MethodSkeleton(steps = listOf("确认表达式", "代入求解")),
                nowUtc = 200L,
            )
        assertEquals("题干求「函数值」时", after.applicable)
        assertEquals(listOf("第 1 步容易算错"), after.pitfalls)
    }

    @Test
    @DisplayName("步骤去重、截断、限条数")
    fun `清洗步骤`() {
        val long = "一".repeat(200)
        val after =
            KpointMethodRewrite.apply(
                card = card(),
                draft =
                    MethodSkeleton(
                        steps =
                            listOf(
                                " 确认表达式 ",
                                "确认表达式",
                                "",
                                long,
                                "代入求解",
                                "化简",
                                "写结果",
                                "检查单位",
                                "检查量纲",
                                "多余的一步",
                            ),
                    ),
                nowUtc = 200L,
            )
        // 重复与被清空的都去掉，再按上限截断
        assertEquals(KpointMethodMerge.MAX_STEPS, after.keySteps.size)
        assertEquals("确认表达式", after.keySteps[0])
        assertEquals(KpointMethodRewrite.MAX_STEP_CHARS, after.keySteps[1].length)
        assertEquals("多余的一步", after.keySteps.last())
    }
}
