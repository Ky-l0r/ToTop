package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `study_note` 表：学生学习笔记（0.7.0 新增）。
 *
 * 为什么单开一张表而不是塞进 `forgotten_kpoint`：后者是「问过哪些知识点」的计数，
 * 语义是频次；笔记是正文，两者一起查会把「问过 3 次但没记下来」和
 * 「问过一次、记了 500 字」混成一条，复习资料就没法按内容取舍。
 *
 * [kpointId] 允许为空且不加外键：陪伴页的回答可能一个知识点都没命中，
 * 这种「没有归属」的笔记仍要留下（学生自己觉得有用），但绝不能因此新建节点。
 */
@Entity(
    tableName = "study_note",
    indices = [
        Index(value = ["kpointId"]),
        Index(value = ["createdAtUtc"]),
    ],
)
data class StudyNoteEntity(
    @PrimaryKey val id: String,
    /** 受控知识点 ID；未命中任何节点时为 null。 */
    val kpointId: String?,
    /** 关联的题目 ID；陪伴问答场景为 null。 */
    val questionId: String?,
    /** [com.totop.app.domain.model.StudyNoteSource] 的 wire 值。 */
    val source: String,
    val title: String,
    val body: String,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
)
