package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * LearningPathPlanner 单测：反向 BFS、掌握度 <60 剪枝、拓扑顺序、目标末尾、空集与环。
 */
class LearningPathPlannerTest {
    private val a = "KP_MATH_0001"
    private val b = "KP_MATH_0002"
    private val c = "KP_MATH_0003"

    private fun catalog(vararg ids: String) = FakeKnowledgeCatalog.of(*ids)

    private fun catalogWith(
        vararg ids: String,
        edges: List<KpointEdge>,
    ) = FakeKnowledgeCatalog(ids.map { FakeKnowledgeCatalog.point(it) }, edges)

    @Nested
    @DisplayName("基本路径")
    inner class Basic {
        @Test
        fun `目标无前置时路径只含目标本身`() {
            val outcome = LearningPathPlanner.plan(c, catalog(a, b, c), mapOf(c to 20.0))
            assertTrue(outcome is LearningPathOutcome.Planned)
            val path = (outcome as LearningPathOutcome.Planned).path
            assertEquals(1, path.size)
            assertEquals(c, path.steps.first().kpointId)
            assertTrue(path.steps.first().isTarget)
        }

        @Test
        fun `链式前置按从底向上输出`() {
            val edges =
                listOf(
                    FakeKnowledgeCatalog.prerequisite(a, b),
                    FakeKnowledgeCatalog.prerequisite(b, c),
                )
            val catalog = catalogWith(a, b, c, edges = edges)
            val path = LearningPathPlanner.planOrNull(c, catalog, mapOf(a to 10.0, b to 20.0, c to 30.0))!!
            assertEquals(listOf(a, b, c), path.steps.map { it.kpointId })
            assertEquals(1, path.steps.first().order)
            assertEquals(3, path.steps.last().order)
            assertTrue(path.steps.last().isTarget)
        }

        @Test
        fun `菱形依赖中前置一定先于依赖方`() {
            val edges =
                listOf(
                    FakeKnowledgeCatalog.prerequisite(a, b),
                    FakeKnowledgeCatalog.prerequisite(a, c),
                    FakeKnowledgeCatalog.prerequisite(b, "KP_MATH_0004"),
                    FakeKnowledgeCatalog.prerequisite(c, "KP_MATH_0004"),
                )
            val catalog = catalogWith(a, b, c, "KP_MATH_0004", edges = edges)
            val path = LearningPathPlanner.planOrNull("KP_MATH_0004", catalog, mapOf(a to 5.0, b to 5.0, c to 5.0))!!
            val order = path.steps.map { it.kpointId }
            assertTrue(order.indexOf(a) < order.indexOf(b))
            assertTrue(order.indexOf(a) < order.indexOf(c))
            assertTrue(order.indexOf(b) < order.indexOf("KP_MATH_0004"))
            assertEquals("KP_MATH_0004", order.last())
        }

        @Test
        fun `路径文案符合先补后攻的表达`() {
            val edges =
                listOf(
                    FakeKnowledgeCatalog.prerequisite(a, b),
                    FakeKnowledgeCatalog.prerequisite(b, c),
                )
            val catalog = catalogWith(a, b, c, edges = edges)
            val path = LearningPathPlanner.planOrNull(c, catalog, mapOf(a to 10.0, b to 20.0, c to 90.0))!!
            assertEquals("先补 " + a + " → " + b + " → 才能攻 " + c, path.describe())
            assertEquals(2, path.weakSteps.size)
        }
    }

