package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `outbox` 表：离线写队列（FR-8.5）。
 *
 * 按 id 升序补传即为入队顺序；nextRetryAt 用于指数退避（1s/2s/4s，最多 3 次）。
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["nextRetryAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    /** 待补传的写操作，JSON 对象。 */
    val payloadJson: String,
    /** 已尝试次数。 */
    val attempts: Int,
    /** 下次可重试时间，UTC 毫秒。 */
    val nextRetryAt: Long,
    /** 入队时间，UTC 毫秒。 */
    val createdAt: Long,
)
