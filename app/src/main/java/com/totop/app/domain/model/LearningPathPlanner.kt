package com.totop.app.domain.model

/** 学习路径规划结果。 */
sealed interface LearningPathOutcome {
    /** 规划成功。 */
    data class Planned(val path: LearningPath) : LearningPathOutcome

    /** 目标知识点不在知识库中。 */
    data class UnknownTarget(val kpointId: String) : LearningPathOutcome

    /** 前置边构成环，无法给出拓扑序（FR-7.3 / FR-7.4）。 */
    data class CyclicPrerequisite(val cycle: List<String>) : LearningPathOutcome
}

/** 学习路径中的一步。 */
data class LearningPathStep(
    /** 受控知识点 ID。 */
    val kpointId: String,
    /** 名称（知识库缺失时回退为 ID）。 */
    val name: String,
    /** 当前掌握度 0..100；缺失视为 0（最薄弱）。 */
    val mastery: Double,
    /** 在第几步（从 1 开始）。 */
    val order: Int,
    /** 是否为规划目标（路径末节点）。 */
    val isTarget: Boolean = false,
) {
    val isWeak: Boolean get() = mastery < Mastery.WEAK_BELOW
}

/**
 * 推荐学习路径（开发方案 FR-7.4）。
 *
 * [steps] 首元素为最底层前置，末元素为目标知识点，形如「先补 A → 再 B → 才能攻 C」。
 */
data class LearningPath(
    val targetKpointId: String,
    val steps: List<LearningPathStep>,
) {
    val isEmpty: Boolean get() = steps.isEmpty()

    val size: Int get() = steps.size

    /** 需要补强的步骤（掌握度 < 60），不含目标节点本身。 */
    val weakSteps: List<LearningPathStep>
        get() = steps.filter { !it.isTarget && it.isWeak }

    /** 面向用户的一句话路径文案。 */
    fun describe(): String {
        if (steps.isEmpty()) return ""
        val names = steps.map { it.name }
        return if (names.size == 1) {
            "直接攻 " + names.first()
        } else {
            "先补 " + names.dropLast(1).joinToString(" → ") + " → 才能攻 " + names.last()
        }
    }
}

/**
 * 学习路径规划器（开发方案 FR-7.4「推荐路径」）。
 *
 * 算法（三步）：
 * 1. **反向 BFS**：从目标知识点出发，沿 prerequisite 边反向回溯（边 `from → to` 表示
 *    from 是 to 的前置，反向即由 to 找 from），只把掌握度 < 60 的节点纳入路径，
 *    掌握度 >= 60 的前置视为已掌握并剪枝；
 * 2. **拓扑排序**：对纳入的节点子图做 Kahn 拓扑排序；若子图仍有环，
 *    返回 [LearningPathOutcome.CyclicPrerequisite]，绝不输出错误顺序；
 * 3. **分层排序**：按最长前置链层级升序、同层按掌握度升序排列（先补最薄弱的），
 *    目标知识点固定放在末尾。
 *
 * 边界：空边集 / 目标无前置 / 目标本身无掌握度记录（按 0 处理）/ 脏数据成环，
 * 均有确定行为，可单测。
 */
object LearningPathPlanner {
    /** 前置回溯的掌握度阈值（<60 才纳入路径）。 */
    const val WEAK_THRESHOLD: Double = Mastery.WEAK_BELOW

    /** 路径长度上限，防止脏数据造成超长路径（开发方案要求单屏节点 ≤ 80）。 */
    const val MAX_PATH_NODES: Int = 80

