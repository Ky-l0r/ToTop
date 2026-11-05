package com.totop.app.data.repo

import com.totop.app.data.local.dao.StudyNoteDao
import com.totop.app.data.local.entity.StudyNoteEntity
import com.totop.app.domain.model.StudyNote
import com.totop.app.domain.model.StudyNoteSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 学习笔记仓储。
 *
 * 只做「存」与「按知识点/科目查」两件事，不对笔记内容做任何模型加工：
 * 笔记是学生自己问出来、核出来的原话，改写它就是替学生总结，复习时反而认不出来。
 */
@Singleton
class StudyNoteRepository
    @Inject
    constructor(
        private val dao: StudyNoteDao,
    ) {
        fun observeByKpoint(kpointId: String): Flow<List<StudyNote>> =
            dao.observeByKpoint(kpointId).map { rows -> rows.map { it.toDomain() } }

        /** 取某知识点下的笔记（一次性读取，供详情页首次渲染）。 */
        suspend fun listByKpoint(kpointId: String): List<StudyNote> = dao.byKpoint(kpointId).map { it.toDomain() }

        /**
         * 按科目取笔记，供复习资料的「我的笔记」一节。
         *
         * 上限 8 条：再多这一节就会长过正文的任何一章，而复习资料的定位是提纲，
         * 不是笔记合订本。取最近的 8 条也符合「考前翻自己最近问过的」这个使用场景。
         */
        suspend fun forReviewMaterial(
            subject: String,
            limit: Int = REVIEW_NOTE_LIMIT,
        ): List<StudyNote> = dao.bySubject(subject, limit).map { it.toDomain() }

        /** 该科目已挂节点的笔记总数（用于解释「这一节为什么是空的」）。 */
        suspend fun countForSubject(subject: String): Int = dao.countBySubject(subject)

        /**
         * 存一条陪伴问答的笔记。
         *
         * 只有命中受控知识点时才带 [kpointId]；命中不到也不拒绝保存——
         * 学生点「存为笔记」是要留住这段话，不是因为这句话挂上了某个节点。
         */
        suspend fun saveCompanion(
            question: String,
            answer: String,
            kpointId: String?,
            nowUtc: Long = System.currentTimeMillis(),
        ): StudyNote {
            val body = StudyNote.clampBody(answer)
            val note =
                StudyNote(
                    id = UUID.randomUUID().toString(),
                    kpointId = kpointId?.takeIf { it.isNotBlank() },
                    questionId = null,
                    source = StudyNoteSource.COMPANION,
                    title = StudyNote.titleOf(answer.ifBlank { question }, StudyNoteSource.COMPANION),
                    body = body,
                    createdAtUtc = nowUtc,
                    updatedAtUtc = nowUtc,
                )
            dao.upsert(note.toEntity())
            return note
        }

        suspend fun delete(id: String): Int = dao.deleteById(id)

        private fun StudyNoteEntity.toDomain(): StudyNote =
            StudyNote(
                id = id,
                kpointId = kpointId,
                questionId = questionId,
                source = StudyNoteSource.fromWire(source),
                title = title,
                body = body,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = updatedAtUtc,
            )

        private fun StudyNote.toEntity(): StudyNoteEntity =
            StudyNoteEntity(
                id = id,
                kpointId = kpointId,
                questionId = questionId,
                source = source.wire,
                title = title,
                body = body,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = updatedAtUtc,
            )

        private companion object {
            /** 复习资料里「我的笔记」一节最多带几条。 */
            const val REVIEW_NOTE_LIMIT: Int = 8
        }
    }
