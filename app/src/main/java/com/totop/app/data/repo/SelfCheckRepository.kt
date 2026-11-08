package com.totop.app.data.repo

import com.totop.app.data.local.dao.MistakeDao
import com.totop.app.data.local.dao.ReviewDao
import com.totop.app.data.local.entity.ReviewLogEntity
import com.totop.app.domain.model.MistakeStatus
import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.ReviewSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 解答卡「核对」通道（0.7.0）。
 *
 * 单独一个仓储而不是塞进 [MistakeRepository]：那边已经有 40 个方法，
 * 再加一个就破 detekt 阈值（规范要求拆分而不是调高阈值），
 * 而「核对」本身也是与错题读写正交的一件事——它只写复习记录 + 改一次状态。
 */
@Singleton
class SelfCheckRepository
    @Inject
    constructor(
        private val reviewDao: ReviewDao,
        private val mistakeDao: MistakeDao,
    ) {
        /** 放在类内而不是构造参数：构造参数会让 Hilt 去要一个 CoroutineDispatcher 绑定。 */
        private val dispatcher: CoroutineDispatcher = Dispatchers.IO

        /**
         * 记一条「核对一致」：写复习记录（source = 核对）并把该题推到「理解」。
         *
         * 为什么不动掌握度公式：核对一致本来就该算一次「做对了」的自评（权重 0.55 那一项），
         * 走已有的 review_log 通道即可，公式一个字都不用改。
         *
         * 同一天对同一道题只记一次：学生反复点「我核对过，一致」不该把分数刷上去。
         * 返回 false 表示今天已经记过，调用方据此给一句说明，而不是当作失败。
         */
        suspend fun recordAgreement(
            mistakeId: String,
            nowUtc: Long = System.currentTimeMillis(),
        ): Boolean =
            withContext(dispatcher) {
                val today = UtcDay.dayRange(UtcDay.of(nowUtc))
                val already =
                    reviewDao.countOfSourceBetween(
                        mistakeId = mistakeId,
                        source = ReviewSource.CHECK.wire,
                        fromUtc = today.first,
                        toUtc = today.last + 1,
                    ) > 0
                if (already) return@withContext false
                reviewDao.upsert(
                    ReviewLogEntity(
                        id = UUID.randomUUID().toString(),
                        mistakeId = mistakeId,
                        reviewedAt = nowUtc,
                        selfResult = RecallSelfResult.GOT_IT.wire,
                        source = ReviewSource.CHECK.wire,
                    ),
                )
                // 「核对一致」= 看懂并能自己重做，按定稿推进到「理解」。
                mistakeDao.updateStatus(mistakeId, MistakeStatus.UNDERSTOOD.wire)
                true
            }
    }
