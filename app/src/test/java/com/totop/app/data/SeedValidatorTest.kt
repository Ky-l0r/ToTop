package com.totop.app.data

import com.totop.app.data.local.seed.SeedEdge
import com.totop.app.data.local.seed.SeedKnowledgePoint
import com.totop.app.data.local.seed.SeedRejectionReason
import com.totop.app.data.local.seed.SeedValidation
import com.totop.app.data.local.seed.SeedValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 种子数据入库前校验的 JVM 单测（FR-7.3：出现环必须拒绝写入）。
 *
 * 覆盖：合法树、成环、自环、悬空边、未知边类型、重复 ID、非法受控 ID、父节点悬空。
 */
class SeedValidatorTest {
    private fun point(
        id: String,
        parentId: String? = null,
        subject: String = "math",
        orderIndex: Int = 1,
    ): SeedKnowledgePoint =
        SeedKnowledgePoint(
            id = id,
            name = "节点 " + id,
            subject = subject,
            parentId = parentId,
            chapter = "章",
            orderIndex = orderIndex,
            definition = "定义",
            commonApproach = "思路",
            pitfalls = listOf("易错点"),
        )

    private fun edge(
        from: String,
        to: String,
        type: String = "prerequisite",
    ): SeedEdge = SeedEdge(fromId = from, toId = to, type = type, weight = 1.0)

    @Test
    @DisplayName("合法树与无环边通过校验")
    fun validTreeAccepted() {
        val points =
            listOf(point("KP_MATH_0001"), point("KP_MATH_0002", "KP_MATH_0001"), point("KP_MATH_0003", "KP_MATH_0002"))
        val edges = listOf(edge("KP_MATH_0002", "KP_MATH_0003"))
        val result = SeedValidator.validate(points, edges, "math")
        assertTrue(result is SeedValidation.Valid, "应通过校验，实际：" + result)
        val valid = result as SeedValidation.Valid
        assertEquals(3, valid.points.size)
        assertEquals(1, valid.edges.size)
        assertEquals("KP_MATH_0002", valid.edges.single().fromId)
    }

    @Test
    @DisplayName("前置边成环被拒绝，并给出环路径")
    fun cycleRejected() {
        val points = listOf(point("KP_MATH_0001"), point("KP_MATH_0002"), point("KP_MATH_0003"))
        val edges =
            listOf(
                edge("KP_MATH_0001", "KP_MATH_0002"),
                edge("KP_MATH_0002", "KP_MATH_0003"),
                edge("KP_MATH_0003", "KP_MATH_0001"),
            )
        val result = SeedValidator.validate(points, edges, "math")
        assertTrue(result is SeedValidation.Invalid)
        val invalid = result as SeedValidation.Invalid
        assertEquals(SeedRejectionReason.CYCLE, invalid.reason)
        assertTrue(invalid.cycle.isNotEmpty())
        assertEquals(invalid.cycle.first(), invalid.cycle.last())
    }

    @Test
    @DisplayName("自环边被拒绝，且优先于环检测报出")
    fun selfLoopRejected() {
        val points = listOf(point("KP_MATH_0001"))
        val result = SeedValidator.validate(points, listOf(edge("KP_MATH_0001", "KP_MATH_0001")), "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.SELF_LOOP, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("引用不存在节点的边被拒绝")
    fun danglingEdgeRejected() {
        val points = listOf(point("KP_MATH_0001"))
        val result = SeedValidator.validate(points, listOf(edge("KP_MATH_0001", "KP_MATH_9999")), "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.DANGLING_EDGE, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("父节点悬空被拒绝")
    fun danglingParentRejected() {
        val points = listOf(point("KP_MATH_0002", parentId = "KP_MATH_9999"))
        val result = SeedValidator.validate(points, emptyList(), "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.DANGLING_EDGE, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("未知边类型被拒绝")
    fun unknownEdgeTypeRejected() {
        val points = listOf(point("KP_MATH_0001"), point("KP_MATH_0002"))
        val result = SeedValidator.validate(points, listOf(edge("KP_MATH_0001", "KP_MATH_0002", "depends")), "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.UNKNOWN_EDGE_TYPE, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("重复 ID 被拒绝")
    fun duplicateIdRejected() {
        val points = listOf(point("KP_MATH_0001"), point("KP_MATH_0001"))
        val result = SeedValidator.validate(points, emptyList(), "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.DUPLICATE_ID, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("不符合受控 ID 约定的 ID 被拒绝")
    fun invalidControlledIdRejected() {
        val points = listOf(point("MATH-1"))
        val result = SeedValidator.validate(points, emptyList(), "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.INVALID_ID, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("学科不一致被拒绝，避免统计串科目")
    fun subjectMismatchRejected() {
        val points = listOf(point("KP_MATH_0001"), point("KP_PHYSICS_0001", subject = "physics"))
        val result = SeedValidator.validate(points, emptyList(), "math")
        assertTrue(result is SeedValidation.Invalid)
    }

    @Test
    @DisplayName("空树被拒绝")
    fun emptyTreeRejected() {
        val result = SeedValidator.validate(emptyList(), emptyList(), "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.EMPTY_TREE, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("重复边去重并产生告警，但不阻断")
    fun duplicateEdgesDeduped() {
        val points = listOf(point("KP_MATH_0001"), point("KP_MATH_0002"))
        val edges =
            listOf(
                edge("KP_MATH_0001", "KP_MATH_0002"),
                edge("KP_MATH_0001", "KP_MATH_0002"),
            )
        val result = SeedValidator.validate(points, edges, "math")
        assertTrue(result is SeedValidation.Valid)
        val valid = result as SeedValidation.Valid
        assertEquals(1, valid.edges.size)
        assertTrue(valid.warnings.any { it.code == "DUPLICATE_EDGE" })
    }

    @Test
    @DisplayName("related 边成环不阻断，只产生告警")
    fun relatedCycleOnlyWarns() {
        val points = listOf(point("KP_MATH_0001"), point("KP_MATH_0002"))
        val edges =
            listOf(
                edge("KP_MATH_0001", "KP_MATH_0002", "related"),
                edge("KP_MATH_0002", "KP_MATH_0001", "related"),
            )
        val result = SeedValidator.validate(points, edges, "math")
        assertTrue(result is SeedValidation.Valid, "related 横向环不应阻断，实际：" + result)
        assertTrue((result as SeedValidation.Valid).warnings.any { it.code == "RELATED_CYCLE" })
    }

    @Test
    @DisplayName("非法权重被规范化并告警")
    fun invalidWeightNormalized() {
        val points = listOf(point("KP_MATH_0001"), point("KP_MATH_0002"))
        val edges = listOf(SeedEdge("KP_MATH_0001", "KP_MATH_0002", "prerequisite", -1.0))
        val result = SeedValidator.validate(points, edges, "math")
        assertTrue(result is SeedValidation.Valid)
        assertEquals(1.0, (result as SeedValidation.Valid).edges.single().weight)
        assertTrue(result.warnings.any { it.code == "EDGE_WEIGHT" })
    }
}
