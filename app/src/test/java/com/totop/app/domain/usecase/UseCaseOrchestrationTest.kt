package com.totop.app.domain.usecase

import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.ReviewLog
import com.totop.app.domain.usecase.port.DAY_MILLIS
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.DomainSettings
import com.totop.app.domain.usecase.port.ExerciseDraftOutcome
import com.totop.app.domain.usecase.port.IdGenerator
import com.totop.app.domain.usecase.port.ReportNarrator
import com.totop.app.domain.usecase.port.VerificationOutcome
import com.totop.app.domain.usecase.port.epochDayOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 掌握度、学习路径、周报、练习题、导出五个用例的编排单测。 */
class UseCaseOrchestrationTest {
    private val now = 1_704_067_200_000L // 2024-01-01T00:00:00Z
    private val today = epochDayOf(now)

    // ---------------- BuildMasteryUseCase ----------------

    @Nested
    @DisplayName("掌握度重算")
    inner class Mastery {
        private fun mistake(
            id: String,
            atUtc: Long,
        ) = MistakeRecord(
            id = id,
            questionId = "q-" + id,
            errorType = ErrorType.CALCULATION_ERROR,
            createdAtUtc = atUtc,
        )

        @Test
        fun `无任何记录时掌握度只保留错题项权重`() =
            runTest {
                val useCase =
                    BuildMasteryUseCase(
                        kpoints = FakeKpointDirectory(),
                        masteryStore = FakeMasteryStore(),
                        mistakes = FakeMistakeStore(),
                        hints = FakeHintStore(),
                        dailyStats = FakeDailyStatsStore(),
                        clock = DomainClock.fixed(now),
                    )
                val result = useCase.rebuild()
                assertEquals(2, result.kpointCount)
                assertEquals(30.0, result.changes.first().newScore, 1e-6)
            }

        @Test
        fun `复习自评能抬高掌握度并写入历史快照`() =
            runTest {
                val store = FakeMistakeStore()
                store.addReview(ReviewLog("r1", "m1", now, RecallSelfResult.GOT_IT))
                val masteryStore = FakeMasteryStore()
                val useCase =
                    BuildMasteryUseCase(
                        kpoints = FakeKpointDirectory(listOf(KnowledgePoint("KP_MATH_0001", "导数与单调性", "math"))),
                        masteryStore = masteryStore,
                        mistakes = FakeMistakeStore(),
                        hints = FakeHintStore(),
                        dailyStats = FakeDailyStatsStore(),
                        clock = DomainClock.fixed(now),
                    )
                // 用带关联的假存储才能取到复习记录
                val realistic =
                    BuildMasteryUseCase(
                        kpoints = FakeKpointDirectory(listOf(KnowledgePoint("KP_MATH_0001", "导数与单调性", "math"))),
                        masteryStore = masteryStore,
                        mistakes =
                            FakeMistakeStore(
                                records = listOf(mistake("m1", now - DAY_MILLIS)),
                                kpointMap = mapOf("m1" to listOf("KP_MATH_0001")),
                            ).also { it.addReview(ReviewLog("r1", "m1", now, RecallSelfResult.GOT_IT)) },
                        hints = FakeHintStore(),
                        dailyStats = FakeDailyStatsStore(),
                        clock = DomainClock.fixed(now),
                    )
                val result = realistic.rebuild()
                assertEquals(1, result.kpointCount)
                assertTrue(result.changes.single().newScore > 30.0, "有正面自评应高于基线 30")
                assertEquals(1, result.changes.single().recallEvents)
                val saved = masteryStore.saved.single()
                assertEquals(1, saved.history.size, "应写入当天快照")
                assertEquals(today, saved.history.single().dateEpochDayUtc)
                assertEquals(now, saved.updatedAtUtc)
                assertNotNull(useCase)
            }

        @Test
        fun `提示记录触发百分点折扣`() =
            runTest {
                val mistakeStore =
                    FakeMistakeStore(
                        records = listOf(mistake("m1", now - DAY_MILLIS)),
                        kpointMap = mapOf("m1" to listOf("KP_MATH_0001")),
                    )
                mistakeStore.addReview(ReviewLog("r1", "m1", now, RecallSelfResult.GOT_IT))
                val withoutHint =
                    BuildMasteryUseCase(
                        kpoints = FakeKpointDirectory(listOf(KnowledgePoint("KP_MATH_0001", "导数", "math"))),
                        masteryStore = FakeMasteryStore(),
                        mistakes = mistakeStore,
                        hints = FakeHintStore(),
                        dailyStats = FakeDailyStatsStore(),
                        clock = DomainClock.fixed(now),
                    ).rebuild()
                val withHint =
                    BuildMasteryUseCase(
                        kpoints = FakeKpointDirectory(listOf(KnowledgePoint("KP_MATH_0001", "导数", "math"))),
                        masteryStore = FakeMasteryStore(),
                        mistakes = mistakeStore,
                        hints =
                            FakeHintStore(
                                hintLogs = listOf(com.totop.app.domain.model.HintLog("h1", "m1", 2, now)),
                                mistakeOwners = mapOf("m1" to "KP_MATH_0001"),
                            ),
                        dailyStats = FakeDailyStatsStore(),
                        clock = DomainClock.fixed(now),
                    ).rebuild()
                assertTrue(withHint.changes.single().hintOnly)
                assertEquals(withoutHint.changes.single().newScore * 0.6, withHint.changes.single().newScore, 1e-6)
            }

        @Test
        fun `同一天重复重算只保留一条快照`() =
            runTest {
                val masteryStore =
                    FakeMasteryStore(
                        items =
                            listOf(
                                Mastery(
                                    kpointId = "KP_MATH_0001",
                                    score = 50.0,
                                    history = listOf(com.totop.app.domain.model.MasterySnapshot(today, 50.0)),
                                    updatedAtUtc = now - DAY_MILLIS,
                                ),
                            ),
                    )
                BuildMasteryUseCase(
                    kpoints = FakeKpointDirectory(listOf(KnowledgePoint("KP_MATH_0001", "导数", "math"))),
                    masteryStore = masteryStore,
                    mistakes = FakeMistakeStore(),
                    hints = FakeHintStore(),
                    dailyStats = FakeDailyStatsStore(),
                    clock = DomainClock.fixed(now),
                ).rebuild()
                assertEquals(1, masteryStore.saved.last().history.size)
            }

        @Test
        fun `只重算指定知识点`() =
            runTest {
                val result =
                    BuildMasteryUseCase(
                        kpoints = FakeKpointDirectory(),
                        masteryStore = FakeMasteryStore(),
                        mistakes = FakeMistakeStore(),
                        hints = FakeHintStore(),
                        dailyStats = FakeDailyStatsStore(),
                        clock = DomainClock.fixed(now),
                    ).rebuild(MasteryScope.Only(listOf("KP_MATH_0002")))
                assertEquals(1, result.kpointCount)
                assertEquals("KP_MATH_0002", result.changes.single().kpointId)
            }

        @Test
        fun `薄弱计数与最薄弱排序`() =
            runTest {
                val result =
                    BuildMasteryUseCase(
                        kpoints = FakeKpointDirectory(),
                        masteryStore = FakeMasteryStore(),
                        mistakes = FakeMistakeStore(),
                        hints = FakeHintStore(),
                        dailyStats = FakeDailyStatsStore(),
                        clock = DomainClock.fixed(now),
                    ).rebuild()
                assertEquals(2, result.weakCount, "基线 30 分低于 60，两个都算薄弱")
                assertEquals(2, result.weakest(5).size)
                assertTrue(result.averageScore in 0.0..100.0)
            }
    }

