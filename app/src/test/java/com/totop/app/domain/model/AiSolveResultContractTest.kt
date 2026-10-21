package com.totop.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * AiSolveResult DTO 契约单测：严格对齐开发方案 6.4 的 JSON 契约。
 *
 * 断言重点：
 * 1. 顶层字段名与顺序；
 * 2. 嵌套结构（question / standardSolution.steps / studentSteps / firstError /
 *    knowledgePoints / summaryCard）；
 * 3. 未知字段被忽略（ignoreUnknownKeys）；
 * 4. 服务商返回的代码块包裹 JSON 也能被宽松解析。
 */
class AiSolveResultContractTest {
    /** 与开发方案 6.4 文档完全一致的示例 JSON。 */
    private val contractJson =
        """
        {
          "question": {"subject":"math","grade":"高一","stem":"求 f(x)=x^3-3x 的单调区间",
                       "known":["f(x)=x^3-3x"], "ask":"求单调区间", "options":[], "hasFigure":false},
          "standardSolution": {
             "steps":[{"idx":1,"content":"求导","why":"导数符号决定单调性"}],
             "finalAnswer":"递增区间为 (-∞,-1) 与 (1,+∞)","method":"导数法"},
          "studentSteps": [{"idx":1,"content":"直接代入 x=0","status":"wrong"}],
          "firstError": {"stepIdx":1, "type":"公式误用", "explain":"没有先求导",
                         "correctThinking":"本题应从求导切入，因为单调性由导数符号决定"},
          "knowledgePoints": [{"id":"KP_MATH_0312","name":"导数与单调性",
                               "confidence":0.92,"usage":"用导数符号判断增减区间"}],
          "summaryCard": {"title":"单调性判断三步走","bullets":["求导","解不等式","写区间"]},
          "aiGenerated": true
        }
        """.trimIndent()

    @Nested
    @DisplayName("字段与嵌套结构")
    inner class Contract {
        @Test
        fun `契约字段名与顺序与文档一致`() {
            assertEquals(
                listOf(
                    "question",
                    "standardSolution",
                    "studentSteps",
                    "firstError",
                    "knowledgePoints",
                    "summaryCard",
                    "aiGenerated",
                ),
                AiSolveResult.CONTRACT_FIELDS,
            )
        }

        @Test
        fun `文档示例可完整解析`() {
            val result = AiSolveResultCodec.decode(contractJson).getOrThrow()
            assertEquals("math", result.question?.subject)
            assertEquals("高一", result.question?.grade)
            assertEquals("求单调区间", result.question?.ask)
            assertFalse(result.question?.hasFigure ?: true)
            assertEquals(1, result.standardSolution?.steps?.size)
            assertEquals(1, result.standardSolution?.steps?.first()?.idx)
            assertEquals("导数法", result.standardSolution?.method)
            assertEquals("wrong", result.studentSteps.single().status)
            assertEquals("公式误用", result.firstError?.type)
            assertEquals(1, result.firstError?.stepIdx)
            assertEquals("KP_MATH_0312", result.knowledgePoints.single().id)
            assertEquals(0.92, result.knowledgePoints.single().confidence!!, 1e-9)
            assertEquals("单调性判断三步走", result.summaryCard?.title)
            assertEquals(3, result.summaryCard?.bullets?.size)
            assertTrue(result.aiGenerated)
        }

        @Test
        fun `序列化后顶层键与契约一致且不含额外字段`() {
            val result = AiSolveResultCodec.decode(contractJson).getOrThrow()
            val encoded = AiSolveResultCodec.encode(result)
            val obj = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(AiSolveResult.CONTRACT_FIELDS.toSet(), obj.keys)
            val firstError = obj["firstError"]!!.jsonObject
            assertEquals(setOf("stepIdx", "type", "explain", "correctThinking"), firstError.keys)
            val step = obj["standardSolution"]!!.jsonObject["steps"]!!.jsonArray[0].jsonObject
            assertEquals(setOf("idx", "content", "why"), step.keys)
        }

        @Test
        fun `文档示例满足严格结构校验`() {
            val result = AiSolveResultCodec.decode(contractJson).getOrThrow()
            assertTrue(result.hasCompleteStructure())
            assertTrue(result.missingStructure().isEmpty())
        }

        @Test
        fun `缺失嵌套结构时严格校验失败`() {
            val empty = AiSolveResult()
            // 只有 question 与 standardSolution 属致命缺失：
            // 没有题干或标准解答，这份结果对用户毫无价值，必须判失败。
            assertEquals(listOf("question", "standardSolution"), empty.missingStructure())
            assertFalse(empty.hasCompleteStructure())
            val partial =
                AiSolveResult(
                    question = AiQuestion(stem = "题干"),
                    standardSolution = AiStandardSolution(steps = emptyList()),
                    studentSteps = listOf(AiStudentStep(1, "a", "wrong")),
                    knowledgePoints = listOf(AiKnowledgePoint("KP_MATH_0001", "n", 0.5, "u")),
                )
            assertEquals(listOf("standardSolution.steps"), partial.missingStructure())
        }

        /**
         * 学生没写解题过程时，阶段 C 按契约返回空 studentSteps、firstError=null。
         * 这是**正确行为**，绝不能因此判整次解题失败——否则不填思路就永远解不出题。
         */
        @Test
        fun `没有学生过程时不算致命缺失`() {
            val noStudentWork =
                AiSolveResult(
                    question = AiQuestion(stem = "求 f(x)=x^3-3x 的单调区间"),
                    standardSolution = AiStandardSolution(steps = listOf(AiSolutionStep(1, "求导", "导数定单调"))),
                    studentSteps = emptyList(),
                    knowledgePoints = emptyList(),
                )
            assertTrue(noStudentWork.missingStructure().isEmpty())
            assertTrue(noStudentWork.hasCompleteStructure())
            assertEquals(listOf("studentSteps", "knowledgePoints"), noStudentWork.missingOptionalStructure())
        }

        /**
         * 非数学科目（化学/物理等）没有内置知识点树，候选清单为空，
         * 阶段 D 必然返回空 kpoints。这同样不能算致命缺失。
         */
        @Test
        fun `知识点为空时不算致命缺失`() {
            val chemistry =
                AiSolveResult(
                    question = AiQuestion(stem = "下列有关物质结构与性质的说法不正确的是"),
                    standardSolution = AiStandardSolution(steps = listOf(AiSolutionStep(1, "逐项分析", ""))),
                    studentSteps = listOf(AiStudentStep(1, "选 A", "wrong")),
                    knowledgePoints = emptyList(),
                )
            assertTrue(chemistry.missingStructure().isEmpty())
            assertEquals(listOf("knowledgePoints"), chemistry.missingOptionalStructure())
        }

        @Test
        fun `aiGenerated 缺省为 false`() {
            val result = AiSolveResultCodec.decode("""{"question":{"stem":"x"}}""").getOrThrow()
            assertFalse(result.aiGenerated)
        }
    }

