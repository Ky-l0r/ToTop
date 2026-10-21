package com.totop.app.data.local.seed

import com.totop.app.domain.model.CycleDetector
import com.totop.app.domain.model.EdgeType
import com.totop.app.domain.model.KnowledgeIdConvention
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointEdge

/**
 * 种子数据校验结果。
 *
 * 校验是纯 Kotlin 的（只依赖 domain），可在 JVM 单测中直接覆盖，
 * 与 Room、Assets 无关。
 */
sealed interface SeedValidation {
    /** 校验通过，可写入。 */
    data class Valid(
        val points: List<KnowledgePoint>,
        val edges: List<KpointEdge>,
        val warnings: List<SeedWarning> = emptyList(),
    ) : SeedValidation

    /** 校验失败，禁止写入（FR-7.3：出现环必须拒绝写入并记录日志）。 */
    data class Invalid(
        val reason: SeedRejectionReason,
        val detail: String,
        val cycle: List<String> = emptyList(),
        val warnings: List<SeedWarning> = emptyList(),
    ) : SeedValidation
}

/** 拒绝写入的原因。 */
enum class SeedRejectionReason {
    /** 知识点 ID 不满足受控 ID 约定。 */
    INVALID_ID,

    /** 文件为空或解析后没有任何知识点。 */
    EMPTY_TREE,

    /** 出现自环边。 */
    SELF_LOOP,

    /** 前置 / 延伸关系成环（DFS 三色标记检出）。 */
    CYCLE,

    /** 边引用了树中不存在的知识点。 */
    DANGLING_EDGE,

    /** 边类型不是 prerequisite / related / extends。 */
    UNKNOWN_EDGE_TYPE,

    /** 同一 ID 在树中出现多次。 */
    DUPLICATE_ID,
}

/** 不阻断写入、但需要记录的问题。 */
data class SeedWarning(val code: String, val message: String)

/**
 * 种子数据入库前校验（开发方案 FR-7.3 / 5.3 / data/README.md 第四节约定）。
 *
 * 校验顺序：结构合法性 -> 引用完整性 -> 自环 -> 环检测（DFS 三色标记，复用 domain 的 CycleDetector）。
 * 任一致命项失败即返回 [SeedValidation.Invalid]，调用方必须拒绝写入。
 */
