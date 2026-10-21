package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 开发方案 5.1 `hint_log` 表：提示记录（level 1..3）。 */
@Entity(
    tableName = "hint_log",
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
        Index(value = ["createdAt"]),
    ],
)
data class HintLogEntity(
    @PrimaryKey val id: String,
    val mistakeId: String,
    /** 提示级别 1..3。 */
    val level: Int,
    /** 提示时间，UTC 毫秒。 */
    val createdAt: Long,
)