    @Nested
    @DisplayName("宽松解析与未知字段")
    inner class Lenient {
        @Test
        fun `未知字段被忽略`() {
            val raw =
                """
                {
                  "question": {"subject":"math","stem":"x","extraField":"unknown"},
                  "unknownTopLevel": {"a":1},
                  "standardSolution": {"steps":[{"idx":1,"content":"c","why":"w","note":"多余"}],"finalAnswer":"a","extra":2},
                  "studentSteps": [{"idx":1,"content":"c","status":"wrong","confidence":0.3}],
                  "firstError": {"stepIdx":1,"type":"计算失误","explain":"e","correctThinking":"t","severity":"high"},
                  "knowledgePoints": [{"id":"KP_MATH_0001","name":"n","confidence":0.5,"usage":"u","chapter":"c"}],
                  "summaryCard": {"title":"t","bullets":["b"],"icon":"i"},
                  "aiGenerated": true
                }
                """.trimIndent()
            val result = AiSolveResultCodec.decode(raw).getOrThrow()
            assertEquals("math", result.question?.subject)
            assertEquals("计算失误", result.firstError?.type)
            assertTrue(result.aiGenerated)
        }

        @Test
        fun `显式 null 字段按缺省处理`() {
            val raw = """{"question":null,"standardSolution":null,"firstError":null,"summaryCard":null}"""
            val result = AiSolveResultCodec.decode(raw).getOrThrow()
            assertEquals(null, result.question)
            assertEquals(null, result.firstError)
            assertTrue(result.studentSteps.isEmpty())
        }

        @Test
        fun `空对象可解析为零值`() {
            val result = AiSolveResultCodec.decode("{}").getOrThrow()
            assertEquals(null, result.question)
            assertEquals(emptyList<String>(), result.question?.known.orEmpty())
            assertFalse(result.aiGenerated)
        }

        @Test
        fun `非法 JSON 返回 failure 而不是抛异常`() {
            val decoded = AiSolveResultCodec.decode("{\"question\": ")
            assertTrue(decoded.isFailure)
        }
    }
}