    /**
     * 规划路径。
     *
     * @param targetKpointId 目标知识点受控 ID
     * @param catalog 知识点目录（提供边与名称）
     * @param masteryById 知识点当前掌握度（0..100），缺失节点的掌握度按 0 处理
     */
    fun plan(
        targetKpointId: String,
        catalog: KnowledgeCatalog,
        masteryById: Map<String, Double> = emptyMap(),
    ): LearningPathOutcome {
        if (!catalog.contains(targetKpointId)) return LearningPathOutcome.UnknownTarget(targetKpointId)

        val prereqIncoming: Map<String, List<String>> =
            catalog.edges()
                .filter { it.type == EdgeType.PREREQUISITE && !it.isSelfLoop }
                .groupBy({ it.toId }, { it.fromId })

        val collected = LinkedHashSet<String>()
        collected.add(targetKpointId)
        val queue = ArrayDeque<String>()
        queue.add(targetKpointId)
        while (queue.isNotEmpty() && collected.size < MAX_PATH_NODES) {
            val current = queue.removeFirst()
            for (parent in prereqIncoming[current].orEmpty()) {
                if (parent in collected) continue
                if (masteryOf(parent, masteryById) >= WEAK_THRESHOLD) continue
                collected.add(parent)
                queue.add(parent)
                if (collected.size >= MAX_PATH_NODES) break
            }
        }
        val nodes = collected.toList()

        // 仅保留子图内的前置边。
        val subsetEdges =
            catalog.edges().filter {
                it.type == EdgeType.PREREQUISITE &&
                    !it.isSelfLoop &&
                    it.fromId in collected &&
                    it.toId in collected
            }

        val ordered =
            topologicalOrder(nodes, subsetEdges)
                ?: return LearningPathOutcome.CyclicPrerequisite(
                    CycleDetector.detectCycle(nodes, subsetEdges).orEmpty(),
                )

        val level = longestPrerequisiteChain(nodes, subsetEdges, ordered)
        val orderedIndex = ordered.withIndex().associate { (position, node) -> node to position }
        val nonTarget =
            nodes.filter { it != targetKpointId }.sortedWith(
                compareBy<String> { level[it] ?: 0 }
                    .thenBy { masteryOf(it, masteryById) }
                    .thenBy { orderedIndex[it] ?: 0 },
            )
        val sequence = nonTarget + targetKpointId

        val steps =
            sequence.mapIndexed { index, id ->
                LearningPathStep(
                    kpointId = id,
                    name = catalog.knowledgePoint(id)?.name ?: id,
                    mastery = masteryOf(id, masteryById),
                    order = index + 1,
                    isTarget = id == targetKpointId,
                )
            }
        return LearningPathOutcome.Planned(LearningPath(targetKpointId = targetKpointId, steps = steps))
    }

    /** 便捷重载：已知每个知识点的 [Mastery] 时使用。 */
    fun plan(
        targetKpointId: String,
        catalog: KnowledgeCatalog,
        masteries: List<Mastery>,
    ): LearningPathOutcome =
        plan(
            targetKpointId = targetKpointId,
            catalog = catalog,
            masteryById = masteries.associate { it.kpointId to it.score },
        )

    /** 便捷重载：成功时返回路径，否则返回 null。 */
    fun planOrNull(
        targetKpointId: String,
        catalog: KnowledgeCatalog,
        masteryById: Map<String, Double> = emptyMap(),
    ): LearningPath? = (plan(targetKpointId, catalog, masteryById) as? LearningPathOutcome.Planned)?.path

    private fun masteryOf(
        kpointId: String,
        masteryById: Map<String, Double>,
    ): Double = (masteryById[kpointId] ?: 0.0).coerceIn(0.0, 100.0)

    /**
     * Kahn 拓扑排序；存在环时返回 null。
     * 同层节点按节点名升序入队，保证结果确定（便于单测复现）。
     */
    private fun topologicalOrder(
        nodes: List<String>,
        edges: List<KpointEdge>,
    ): List<String>? {
        val indexOf = nodes.withIndex().associate { (i, id) -> id to i }
        val indegree = IntArray(nodes.size)
        val adjacency = Array(nodes.size) { mutableListOf<Int>() }
        for (edge in edges) {
            val from = indexOf[edge.fromId] ?: continue
            val to = indexOf[edge.toId] ?: continue
            adjacency[from].add(to)
            indegree[to]++
        }
        val ready = nodes.indices.filter { indegree[it] == 0 }.sortedBy { nodes[it] }
        val queue = ArrayDeque(ready)
        val result = ArrayList<String>(nodes.size)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            result.add(nodes[current])
            val newlyReady = mutableListOf<Int>()
            for (next in adjacency[current]) {
                indegree[next]--
                if (indegree[next] == 0) newlyReady.add(next)
            }
            newlyReady.sortedByDescending { nodes[it] }.forEach { queue.addFirst(it) }
        }
        return if (result.size == nodes.size) result else null
    }

    /** 最长前置链长度：level(v) = max(level(前置)) + 1，无前置为 0。 */
    private fun longestPrerequisiteChain(
        nodes: List<String>,
        edges: List<KpointEdge>,
        topological: List<String>,
    ): Map<String, Int> {
        val level = nodes.associateWith { 0 }.toMutableMap()
        val incoming = edges.groupBy({ it.toId }, { it.fromId })
        for (node in topological) {
            val parents = incoming[node].orEmpty()
            if (parents.isEmpty()) continue
            val maxParent = parents.maxOf { level[it] ?: 0 }
            level[node] = maxParent + 1
        }
        return level
    }
}