    // ---------------- BuildLearningPathUseCase ----------------

    @Nested
    @DisplayName("学习路径")
    inner class Path {
        private val a = "KP_MATH_0001"
        private val b = "KP_MATH_0002"
        private val c = "KP_MATH_0003"

        private fun directory() =
            FakeKpointDirectory(
                points =
                    listOf(
                        KnowledgePoint(a, "一元二次不等式", "math"),
                        KnowledgePoint(b, "函数单调性", "math"),
                        KnowledgePoint(c, "导数与单调性", "math"),
                    ),
                edgeList =
                    listOf(
                        KpointEdge(a, b, com.totop.app.domain.model.EdgeType.PREREQUISITE),
                        KpointEdge(b, c, com.totop.app.domain.model.EdgeType.PREREQUISITE),
                    ),
            )

        @Test
        fun `输出先补后攻的路径文案`() =
            runTest {
                val result =
                    BuildLearningPathUseCase(
                        kpoints = directory(),
                        masteryStore =
                            FakeMasteryStore(
                                listOf(
                                    Mastery(a, 10.0, emptyList(), now),
                                    Mastery(b, 20.0, emptyList(), now),
                                    Mastery(c, 90.0, emptyList(), now),
                                ),
                            ),
                        clock = DomainClock.fixed(now),
                    ).build(c)
                assertTrue(result is LearningPathResult.Planned)
                val planned = result as LearningPathResult.Planned
                assertEquals(listOf(a, b, c), planned.path.steps.map { it.kpointId })
                assertEquals("先补 一元二次不等式 → 函数单调性 → 才能攻 导数与单调性", planned.headline)
                assertEquals(2, planned.totalMasteryDelta)
                assertFalse(planned.targetOnly)
            }

        @Test
        fun `前置都已掌握时只输出目标`() =
            runTest {
                val result =
                    BuildLearningPathUseCase(
                        kpoints = directory(),
                        masteryStore =
                            FakeMasteryStore(
                                listOf(
                                    Mastery(a, 80.0, emptyList(), now),
                                    Mastery(b, 80.0, emptyList(), now),
                                    Mastery(c, 30.0, emptyList(), now),
                                ),
                            ),
                        clock = DomainClock.fixed(now),
                    ).build(c) as LearningPathResult.Planned
                assertTrue(result.targetOnly)
                assertEquals(1, result.path.size)
            }

        @Test
        fun `未知目标返回 UnknownTarget`() =
            runTest {
                val result =
                    BuildLearningPathUseCase(
                        kpoints = directory(),
                        masteryStore = FakeMasteryStore(),
                        clock = DomainClock.fixed(now),
                    ).build("KP_MATH_9999")
                assertTrue(result is LearningPathResult.UnknownTarget)
            }

        @Test
        fun `成环时拒绝输出路径`() =
            runTest {
                val cyclic =
                    FakeKpointDirectory(
                        points = listOf(KnowledgePoint(a, "A", "math"), KnowledgePoint(b, "B", "math")),
                        edgeList =
                            listOf(
                                KpointEdge(a, b, com.totop.app.domain.model.EdgeType.PREREQUISITE),
                                KpointEdge(b, a, com.totop.app.domain.model.EdgeType.PREREQUISITE),
                            ),
                    )
                val result =
                    BuildLearningPathUseCase(
                        kpoints = cyclic,
                        masteryStore = FakeMasteryStore(),
                        clock = DomainClock.fixed(now),
                    ).build(b)
                assertTrue(result is LearningPathResult.CyclicGraph)
                assertTrue((result as LearningPathResult.CyclicGraph).headline.contains("循环依赖"))
            }

        @Test
        fun `按最薄弱知识点批量规划`() =
            runTest {
                val results =
                    BuildLearningPathUseCase(
                        kpoints = directory(),
                        masteryStore =
                            FakeMasteryStore(
                                listOf(
                                    Mastery(a, 5.0, emptyList(), now),
                                    Mastery(b, 15.0, emptyList(), now),
                                    Mastery(c, 95.0, emptyList(), now),
                                ),
                            ),
                        clock = DomainClock.fixed(now),
                    ).buildForWeakest(limit = 2)
                assertEquals(2, results.size)
                assertEquals(a, results.first().target.id)
            }
    }

