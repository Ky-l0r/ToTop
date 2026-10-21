package com.totop.app.data.local.seed

import kotlinx.serialization.Serializable

/**
 * assets/seed 中知识点树与边表的反序列化模型（字段与 assets 文件一一对应）。
 *
 * 只用于 data 层解析，不对外暴露；转换为领域模型由 SeedValidator / SeedRepository 完成。
 */
@Serializable
data class SeedKnowledgePoint(
    /** 受控 ID：KP_<SUBJECT>_<四位序号>。 */
    val id: String,
    val name: String,
    val subject: String,
    /** 根节点为 null。 */
    val parentId: String? = null,
    val chapter: String = "",
    val orderIndex: Int = 0,
    val definition: String = "",
    val commonApproach: String = "",
    val pitfalls: List<String> = emptyList(),
)

/** assets 边表条目。type 为 prerequisite / related / extends（见 domain 的 EdgeType）。 */
@Serializable
data class SeedEdge(
    val fromId: String,
    val toId: String,
    val type: String,
    val weight: Double = 1.0,
)

/**
 * 一个学科的种子规格：知识点树文件 + 边表文件。
 *
 * 学科扩展（物理、化学）只需在此追加一条规格，代码无需改动（方案 5.3）。
 */
data class SeedSpec(
    val subject: String,
    val pointsAsset: String,
    val edgesAsset: String,
) {
    companion object {
        /** 数学树与边（当前唯一已提供资产）。 */
        val MATH: SeedSpec =
            SeedSpec(
                subject = "math",
                pointsAsset = "seed/knowledge_point_math.json",
                edgesAsset = "seed/knowledge_point_edge_math.json",
            )

        /** 当前版本内置的全部种子规格，按加载顺序排列。 */
        val ALL: List<SeedSpec> = listOf(MATH)

        /** 参与版本计算的资产路径汇总。 */
        val ALL_ASSET_PATHS: List<String> = ALL.flatMap { listOf(it.pointsAsset, it.edgesAsset) }
    }
}
