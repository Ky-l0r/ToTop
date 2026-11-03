package com.totop.app.data.repo

import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.LearnedMaterialDao
import com.totop.app.data.local.entity.LearnedMaterialEntity
import com.totop.app.domain.model.LearnedMaterial
import com.totop.app.domain.model.Subjects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 资料库仓储：学生收集的解析。
 *
 * 检索口径（本轮定的默认值，见 docs/偏离记录.md 第 33 节）：
 * - **按科目召回**：题目识别出科目后才查，避免物理题拿到语文的解析；
 * - **条数与字数双限**：默认 3 条、单条 400 字，合计不超过 1200 字，
 *   再多就会把题干、候选知识点挤出阶段 B 的预算；
 * - **时间倒序**：学生刚收集的通常正是他现在在学的那一类。
 */
@Singleton
class LearnedMaterialRepository
    @Inject
    constructor(
        private val dao: LearnedMaterialDao,
    ) {
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO

        fun observeAll(): Flow<List<LearnedMaterial>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        fun observeCount(): Flow<Int> = dao.observeCount()

        suspend fun recent(limit: Int = DEFAULT_LIMIT): List<LearnedMaterial> =
            withContext(dispatcher) { dao.recent(limit).map { it.toDomain() } }

        /**
         * 按科目召回参考资料。
         *
         * 科目未知（`general`）时退回「最近几条」：宁可用不上，也不要一条都不给——
         * 学生刚存进来的资料，很可能就是他正要问的那类题。
         */
        suspend fun forSolve(
            subject: String,
            limit: Int = DEFAULT_LIMIT,
        ): List<LearnedMaterial> =
            withContext(dispatcher) {
                val normalized = Subjects.normalize(subject)
                val rows =
                    if (normalized.isBlank() || normalized == Subjects.GENERAL) {
                        dao.recent(limit)
                    } else {
                        val bySubject = dao.bySubject(normalized, limit)
                        if (bySubject.isNotEmpty()) bySubject else dao.recent(limit)
                    }
                rows.map { it.toDomain() }
            }

        /** 保存一条资料（从一次解答里抽出来的）。 */
        suspend fun save(
            material: LearnedMaterial,
            id: String = UUID.randomUUID().toString(),
        ): String =
            withContext(dispatcher) {
                dao.upsert(material.copy(id = id).toEntity())
                id
            }

        /** 删除一条：学生自己加的，误存的要能删掉。 */
        suspend fun delete(id: String): Boolean = withContext(dispatcher) { dao.deleteById(id) > 0 }

        private fun LearnedMaterialEntity.toDomain(): LearnedMaterial =
            LearnedMaterial(
                id = id,
                subject = subject,
                kpointId = kpointId,
                kpointName = kpointName,
                title = title,
                method = method,
                keySteps = decodeList(keyStepsJson),
                answer = answer,
                pitfalls = decodeList(pitfallsJson),
                sourceLabel = sourceLabel,
                imagePath = imagePath,
                createdAtUtc = createdAtUtc,
            )

        private fun LearnedMaterial.toEntity(): LearnedMaterialEntity =
            LearnedMaterialEntity(
                id = id,
                subject = subject,
                kpointId = kpointId,
                kpointName = kpointName,
                title = title,
                method = method,
                keyStepsJson = encodeList(keySteps),
                answer = answer,
                pitfallsJson = encodeList(pitfalls),
                sourceLabel = sourceLabel,
                imagePath = imagePath,
                createdAtUtc = createdAtUtc,
            )

        private fun encodeList(values: List<String>): String =
            JSON.encodeToString(ListSerializer(String.serializer()), values.take(MAX_LIST_ITEMS))

        private fun decodeList(raw: String): List<String> =
            runCatching { JSON.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())

        /** 备份/清库按表名操作，这里暴露一下，避免调用方写字符串。 */
        val tableName: String get() = AppDatabase.Tables.LEARNED_MATERIAL

        companion object {
            /** 单次进提示词的资料条数。 */
            const val DEFAULT_LIMIT: Int = 3

            /** 单条资料保存的步骤/易错点上限，防止一条资料里塞进整篇文章。 */
            private const val MAX_LIST_ITEMS: Int = 12
        }
    }

/** 资料库用的宽松 Json：只存字符串数组，字段名固定，不需要额外配置。 */
private val JSON: Json = Json { ignoreUnknownKeys = true }
