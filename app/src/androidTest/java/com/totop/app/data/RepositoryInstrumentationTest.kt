package com.totop.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.seed.SeedRepository
import com.totop.app.data.repo.CacheKey
import com.totop.app.data.repo.CacheRepository
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.LruEvictionPlan
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.PageRequest
import com.totop.app.data.repo.QuestionRepository
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasterySnapshot
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.QuestionSourceType
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

/** Repository 层 instrumentation 测试：验证事务、去重、90 天裁剪与 LRU 淘汰的真实行为。 */
@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentationTest {
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
    ): Question =
        Question(
            id = id,
            subject = "math",
            grade = "高一",
            stemText = "题干 " + id,
            ask = "求 x",
            imageSha256 = sha,
            sourceType = QuestionSourceType.CAMERA,
            createdAtUtc = 1_700_000_000_000L,
        )

    @Test
    fun saveDeduplicatedReturnsExistingQuestion() =
        runBlocking {
            val repository = QuestionRepository(database, database.questionDao())
            val first = repository.saveDeduplicated(question("q1", "sha-1"))
            assertTrue(first.createdNew)
            val second = repository.saveDeduplicated(question("q2", "sha-1"))
            assertEquals(false, second.createdNew)
            assertEquals("q1", second.question.id)
            assertEquals(1, repository.count())
            assertNotNull(repository.byImageSha256("sha-1"))
            assertNull(repository.byImageSha256("sha-2"))
        }

    @Test
    fun mistakeSaveWritesKpointLinksAtomically() =
        runBlocking {
            val questionRepository = QuestionRepository(database, database.questionDao())
            val mistakeRepository =
                MistakeRepository(
                    database,
                    database.mistakeDao(),
                    database.reviewDao(),
                    database.hintDao(),
                )
            questionRepository.save(question("q1"))
            val record =
                MistakeRecord(
                    id = "m1",
                    questionId = "q1",
                    errorType = ErrorType.CALCULATION_ERROR,
                    createdAtUtc = 10L,
                )
            val saved =
                mistakeRepository.save(
                    record,
                    assignments =
                        listOf(
                            KpointAssignment("KP_MATH_0001", "有理数", 0.6, "运算"),
                            KpointAssignment("KP_MATH_0001", "有理数", 0.9, "运算"),
                            KpointAssignment("KP_MATH_0002", "数轴", 0.4, "表示"),
                        ),
                    recomputeRecurrence = false,
                )
            assertEquals(1, saved.recurrenceIndex)
            assertEquals(listOf("KP_MATH_0001", "KP_MATH_0002"), mistakeRepository.kpointsOfMistake("m1").sorted())
            val links = mistakeRepository.linksOfKpoint("KP_MATH_0001")
            assertEquals(1, links.size)
            assertEquals(0.9, links.single().confidence, 1e-9)

            val second =
                mistakeRepository.save(
                    record.copy(id = "m2"),
                    assignments = listOf(KpointAssignment("KP_MATH_0001", "有理数", 0.9, "运算")),
                    recomputeRecurrence = true,
                )
            assertEquals(2, second.recurrenceIndex)

            mistakeRepository.updateKpoints("m1", listOf(KpointAssignment("KP_MATH_0003", "相反数", 1.0, "符号")))
            assertEquals(listOf("KP_MATH_0003"), mistakeRepository.kpointsOfMistake("m1"))
        }

    @Test
    fun masteryHistoryTrimmedTo90Days() =
        runBlocking {
            val seedRepository =
                SeedRepository(
                    database,
                    database.knowledgeDao(),
                    database.settingsDao(),
                    { null },
                )
            val repository =
                KnowledgeRepository(database, database.knowledgeDao(), database.masteryDao(), seedRepository)
            val history = (0 until 120).map { MasterySnapshot(19_000L + it, 50.0) }
            val saved =
                repository.saveMastery(
                    Mastery(kpointId = "KP_MATH_0001", score = 66.0, history = history, updatedAtUtc = 1L),
                )
            assertEquals(KnowledgeRepository.HISTORY_KEEP_DAYS, saved.history.size)
            assertEquals(19_000L + 119, saved.history.last().dateEpochDayUtc)
            assertEquals(19_000L + 30, saved.history.first().dateEpochDayUtc)
            assertEquals(90, repository.masteryOf("KP_MATH_0001")?.history?.size)
        }

    @Test
    fun cachePutGetAndLruPlan() =
        runBlocking {
            val repository = CacheRepository(database, database.cacheEntryDao())
            val key = CacheKey("sha-1", "1.0.0", "qwen-max")
            assertEquals(0, repository.put(key, "{\"a\":1}", nowUtc = 1_000L))
            assertEquals("{\"a\":1}", repository.get(key, nowUtc = 2_000L))
            assertEquals(1, repository.count())

            val plan =
                LruEvictionPlan.plan(
                    totalBytes = 120,
                    maxBytes = 100,
                    targetBytes = 90,
                    candidates =
                        listOf(
                            LruEvictionPlan.Candidate("a", "1.0.0", "m", 40),
                            LruEvictionPlan.Candidate("b", "1.0.0", "m", 40),
                        ),
                )
            assertEquals(1, plan.victims.size)
            assertEquals(80, plan.remainingBytes)
        }

    @Test
    fun paginationFollowsPageRequest() =
        runBlocking {
            val repository = QuestionRepository(database, database.questionDao())
            (1..7).forEach { repository.save(question("q" + it)) }
            val page0 = repository.page(PageRequest(page = 0, size = 3))
            val page2 = repository.page(PageRequest(page = 2, size = 3))
            assertEquals(3, page0.size)
            assertEquals(1, page2.size)
            assertEquals(7, repository.observeCount().first())
        }
}
