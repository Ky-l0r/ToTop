package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 开发方案 5.1 `mistake_kpoint` 表：错题与知识点多对多。
 *
 * 索引要求（文档 5.2）：(kpointId) 与 (mistakeId) 双索引，掌握度聚合频繁。
 * 说明：只对 mistakeId 建外键。知识点树可整树重建，若对 kpointId 加级联删除，
 * 重建种子会误删用户的错题归属关系，因此 kpointId 仅建索引不建外键。
 */
@Entity(
    tableName = "mistake_kpoint",
    primaryKeys = ["mistakeId", "kpointId"],
    foreignKeys = [
        ForeignKey(
            entity = MistakeRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["mistakeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["kpointId"]),
        Index(value = ["mistakeId"]),
    ],
)
data class MistakeKpointEntity(
    val mistakeId: String,
    /** 受控知识点 ID。 */
    val kpointId: String,
    /** AI 给出的归属置信度 0..1。 */
    val confidence: Double,
)
