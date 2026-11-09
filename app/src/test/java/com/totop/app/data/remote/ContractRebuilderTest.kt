package com.totop.app.data.remote

import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolveResultCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 合并阶段兜底重建的单元测试。
 *
 * 每个用例都对应一种「四阶段都跑完了，却在合并时被判失败」的真实形状偏差。
 * 断言的重点不是「宽容」，而是**不把已完成的工作扔掉**：
 * 只要模型确实写了内容，重建后就必须还能看到它。
 */
class ContractRebuilderTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private val emptyStage = json("{}")

    private fun rebuild(rawSolution: String) =
        ContractRebuilder.rebuild(
            question = AiQuestion(stem = "求最小值"),
            solution = json(rawSolution),
            stageC = emptyStage,
            stageD = emptyStage,
        )

    @Test
    @DisplayName("步骤写成数组套数组时，仍能从原文重建出步骤")
    fun nestedArrayStepsRecovered() {
        val rebuilt = rebuild("""{"steps":[["配方得 (x-1)^2","最小值在 x=1 处为 0"]],"finalAnswer":"0"}""")
        val steps = rebuilt.result.standardSolution?.steps.orEmpty()
        assertEquals(2, steps.size)
        assertEquals("配方得 (x-1)^2", steps.first().content)
        assertTrue(rebuilt.notes.any { it.contains("重新分步") }, "降级必须留下可读说明")
    }

    @Test
    @DisplayName("steps 缺失但解答写在其它字段里时，按原文行重建")
    fun solutionWithoutStepsRecovered() {
        val rebuilt = rebuild("""{"check":"先配方\n再看顶点","finalAnswer":"0"}""")
        val steps = rebuilt.result.standardSolution?.steps.orEmpty()
        assertEquals(2, steps.size)
        assertEquals("先配方", steps.first().content)
    }

    @Test
    @DisplayName("confidence 写成 NaN 时丢弃该值，整份契约仍可解码")
    fun notANumberConfidenceDropped() {
        val contract =
            SolveWireSanitizer.sanitizeContract(
                json(
                    """{"question":{"stem":"题"},"standardSolution":{"steps":[{"idx":1,"content":"步骤"}]},""" +
                        """"knowledgePoints":[{"id":"KP_MATH_0001","name":"一次函数","confidence":"NaN"}]}""",
                ),
            )
        val kpoint = (contract["knowledgePoints"] as JsonArray).first().jsonObject
        assertNull(kpoint["confidence"])
        assertTrue(AiSolveResultCodec.decode(contract.toString()).isSuccess)
    }

    @Test
    @DisplayName("步骤内容写成 text 别名时归一为 content")
    fun textAliasBecomesContent() {
        val contract =
            SolveWireSanitizer.sanitizeContract(
                json("""{"standardSolution":{"steps":[{"idx":1,"text":"配方得 (x-1)^2"}]}}"""),
            )
        val step = ((contract["standardSolution"]?.jsonObject?.get("steps")) as JsonArray).first().jsonObject
        assertEquals("配方得 (x-1)^2", (step["content"] as JsonPrimitive).content)
    }
}
