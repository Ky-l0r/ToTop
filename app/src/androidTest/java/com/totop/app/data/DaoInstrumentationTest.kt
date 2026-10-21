package com.totop.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.entity.CacheEntryEntity
import com.totop.app.data.local.entity.DailyStatsEntity
import com.totop.app.data.local.entity.MistakeKpointEntity
import com.totop.app.data.local.entity.MistakeRecordEntity
import com.totop.app.data.local.entity.QuestionEntity
import com.totop.app.data.local.entity.ReviewLogEntity
import com.totop.app.data.local.seed.AssetsSeedAssetReader
import com.totop.app.data.local.seed.SeedRepository
import com.totop.app.data.local.seed.SeedResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DAO 与种子加载的 instrumentation 测试（内存数据库）。
 *
 * 为什么不用 Robolectric：工程固定 JUnit5（de.mannodermaus.android-junit5）作为单测框架，
 * 而 Robolectric 只提供 JUnit4 runner；混用需要改 Gradle（本任务禁止）。
 * 因此真实 SQLite 行为（唯一索引、复合主键、外键级联、UPSERT、Flow 触发）统一放在 androidTest，
 * 用 Room 官方推荐的内存数据库写法验证，等价且不引入新依赖。
 */
@RunWith(AndroidJUnit4::class)
class DaoInstrumentationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun question(
        id: String,
        sha: String? = null,
    ): QuestionEntity =
        QuestionEntity(
            id = id,
            subject = "math",
            grade = "高一",
            stemText = "题干 " + id,
            knownJson = "[]",
            ask = "求 x",
            optionsJson = "[]",
            hasFigure = false,
            imagePath = null,
            imageSha256 = sha,
            sourceType = "manual",
            createdAt = 1_700_000_000_000L,
        )

    private fun mistake(
        id: String,
        questionId: String,
    ): MistakeRecordEntity =
        MistakeRecordEntity(
            id = id,
            questionId = questionId,
            studentStepsJson = "[]",
            firstErrorStepIdx = null,
            errorType = "CALCULATION_ERROR",
            errorExplain = "",
            correctPathJson = "{}",
            finalAnswer = "",
            mode = "GUIDED",
            createdAt = 1L,
            recurrenceIndex = 1,
        )

    @Test
    fun upsertAndUniqueImageSha256() =
        runBlocking {
            val dao = database.questionDao()
            dao.upsert(question("q1", "sha-1"))
            assertEquals(1, dao.count())
            val rowId = dao.insertOrIgnore(question("q2", "sha-1"))
            assertEquals(-1L, rowId)
            assertEquals(1, dao.count())
            assertEquals("q1", dao.findByImageSha256("sha-1")?.id)
            assertNull(dao.findByImageSha256("sha-x"))
        }

    @Test
    fun limitOffsetPagination() =
        runBlocking {
            val dao = database.questionDao()
            (1..5).forEach { index ->
                dao.upsert(question("q" + index).copy(createdAt = 1_000L + index))
            }
            val firstPage = dao.page(2, 0).map { it.id }
            val secondPage = dao.page(2, 2).map { it.id }
            assertEquals(2, firstPage.size)
            assertEquals(2, secondPage.size)
            assertEquals(4, (firstPage + secondPage).distinct().size)
            assertEquals("q5", firstPage.first())
        }

    @Test
    fun dailyStatsCompositePrimaryKeyUpsert() =
        runBlocking {
            val dao = database.dailyStatsDao()
            dao.upsert(DailyStatsEntity(19_000L, "math", 1, 1, 0, 0, 60L))
            dao.upsert(DailyStatsEntity(19_000L, "math", 5, 2, 1, 3, 300L))
            dao.upsert(DailyStatsEntity(19_000L, "physics", 2, 1, 0, 0, 60L))
            val math = dao.find(19_000L, "math")
            assertNotNull(math)
            assertEquals(5, math!!.mistakeCount)
            assertEquals(2, dao.between(19_000L, 19_000L).size)
        }

    @Test
    fun cacheEntryCompositeKeyAndLru() =
        runBlocking {
            val dao = database.cacheEntryDao()
            dao.upsert(CacheEntryEntity("s1", "1.0.0", "m", "{}", 100L, 1_000L, 1_000L))
            dao.upsert(CacheEntryEntity("s1", "1.0.0", "other", "{}", 200L, 2_000L, 2_000L))
            dao.upsert(CacheEntryEntity("s2", "1.0.0", "m", "{}", 300L, 3_000L, 3_000L))
            assertEquals(3, dao.count())
            assertEquals(600L, dao.totalBytes())
            assertNotNull(dao.find("s1", "1.0.0", "m"))
            assertNotNull(dao.find("s1", "1.0.0", "other"))
            assertEquals("s1", dao.leastRecentlyUsed(1).single().sha256)
            assertEquals(1_000L, dao.leastRecentlyUsed(1).single().lastAccessAt)
            assertEquals(0, dao.deleteOtherPromptVersions("1.0.0"))
            dao.upsert(CacheEntryEntity("s3", "0.9.0", "m", "{}", 10L, 4_000L, 4_000L))
            assertEquals(1, dao.deleteOtherPromptVersions("1.0.0"))
        }

    @Test
    fun foreignKeyCascadeDeletesDependents() =
        runBlocking {
            val questionDao = database.questionDao()
            val mistakeDao = database.mistakeDao()
            val reviewDao = database.reviewDao()
            questionDao.upsert(question("q1"))
            mistakeDao.upsert(mistake("m1", "q1"))
            mistakeDao.insertKpointLinks(listOf(MistakeKpointEntity("m1", "KP_MATH_0001", 0.9)))
            reviewDao.upsert(ReviewLogEntity("r1", "m1", 2L, "got_it"))
            assertEquals(1, mistakeDao.count())
            questionDao.deleteById("q1")
            assertEquals(0, mistakeDao.count())
            assertEquals(0, reviewDao.byMistakeId("m1").size)
            assertEquals(0, mistakeDao.kpointsOfMistake("m1").size)
        }

    @Test
    fun mistakeKpointDoubleIndexQueries() =
        runBlocking {
            val mistakeDao = database.mistakeDao()
            mistakeDao.upsert(mistake("m1", "q1"))
            mistakeDao.upsert(mistake("m2", "q1").copy(createdAt = 2L))
            mistakeDao.insertKpointLinks(
                listOf(
                    MistakeKpointEntity("m1", "KP_MATH_0001", 0.8),
                    MistakeKpointEntity("m1", "KP_MATH_0002", 0.6),
                    MistakeKpointEntity("m2", "KP_MATH_0001", 0.7),
                ),
            )
            assertEquals(2, mistakeDao.mistakesOfKpoint("KP_MATH_0001").size)
            assertEquals(2, mistakeDao.kpointsOfMistake("m1").size)
            assertEquals(2, mistakeDao.countMistakesOfKpoint("KP_MATH_0001"))
            assertEquals(
                listOf("KP_MATH_0001", "KP_MATH_0002"),
                mistakeDao.kpointsOfMistake("m1").map { it.kpointId }.sorted(),
            )
        }

    @Test
    fun flowEmitsOnChange() =
        runBlocking {
            val dao = database.questionDao()
            dao.upsert(question("q1"))
            assertEquals(1, dao.observeCount().first())
            dao.upsert(question("q2"))
            assertEquals(2, dao.observeCount().first())
        }

    @Test
    fun seedRepositoryWritesPointsAndIsIdempotent() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val repository =
                SeedRepository(
                    database = database,
                    knowledgeDao = database.knowledgeDao(),
                    settingsDao = database.settingsDao(),
                    assetReader = AssetsSeedAssetReader(context.assets),
                )

            val first = repository.loadAll()
            assertTrue("首启应写入成功，实际：" + first, first.all { it is SeedResult.Applied })
            val pointCount = database.knowledgeDao().countKnowledgePoints()
            val edgeCount = database.knowledgeDao().countEdges()
            assertTrue(pointCount > 100)
            assertTrue(edgeCount > 100)

            val second = repository.loadAll()
            assertTrue("重复加载应跳过，实际：" + second, second.all { it is SeedResult.Skipped })
            assertEquals(pointCount, database.knowledgeDao().countKnowledgePoints())
            assertEquals(edgeCount, database.knowledgeDao().countEdges())
        }

    @Test
    fun seedRepositoryFailsWhenAssetMissing() =
        runBlocking {
            val repository =
                SeedRepository(
                    database = database,
                    knowledgeDao = database.knowledgeDao(),
                    settingsDao = database.settingsDao(),
                    assetReader = { null },
                )
            val results = repository.loadAll()
            assertTrue(results.all { it is SeedResult.Failed })
            assertEquals(0, database.knowledgeDao().countKnowledgePoints())
        }
}
