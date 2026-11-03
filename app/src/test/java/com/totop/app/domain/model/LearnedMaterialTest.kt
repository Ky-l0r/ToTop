package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 资料进提示词的渲染规则。
 *
 * 这段文本会被塞进阶段 B 的输入，写多了会挤掉题干，写少了等于没参考，
 * 因此长度上限与「必须带来源」都是硬约定。
 */
@DisplayName("资料进提示词")
class LearnedMaterialTest {
    private fun material(
        subject: String = "physics",
        sourceLabel: String = "作业帮截图",
        steps: List<String> = listOf("受力分析", "列牛二定律", "代入求解"),
    ): LearnedMaterial =
        LearnedMaterial(
            id = "m1",
            subject = subject,
            kpointId = null,
            kpointName = "牛顿第二定律",
            title = "斜面上的物块",
            method = "牛顿第二定律",
            keySteps = steps,
            answer = "a = 2 m/s²",
            pitfalls = listOf("漏掉摩擦力"),
            sourceLabel = sourceLabel,
            imagePath = null,
            createdAtUtc = 0L,
        )

    @Test
    @DisplayName("包含来源、方法、步骤与结论")
    fun `渲染完整`() {
        val block = material().promptBlock()
        assertTrue(block.contains("作业帮截图"), "必须带来源，否则模型不知道这不是它自己的结论")
        assertTrue(block.contains("牛顿第二定律"))
        assertTrue(block.contains("受力分析"))
        assertTrue(block.contains("a = 2 m/s²"))
        assertTrue(block.contains("漏掉摩擦力"))
    }

    @Test
    @DisplayName("步骤超过五条只列前五条")
    fun `步骤截断`() {
        val many = (1..9).map { "第" + it + "步" }
        val block = material(steps = many).promptBlock()
        assertTrue(block.contains("第5步"))
        assertFalse(block.contains("第6步"), "超过五步就不是要点而是原文")
    }

    @Test
    @DisplayName("整块不超过字数上限")
    fun `长度上限`() {
        val huge = material(steps = List(5) { "很长的步骤".repeat(200) })
        assertTrue(huge.promptBlock().length <= LearnedMaterial.MAX_BLOCK_CHARS)
    }

    @Test
    @DisplayName("没有来源时不硬塞括号")
    fun `无来源`() {
        val block = material(sourceLabel = "").promptBlock()
        assertFalse(block.contains("来源：）"))
        assertTrue(block.contains("仅供参考"))
    }
}
