@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.totop.app.ui.mistakes

import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.StudentStepStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 错题回顾页的纯逻辑测试。
 *
 * 覆盖两类问题：
 * 1. 库里的 JSON 列可能损坏或字段缺失，解析必须**降级而不是崩溃**；
 * 2. 知识点候选必须随科目级联收敛，不能出现「选了数学却看到文言文」。
 */
class MistakeDetailTest {
    @Nested
    @DisplayName("JSON 列解析")
    inner class Codec {
        @Test
        @DisplayName("字符串数组列正常解析并去空")
        fun `解析字符串数组`() {
            assertEquals(listOf("a", "b"), MistakeDetailCodec.decodeStringList("[\"a\",\" b \",\"\"]"))
        }

        @Test
        @DisplayName("字符串数组列为空或损坏时返回空列表而不抛异常")
        fun `字符串数组容错`() {
            assertTrue(MistakeDetailCodec.decodeStringList(null).isEmpty())
            assertTrue(MistakeDetailCodec.decodeStringList("").isEmpty())
            assertTrue(MistakeDetailCodec.decodeStringList("不是 JSON").isEmpty())
            assertTrue(MistakeDetailCodec.decodeStringList("[\"未闭合").isEmpty())
        }

        @Test
        @DisplayName("标准解答解析出步骤、答案与方法")
        fun `解析标准解答`() {
            val raw =
                "{\"steps\":[{\"idx\":1,\"content\":\"配方\",\"why\":\"化顶点式\"}]," +
                    "\"finalAnswer\":\"A. 0\",\"method\":\"配方法\"}"
            val decoded = MistakeDetailCodec.decodeSolution(raw)
            assertEquals(1, decoded.steps.size)
            assertEquals("配方", decoded.steps.single().content)
            assertEquals("化顶点式", decoded.steps.single().why)
            assertEquals("A. 0", decoded.finalAnswer)
            assertEquals("配方法", decoded.method)
        }

        @Test
        @DisplayName("标准解答列损坏时降级为空结构，页面照常可打开")
        fun `标准解答容错`() {
            listOf(null, "", "{}", "{\"steps\":[{\"idx\":1", "null").forEach { raw ->
                val decoded = MistakeDetailCodec.decodeSolution(raw)
                assertTrue(decoded.steps.isEmpty(), "损坏内容不应产出步骤")
                assertEquals("", decoded.finalAnswer)
            }
        }

        @Test
        @DisplayName("步骤正文为空或全空白的条目被丢掉")
        fun `过滤空步骤`() {
            val raw = "{\"steps\":[{\"idx\":1,\"content\":\"  \"},{\"idx\":2,\"content\":\"有效\"}]}"
            val decoded = MistakeDetailCodec.decodeSolution(raw)
            assertEquals(1, decoded.steps.size)
            assertEquals("有效", decoded.steps.single().content)
        }

        @Test
        @DisplayName("学生步骤解析出三态")
        fun `解析学生步骤`() {
            val raw =
                "[{\"idx\":1,\"content\":\"写对了\",\"status\":\"correct\"}," +
                    "{\"idx\":2,\"content\":\"这里错了\",\"status\":\"wrong\"}," +
                    "{\"idx\":3,\"content\":\"漏了\",\"status\":\"missing\"}]"
            val steps = MistakeDetailCodec.decodeStudentSteps(raw)
            assertEquals(3, steps.size)
            assertEquals(StudentStepStatus.CORRECT, steps[0].status)
            assertEquals(StudentStepStatus.WRONG, steps[1].status)
            assertEquals(StudentStepStatus.MISSING, steps[2].status)
        }

        @Test
        @DisplayName("学生步骤未知状态归到缺失，不崩")
        fun `学生步骤状态兜底`() {
            val steps =
                MistakeDetailCodec.decodeStudentSteps("[{\"idx\":1,\"content\":\"x\",\"status\":\"???\"}]")
            assertEquals(StudentStepStatus.MISSING, steps.single().status)
        }

        @Test
        @DisplayName("学生步骤列损坏时返回空列表")
        fun `学生步骤容错`() {
            assertTrue(MistakeDetailCodec.decodeStudentSteps(null).isEmpty())
            assertTrue(MistakeDetailCodec.decodeStudentSteps("[[[").isEmpty())
        }
    }

    @Nested
    @DisplayName("知识点按科目级联")
    inner class Cascade {
        private val points =
            listOf(
                point("KP_MATH_0001", "函数单调性", "math", "函数"),
                point("KP_MATH_0002", "导数与极值", "math", "导数"),
                point("KP_PHYSICS_0001", "牛顿第二定律", "physics", "力学"),
                point("KP_CHINESE_0001", "文言实词", "chinese", "文言文"),
            )

        private fun point(
            id: String,
            name: String,
            subject: String,
            chapter: String,
        ): KnowledgePoint =
            KnowledgePoint(
                id = id,
                name = name,
                subject = subject,
                parentId = "ch",
                chapter = chapter,
                orderIndex = 1,
            )

        @Test
        @DisplayName("未选科目时给出全部知识点")
        fun `未选科目返回全部`() {
            val options = MistakesViewModel.kpointOptionsFor(points, null)
            assertEquals(4, options.size)
        }

        @Test
        @DisplayName("选中数学后只剩数学的知识点")
        fun `按科目收敛`() {
            val options = MistakesViewModel.kpointOptionsFor(points, "math")
            assertEquals(2, options.size)
            assertTrue(options.all { it.id.startsWith("KP_MATH_") })
            assertFalse(options.any { it.id.startsWith("KP_CHINESE_") })
        }

        @Test
        @DisplayName("候选为空串科目时按全部处理")
        fun `空科目视为全部`() {
            assertEquals(4, MistakesViewModel.kpointOptionsFor(points, "").size)
        }

        @Test
        @DisplayName("没有知识点的科目返回空而不是把别科塞进来")
        fun `无节点科目返回空`() {
            assertTrue(MistakesViewModel.kpointOptionsFor(points, "technology").isEmpty())
        }
    }
}
