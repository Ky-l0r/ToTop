package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 开发方案 5.1 `cache_entry` 表：AI 结果缓存。
 *
 * 主键 (sha256, promptVer, model)：任一变化即为不同的缓存条目（方案 6.3 缓存键规则）。
 * lastAccessAt 支撑 LRU 淘汰，sizeBytes 支撑 100MB 磁盘上限计数（FR-8.4）。
 */
@Entity(
    tableName = "cache_entry",
    primaryKeys = ["sha256", "promptVer", "model"],
    indices = [
        Index(value = ["lastAccessAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class CacheEntryEntity(
    /** 图片或题干的 sha256。 */
    val sha256: String,
    /** 提示词版本。 */
    val promptVer: String,
    /** 模型名。 */
    val model: String,
    /** AI 结果 JSON。 */
    val resultJson: String,
    /** 条目字节数，用于磁盘配额统计。 */
    val sizeBytes: Long,
    /** 最近访问时间，UTC 毫秒（LRU 依据）。 */
    val lastAccessAt: Long,
    /** 写入时间，UTC 毫秒。 */
    val createdAt: Long,
)