object SeedValidator {
    fun validate(
        points: List<SeedKnowledgePoint>,
        edges: List<SeedEdge>,
        subject: String,
    ): SeedValidation {
        val warnings = mutableListOf<SeedWarning>()

        if (points.isEmpty()) {
            return SeedValidation.Invalid(
                reason = SeedRejectionReason.EMPTY_TREE,
                detail = "知识点树为空：" + subject,
                warnings = warnings,
            )
        }

        // 1. 受控 ID 与重复检查
        val seen = HashSet<String>(points.size)
        for (point in points) {
            if (!KnowledgeIdConvention.isValid(point.id)) {
                return SeedValidation.Invalid(
                    reason = SeedRejectionReason.INVALID_ID,
                    detail = "知识点 ID 不符合受控约定 KP_<SUBJECT>_<四位序号>：" + point.id,
                    warnings = warnings,
                )
            }
            if (!seen.add(point.id)) {
                return SeedValidation.Invalid(
                    reason = SeedRejectionReason.DUPLICATE_ID,
                    detail = "知识点 ID 重复：" + point.id,
                    warnings = warnings,
                )
            }
        }

        // 学科一致性：文件里的 subject 必须与规格一致，否则统计会串科目
        points.firstOrNull { it.subject != subject }?.let {
            return SeedValidation.Invalid(
                reason = SeedRejectionReason.INVALID_ID,
                detail = "知识点 " + it.id + " 的 subject 为 " + it.subject + "，与种子规格 " + subject + " 不一致",
                warnings = warnings,
            )
        }

        // 2. 父节点引用完整性（父必须是本树内的节点）
        for (point in points) {
            val parentId = point.parentId ?: continue
            if (parentId !in seen) {
                return SeedValidation.Invalid(
                    reason = SeedRejectionReason.DANGLING_EDGE,
                    detail = "知识点 " + point.id + " 的 parentId 不存在于树中：" + parentId,
                    warnings = warnings,
                )
            }
            if (parentId == point.id) {
                return SeedValidation.Invalid(
                    reason = SeedRejectionReason.SELF_LOOP,
                    detail = "知识点 " + point.id + " 的 parentId 指向自身",
                    warnings = warnings,
                )
            }
        }

        // 3. 边类型与端点
        val domainEdges = ArrayList<KpointEdge>(edges.size)
        for (edge in edges) {
            val type =
                EdgeType.fromWire(edge.type)
                    ?: return SeedValidation.Invalid(
                        reason = SeedRejectionReason.UNKNOWN_EDGE_TYPE,
                        detail = "边类型无法识别：" + edge.type + "（" + edge.fromId + " -> " + edge.toId + "）",
                        warnings = warnings,
                    )
            if (edge.fromId !in seen || edge.toId !in seen) {
                return SeedValidation.Invalid(
                    reason = SeedRejectionReason.DANGLING_EDGE,
                    detail = "边引用了树中不存在的知识点：" + edge.fromId + " -> " + edge.toId,
                    warnings = warnings,
                )
            }
            if (!edge.weight.isFinite() || edge.weight <= 0.0) {
                warnings += SeedWarning("EDGE_WEIGHT", "边权重非法，按 1.0 处理：" + edge.fromId + " -> " + edge.toId)
            }
            domainEdges +=
                KpointEdge(
                    fromId = edge.fromId,
                    toId = edge.toId,
                    type = type,
                    weight = if (edge.weight.isFinite() && edge.weight > 0.0) edge.weight else 1.0,
                )
        }
        val deduped = domainEdges.distinctBy { Triple(it.fromId, it.toId, it.type) }
        if (deduped.size != domainEdges.size) {
            warnings +=
                SeedWarning(
                    "DUPLICATE_EDGE",
                    "存在重复边，已去重 " + (domainEdges.size - deduped.size) + " 条",
                )
        }

        // 4. 自环
        deduped.firstOrNull { it.isSelfLoop }?.let { loop ->
            return SeedValidation.Invalid(
                reason = SeedRejectionReason.SELF_LOOP,
                detail = "存在自环边：" + loop.fromId + " -> " + loop.toId,
                cycle = listOf(loop.fromId, loop.toId),
                warnings = warnings,
            )
        }

        // 5. 环检测（DFS 三色标记，domain 实现）
        val report = CycleDetector.validateForInsert(seen, deduped)
        if (report.hasCycle) {
            return SeedValidation.Invalid(
                reason = SeedRejectionReason.CYCLE,
                detail = "知识点边成环，拒绝写入：" + report.cycle.joinToString(" -> "),
                cycle = report.cycle,
                warnings = warnings,
            )
        }

        // 6. related 边参与检测的提示（当前产品允许横向关联，不阻断）
        val relatedOnly = CycleDetector.detect(seen, deduped, includeRelated = true)
        if (relatedOnly.hasCycle) {
            warnings +=
                SeedWarning(
                    "RELATED_CYCLE",
                    "related 边存在横向环（产品允许，不影响前置路径）：" + relatedOnly.cycle.joinToString(" -> "),
                )
        }

        val domainPoints =
            points.map {
                KnowledgePoint(
                    id = it.id,
                    name = it.name,
                    subject = it.subject,
                    parentId = it.parentId,
                    chapter = it.chapter,
                    orderIndex = it.orderIndex,
                    definition = it.definition,
                    commonApproach = it.commonApproach,
                    pitfalls = it.pitfalls,
                )
            }

        return SeedValidation.Valid(points = domainPoints, edges = deduped, warnings = warnings)
    }
}
