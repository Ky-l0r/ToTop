package com.totop.app.data.repo

import androidx.room.withTransaction
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.QuestionDao
import com.totop.app.data.local.mapper.EntityMappers.toDomain
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.domain.model.Question
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 题目仓库：屏蔽 Room 细节，只对上层暴露领域模型与 [Flow]。
 *
 * 「屏蔽本地/远端」：To Top 无自建后端（方案 C2），题目唯一来源是本机数据库与用户相机/相册，
 * 因此本仓库是**单一本地数据源**（Single Source of Truth），不存在远端分支；
 * 未来若接入云同步，只在此类内部增加「远端 -> 本地」的写入路径，对上层接口保持不变。
 *
 * 线程约定：suspend 方法切到 IO；Flow 在读取侧 flowOn(IO)。
 */
@Singleton
class QuestionRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val dao: QuestionDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        /** 测试专用：替换调度器。 */
        internal fun withDispatcher(dispatcher: CoroutineDispatcher): QuestionRepository {
            this.dispatcher = dispatcher
            return this
        }

        suspend fun byId(id: String): Question? = withContext(dispatcher) { dao.findById(id)?.toDomain() }

        fun observeById(id: String): Flow<Question?> = dao.observeById(id).map { it?.toDomain() }.flowOn(dispatcher)

        suspend fun byImageSha256(sha256: String): Question? =
            withContext(dispatcher) { dao.findByImageSha256(sha256)?.toDomain() }

        fun observeByImageSha256(sha256: String): Flow<Question?> =
            dao.observeByImageSha256(sha256).map { it?.toDomain() }.flowOn(dispatcher)

        suspend fun page(request: PageRequest): List<Question> =
            withContext(dispatcher) {
                dao.page(request.size, request.offset).map { it.toDomain() }
            }

        fun observePage(request: PageRequest): Flow<List<Question>> =
            dao.observePage(request.size, request.offset)
                .map { list -> list.map { it.toDomain() } }
                .flowOn(dispatcher)

        fun observePageBySubject(
            subject: String,
            request: PageRequest,
        ): Flow<List<Question>> =
            dao.observePageBySubject(subject, request.size, request.offset)
                .map { list -> list.map { it.toDomain() } }
                .flowOn(dispatcher)

        suspend fun count(): Int = withContext(dispatcher) { dao.count() }

        fun observeCount(): Flow<Int> = dao.observeCount().flowOn(dispatcher)

        /**
         * 保存题目。
         *
         * 当 [Question.imageSha256] 命中已有记录时按「同一张图」处理：整体覆盖原记录，
         * 保证 imageSha256 唯一索引不会被并发写入打破。
         */
        suspend fun save(question: Question) =
            withContext(dispatcher) {
                database.withTransaction { dao.upsert(question.toEntity()) }
            }

        /**
         * 去重保存：图片 sha256 已存在时返回已有题目而不是新增。
         *
         * @return 落库后的题目；[createdNew] 表示本次是否新建。
         */
        suspend fun saveDeduplicated(question: Question): SaveOutcome =
            withContext(dispatcher) {
                val sha = question.imageSha256
                if (sha != null) {
                    dao.findByImageSha256(sha)?.let { existing ->
                        return@withContext SaveOutcome(question = existing.toDomain(), createdNew = false)
                    }
                }
                val rowId = dao.insertOrIgnore(question.toEntity())
                if (rowId == -1L) {
                    // 并发下被其它写入抢先插入：回读已有记录
                    val existing = sha?.let { dao.findByImageSha256(it) } ?: dao.findById(question.id)
                    return@withContext SaveOutcome(
                        question = existing?.toDomain() ?: question,
                        createdNew = false,
                    )
                }
                SaveOutcome(question = question, createdNew = true)
            }

        /**
         * 标记这道题「已掌握」。
         *
         * @return 是否命中一行；题目已被删掉时返回 false，调用方据此提示，不静默成功
         */
        suspend fun markMastered(
            questionId: String,
            atUtc: Long,
        ): Boolean = withContext(dispatcher) { dao.markSelfMastered(questionId, atUtc) > 0 }

        /** 这道题是否已被学生标记为掌握。 */
        suspend fun isMastered(questionId: String): Boolean =
            withContext(dispatcher) { dao.findById(questionId)?.selfMasteredAt != null }

        suspend fun delete(id: String): Boolean = withContext(dispatcher) { dao.deleteById(id) > 0 }

        suspend fun deleteAll() = withContext(dispatcher) { dao.deleteAll() }

        /** 保存结果。 */
        data class SaveOutcome(
            val question: Question,
            val createdNew: Boolean,
        )
    }
