package com.totop.app.data.repo

import com.totop.app.data.local.dao.ExerciseDao
import com.totop.app.data.local.mapper.EntityMappers
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.domain.model.Exercise
import com.totop.app.domain.model.ExerciseDifficulty
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 练习题库仓库（开发方案 5.1 `exercise` / FR-9）。
 *
 * 业务约束：**只有通过独立重解校验的题目（verified = true）才能进入巩固练习**（FR-9.2）。
 * 因此对外查询一律带 verified 条件；未通过校验的记录仍保留在库中，用于统计生成质量。
 */
@Singleton
class ExerciseRepository
    @Inject
    constructor(
        private val dao: ExerciseDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): ExerciseRepository {
            this.dispatcher = dispatcher
            return this
        }

        suspend fun byId(id: String): Exercise? =
            withContext(dispatcher) {
                dao.findById(id)?.let { with(EntityMappers) { it.toDomain() } }
            }

        /** 某知识点下已通过校验的题目。 */
        suspend fun verifiedOfKpoint(kpointId: String): List<Exercise> =
            withContext(dispatcher) {
                dao.verifiedOfKpoint(kpointId).map { with(EntityMappers) { it.toDomain() } }
            }

        fun observeVerifiedOfKpoint(kpointId: String): Flow<List<Exercise>> =
            dao.observeVerifiedOfKpoint(kpointId)
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        /** 巩固练习：按知识点与难度随机取已通过校验的题目。 */
        suspend fun randomVerified(
            kpointId: String,
            difficulty: ExerciseDifficulty,
            limit: Int,
        ): List<Exercise> =
            withContext(dispatcher) {
                dao.randomVerified(kpointId, difficulty.wire, limit).map { with(EntityMappers) { it.toDomain() } }
            }

        fun observeVerifiedPage(request: PageRequest): Flow<List<Exercise>> =
            dao.observeVerifiedPage(request.size, request.offset)
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        suspend fun save(exercise: Exercise) = withContext(dispatcher) { dao.upsert(exercise.toEntity()) }

        suspend fun saveAll(exercises: List<Exercise>) =
            withContext(dispatcher) { dao.upsertAll(exercises.map { it.toEntity() }) }

        suspend fun countVerified(): Int = withContext(dispatcher) { dao.countVerified() }

        suspend fun countRejected(): Int = withContext(dispatcher) { dao.countRejected() }

        fun observeTotal(): Flow<Int> = dao.observeTotal().flowOn(dispatcher)

        suspend fun deleteOfKpoint(kpointId: String): Int = withContext(dispatcher) { dao.deleteOfKpoint(kpointId) }

        suspend fun clear() = withContext(dispatcher) { dao.deleteAll() }
    }
