package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `ai_trace` 表：AI 调用追踪。
 *
 * 只记录用量与结果，不记录提示词正文与用户图片，避免隐私外泄。
 */
@Entity(
    tableName = "ai_trace",
    indices = [
        Index(value = ["requestId"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["stage"]),
    ],
)
data class AiTraceEntity(
    @PrimaryKey val id: String,
    /** 客户端幂等请求 ID。 */
    val requestId: String,
    /** A / B / C / D。 */
    val stage: String,
    val model: String,
    /** 提示词版本，参与缓存键。 */
    val promptVer: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long,
    val ok: Boolean,
    val errorMsg: String?,
    /** 调用时间，UTC 毫秒。 */
    val createdAt: Long,
)
