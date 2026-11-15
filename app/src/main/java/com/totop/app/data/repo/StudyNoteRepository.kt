package com.totop.app.data.repo

import com.totop.app.data.local.dao.StudyNoteDao
import com.totop.app.data.local.entity.StudyNoteEntity
import com.totop.app.domain.model.StudyNote
import com.totop.app.domain.model.StudyNoteKind
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

        /** 全部笔记（重点在前），供「卡点笔记」板块使用。 */
        fun observeAll(): Flow<List<StudyNote>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

        /** 取某知识点下的笔记（一次性读取，供详情页首次渲染）。 */
        suspend fun listByKpoint(kpointId: String): List<StudyNote> = dao.byKpoint(kpointId).map { it.toDomain() }

        /**
         * 按科目取笔记，供复习资料的「重点 / 卡点」两节使用。
         *
         * 这里只做**上限保护**（防止极端情况下把整表读进内存），真正取哪几条由
         * [StudyNoteDigest.pick] 按分类与字数预算决定。上限从 8 提到 60：
         * 条数限制原本是为了控制篇幅，但它既不反映字数，也会让某一类把另一类挤掉。
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
         *
         * @param kind 重点 / 卡点。由学生在气泡上直接选，不给默认值，避免「都存成同一类」。
         */
        suspend fun saveCompanion(
            question: String,
            answer: String,
            kpointId: String?,
            kind: StudyNoteKind,
            nowUtc: Long = System.currentTimeMillis(),
        ): StudyNote {
            val body = StudyNote.clampBody(answer)
            val note =
                StudyNote(
                    id = UUID.randomUUID().toString(),
                    kpointId = kpointId?.takeIf { it.isNotBlank() },
                    questionId = null,
                    source = StudyNoteSource.COMPANION,
                    kind = kind,
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
                kind = StudyNoteKind.fromWire(kind),
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
                kind = kind.wire,
                title = title,
                body = body,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = updatedAtUtc,
            )

        private companion object {
            /**
             * 单次读取的硬上限。
             *
             * 它不是「显示多少条」，而是防止某个科目积累上百条笔记时把整表读进内存；
             * 实际取哪几条交给 `StudyNoteDigest.pick`（按分类与字数预算）。
             */
            const val REVIEW_NOTE_LIMIT: Int = 60
        }
    }
