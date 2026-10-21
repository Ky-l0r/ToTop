package com.totop.app.data.repo

import androidx.room.withTransaction
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.KnowledgeDao
import com.totop.app.data.local.dao.MasteryDao
import com.totop.app.data.local.mapper.EntityMappers
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.data.local.mapper.toDomainEdgeOrNull
import com.totop.app.data.local.seed.SeedRepository
import com.totop.app.data.local.seed.SeedResult
import com.totop.app.domain.model.EdgeType
import com.totop.app.domain.model.KnowledgeCatalog
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasterySnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 知识库仓库：知识点树、边表与掌握度的唯一读取入口。
 *
 * 同时提供两套读取形态：
 * - **同步快照 [catalog]**：实现 domain 的 [KnowledgeCatalog] 接口，供 LearningPathPlanner 等
 *   纯算法在内存中同步计算（算法本身要求同步、无 IO）；
 * - **Flow 观察**：UI 侧订阅，知识点树与掌握度变化自动刷新。
 *
 * 屏蔽本地/远端：知识点数据只来自内置种子资产（assets/seed），无远端来源。
 * 首次读取前可调用 [ensureSeeded] 完成首启初始化（幂等）。
 */
@Singleton
class KnowledgeRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val knowledgeDao: KnowledgeDao,
        private val masteryDao: MasteryDao,
        private val seedRepository: SeedRepository,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): KnowledgeRepository {
            this.dispatcher = dispatcher
            return this
        }

        /**
         * 在数据库事务内执行一组知识点/掌握度写入（备份恢复、批量重算掌握度使用）。
         *
         * 保持 [database] 依赖的显式用途：掌握度的批量写入与知识点树的替换必须原子完成。
         */
        suspend fun <R> inTransaction(block: suspend () -> R): R =
            withContext(dispatcher) {
                database.withTransaction { block() }
            }

        /**
         * 确保内置种子已写入（首启幂等）。
         *
         * 重复调用安全：SeedRepository 以资产 sha256 指纹与 settings 版本号判定，已写入则返回 Skipped。
         * 环检测失败时不写入任何知识点，返回值里带拒绝原因，供设置页提示。
         */
        suspend fun ensureSeeded(): List<SeedResult> = withContext(dispatcher) { seedRepository.loadAll() }

        /**
         * 内存快照目录：同步接口，供 domain 的纯算法使用。
         *
         * 构造时一次性建好索引，[allKnowledgePoints] 等调用均为 O(1) 或 O(n) 内存访问。
         */
        class CatalogSnapshot(
            points: List<KnowledgePoint>,
            private val edgeList: List<KpointEdge>,
        ) : KnowledgeCatalog {
            private val orderedPoints: List<KnowledgePoint> = points.sortedWith(ORDER)

            private val byId: Map<String, KnowledgePoint> = points.associateBy { it.id }

            private val childrenByParent: Map<String?, List<KnowledgePoint>> = orderedPoints.groupBy { it.parentId }

            private val prerequisiteByTarget: Map<String, List<String>> =
                edgeList.filter { it.type == EdgeType.PREREQUISITE }.groupBy({ it.toId }, { it.fromId })

            override fun allKnowledgePoints(): List<KnowledgePoint> = orderedPoints

            override fun knowledgePoint(id: String): KnowledgePoint? = byId[id]

            override fun childrenOf(parentId: String?): List<KnowledgePoint> = childrenByParent[parentId].orEmpty()

            override fun edges(): List<KpointEdge> = edgeList

            override fun contains(id: String): Boolean = byId.containsKey(id)

            override fun prerequisiteIdsOf(id: String): List<String> = prerequisiteByTarget[id].orEmpty()

            private companion object {
                val ORDER: Comparator<KnowledgePoint> =
                    compareBy({ it.subject }, { it.chapter }, { it.orderIndex }, { it.id })
            }
        }

        /** 读取快照目录（数据库为空时返回空目录，不抛异常）。 */
        suspend fun catalog(): KnowledgeCatalog =
            withContext(dispatcher) {
                val points = knowledgeDao.allKnowledgePoints().map { with(EntityMappers) { it.toDomain() } }
                val edges = knowledgeDao.allEdges().mapNotNull { it.toDomainEdgeOrNull() }
                CatalogSnapshot(points, edges)
            }

        /** 读取快照目录，并在读取前确保种子已写入（首启自动初始化）。 */
        suspend fun catalogEnsuringSeeded(): KnowledgeCatalog {
            ensureSeeded()
            return catalog()
        }

        suspend fun knowledgePoint(id: String): KnowledgePoint? =
            withContext(dispatcher) { knowledgeDao.knowledgePoint(id)?.let { with(EntityMappers) { it.toDomain() } } }

        fun observeKnowledgePoint(id: String): Flow<KnowledgePoint?> =
            knowledgeDao.observeKnowledgePoint(id)
                .map { row -> row?.let { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        fun observeAllKnowledgePoints(): Flow<List<KnowledgePoint>> =
            knowledgeDao.observeKnowledgePoints()
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        fun observeEdges(): Flow<List<KpointEdge>> =
            knowledgeDao.observeEdges()
                .map { rows -> rows.mapNotNull { it.toDomainEdgeOrNull() } }
                .flowOn(dispatcher)

        suspend fun edges(): List<KpointEdge> = withContext(dispatcher) { seedRepository.edges() }

        suspend fun edgeCount(): Int = withContext(dispatcher) { knowledgeDao.countEdges() }

        suspend fun knowledgePointCount(): Int = withContext(dispatcher) { knowledgeDao.countKnowledgePoints() }

        /** 名称查询：知识点名称 -> 受控 ID，未命中返回 null。 */
        suspend fun idByName(name: String): String? =
            withContext(dispatcher) {
                knowledgeDao.allKnowledgePoints().firstOrNull { it.name == name }?.id
            }

        // ---------- 掌握度 ----------

        suspend fun masteryOf(kpointId: String): Mastery? =
            withContext(dispatcher) {
                masteryDao.findById(kpointId)?.let { with(EntityMappers) { it.toDomain() } }
            }

        fun observeMasteryOf(kpointId: String): Flow<Mastery?> =
            masteryDao.observeById(kpointId)
                .map { row -> row?.let { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        suspend fun allMastery(): List<Mastery> =
            withContext(dispatcher) {
                masteryDao.all().map { with(EntityMappers) { it.toDomain() } }
            }

        fun observeAllMastery(): Flow<List<Mastery>> =
            masteryDao.observeAll()
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        suspend fun weakMastery(threshold: Double = DEFAULT_WEAK_THRESHOLD): List<Mastery> =
            withContext(dispatcher) { masteryDao.below(threshold).map { with(EntityMappers) { it.toDomain() } } }

        fun observeWeakMastery(threshold: Double = DEFAULT_WEAK_THRESHOLD): Flow<List<Mastery>> =
            masteryDao.observeBelow(threshold)
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        fun observeAverageMastery(): Flow<Double?> = masteryDao.observeAverageScore().flowOn(dispatcher)

        /**
         * 写入掌握度，并按文档 5.2 裁剪 historyJson 到最近 90 天快照。
         *
         * 裁剪规则：按 [MasterySnapshot.dateEpochDayUtc] 去重（同日以列表中后者为准），
         * 再按日期升序保留最后 [HISTORY_KEEP_DAYS] 条。
         */
        suspend fun saveMastery(mastery: Mastery): Mastery =
            withContext(dispatcher) {
                val trimmed = mastery.copy(history = trimHistory(mastery.history))
                masteryDao.upsert(with(EntityMappers) { trimmed.toEntity() })
                trimmed
            }

        suspend fun saveMasteryAll(items: List<Mastery>): List<Mastery> =
            withContext(dispatcher) {
                val trimmed = items.map { it.copy(history = trimHistory(it.history)) }
                masteryDao.upsertAll(trimmed.map { with(EntityMappers) { it.toEntity() } })
                trimmed
            }

        /** 知识点树 + 边 + 掌握度的联合流，知识图谱与推荐路径页使用。 */
        fun observeTreeWithMastery(): Flow<KnowledgeTreeSnapshot> =
            combine(
                knowledgeDao.observeKnowledgePoints(),
                knowledgeDao.observeEdges(),
                masteryDao.observeAll(),
            ) { pointRows, edgeRows, masteryRows ->
                KnowledgeTreeSnapshot(
                    points = pointRows.map { with(EntityMappers) { it.toDomain() } },
                    edges = edgeRows.mapNotNull { it.toDomainEdgeOrNull() },
                    mastery = masteryRows.map { with(EntityMappers) { it.toDomain() } },
                )
            }.flowOn(dispatcher)

        /** 知识点树 + 边 + 掌握度的联合快照。 */
        data class KnowledgeTreeSnapshot(
            val points: List<KnowledgePoint>,
            val edges: List<KpointEdge>,
            val mastery: List<Mastery>,
        ) {
            /** kpointId -> 掌握度分数。 */
            val masteryById: Map<String, Double> = mastery.associate { it.kpointId to it.score }

            /** 知识点树中的根节点。 */
            val roots: List<KnowledgePoint> = points.filter { it.parentId == null }

            fun childrenOf(parentId: String?): List<KnowledgePoint> =
                points.filter { it.parentId == parentId }.sortedBy { it.orderIndex }
        }

        /** 90 天快照裁剪（见纯函数对象 [MasteryHistory]）。 */
        internal fun trimHistory(history: List<MasterySnapshot>): List<MasterySnapshot> =
            MasteryHistory.trim(history, HISTORY_KEEP_DAYS)

        companion object {
            /** 掌握度保留的快照天数（文档 5.2：最近 90 天），与 [MasteryHistory.KEEP_DAYS] 一致。 */
            const val HISTORY_KEEP_DAYS: Int = MasteryHistory.KEEP_DAYS

            /** 薄弱阈值，与 domain 的 Mastery.WEAK_BELOW 保持一致。 */
            const val DEFAULT_WEAK_THRESHOLD: Double = 60.0
        }
    }
