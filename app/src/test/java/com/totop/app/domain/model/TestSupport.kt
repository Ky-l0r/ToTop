package com.totop.app.domain.model

/**
 * 测试用知识点目录：内存实现，行为与 data 层基于种子数据的实现保持一致。
 */
class FakeKnowledgeCatalog(
    points: List<KnowledgePoint>,
    private val edgeList: List<KpointEdge>,
) : KnowledgeCatalog {
    private val byId: Map<String, KnowledgePoint> = points.associateBy { it.id }

    override fun allKnowledgePoints(): List<KnowledgePoint> = byId.values.toList()

    override fun knowledgePoint(id: String): KnowledgePoint? = byId[id]

    override fun childrenOf(parentId: String?): List<KnowledgePoint> = byId.values.filter { it.parentId == parentId }

    override fun edges(): List<KpointEdge> = edgeList

    companion object {
        fun point(
            id: String,
            name: String = id,
            subject: String = "math",
        ): KnowledgePoint = KnowledgePoint(id = id, name = name, subject = subject)

        fun prerequisite(
            from: String,
            to: String,
        ): KpointEdge = KpointEdge(fromId = from, toId = to, type = EdgeType.PREREQUISITE)

        fun related(
            from: String,
            to: String,
        ): KpointEdge = KpointEdge(fromId = from, toId = to, type = EdgeType.RELATED)

        fun extends(
            from: String,
            to: String,
        ): KpointEdge = KpointEdge(fromId = from, toId = to, type = EdgeType.EXTENDS)

        fun of(vararg ids: String): FakeKnowledgeCatalog = FakeKnowledgeCatalog(ids.map { point(it) }, emptyList())
    }
}

/** UTC 毫秒工具：所有用例都通过它构造时间，避免任何本地时区依赖。 */
object UtcTime {
    const val SECOND_MS: Long = 1_000L
    const val MINUTE_MS: Long = 60_000L

    /** 一天的毫秒数。 */
    const val DAY_MS: Long = 86_400_000L

    /** 固定基准时刻：2024-01-01T00:00:00Z。 */
    const val EPOCH_2024_01_01: Long = 1_704_067_200_000L

    fun daysAfter(
        base: Long,
        days: Long,
    ): Long = base + days * DAY_MS

    fun hoursAfter(
        base: Long,
        hours: Long,
    ): Long = base + hours * 3_600_000L
}