    // ---------------- BuildWeeklyReportUseCase ----------------

    @Nested
    @DisplayName("周报渲染")
    inner class WeeklyReport {
        private fun mistake(
            id: String,
            atUtc: Long,
        ) = MistakeRecord(
            id = id,
            questionId = "q-" + id,
            errorType = ErrorType.CALCULATION_ERROR,
            createdAtUtc = atUtc,
        )

        private fun build(
            daily: List<DailyStats> =
                listOf(
                    DailyStats(
                        today - 1,
                        "math",
                        mistakeCount = 3,
                        kpointCount = 2,
                        hintCount = 1,
                        reviewDoneCount = 2,
                        studySeconds = 1800,
                    ),
                    DailyStats(
                        today - 2,
                        "math",
                        mistakeCount = 1,
                        kpointCount = 1,
                        hintCount = 0,
                        reviewDoneCount = 1,
                        studySeconds = 600,
                    ),
                ),
            narrator: FakeReportNarrator = FakeReportNarrator(),
            settings: DomainSettings = DomainSettings.DEFAULTS,
            recorder: FakeDailyStatsStore = FakeDailyStatsStore(daily),
        ) = Triple(
            BuildWeeklyReportUseCase(
                dailyStats = recorder,
                mistakes =
                    FakeMistakeStore(
                        records =
                            listOf(
                                mistake("m1", now - DAY_MILLIS),
                                mistake("m2", now - 2 * DAY_MILLIS),
                            ),
                        kpointMap = mapOf("m1" to listOf("KP_MATH_0001"), "m2" to listOf("KP_MATH_0001")),
                    ),
                kpoints = FakeKpointDirectory(),
                reports = FakeReportStore(),
                narrator = narrator,
                settings = settings,
                clock = DomainClock.fixed(now),
                ids = IdGenerator.sequence("rep"),
            ),
            narrator,
            recorder,
        )

        @Test
        fun `只按日期区间读取日汇总_不做全表扫描`() =
            runTest {
                val (useCase, _, recorder) = build()
                useCase.build(ReportPeriod.WEEKLY, now)
                val range = recorder.lastBetweenRange
                assertNotNull(range)
                val (from, to) = range!!
                // 覆盖「含今天在内的最近 7 天」：from = 今天-6，to = 今天（区间含两端）
                assertEquals(today - 6, from, "应覆盖含今天在内的最近 7 天")
                assertEquals(today, to, "应截止到今天（含）")
            }

        @Test
        fun `所有数字由模板渲染且包含六项指标`() =
            runTest {
                val (useCase, _, _) = build()
                val outcome = useCase.build(ReportPeriod.WEEKLY, now) as ReportOutcome.Generated
                val markdown = outcome.markdown
                assertTrue(markdown.contains("# 学情周报"))
                assertTrue(markdown.contains("- 新增错题：" + outcome.stats.newMistakeCount + " 道"))
                assertTrue(markdown.contains("- 完成复习：" + outcome.stats.reviewDoneCount + " 次"))
                assertTrue(markdown.contains("- 复习完成率：" + outcome.stats.reviewRateText))
                assertTrue(markdown.contains("- 学习时长：" + outcome.stats.studyDurationText))
                assertTrue(
                    markdown.contains("- 活跃天数：" + outcome.stats.activeDays + " / " + outcome.stats.periodDays + " 天"),
                )
                assertTrue(markdown.contains("本机统计生成"))
                assertTrue(markdown.contains("注意的知识点"))
                assertTrue(markdown.contains("错因分布"))
            }

        @Test
        fun `错因分布固定七类全部出现`() =
            runTest {
                val (useCase, _, _) = build()
                val stats = useCase.preview(ReportPeriod.WEEKLY, now)
                assertEquals(7, stats.errorTypeDistribution.size)
                assertEquals(0, stats.errorTypeDistribution.first { it.code == "FORMULA_MISUSE" }.count)
                assertTrue(stats.errorTypeDistribution.any { it.code == "CALCULATION_ERROR" && it.count > 0 })
            }

        @Test
        fun `AI 解读开启时追加 AI 段落并标注`() =
            runTest {
                val narrator =
                    FakeReportNarrator(
                        ReportNarrator.NarrativeOutcome.Success("本周你在计算失误上要更细致。"),
                    )
                val (useCase, _, _) = build(narrator = narrator)
                val outcome = useCase.build(ReportPeriod.WEEKLY, now, forceAiComment = true) as ReportOutcome.Generated
                assertEquals("本周你在计算失误上要更细致。", outcome.aiComment)
                assertTrue(outcome.markdown.contains("AI 解读与建议（AI 生成）"))
                assertTrue(outcome.markdown.contains("由 AI 生成，仅供参考"))
                assertFalse(outcome.pureStatistics)
                assertFalse(outcome.aiDegraded)
            }

        @Test
        fun `AI 解读关闭时输出纯统计版`() =
            runTest {
                val narrator = FakeReportNarrator()
                val (useCase, _, _) = build(narrator = narrator)
                val outcome = useCase.build(ReportPeriod.WEEKLY, now, forceAiComment = false) as ReportOutcome.Generated
                assertNull(outcome.aiComment)
                assertTrue(outcome.pureStatistics)
                assertFalse(outcome.markdown.contains("AI 解读与建议"))
                assertEquals(0, narrator.calls, "关闭时绝不能调用模型")
            }

        @Test
        fun `AI 解读失败时降级为纯统计版`() =
            runTest {
                val narrator = FakeReportNarrator(ReportNarrator.NarrativeOutcome.Failure("余额不足"))
                val (useCase, _, _) = build(narrator = narrator)
                val outcome = useCase.build(ReportPeriod.WEEKLY, now, forceAiComment = true) as ReportOutcome.Generated
                assertNull(outcome.aiComment)
                assertTrue(outcome.aiDegraded)
                assertTrue(outcome.markdown.contains("本期概览"))
            }

        @Test
        fun `没有任何数据时返回 NoData 不写库`() =
            runTest {
                val reports = FakeReportStore()
                val useCase =
                    BuildWeeklyReportUseCase(
                        dailyStats = FakeDailyStatsStore(),
                        mistakes = FakeMistakeStore(),
                        kpoints = FakeKpointDirectory(),
                        reports = reports,
                        narrator = FakeReportNarrator(),
                        settings = DomainSettings.DEFAULTS,
                        clock = DomainClock.fixed(now),
                        ids = IdGenerator.sequence("rep"),
                    )
                val outcome = useCase.build(ReportPeriod.WEEKLY, now)
                assertTrue(outcome is ReportOutcome.NoData)
                assertTrue(reports.saved.isEmpty())
            }

        @Test
        fun `周期键按 UTC 计算`() =
            runTest {
                val (useCase, _, _) = build()
                assertEquals("2024-W01", useCase.periodKeyOf(ReportPeriod.WEEKLY, now))
                assertEquals("2024-01", useCase.periodKeyOf(ReportPeriod.MONTHLY, now))
            }
    }

