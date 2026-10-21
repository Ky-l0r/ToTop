package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.DailyStatsEntity
import kotlinx.coroutines.flow.Flow

/**
 * `daily_stats` 表访问对象（开发方案 5.1，主键 (date, subject)，DailyStatsWorker 使用 UPSERT）。
 *
 * date 为 UTC 日序号（自 1970-01-01 起的天数）。
 */
@Dao
interface DailyStatsDao {
    @Query("SELECT * FROM daily_stats WHERE date = :date AND subject = :subject LIMIT 1")
    suspend fun find(
        date: Long,
        subject: String,
    ): DailyStatsEntity?

    @Query("SELECT * FROM daily_stats WHERE date = :date AND subject = :subject LIMIT 1")
    fun observe(
        date: Long,
        subject: String,
    ): Flow<DailyStatsEntity?>

    @Query(
        "SELECT * FROM daily_stats WHERE date >= :fromDate AND date <= :toDate " +
            "ORDER BY date ASC, subject ASC",
    )
    suspend fun between(
        fromDate: Long,
        toDate: Long,
    ): List<DailyStatsEntity>

    @Query(
        "SELECT * FROM daily_stats WHERE date >= :fromDate AND date <= :toDate " +
            "ORDER BY date ASC, subject ASC",
    )
    fun observeBetween(
        fromDate: Long,
        toDate: Long,
    ): Flow<List<DailyStatsEntity>>

    /** 某学科最近 N 天，趋势图使用。 */
    @Query(
        "SELECT * FROM daily_stats WHERE subject = :subject " +
            "ORDER BY date DESC LIMIT :days",
    )
    suspend fun latestOfSubject(
        subject: String,
        days: Int,
    ): List<DailyStatsEntity>

    @Query(
        "SELECT * FROM daily_stats WHERE subject = :subject " +
            "ORDER BY date DESC LIMIT :days",
    )
    fun observeLatestOfSubject(
        subject: String,
        days: Int,
    ): Flow<List<DailyStatsEntity>>

    @Query("SELECT COALESCE(SUM(mistakeCount), 0) FROM daily_stats WHERE date >= :fromDate AND date <= :toDate")
    suspend fun sumMistakeCount(
        fromDate: Long,
        toDate: Long,
    ): Int

    @Query("SELECT COALESCE(SUM(reviewDoneCount), 0) FROM daily_stats WHERE date >= :fromDate AND date <= :toDate")
    suspend fun sumReviewDoneCount(
        fromDate: Long,
        toDate: Long,
    ): Int

    @Query("SELECT COALESCE(SUM(studySeconds), 0) FROM daily_stats WHERE date >= :fromDate AND date <= :toDate")
    suspend fun sumStudySeconds(
        fromDate: Long,
        toDate: Long,
    ): Long

    /** UPSERT 语义由 @Upsert 保证：同一天同一学科重复写入为覆盖更新，不是插入新行。 */
    @Upsert
    suspend fun upsert(entity: DailyStatsEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DailyStatsEntity>)

    @Query("DELETE FROM daily_stats WHERE date = :date AND subject = :subject")
    suspend fun delete(
        date: Long,
        subject: String,
    ): Int

    /** 保留策略：清理早于指定日期的汇总，避免无限增长。 */
    @Query("DELETE FROM daily_stats WHERE date < :keepFromDate")
    suspend fun deleteOlderThan(keepFromDate: Long): Int

    @Query("DELETE FROM daily_stats")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM daily_stats")
    fun observeTotal(): Flow<Int>
}