    @Nested
    @DisplayName("掌握度剪枝")
    inner class Pruning {
        @Test
        fun `掌握度不低于六十的前置被剪枝`() {
            val edges =
                listOf(
                    FakeKnowledgeCatalog.prerequisite(a, b),
                    FakeKnowledgeCatalog.prerequisite(b, c),
                )
            val catalog = catalogWith(a, b, c, edges = edges)
            val path = LearningPathPlanner.planOrNull(c, catalog, mapOf(a to 90.0, b to 59.9, c to 30.0))!!
            assertEquals(listOf(b, c), path.steps.map { it.kpointId })
        }

        @Test
        fun `全部门槛之上时只输出目标`() {
            val edges = listOf(FakeKnowledgeCatalog.prerequisite(a, b), FakeKnowledgeCatalog.prerequisite(b, c))
            val catalog = catalogWith(a, b, c, edges = edges)
            val path = LearningPathPlanner.planOrNull(c, catalog, mapOf(a to 80.0, b to 80.0, c to 80.0))!!
            assertEquals(listOf(c), path.steps.map { it.kpointId })
            assertTrue(path.weakSteps.isEmpty())
        }

        @Test
        fun `缺失掌握度记录按零处理`() {
            val edges = listOf(FakeKnowledgeCatalog.prerequisite(a, b))
            val catalog = catalogWith(a, b, edges = edges)
            val path = LearningPathPlanner.planOrNull(b, catalog, emptyMap())!!
            assertEquals(listOf(a, b), path.steps.map { it.kpointId })
            assertEquals(0.0, path.steps.first().mastery, 0.0)
        }

        @Test
        fun `无关边与 related 边不进入路径`() {
            val edges =
                listOf(
                    FakeKnowledgeCatalog.prerequisite(a, b),
                    FakeKnowledgeCatalog.related(b, c),
                )
            val catalog = catalogWith(a, b, c, edges = edges)
            val path = LearningPathPlanner.planOrNull(b, catalog, mapOf(a to 10.0, c to 10.0))!!
            assertEquals(listOf(a, b), path.steps.map { it.kpointId })
        }

        @Test
        fun `路径节点数受上限约束`() {
            val ids = (0 until 120).map { "KP_MATH_" + it.toString().padStart(4, '0') }
            val edges = ids.zipWithNext { from, to -> FakeKnowledgeCatalog.prerequisite(from, to) }
            val catalog = FakeKnowledgeCatalog(ids.map { FakeKnowledgeCatalog.point(it) }, edges)
            val path = LearningPathPlanner.planOrNull(ids.last(), catalog, emptyMap())!!
            assertTrue(path.size <= LearningPathPlanner.MAX_PATH_NODES, "路径长度超限：${path.size}")
        }
    }

    @Nested
    @DisplayName("错误场景")
    inner class Errors {
        @Test
        fun `未知目标返回 UnknownTarget`() {
            val outcome = LearningPathPlanner.plan("KP_MATH_9999", catalog(a))
            assertTrue(outcome is LearningPathOutcome.UnknownTarget)
            assertEquals("KP_MATH_9999", (outcome as LearningPathOutcome.UnknownTarget).kpointId)
        }

        @Test
        fun `前置成环时返回 CyclicPrerequisite 而不是错误顺序`() {
            val edges =
                listOf(
                    FakeKnowledgeCatalog.prerequisite(a, b),
                    FakeKnowledgeCatalog.prerequisite(b, a),
                )
            val catalog = catalogWith(a, b, edges = edges)
            val outcome = LearningPathPlanner.plan(b, catalog, mapOf(a to 10.0, b to 10.0))
            assertTrue(outcome is LearningPathOutcome.CyclicPrerequisite)
            assertTrue((outcome as LearningPathOutcome.CyclicPrerequisite).cycle.contains(a))
        }

        @Test
        fun `空知识库不会崩溃`() {
            val empty = FakeKnowledgeCatalog(emptyList(), emptyList())
            assertTrue(LearningPathPlanner.plan(c, empty) is LearningPathOutcome.UnknownTarget)
        }
    }

    @Nested
    @DisplayName("Mastery 重载")
    inner class MasteryOverload {
        @Test
        fun `可用 Mastery 列表直接规划`() {
            val edges = listOf(FakeKnowledgeCatalog.prerequisite(a, b))
            val catalog = catalogWith(a, b, edges = edges)
            val now = UtcTime.EPOCH_2024_01_01
            val masteries =
                listOf(
                    Mastery(a, 15.0, emptyList(), now),
                    Mastery(b, 45.0, emptyList(), now),
                )
            val path = (LearningPathPlanner.plan(b, catalog, masteries) as LearningPathOutcome.Planned).path
            assertEquals(listOf(a, b), path.steps.map { it.kpointId })
        }
    }
}