    // ---------------- GenerateExerciseUseCase ----------------

    @Nested
    @DisplayName("练习题生成与二次校验")
    inner class ExerciseGeneration {
        private fun useCase(
            generator: FakeExerciseGenerator = FakeExerciseGenerator(),
            store: FakeExerciseStore = FakeExerciseStore(),
            settings: DomainSettings = DomainSettings.DEFAULTS,
        ) = GenerateExerciseUseCase(
            generator = generator,
            exercises = store,
            kpoints = FakeKpointDirectory(),
            settings = settings,
            clock = DomainClock.fixed(now),
            ids = IdGenerator.sequence("ex"),
        )

        @Test
        fun `独立重解一致才入库`() =
            runTest {
                val store = FakeExerciseStore()
                val outcome = useCase(store = store).generate("KP_MATH_0001") as GenerateExerciseOutcome.Completed
                assertEquals(1, outcome.verified.size)
                assertTrue(outcome.verified.single().verified)
                assertEquals(1, store.saved.size)
                assertTrue(outcome.rejected.isEmpty())
                assertTrue(outcome.summaryText().contains("1 / 1"))
            }

        @Test
        fun `重解答案不一致时丢弃且不入库`() =
            runTest {
                val store = FakeExerciseStore()
                val generator =
                    FakeExerciseGenerator(
                        verifications =
                            mutableListOf<VerificationOutcome>(
                                VerificationOutcome.Success(consistent = false, derivedAnswer = "(2,+∞) 递减"),
                            ),
                    )
                val outcome =
                    useCase(generator = generator, store = store)
                        .generate("KP_MATH_0001") as GenerateExerciseOutcome.Completed
                assertTrue(outcome.verified.isEmpty())
                assertTrue(outcome.allRejected)
                assertEquals(1, outcome.rejected.size)
                assertTrue(outcome.rejected.single().reason.contains("不一致"))
                assertTrue(store.saved.isEmpty(), "未通过校验的题绝不能入库")
            }

        @Test
        fun `校验调用失败时该题不入库`() =
            runTest {
                val store = FakeExerciseStore()
                val generator =
                    FakeExerciseGenerator(
                        verifications = mutableListOf<VerificationOutcome>(VerificationOutcome.Failure("网络不通")),
                    )
                val outcome =
                    useCase(generator = generator, store = store)
                        .generate("KP_MATH_0001") as GenerateExerciseOutcome.Completed
                assertTrue(outcome.verified.isEmpty())
                assertTrue(store.saved.isEmpty())
                assertTrue(outcome.rejected.single().reason.contains("未能完成"))
            }

        @Test
        fun `非受控知识点 ID 直接拒绝`() =
            runTest {
                val generator = FakeExerciseGenerator()
                val outcome = useCase(generator = generator).generate("导数")
                assertTrue(outcome is GenerateExerciseOutcome.InvalidKpoint)
                assertTrue(generator.generated.isEmpty(), "非法 ID 不得发起模型调用")
            }

        @Test
        fun `设置关闭时不消耗 token`() =
            runTest {
                val generator = FakeExerciseGenerator()
                val outcome =
                    useCase(generator = generator, settings = DomainSettings.LOCAL_ONLY)
                        .generate("KP_MATH_0001")
                assertTrue(outcome is GenerateExerciseOutcome.LlmNotConfigured)
                assertTrue(generator.generated.isEmpty())
            }

        @Test
        fun `生成失败时透传原因`() =
            runTest {
                val generator =
                    FakeExerciseGenerator(
                        drafts = ExerciseDraftOutcome.Failure("模型名错误", retryable = false),
                    )
                val outcome = useCase(generator = generator).generate("KP_MATH_0001")
                assertTrue(outcome is GenerateExerciseOutcome.GenerationFailed)
                assertEquals("模型名错误", (outcome as GenerateExerciseOutcome.GenerationFailed).userMessage)
            }

        @Test
        fun `答案一致性判定吸收排版差异`() {
            val useCase = useCase()
            assertTrue(useCase.answersMatch("x = 1", "x=1"))
            assertTrue(useCase.answersMatch("（1，+∞）", "(1,+∞)"))
            assertTrue(useCase.answersMatch("\$x=1\$", "x=1"))
            assertFalse(useCase.answersMatch("x=1", "x=2"))
        }

        @Test
        fun `请求条数被裁剪到上限`() =
            runTest {
                val generator =
                    FakeExerciseGenerator(
                        verifications = MutableList<VerificationOutcome>(10) { VerificationOutcome.Success(true, "a") },
                    )
                val outcome = useCase(generator = generator).generate("KP_MATH_0001", count = 99)
                assertTrue(outcome is GenerateExerciseOutcome.Completed)
                assertEquals(GenerateExerciseUseCase.MAX_PER_REQUEST, generator.generated.single().count)
            }
    }

