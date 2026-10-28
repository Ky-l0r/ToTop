package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.QuestionEntity
import kotlinx.coroutines.flow.Flow

/**
 * `question` 表访问对象（开发方案 5.1 / 5.2）。
 *
 * 分页约定：全库统一使用 LIMIT/OFFSET 分页（见 [PageRequest] 的说明），
 * 不引入 Paging3：错题本单页 20 条，数据量上限为单机个人错题，OFFSET 成本可忽略，
 * 且能避免为分页再引入一个体积与学习成本都不小的库（方案 7 章包体门禁 30MB）。
 *
 * 所有时间参数均为 UTC 毫秒。
 */
@Dao
interface QuestionDao {
    @Query("SELECT * FROM question WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): QuestionEntity?

    @Query("SELECT * FROM question WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<QuestionEntity?>

    /** 按图片 sha256 查重（唯一索引，5.2「缓存与去重锚点」）。 */
    @Query("SELECT * FROM question WHERE imageSha256 = :sha256 LIMIT 1")
    suspend fun findByImageSha256(sha256: String): QuestionEntity?

    @Query("SELECT * FROM question WHERE imageSha256 = :sha256 LIMIT 1")
    fun observeByImageSha256(sha256: String): Flow<QuestionEntity?>

    @Query("SELECT COUNT(*) FROM question")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM question")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM question ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun page(
        limit: Int,
        offset: Int,
    ): List<QuestionEntity>

    @Query("SELECT * FROM question ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observePage(
        limit: Int,
        offset: Int,
    ): Flow<List<QuestionEntity>>

    @Query(
        "SELECT * FROM question WHERE subject = :subject " +
            "ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset",
    )
    fun observePageBySubject(
        subject: String,
        limit: Int,
        offset: Int,
    ): Flow<List<QuestionEntity>>

    /**
     * 去重插入：命中 imageSha256 唯一约束时忽略该行并返回 -1。
     * 调用方据此判断「是已有题目」而不是新增。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: QuestionEntity): Long

    @Upsert
    suspend fun upsert(entity: QuestionEntity)

    @Upsert
    suspend fun upsertAll(entities: List<QuestionEntity>)

    @Query("DELETE FROM question WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM question")
    suspend fun deleteAll()

    /** 清空无图题目的 sha256 占位（理论上不应出现，防御性提供）。 */
    @Query("UPDATE question SET imageSha256 = NULL WHERE id = :id")
    suspend fun clearImageSha256(id: String)

    /**
     * 写入「已掌握」标记。
     *
     * 只动这一个字段：掌握度由 mastery 表与纯算法推导，自评不能直接改分数
     * （口径见 docs/需求定稿-0.5.0.md）。
     */
    @Query("UPDATE question SET selfMasteredAt = :atUtc WHERE id = :id")
    suspend fun markSelfMastered(
        id: String,
        atUtc: Long,
    ): Int
}
