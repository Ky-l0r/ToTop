package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 开发方案 5.1 `kpoint_edge` 表：知识点有向边。
 *
 * 主键为 (fromId, toId, type)：同一对节点允许同时存在 prerequisite 与 related 等不同类型的边。
 * 入库前必须通过 CycleDetector 环检测（FR-7.3）。
 */
@Entity(
    tableName = "kpoint_edge",
    primaryKeys = ["fromId", "toId", "type"],
    indices = [
        Index(value = ["toId"]),
        Index(value = ["fromId"]),
        Index(value = ["type"]),
    ],
)
data class KpointEdgeEntity(
    val fromId: String,
    val toId: String,
    /** [com.totop.app.domain.model.EdgeType] 的 wire 值。 */
    val type: String,
    /** 人工整理权重，默认 1.0。 */
    val weight: Double,
)
