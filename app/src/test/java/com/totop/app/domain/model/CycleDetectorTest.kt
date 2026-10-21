package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CycleDetector 单测：空集、无环链、二元环、自环、长环、related 边豁免与三色标记不误报。
 */
class CycleDetectorTest {
    private fun p(
        from: String,
        to: String,
    ) = FakeKnowledgeCatalog.prerequisite(from, to)

    @Nested
    @DisplayName("无环场景")
    inner class Acyclic {
        @Test
        fun `空边集无环`() {
            val report = CycleDetector.detect(emptyList())
            assertFalse(report.hasCycle)
            assertTrue(report.cycle.isEmpty())
        }

        @Test
        fun `线性链无环`() {
            val edges = listOf(p("A", "B"), p("B", "C"), p("C", "D"))
            assertFalse(CycleDetector.detect(edges).hasCycle)
            assertNull(CycleDetector.detectCycle(edges))
        }

        @Test
        fun `菱形依赖无环`() {
            val edges = listOf(p("A", "B"), p("A", "C"), p("B", "D"), p("C", "D"))
            assertFalse(CycleDetector.detect(edges).hasCycle)
        }

        @Test
        fun `孤立节点不产生环`() {
            val report = CycleDetector.detect(nodes = listOf("A", "B", "C"), edges = emptyList())
            assertFalse(report.hasCycle)
        }

        @Test
        fun `同层多条边重复不误报`() {
            val edges = listOf(p("A", "B"), p("A", "B"), p("A", "C"))
            assertFalse(CycleDetector.detect(edges).hasCycle)
        }
    }

    @Nested
    @DisplayName("有环场景")
    inner class Cyclic {
        @Test
        fun `二元环被检出`() {
            val report = CycleDetector.detect(listOf(p("A", "B"), p("B", "A")))
            assertTrue(report.hasCycle)
            assertEquals(3, report.cycle.size)
            assertEquals(report.cycle.first(), report.cycle.last())
        }

        @Test
        fun `三元环被检出且首尾闭合`() {
            val report = CycleDetector.detect(listOf(p("A", "B"), p("B", "C"), p("C", "A")))
            assertTrue(report.hasCycle)
            assertEquals(4, report.cycle.size)
            assertEquals(report.cycle.first(), report.cycle.last())
            assertTrue(report.cycle.containsAll(listOf("A", "B", "C")))
        }

        @Test
        fun `自环被检出`() {
            val report = CycleDetector.detect(listOf(p("A", "A")))
            assertTrue(report.hasCycle)
            assertEquals(listOf("A", "A"), report.cycle)
        }

        @Test
        fun `长链末端回指入口也能检出`() {
            val edges = listOf(p("A", "B"), p("B", "C"), p("C", "D"), p("D", "B"))
            val report = CycleDetector.detect(edges)
            assertTrue(report.hasCycle)
            assertTrue(report.cycle.contains("B"))
        }

        @Test
        fun `多个环时至少报出其中一个`() {
            val edges = listOf(p("A", "B"), p("B", "A"), p("C", "D"), p("D", "C"))
            val report = CycleDetector.detect(edges)
            assertTrue(report.hasCycle)
            assertEquals(report.cycle.first(), report.cycle.last())
        }
    }

    @Nested
    @DisplayName("边类型过滤与入库守卫")
    inner class EdgeTypesAndGuard {
        @Test
        fun `related 边默认不参与依赖环判定`() {
            val edges =
                listOf(
                    FakeKnowledgeCatalog.related("A", "B"),
                    FakeKnowledgeCatalog.related("B", "A"),
                )
            assertFalse(CycleDetector.detect(edges).hasCycle)
            assertTrue(CycleDetector.detect(edges, includeRelated = true).hasCycle)
        }

        @Test
        fun `extends 边参与环判定`() {
            val edges =
                listOf(
                    KpointEdge("A", "B", EdgeType.EXTENDS),
                    KpointEdge("B", "A", EdgeType.EXTENDS),
                )
            assertTrue(CycleDetector.detect(edges).hasCycle)
        }

        @Test
        fun `入库守卫拒绝自环`() {
            val edge = p("A", "A")
            val verdict = KpointGraphGuard.verify(listOf("A"), listOf(edge))
            assertTrue(verdict is KpointGraphGuard.Verdict.SelfLoop)
        }

        @Test
        fun `入库守卫拒绝成环`() {
            val verdict = KpointGraphGuard.verify(listOf("A", "B"), listOf(p("A", "B"), p("B", "A")))
            assertTrue(verdict is KpointGraphGuard.Verdict.Cyclic)
        }

        @Test
        fun `入库守卫放行无环边集`() {
            val verdict = KpointGraphGuard.verify(listOf("A", "B", "C"), listOf(p("A", "B"), p("B", "C")))
            assertTrue(verdict is KpointGraphGuard.Verdict.Allowed)
        }

        @Test
        fun `自环辅助判定`() {
            assertTrue(CycleDetector.hasSelfLoop(listOf(p("A", "A"))))
            assertFalse(CycleDetector.hasSelfLoop(listOf(p("A", "B"))))
        }
    }
}