    // ---------------- ExportDataUseCase ----------------

    @Nested
    @DisplayName("导出渲染")
    inner class Export {
        private fun record() =
            MistakeRecord(
                id = "m1",
                questionId = "q1",
                errorType = ErrorType.CALCULATION_ERROR,
                errorExplain = "符号写错",
                firstErrorStepIdx = 2,
                finalAnswer = "x=1",
                correctSolution =
                    com.totop.app.domain.model.StandardSolution(
                        steps = listOf(com.totop.app.domain.model.SolutionStep(1, "求导", "判断单调性")),
                        finalAnswer = "x=1",
                        method = "导数法",
                    ),
                createdAtUtc = now,
            )

        private fun useCase(exporter: FakeDataExporter = FakeDataExporter()) =
            ExportDataUseCase(
                questions =
                    FakeQuestionStore(
                        mapOf(
                            "q1" to
                                com.totop.app.domain.model.Question(
                                    id = "q1",
                                    subject = "math",
                                    grade = "高一",
                                    stemText = "求 f(x) 的单调区间",
                                    known = listOf("f(x)=x^3-3x"),
                                    ask = "求单调区间",
                                    createdAtUtc = now,
                                ),
                        ),
                    ),
                mistakes =
                    FakeMistakeStore(
                        records = listOf(record()),
                        kpointMap = mapOf("m1" to listOf("KP_MATH_0001")),
                    ),
                kpoints = FakeKpointDirectory(),
                masteries = FakeMasteryStore(listOf(Mastery("KP_MATH_0001", 42.0, emptyList(), now))),
                reports = FakeReportStore(),
                exporter = exporter,
                clock = DomainClock.fixed(now),
            )

        @Test
        fun `Markdown 导出包含题干错因与解法`() =
            runTest {
                val outcome =
                    useCase().render(
                        com.totop.app.domain.usecase.port.ExportRequest(
                            format = com.totop.app.domain.usecase.port.ExportFormat.MARKDOWN,
                        ),
                    )
                assertTrue(outcome is com.totop.app.domain.usecase.port.ExportOutcome.Success)
                val content = (outcome as com.totop.app.domain.usecase.port.ExportOutcome.Success).artifact.content
                assertTrue(content.contains("# To Top 错题本"))
                assertTrue(content.contains("求 f(x) 的单调区间"))
                assertTrue(content.contains("计算失误"))
                assertTrue(content.contains("求导"))
                assertTrue(content.contains("由 AI 生成"))
            }

        @Test
        fun `CSV 正确转义引号与逗号`() =
            runTest {
                val csv = useCase().csvRow(listOf("含,逗号", "含\"引号\"", "普通"))
                assertEquals("\"含,逗号\",\"含\"\"引号\"\"\",\"普通\"\n", csv)
            }

        @Test
        fun `CSV 知识点统计导出掌握度与色阶`() =
            runTest {
                val outcome =
                    useCase().render(
                        com.totop.app.domain.usecase.port.ExportRequest(
                            format = com.totop.app.domain.usecase.port.ExportFormat.CSV,
                            view = com.totop.app.domain.usecase.port.ExportView.KPOINT_STATS,
                        ),
                    )
                val content = (outcome as com.totop.app.domain.usecase.port.ExportOutcome.Success).artifact.content
                assertTrue(content.contains("掌握度"))
                assertTrue(content.contains("42.0"))
                assertTrue(content.contains("黄"))
            }

        @Test
        fun `Anki 导出使用 tab 分隔与 html 换行`() =
            runTest {
                val outcome =
                    useCase().render(
                        com.totop.app.domain.usecase.port.ExportRequest(
                            format = com.totop.app.domain.usecase.port.ExportFormat.ANKI,
                        ),
                    )
                val content = (outcome as com.totop.app.domain.usecase.port.ExportOutcome.Success).artifact.content
                assertTrue(content.contains("#separator:tab"))
                assertTrue(content.contains("<br>"))
                assertTrue(content.contains("\t"))
            }

        @Test
        fun `备份 JSON 可被标准解析器读取`() =
            runTest {
                val outcome =
                    useCase().render(
                        com.totop.app.domain.usecase.port.ExportRequest(
                            format = com.totop.app.domain.usecase.port.ExportFormat.BACKUP_JSON,
                        ),
                    )
                val content = (outcome as com.totop.app.domain.usecase.port.ExportOutcome.Success).artifact.content
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(content)
                assertTrue(parsed is kotlinx.serialization.json.JsonObject)
                assertTrue(content.contains("schemaVersion"))
            }

        @Test
        fun `不支持的格式给出可读原因`() =
            runTest {
                val exporter =
                    FakeDataExporter(formats = setOf(com.totop.app.domain.usecase.port.ExportFormat.MARKDOWN))
                val outcome =
                    useCase(exporter).render(
                        com.totop.app.domain.usecase.port.ExportRequest(
                            format = com.totop.app.domain.usecase.port.ExportFormat.ANKI,
                        ),
                    )
                assertTrue(outcome is com.totop.app.domain.usecase.port.ExportOutcome.Failure)
                assertTrue(
                    (outcome as com.totop.app.domain.usecase.port.ExportOutcome.Failure)
                        .userMessage.contains("不支持"),
                )
            }

        @Test
        fun `写盘失败时透传原因`() =
            runTest {
                val exporter =
                    FakeDataExporter(
                        writeResult = com.totop.app.domain.usecase.port.ExportWriteResult.Failure("存储空间不足"),
                    )
                val outcome =
                    useCase(exporter).export(
                        com.totop.app.domain.usecase.port.ExportRequest(
                            format = com.totop.app.domain.usecase.port.ExportFormat.MARKDOWN,
                        ),
                    )
                assertTrue(outcome is com.totop.app.domain.usecase.port.ExportOutcome.Failure)
                assertEquals("存储空间不足", (outcome as com.totop.app.domain.usecase.port.ExportOutcome.Failure).userMessage)
            }

        @Test
        fun `空数据时给出提示而不是空文件`() =
            runTest {
                val empty =
                    ExportDataUseCase(
                        questions = FakeQuestionStore(),
                        mistakes = FakeMistakeStore(),
                        kpoints = FakeKpointDirectory(),
                        masteries = FakeMasteryStore(),
                        reports = FakeReportStore(),
                        exporter = FakeDataExporter(),
                        clock = DomainClock.fixed(now),
                    )
                val outcome =
                    empty.render(
                        com.totop.app.domain.usecase.port.ExportRequest(
                            format = com.totop.app.domain.usecase.port.ExportFormat.MARKDOWN,
                        ),
                    )
                assertTrue(outcome is com.totop.app.domain.usecase.port.ExportOutcome.Failure)
            }
    }
}
