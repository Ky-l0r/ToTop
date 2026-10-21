package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 开发方案 5.1 `review_log` 表：复习记录（学生自评结果）。 */
@Entity(
    tableName = "review_log",
    foreignKeys = [
        ForeignKey(
            entity = MistakeRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["mistakeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["mistakeId"]),
        Index(value = ["reviewedAt"]),
    ],
)
data class ReviewLogEntity(
    @PrimaryKey val id: String,
    val mistakeId: String,
    /** 复习时间，UTC 毫秒。 */
    val reviewedAt: Long,
    /** [com.totop.app.domain.model.RecallSelfResult] 的 wire 值。 */
    val selfResult: String,
)
