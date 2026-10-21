package com.totop.app.domain.model

/**
 * 环检测结果。
 *
 * @param hasCycle 是否存在环
 * @param cycle 首个被发现的环（首尾为同一节点，例如 [A, B, C, A]）；无环时为空
 */
data class CycleReport(
    val hasCycle: Boolean,
    val cycle: List<String> = emptyList(),
) {
    companion object {
        val ACYCLIC: CycleReport = CycleReport(hasCycle = false, cycle = emptyList())
    }
}

/**
 * 知识点边环检测（开发方案 FR-7.3：入库前必须做环检测，DFS 三色标记）。
 *
 * 三色语义：
 * - WHITE(0)：未访问；
 * - GRAY(1)：当前 DFS 栈上（再次遇到即为回边，说明存在环）；
 * - BLACK(2)：已完成，其所有后代都已访问完毕。
 *
 * 纯 Kotlin 实现：不依赖 Android API、不依赖具体存储，可在 JVM 单测直接调用。
 *
 * 说明：`related` 边表示知识点之间的横向关联，产品允许双向关联，
 * 默认不参与「依赖环」判定；需要严格判定全部边时把 [includeRelated] 置为 true。
 */
object CycleDetector {
    private const val WHITE = 0
    private const val GRAY = 1
    private const val BLACK = 2

    /**
     * 检测边集合中的环。
     *
     * @param nodes 显式节点集合；未在边中出现但需要参与检测的孤立节点也应传入
     * @param edges 有向边
     * @param includeRelated 是否把 [EdgeType.RELATED] 边一并纳入判定（默认 false）
     */
    fun detect(
        nodes: Collection<String>,
        edges: List<KpointEdge>,
        includeRelated: Boolean = false,
    ): CycleReport = detectCycle(nodes, edges, includeRelated)?.let { CycleReport(true, it) } ?: CycleReport.ACYCLIC

    /** 便捷重载：仅传入边，节点从边端点推导。 */
    fun detect(
        edges: List<KpointEdge>,
        includeRelated: Boolean = false,
    ): CycleReport = detect(edges.flatMap { listOf(it.fromId, it.toId) }.distinct(), edges, includeRelated)

    /**
     * 返回首个环（形如 [A, B, A]）；无环返回 null。
     */
    fun detectCycle(
        nodes: Collection<String>,
        edges: List<KpointEdge>,
        includeRelated: Boolean = false,
    ): List<String>? {
        val relevant = filterEdges(edges, includeRelated)
        val adjacency = LinkedHashMap<String, MutableList<String>>()
        for (node in nodes) adjacency.getOrPut(node) { mutableListOf() }
        for (edge in relevant) {
            adjacency.getOrPut(edge.fromId) { mutableListOf() }.add(edge.toId)
            adjacency.getOrPut(edge.toId) { mutableListOf() }
        }
        val colors = HashMap<String, Int>(adjacency.size)
        val parent = HashMap<String, String?>()
        val stack = ArrayDeque<String>()

        fun dfs(start: String): List<String>? {
            val iteratorStack = ArrayDeque<Pair<String, Iterator<String>>>()
            colors[start] = GRAY
            parent[start] = null
            stack.addLast(start)
            iteratorStack.addLast(start to adjacency[start].orEmpty().iterator())
            while (iteratorStack.isNotEmpty()) {
                val (node, iterator) = iteratorStack.last()
                if (iterator.hasNext()) {
                    val next = iterator.next()
                    when (colors[next] ?: WHITE) {
                        WHITE -> {
                            colors[next] = GRAY
                            parent[next] = node
                            stack.addLast(next)
                            iteratorStack.addLast(next to adjacency[next].orEmpty().iterator())
                        }
                        GRAY -> return buildCycle(next, node, parent)
                        else -> Unit // BLACK：已完成的子树，安全跳过
                    }
                } else {
                    colors[node] = BLACK
                    stack.removeLast()
                    iteratorStack.removeLast()
                }
            }
            return null
        }

        for (node in adjacency.keys) {
            if ((colors[node] ?: WHITE) == WHITE) {
                val found = dfs(node)
                if (found != null) return found
            }
        }
        return null
    }

    /** 便捷重载：仅传入边。 */
    fun detectCycle(
        edges: List<KpointEdge>,
        includeRelated: Boolean = false,
    ): List<String>? = detectCycle(edges.flatMap { listOf(it.fromId, it.toId) }.distinct(), edges, includeRelated)

    /**
     * 入库前守卫：有环时返回 [CycleReport]，调用方必须拒绝写入并记录日志。
     */
    fun validateForInsert(
        nodes: Collection<String>,
        edges: List<KpointEdge>,
        includeRelated: Boolean = false,
    ): CycleReport = detect(nodes, edges, includeRelated)

    /** 是否存在自环（fromId == toId）。 */
    fun hasSelfLoop(edges: List<KpointEdge>): Boolean = edges.any { it.isSelfLoop }

    private fun filterEdges(
        edges: List<KpointEdge>,
        includeRelated: Boolean,
    ): List<KpointEdge> = edges.filter { if (includeRelated) true else it.type != EdgeType.RELATED }

    /** 由 parent 链回溯出环：[重复节点, ...路径..., 重复节点]。 */
    private fun buildCycle(
        repeated: String,
        current: String,
        parent: Map<String, String?>,
    ): List<String> {
        val cycle = ArrayDeque<String>()
        cycle.addFirst(repeated)
        var node: String? = current
        while (node != null && node != repeated) {
            cycle.addFirst(node)
            node = parent[node]
        }
        cycle.addFirst(repeated)
        return cycle.toList()
    }
}

/**
 * 知识点边入图前的守卫（FR-7.3）：把「环检测 + 自环」统一成一次调用。
 */
object KpointGraphGuard {
    sealed class Verdict {
        /** 允许写入。 */
        object Allowed : Verdict()

        /** 拒绝写入：自环。 */
        data class SelfLoop(val edge: KpointEdge) : Verdict()

        /** 拒绝写入：成环。 */
        data class Cyclic(val cycle: List<String>) : Verdict()
    }

    fun verify(
        nodes: Collection<String>,
        edges: List<KpointEdge>,
    ): Verdict {
        edges.firstOrNull { it.isSelfLoop }?.let { return Verdict.SelfLoop(it) }
        val report = CycleDetector.validateForInsert(nodes, edges)
        return if (report.hasCycle) Verdict.Cyclic(report.cycle) else Verdict.Allowed
    }
}
