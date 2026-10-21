package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `knowledge_point` 表：内置知识点树。
 *
 * 由 SeedRepository 从 assets/seed 写入，AI 只能引用受控 ID，不得新增节点。
 */
@Entity(
    tableName = "knowledge_point",
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["subject"]),
        Index(value = ["subject", "chapter", "orderIndex"]),
    ],
)
data class KnowledgePointEntity(
    /** 受控 ID：KP_<SUBJECT>_<四位序号>。 */
    @PrimaryKey val id: String,
    val name: String,
    val subject: String,
    /** 父节点受控 ID；根节点为 null。 */
    val parentId: String?,
    val chapter: String,
    val orderIndex: Int,
    val definition: String,
    val commonApproach: String,
    /** 高频易错点，JSON 字符串数组。 */
    val pitfallJson: String,
)
