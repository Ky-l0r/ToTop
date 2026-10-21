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
 * 学科扩展只需在此追加一条规格，**加载逻辑不需要任何改动**。
 * 资产文件命名统一为 `knowledge_point_<subject>.json` 与 `knowledge_point_edge_<subject>.json`。
 *
 * 覆盖范围（10 个科目）：
 * 语文 / 数学 / 英语 / 物理 / 化学 / 生物 / 政治 / 历史 / 地理 / 技术。
 * 「技术」是浙江省选考科目（信息技术 + 通用技术），因此与其它省份不同、单独成科。
 */
data class SeedSpec(
    val subject: String,
    val pointsAsset: String,
    val edgesAsset: String,
) {
    companion object {
        /** 由科目代码构造规格（命名规则固定，避免逐个手写路径）。 */
        fun of(subject: String): SeedSpec =
            SeedSpec(
                subject = subject,
                pointsAsset = "seed/knowledge_point_" + subject + ".json",
                edgesAsset = "seed/knowledge_point_edge_" + subject + ".json",
            )

        val CHINESE: SeedSpec = of("chinese")

        val MATH: SeedSpec = of("math")

        val ENGLISH: SeedSpec = of("english")

        val PHYSICS: SeedSpec = of("physics")

        val CHEMISTRY: SeedSpec = of("chemistry")

        val BIOLOGY: SeedSpec = of("biology")

        val POLITICS: SeedSpec = of("politics")

        val HISTORY: SeedSpec = of("history")

        val GEOGRAPHY: SeedSpec = of("geography")

        val TECHNOLOGY: SeedSpec = of("technology")

        /**
         * 当前版本内置的全部种子规格，按加载顺序排列。
         *
         * 顺序不影响正确性（写入已按科目隔离），但保持「语数英物化生政史地技」的
         * 课程顺序，便于排查资产问题时按科目定位。
         */
        val ALL: List<SeedSpec> =
            listOf(CHINESE, MATH, ENGLISH, PHYSICS, CHEMISTRY, BIOLOGY, POLITICS, HISTORY, GEOGRAPHY, TECHNOLOGY)

        /** 参与版本计算的资产路径汇总。 */
        val ALL_ASSET_PATHS: List<String> = ALL.flatMap { listOf(it.pointsAsset, it.edgesAsset) }
    }
}
