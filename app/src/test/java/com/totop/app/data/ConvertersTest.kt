package com.totop.app.data

import com.totop.app.data.local.Converters
import com.totop.app.domain.model.MasterySnapshot
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.StudentStep
import com.totop.app.domain.model.StudentStepStatus
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Room TypeConverter 的 JVM 单测（不需要 Android 运行时）。
 *
 * 重点验证：*Json 列存的是「裸 JSON 数组/对象」，以及空值与损坏数据的处理约定。
 */
class ConvertersTest {
    private val converters = Converters()

    @Test
    @DisplayName("字符串数组往返稳定，空数组编码为 []")
    fun stringListRoundTrip() {
        val values = listOf("已知 x>0", "已知 y 为整数")
        val encoded = converters.fromStringList(values)
        assertEquals("""["已知 x>0","已知 y 为整数"]""", encoded)
        assertEquals(values, converters.toStringList(encoded))
        assertEquals("[]", converters.fromStringList(emptyList()))
        assertEquals(emptyList<String>(), converters.toStringList("[]"))
        assertEquals(emptyList<String>(), converters.toStringList(null))
        assertEquals(emptyList<String>(), converters.toStringList("   "))
    }

    @Test
    @DisplayName("学生步骤往返保留 idx/content/status")
    fun studentStepsRoundTrip() {
        val steps =
            listOf(
                StudentStep(idx = 1, content = "去分母", status = StudentStepStatus.CORRECT),
                StudentStep(idx = 2, content = "移项未变号", status = StudentStepStatus.WRONG),
                StudentStep(idx = 3, content = "", status = StudentStepStatus.MISSING),
            )
        val encoded = converters.fromStudentSteps(steps)
        assertTrue(encoded.startsWith("["))
        assertEquals(steps, converters.toStudentSteps(encoded))
        assertEquals(emptyList<StudentStep>(), converters.toStudentSteps(null))
    }

    @Test
    @DisplayName("未知步骤状态回退为 missing，而不是抛异常")
    fun unknownStudentStatusFallsBack() {
        val raw = """[{"idx":1,"content":"x","status":"weird"}]"""
        val decoded = converters.toStudentSteps(raw)
        assertEquals(1, decoded.size)
        assertEquals(StudentStepStatus.MISSING, decoded.single().status)
    }

    @Test
    @DisplayName("标准解答往返保留步骤、答案与方法")
    fun standardSolutionRoundTrip() {
        val solution =
            StandardSolution(
                steps =
                    listOf(
                        SolutionStep(idx = 1, content = "两边同乘 2", why = "消去分母"),
                        SolutionStep(idx = 2, content = "得 x = 3", why = "解一元一次方程"),
                    ),
                finalAnswer = "x = 3",
                method = "去分母法",
            )
        val encoded = converters.fromStandardSolution(solution)
        assertEquals(solution, converters.toStandardSolution(encoded))
        assertEquals(StandardSolution(), converters.toStandardSolution(null))
        assertEquals(StandardSolution(), converters.toStandardSolution("  "))
    }

    @Test
    @DisplayName("掌握度快照往返；越界快照被丢弃而不是让整表查询失败")
    fun masteryHistoryDropsOutOfRange() {
        val history =
            listOf(
                MasterySnapshot(dateEpochDayUtc = 19_000L, score = 42.5),
                MasterySnapshot(dateEpochDayUtc = 19_001L, score = 55.0),
            )
        val encoded = converters.fromMasteryHistory(history)
        assertEquals(history, converters.toMasteryHistory(encoded))

        val corrupted = """[{"day":19000,"score":42.5},{"day":19001,"score":180.0},{"day":19002,"score":12.0}]"""
        val decoded = converters.toMasteryHistory(corrupted)
        assertEquals(2, decoded.size)
        assertEquals(listOf(19_000L, 19_002L), decoded.map { it.dateEpochDayUtc })
    }

    @Test
    @DisplayName("结构损坏的 JSON 抛出 SerializationException，由调用方决定处理方式")
    fun corruptedJsonThrows() {
        assertThrows<SerializationException> { converters.toStringList("{not an array}") }
        assertThrows<SerializationException> { converters.toStandardSolution("{\"steps\": 1}") }
    }

    @Test
    @DisplayName("未知字段被忽略，兼容后续版本新增列")
    fun unknownFieldsIgnored() {
        val raw = """[{"idx":1,"content":"x","why":"y","extraField":1}]"""
        val decoded = converters.toStandardSolution("""{"steps":$raw,"finalAnswer":"x","method":"m","newField":true}""")
        assertEquals(1, decoded.stepCount)
        assertEquals("x", decoded.finalAnswer)
    }
}
