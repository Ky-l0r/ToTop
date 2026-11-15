package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识点上的「通用解法卡」（见 [com.totop.app.domain.model.KpointMethod]）。
 *
 * 唯一索引建在 `(kpointId, nameKey)` 上：合并规则是「同一知识点 + 同一方法名只留一张卡」，
 * 由数据库兜底可以避免并发写入时出现两张同名卡（列表里会变成两个「配方法 2 道题」）。
 *
 * 存的是归纳结论而不是原文：原文在 `learned_material` 里，这张卡只保留骨架。
 * 这样阶段 B 的提示预算与资料条数解耦——学生存一百条解析，进提示词的仍是两张卡。
 */
@Entity(
    tableName = "kpoint_method",
    indices = [
        Index(value = ["kpointId", "nameKey"], unique = true),
        Index(value = ["kpointId"]),
        Index(value = ["updatedAtUtc"]),
    ],
)
data class KpointMethodEntity(
    @PrimaryKey val id: String,
    /** 受控知识点 ID（与 KnowledgeIdConvention 一致）。 */
    val kpointId: String,
    val kpointName: String,
    /** 受控科目代码。 */
    val subject: String,
    /** 方法名原文，展示用。 */
    val name: String,
    /** 归一化后的方法名，唯一键与比较都用它。 */
    val nameKey: String,
    /** 通用步骤，JSON 字符串数组。 */
    val keyStepsJson: String,
    val applicable: String,
    /** 易错点，JSON 字符串数组。 */
    val pitfallsJson: String,
    val sampleCount: Int,
    val lastTitle: String,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
)
