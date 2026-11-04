package com.totop.app.data.remote

import com.totop.app.domain.model.AiSolveResultCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 形状纠正的单元测试。
 *
 * 每条用例都对应一个**真实见过**的模型输出偏差（DeepSeek Flash 这类小模型更常见），
 * 断言两件事：纠正后的值是什么、纠正后能否被契约成功解码。
 *
 * 这些纠正的目的不是「宽容」，而是**少一次重试**：每修好一种形状，
 * 就少发一次整请求——那才是真正的成本。
 */
class SolveWireSanitizerTest {
    private fun contract(raw: String): JsonObject =
        SolveWireSanitizer.sanitizeContract(Json.parseToJsonElement(raw).jsonObject)

    @Test
    @DisplayName("finalAnswer 写成数字时纠正成字符串，且契约可解码")
    fun numericFinalAnswerBecomesText() {
        val out =
            contract(
                """{"standardSolution":{"steps":[{"idx":1,"content":"配方","why":"顶点式"}],"finalAnswer":24}}""",
            )
        val solution = out["standardSolution"]!!.jsonObject
        assertEquals("24", solution["finalAnswer"]?.jsonPrimitive?.content)
        assertTrue(AiSolveResultCodec.decode(out.toString()).isSuccess)
    }

    @Test
    @DisplayName("steps 写成一句话数组时补成对象，内容与顺序都不变")
    fun stringStepsBecomeObjects() {
        val out =
            contract(
                """{"standardSolution":{"steps":["配方得 (x-1)^2","最小值在 x=1 处为 0"],"finalAnswer":"0"}}""",
            )
        val steps = out["standardSolution"]!!.jsonObject["steps"]!!.jsonArray
        assertEquals(2, steps.size)
        assertEquals("配方得 (x-1)^2", steps.first().jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals(1, steps.first().jsonObject["idx"]?.jsonPrimitive?.content?.toInt())
        assertEquals(2, steps.last().jsonObject["idx"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    @DisplayName("steps 整段写成字符串时按换行切，不按标点切")
    fun wholeStringStepsSplitByLine() {
        val out =
            contract(
                """{"standardSolution":{"steps":"先配方\n再看顶点\n最后比较端点","finalAnswer":"0"}}""",
            )
        val steps = out["standardSolution"]!!.jsonObject["steps"]!!.jsonArray
        assertEquals(3, steps.size)
        assertEquals("再看顶点", steps[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("studentSteps 写成字符串时补成一步，而不是整份丢弃")
    fun stringStudentStepsBecomeSingleStep() {
        val out = contract("""{"studentSteps":"我先代入 x=0 再求导"}""")
        val steps = out["studentSteps"]!!.jsonArray
        assertEquals(1, steps.size)
        assertEquals("我先代入 x=0 再求导", steps.single().jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("studentSteps 漏了方括号时包成单元素数组")
    fun studentStepsObjectBecomesArray() {
        val out = contract("""{"studentSteps":{"idx":1,"content":"代入 x=0","status":"wrong"}}""")
        assertEquals(1, out["studentSteps"]!!.jsonArray.size)
    }

    @Test
    @DisplayName("firstError 写成一整句话时放进 explain，类型留空")
    fun sentenceFirstErrorBecomesObject() {
        val out = contract("""{"firstError":"第二步移项没有变号"}""")
        val error = out["firstError"]!!.jsonObject
        assertEquals("第二步移项没有变号", error["explain"]?.jsonPrimitive?.content)
        assertNull(error["type"])
    }

    @Test
    @DisplayName("firstError 恰好是七类错因名时同时补出 type")
    fun categoryFirstErrorAlsoFillsType() {
        val out = contract("""{"firstError":"计算失误"}""")
        assertEquals("计算失误", out["firstError"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("firstError 是数组时取第一个对象，取不到就置空")
    fun arrayFirstErrorFallsBackToNull() {
        assertEquals(JsonNull, contract("""{"firstError":["第一步就错了"]}""")["firstError"])
        val fromObjects = contract("""{"firstError":[{"stepIdx":"2","explain":"符号错"}]}""")
        assertEquals(2, fromObjects["firstError"]!!.jsonObject["stepIdx"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    @DisplayName("summaryCard 写成一句话时放进 title")
    fun sentenceSummaryCardBecomesObject() {
        val out = contract("""{"summaryCard":"先配方再看顶点"}""")
        assertEquals("先配方再看顶点", out["summaryCard"]!!.jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("没有 id 的知识点整条丢弃，绝不编一个 id")
    fun kpointWithoutIdDropped() {
        val out =
            contract(
                """{"knowledgePoints":[{"name":"二次函数"},{"id":"KP_MATH_0001","name":"二次函数"}]}""",
            )
        assertEquals(1, out["knowledgePoints"]!!.jsonArray.size)
        val kept = out["knowledgePoints"]!!.jsonArray.single().jsonObject
        assertEquals("KP_MATH_0001", kept["id"]!!.jsonPrimitive.content)
    }

    @Test
    @DisplayName("阶段 A 的数字选项与单个已知条件都纠正成字符串数组")
    fun questionOptionShapeFixed() {
        val out =
            SolveWireSanitizer.sanitizeQuestion(
                Json.parseToJsonElement(
                    """{"stem":"求最小值","options":[1,2,3,4],"known":"f(x)=x^2","hasFigure":"false"}""",
                ).jsonObject,
            )
        assertEquals(4, out["options"]!!.jsonArray.size)
        assertEquals("1", out["options"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals(1, out["known"]!!.jsonArray.size)
        assertEquals("false", out["hasFigure"]?.jsonPrimitive?.content)
    }
}
