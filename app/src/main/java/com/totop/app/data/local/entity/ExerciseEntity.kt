package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `exercise` 表：AI 生成的变式练习。
 *
 * 只存通过独立重解校验的题目（verified=1）供巩固使用；未通过校验的记录保留但置 0，
 * 便于统计生成质量，筛选查询一律带 verified 条件。
 *
 * 说明：kpointId 仅建索引不建外键，理由同 mistake_kpoint（知识点树可整树重建）。
 */
@Entity(
    tableName = "exercise",
    indices = [
        Index(value = ["kpointId"]),
        Index(value = ["kpointId", "difficulty", "verified"]),
        Index(value = ["createdAt"]),
    ],
)
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val kpointId: String,
    val stem: String,
    val answer: String,
    /** [com.totop.app.domain.model.ExerciseDifficulty] 的 wire 值。 */
    val difficulty: String,
    /** 是否通过独立重解校验（FR-9.2）。 */
    val verified: Boolean,
    /** 创建时间，UTC 毫秒。 */
    val createdAt: Long,
)
