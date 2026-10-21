package com.totop.app.domain.model

/**
 * 知识点领域模型，对应开发方案 5.1 `knowledge_point` 表与内置种子数据。
 *
 * [id] 必须满足 [KnowledgeIdConvention] 受控 ID 约定。
 */
data class KnowledgePoint(
    /** 受控 ID：KP_<SUBJECT>_<四位序号>。 */
    val id: String,
    /** 名称，例如「导数与单调性」。 */
    val name: String,
    /** 学科，例如 math。 */
    val subject: String,
    /** 父知识点受控 ID；根节点为 null。 */
    val parentId: String? = null,
    /** 教材章节。 */
    val chapter: String = "",
    /** 同层排序。 */
    val orderIndex: Int = 0,
    /** 定义与适用范围（内置数据，可被 AI 补充但不覆盖）。 */
    val definition: String = "",
    /** 通用解题思路模板。 */
    val commonApproach: String = "",
    /** 高频易错点。 */
    val pitfalls: List<String> = emptyList(),
) {
    /** 是否为合法的受控 ID。 */
    val hasControlledId: Boolean get() = KnowledgeIdConvention.isValid(id)
}

/**
 * 知识点有向边，对应开发方案 5.1 `kpoint_edge` 表。
 *
 * 方向约定：`fromId -> toId` 表示「学习方向」，即先掌握 [fromId] 再学 [toId]。
 * 对 [EdgeType.PREREQUISITE] 而言，[fromId] 是 [toId] 的前置知识点；
 * 因而「反向 BFS」即沿 [toId] 找到 [fromId]（见 LearningPathPlanner）。
 *
 * 注意：入图前必须通过 CycleDetector 做环检测（FR-7.3）。
 */
data class KpointEdge(
    val fromId: String,
    val toId: String,
    val type: EdgeType = EdgeType.PREREQUISITE,
    /** 人工整理的权重，默认 1.0。 */
    val weight: Double = 1.0,
) {
    val isSelfLoop: Boolean get() = fromId == toId

    /** 该边是否与指定知识点相邻。 */
    fun involves(kpointId: String): Boolean = fromId == kpointId || toId == kpointId
}

/**
 * 知识点目录接口：由 data 层基于内置种子数据（assets/seed）实现。
 * domain 层只依赖该抽象，不感知 Room 与文件系统。
 */
interface KnowledgeCatalog {
    /** 全量知识点（按章节与 orderIndex 排序的责任在实现方）。 */
    fun allKnowledgePoints(): List<KnowledgePoint>

    /** 按受控 ID 查询；不存在返回 null。 */
    fun knowledgePoint(id: String): KnowledgePoint?

    /** 子节点；parentId 为 null 时返回根节点。 */
    fun childrenOf(parentId: String?): List<KnowledgePoint>

    /** 全量边。 */
    fun edges(): List<KpointEdge>

    /** 是否存在该受控 ID。 */
    fun contains(id: String): Boolean = knowledgePoint(id) != null

    /** 某学科的全部知识点。 */
    fun knowledgePointsOfSubject(subject: String): List<KnowledgePoint> =
        allKnowledgePoints().filter { it.subject == subject }

    /** 某知识点的直接前置知识点 ID 列表（仅有 prerequisite 边）。 */
    fun prerequisiteIdsOf(id: String): List<String> =
        edges().filter { it.type == EdgeType.PREREQUISITE && it.toId == id }.map { it.fromId }
}
