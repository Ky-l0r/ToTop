package com.totop.app.data.repo

import com.totop.app.data.local.dao.KpointMethodDao
import com.totop.app.data.local.entity.KpointMethodEntity
import com.totop.app.domain.model.KpointMethod
import com.totop.app.domain.model.KpointMethodMerge
import com.totop.app.domain.model.KpointMethodRewrite
import com.totop.app.domain.model.MethodEvidence
import com.totop.app.domain.model.MethodSkeleton
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
 * 方法卡仓储：把每次解答的「方法 + 步骤」按知识点叠起来，并在解题前按知识点召回。
 *
 * 写：一次解答最多产出两张卡（阶段 D 通常给 1-2 个知识点），合并规则见 [KpointMethodMerge]。
 * 读：按本题的候选知识点取最多的两张，按被验证次数排序。
 *
 * 刻意不做的事：
 * - **不每做一题都调模型**。多一次调用就多一次延迟与费用，而「把同类题的步骤叠起来」
 *   这件事在本地用字符串与计数就能做到。唯一一次额外的模型调用发生在
 *   攒够样本之后的**整卡重写**（[KpointMethodRewrite]），一张卡一辈子只此一次。
 * - **不把原文存进这张表**。原文留在 `learned_material`，方法卡只留骨架，
 *   这样进提示词的预算与资料条数解耦。
 */
@Singleton
class KpointMethodRepository
    @Inject
    constructor(
        private val dao: KpointMethodDao,
    ) {
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO

        fun observeAll(): Flow<List<KpointMethod>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        fun observeCount(): Flow<Int> = dao.observeCount()

        /**
         * 吸收一次解答的证据。
         *
         * 失败不抛：方法卡是「锦上添花」的数据，写不进去不该让本次解题失败。
         *
         * @return 合并后的那张卡（调用方据此判断要不要整卡重写）；未写入时返回 null。
         *   同知识点同类方法会累加计数而不是新增。
         */
        suspend fun absorb(evidence: MethodEvidence): KpointMethod? =
            withContext(dispatcher) {
                if (evidence.kpointId.isBlank() || evidence.name.isBlank()) return@withContext null
                runCatching {
                    val key = KpointMethodMerge.nameKey(evidence.name)
                    if (key.isBlank()) return@runCatching null
                    val existing = dao.find(evidence.kpointId, key)?.toDomain()
                    val merged =
                        KpointMethodMerge.merge(
                            existing = existing,
                            evidence = evidence,
                            newId = existing?.id ?: UUID.randomUUID().toString(),
                            nowUtc = System.currentTimeMillis(),
                        )
                    dao.upsert(merged.toEntity(nameKey = key))
                    merged
                }.getOrNull()
            }

        /**
         * 整卡重写：把模型给的骨架贴回卡片。
         *
         * 必须重新读一次库里的最新行，不能用手上那份快照：
         * 重写是在后台跑的，这期间同一张卡可能又被另一道题合并过一次，
         * 直接 upsert 旧快照会把那次合并（计数与步骤）覆盖掉。
         *
         * @return 是否真的写了库。模型给的步骤为空、或与当前一致时返回 false
         */
        suspend fun applyRewrite(
            id: String,
            skeleton: MethodSkeleton,
        ): Boolean =
            withContext(dispatcher) {
                runCatching {
                    val row = dao.findById(id) ?: return@runCatching false
                    val current = row.toDomain()
                    val rewritten = KpointMethodRewrite.apply(current, skeleton, System.currentTimeMillis())
                    if (rewritten == current) return@runCatching false
                    dao.upsert(rewritten.toEntity(nameKey = row.nameKey))
                    true
                }.getOrDefault(false)
            }

        /**
         * 按候选知识点召回，供阶段 B 参考。
         *
         * @param kpointIds 本地粗筛出来的候选知识点；为空时直接返回空表——没有知识点就没有归属，
         *   退回「最近几条」等于又回到按时间碰运气的老口径
         */
        suspend fun forCandidates(
            kpointIds: List<String>,
            limit: Int = DEFAULT_LIMIT,
        ): List<KpointMethod> =
            withContext(dispatcher) {
                val ids = kpointIds.filter { it.isNotBlank() }.distinct()
                if (ids.isEmpty()) return@withContext emptyList()
                dao.byKpoints(ids, limit).map { it.toDomain() }
            }

        /** 删除一张卡：合并错了（例如把两种方法认成同一种）要能回退。 */
        suspend fun delete(id: String): Boolean = withContext(dispatcher) { dao.deleteById(id) > 0 }

        private fun KpointMethodEntity.toDomain(): KpointMethod =
            KpointMethod(
                id = id,
                kpointId = kpointId,
                kpointName = kpointName,
                subject = subject,
                name = name,
                keySteps = decodeList(keyStepsJson),
                applicable = applicable,
                pitfalls = decodeList(pitfallsJson),
                sampleCount = sampleCount,
                lastTitle = lastTitle,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = updatedAtUtc,
            )

        private fun KpointMethod.toEntity(nameKey: String): KpointMethodEntity =
            KpointMethodEntity(
                id = id,
                kpointId = kpointId,
                kpointName = kpointName,
                subject = subject,
                name = name,
                nameKey = nameKey,
                keyStepsJson = encodeList(keySteps),
                applicable = applicable,
                pitfallsJson = encodeList(pitfalls),
                sampleCount = sampleCount,
                lastTitle = lastTitle,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = updatedAtUtc,
            )

        private fun encodeList(values: List<String>): String =
            JSON.encodeToString(ListSerializer(String.serializer()), values)

        private fun decodeList(raw: String): List<String> =
            runCatching { JSON.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())

        private companion object {
            /** 进阶段 B 的方法卡最多几张：两张 400 字已经占了参考答案预算的三分之二。 */
            const val DEFAULT_LIMIT: Int = 2
        }
    }

/** 只存字符串数组，字段名固定，不需要额外配置。 */
private val JSON: Json = Json { ignoreUnknownKeys = true }
