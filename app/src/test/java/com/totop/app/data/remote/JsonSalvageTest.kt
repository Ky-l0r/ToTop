package com.totop.app.data.remote

import com.totop.app.domain.model.AiStage
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 模型输出的「抢救式解析」。
 *
 * 现实里的失败几乎都不是「模型完全乱写」，而是围栏、前后解释、字符串里的裸换行、
 * 末尾多一个逗号、结尾又补一段话。这些都能在不改变语义的前提下修好，
 * 修好就意味着少一次重试、少一次「本次结果不会写入错题本」。
 */
@DisplayName("模型输出 JSON 抢救")
class JsonSalvageTest {
    @Test
    @DisplayName("带 json 围栏与前置说明")
    fun `围栏与前言`() {
        val raw = "好的，下面是结果：\n```json\n{\"steps\":[{\"idx\":1}]}\n```\n以上。"
        val obj = JsonSalvage.parseObject(raw)
        assertNotNull(obj)
        assertEquals(1, obj!!.keys.size)
    }

    @Test
    @DisplayName("结尾有带花括号的补充说明也能截对")
    fun `尾部花括号`() {
        val raw = """{"stem":"求 x","ask":"x=?"} 解析：当 x∈{1,2} 时成立。"""
        val obj = JsonSalvage.parseObject(raw)
        assertNotNull(obj)
        assertEquals("求 x", obj!!["stem"].toString().trim('"'))
    }

    @Test
    @DisplayName("字符串里未转义的换行按 \\n 修复")
    fun `裸换行`() {
        val raw = "{\"steps\":[{\"why\":\"因为 a=1\n所以 b=2\"}]}"
        val obj = JsonSalvage.parseObject(raw)
        assertNotNull(obj)
        assertTrue(obj.toString().contains("因为 a=1"))
    }

    @Test
    @DisplayName("对象与数组末尾的多余逗号被去掉")
    fun `多余逗号`() {
        val raw = """{"steps":[{"idx":1},],"finalAnswer":"x",}"""
        val obj = JsonSalvage.parseObject(raw)
        assertNotNull(obj)
        assertTrue(obj!!.containsKey("finalAnswer"))
    }

    @Test
    @DisplayName("字符串里的逗号与方括号不被误删")
    fun `字符串内逗号`() {
        val raw = """{"text":"a,]," }"""
        val obj = JsonSalvage.parseObject(raw)
        assertNotNull(obj)
        assertEquals("a,],", obj!!["text"].toString().trim('"'))
    }

    @Test
    @DisplayName("LaTeX 的非法转义补成字面反斜杠，公式不被判成坏 JSON")
    fun `非法转义`() {
        val raw = """{"content":"\dfrac{1}{2} 与 \sqrt{x}"}"""
        val obj = JsonSalvage.parseObject(raw)
        assertNotNull(obj)
        assertEquals("""\dfrac{1}{2} 与 \sqrt{x}""", obj!!["content"]!!.jsonPrimitive.content)
    }

    @Test
    @DisplayName("合法的 \\n 转义保持原样")
    fun `合法转义`() {
        val obj = JsonSalvage.parseObject("""{"content":"第一行\n第二行"}""")
        assertNotNull(obj)
        assertTrue(obj!!["content"].toString().contains("\\n"))
    }

    @Test
    @DisplayName("被截断的输出返回 null，交给上层重试")
    fun `截断`() {
        val raw = """{"steps":[{"idx":1,"content":"先求导"""
        assertNull(JsonSalvage.parseObject(raw))
        assertNull(JsonSalvage.extractBalancedObject(raw))
    }

    @Test
    @DisplayName("完全不是 JSON 时返回 null")
    fun `纯文本`() {
        assertNull(JsonSalvage.parseObject("这道题先把两边同乘 2，再移项。"))
    }

    @Test
    @DisplayName("合规输出原样通过")
    fun `正常输出`() {
        val raw = """{"steps":[{"idx":1,"content":"求导","why":"导数定义"}],"finalAnswer":"2"}"""
        val obj = JsonSalvage.parseObject(raw)
        assertNotNull(obj)
        assertEquals(2, obj!!.keys.size)
    }

    @Test
    @DisplayName("阶段校验：JSON 合法但字段名不对，要判失败以便重试")
    fun `字段不对也算失败`() {
        // 旧实现只看「是不是 JSON」，这种输出会溜到阶段解析里直接抛错，白白浪费一次重试机会
        val error = AiGateway.validateStageJson(AiStage.B, """{"solution":["a","b"]}""")
        assertNotNull(error)
    }

    @Test
    @DisplayName("阶段校验：字段齐全即通过")
    fun `字段齐全`() {
        assertNull(
            AiGateway.validateStageJson(
                AiStage.B,
                """{"steps":[{"idx":1,"content":"求导"}],"finalAnswer":"2"}""",
            ),
        )
        assertNull(AiGateway.validateStageJson(AiStage.A, """{"stem":"求 x"}"""))
        assertNull(AiGateway.validateStageJson(AiStage.D, """{"kpoints":[]}"""))
    }

    @Test
    @DisplayName("阶段校验：求解阶段没有步骤等于没答，要重试而不是直接失败")
    fun `步骤为空`() {
        assertNotNull(AiGateway.validateStageJson(AiStage.B, """{"steps":[],"finalAnswer":"2"}"""))
        assertNotNull(AiGateway.validateStageJson(AiStage.B, """{"finalAnswer":"2"}"""))
    }

    @Test
    @DisplayName("阶段校验：纯文本报「不是合法 JSON」，且 detail 不泄露正文")
    fun `纯文本报错`() {
        val error = AiGateway.validateStageJson(AiStage.C, "学生这一步把符号写反了，应该先移项。")
        assertNotNull(error)
        assertTrue(error!!.contains("不是合法 JSON"))
        assertTrue(!error.contains("符号写反"))
    }

    @Test
    @DisplayName("阶段预算：求解与错因有下限，知识点压到基准以内")
    fun `阶段预算`() {
        assertEquals(2048, AiGateway.stageMaxTokens(AiStage.A, 2048))
        assertEquals(4096, AiGateway.stageMaxTokens(AiStage.B, 2048))
        assertEquals(8192, AiGateway.stageMaxTokens(AiStage.B, 8192))
        assertEquals(3072, AiGateway.stageMaxTokens(AiStage.C, 2048))
        assertEquals(2048, AiGateway.stageMaxTokens(AiStage.D, 8192))
    }
}
