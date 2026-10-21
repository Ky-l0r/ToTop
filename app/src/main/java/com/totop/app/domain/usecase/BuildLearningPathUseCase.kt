package com.totop.app.domain.usecase

import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.LearningPath
import com.totop.app.domain.model.LearningPathOutcome
import com.totop.app.domain.model.LearningPathPlanner
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.MasteryStore

/**
 * 推荐学习路径结果。
 */
sealed interface LearningPathResult {
    /**
     * 规划成功。
     *
     * @param path 路径（首元素为最底层前置，末元素为目标知识点）
     * @param headline 面向用户的一句话文案，例如「先补 一元二次方程 → 再 函数单调性 → 才能攻 导数与单调性」
     * @param target 目标知识点
     * @param totalMasteryDelta 走完路径预计补强的知识点数（掌握度 < 60 的节点数）
     */
    data class Planned(
        val path: LearningPath,
        val headline: String,
        val target: KnowledgePoint,
        val totalMasteryDelta: Int,
    ) : LearningPathResult {
        /** 需要补强的步骤（不含目标）。 */
        val weakSteps get() = path.weakSteps

        /** 路径是否只有目标本身（说明前置都已掌握，可以直接攻目标）。 */
        val targetOnly: Boolean get() = path.size <= 1
    }

    /** 目标知识点不存在（受控 ID 校验失败）。 */
    data class UnknownTarget(val kpointId: String) : LearningPathResult

    /** 前置边成环（脏数据）：拒绝输出错误顺序，并提示重建知识树。 */
    data class CyclicGraph(val cycle: List<String>) : LearningPathResult {
        val headline: String
            get() = "知识图谱中存在循环依赖（" + cycle.joinToString(" → ") + "），已拒绝生成路径。请检查知识点前置关系配置。"
    }
}

/**
 * 推荐学习路径用例（开发方案 FR-7.4）。
 *
 * 纯本地计算，0 token：**不调用模型**，只在知识图谱上做反向 BFS + 拓扑排序。
 * 因此即使未配置 Key 也可以给出路径（FR-6.5 本地可用能力）。
 *
 * 输出文案固定为「先补 A → 再 B → 才能攻 C」的形态（[LearningPath.describe]），
 * 由 UI 直接展示，不需要再拼字符串。
 */
class BuildLearningPathUseCase(
    private val kpoints: KpointDirectory,
    private val masteryStore: MasteryStore,
    @Suppress("unused") // 构造参数：调用方按名注入，当前实现未直接使用
    private val clock: DomainClock = DomainClock.SYSTEM,
) {
    /**
     * 以 [targetKpointId] 为终点规划路径。
     *
     * @param weakThreshold 掌握度低于该值的前置节点才纳入路径（默认 60，与产品阈值一致）
     */
    suspend fun build(
        targetKpointId: String,
        weakThreshold: Double = DEFAULT_WEAK_THRESHOLD,
    ): LearningPathResult {
        require(weakThreshold in 0.0..100.0) { "weakThreshold 必须位于 0..100，实际为：" + weakThreshold }

        val all = runCatching { kpoints.allKnowledgePoints() }.getOrDefault(emptyList())
        val edges = runCatching { kpoints.edges() }.getOrDefault(emptyList())
        val catalog = InMemoryKnowledgeCatalog(all, edges)
        if (!catalog.contains(targetKpointId)) return LearningPathResult.UnknownTarget(targetKpointId)

        val target =
            catalog.knowledgePoint(targetKpointId)
                ?: return LearningPathResult.UnknownTarget(targetKpointId)
        val masteryById =
            runCatching { masteryStore.allMastery() }
                .getOrDefault(emptyList())
                .associate { it.kpointId to it.score }

        val outcome =
            LearningPathPlanner.plan(
                targetKpointId = targetKpointId,
                catalog = catalog,
                masteryById = masteryById,
            )
        return when (outcome) {
            is LearningPathOutcome.UnknownTarget -> LearningPathResult.UnknownTarget(outcome.kpointId)
            is LearningPathOutcome.CyclicPrerequisite -> LearningPathResult.CyclicGraph(outcome.cycle)
            is LearningPathOutcome.Planned -> {
                val path = outcome.path
                LearningPathResult.Planned(
                    path = path,
                    headline = path.describe(),
                    target = target,
                    totalMasteryDelta = path.weakSteps.size,
                )
            }
        }
    }

    /**
     * 规划到「当前最薄弱的 N 个知识点」的路径（首页「今天练什么」入口）。
     *
     * 排序沿用掌握度升序，逐个规划；某个目标成环或不存在时跳过，不影响其它路径。
     */
    suspend fun buildForWeakest(
        limit: Int = 3,
        weakThreshold: Double = DEFAULT_WEAK_THRESHOLD,
    ): List<LearningPathResult.Planned> {
        require(limit > 0) { "limit 必须为正数" }
        val masteries = runCatching { masteryStore.allMastery() }.getOrDefault(emptyList())
        val candidates =
            masteries
                .filter { it.score < weakThreshold }
                .sortedBy(Mastery::score)
                .take(limit)
        return candidates.mapNotNull { mastery ->
            build(mastery.kpointId, weakThreshold) as? LearningPathResult.Planned
        }
    }

    /**
     * 内联知识点目录（只读快照）。
     *
     * 为什么不用 data 层的 catalog 实现：domain 不能依赖 data；
     * 这里用已取到的列表构造一个纯内存视图，行为与 data 层实现一致（同一份算法被两边共用）。
     */
    private class InMemoryKnowledgeCatalog(
        private val points: List<KnowledgePoint>,
        private val edgeList: List<KpointEdge>,
    ) : com.totop.app.domain.model.KnowledgeCatalog {
        private val byId: Map<String, KnowledgePoint> = points.associateBy { it.id }

        override fun allKnowledgePoints(): List<KnowledgePoint> = points

        override fun knowledgePoint(id: String): KnowledgePoint? = byId[id]

        override fun childrenOf(parentId: String?): List<KnowledgePoint> = points.filter { it.parentId == parentId }

        override fun edges(): List<KpointEdge> = edgeList
    }

    companion object {
        /** 默认薄弱阈值：60（与掌握度色阶、路径规划一致）。 */
        const val DEFAULT_WEAK_THRESHOLD: Double = 60.0
    }
}
