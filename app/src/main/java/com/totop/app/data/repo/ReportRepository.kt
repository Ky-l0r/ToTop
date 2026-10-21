package com.totop.app.data.repo

import com.totop.app.data.local.dao.ReportDao
import com.totop.app.data.local.entity.ReportEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 周报 / 月报仓库（开发方案 5.1 `report`）。
 *
 * 报告的统计数据以 JSON 原样保存（statsJson），领域层负责序列化与解释；
 * 数据层不做统计口径判断，避免与 ReportBuilder 的口径重复。
 */
@Singleton
class ReportRepository
    @Inject
    constructor(
        private val dao: ReportDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): ReportRepository {
            this.dispatcher = dispatcher
            return this
        }

        suspend fun byId(id: String): ReportEntity? = withContext(dispatcher) { dao.findById(id) }

        suspend fun byPeriod(
            type: String,
            periodKey: String,
        ): ReportEntity? = withContext(dispatcher) { dao.findByPeriod(type, periodKey) }

        fun observeByPeriod(
            type: String,
            periodKey: String,
        ): Flow<ReportEntity?> = dao.observeByPeriod(type, periodKey).flowOn(dispatcher)

        fun observeLatestOfType(
            type: String,
            limit: Int = DEFAULT_LIMIT,
        ): Flow<List<ReportEntity>> = dao.observeLatestOfType(type, limit).flowOn(dispatcher)

        fun observePage(request: PageRequest): Flow<List<ReportEntity>> =
            dao.observePage(request.size, request.offset).flowOn(dispatcher)

        suspend fun save(report: ReportEntity) = withContext(dispatcher) { dao.upsert(report) }

        suspend fun saveAll(reports: List<ReportEntity>) = withContext(dispatcher) { dao.upsertAll(reports) }

        suspend fun delete(id: String): Boolean = withContext(dispatcher) { dao.deleteById(id) > 0 }

        suspend fun clear() = withContext(dispatcher) { dao.deleteAll() }

        companion object {
            /** 默认取最近 8 份报告。 */
            const val DEFAULT_LIMIT: Int = 8

            /** 报告类型。 */
            const val TYPE_WEEKLY: String = "weekly"
            const val TYPE_MONTHLY: String = "monthly"
        }
    }
