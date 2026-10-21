package com.totop.app.ui.chat

import com.totop.app.data.remote.FollowUpTurn
import com.totop.app.data.repo.ChatTurn
import com.totop.app.data.repo.SolveSession
import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolvedContent
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.FirstError
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.Subjects
import com.totop.app.domain.model.SummaryCard
import com.totop.app.domain.model.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 会话 / 追问 / 映射的回归测试。
 *
 * 覆盖真机反馈里最痛的三条：
 * 1. 科目归一（非数学题不能被塞进数学，技术的常见写法要能识别）；
 * 2. 领域结果 -> 展示快照 -> 会话记录的往返不丢关键字段；
 * 3. 会话标题与副标题的推断规则。
 */
class SolveSessionTest {
    // ---------- 科目归一 ----------

    @Test
    @DisplayName("科目归一覆盖十科与常见写法")
    fun subjectNormalizeCoversTenSubjects() {
        assertEquals("math", Subjects.normalize("数学"))
        assertEquals("math", Subjects.normalize("math"))
        assertEquals("math", Subjects.normalize("Math"))
        assertEquals("physics", Subjects.normalize("高中物理"))
        assertEquals("chemistry", Subjects.normalize("化学"))
        assertEquals("biology", Subjects.normalize("生物"))
        assertEquals("chinese", Subjects.normalize("语文"))
        assertEquals("english", Subjects.normalize("英语"))
        assertEquals("history", Subjects.normalize("历史"))
        assertEquals("geography", Subjects.normalize("地理"))
        assertEquals("politics", Subjects.normalize("政治"))
    }

    @Test
    @DisplayName("技术科目支持浙江选考的两种写法")
    fun technologySupportsZhejiangAliases() {
        assertEquals("technology", Subjects.normalize("技术"))
        assertEquals("technology", Subjects.normalize("信息技术"))
        assertEquals("technology", Subjects.normalize("通用技术"))
    }

    @Test
    @DisplayName("识别不了的科目落到通用而不是数学")
    fun unknownSubjectFallsBackToGeneralNotMath() {
        // 旧实现一律回退 math，导致「综合」「通用」类题悄悄计入数学错题本
        assertEquals(Subjects.GENERAL, Subjects.normalize("综合"))
        assertEquals(Subjects.GENERAL, Subjects.normalize(""))
        assertEquals(Subjects.GENERAL, Subjects.normalize(null))
    }

    @Test
    @DisplayName("十科中文名全部存在且顺序稳定")
    fun allLabelsContainElevenEntries() {
        assertEquals(11, Subjects.ALL_LABELS.size)
        assertEquals("语文", Subjects.ALL_LABELS.first())
        assertTrue(Subjects.ALL_LABELS.contains("技术"))
        assertEquals("technology", Subjects.codeOf("技术"))
    }

    // ---------- 领域结果 -> 展示快照 ----------

    private fun content(
        finalAnswer: String = "x=2",
        withError: Boolean = true,
        kpoints: List<KpointAssignment> = emptyList(),
    ): AiSolvedContent =
        AiSolvedContent(
            question =
                Question(
                    id = "q-1",
                    subject = "math",
                    grade = "初中",
                    stemText = "解方程 2x-1=3",
                    known = listOf("2x-1=3"),
                    ask = "求 x",
                    createdAtUtc = 1_700_000_000_000L,
                ),
            standardSolution =
                StandardSolution(
                    steps =
                        listOf(
                            SolutionStep(idx = 1, content = "移项得 2x=4", why = "两边同加 1"),
                            SolutionStep(idx = 2, content = "化系数为 1 得 x=2", why = "两边同除 2"),
                        ),
                    finalAnswer = finalAnswer,
                    method = "移项合并",
                ),
            studentSteps = emptyList(),
            firstError =
                if (withError) {
                    FirstError(
                        stepIdx = 1,
                        type = ErrorType.CALCULATION_ERROR,
                        explain = "移项没有变号",
                        correctThinking = "移项必须变号",
                    )
                } else {
                    null
                },
            kpointAssignments = kpoints,
            summaryCard = SummaryCard(title = "一元一次方程", bullets = listOf("移项变号")),
            aiGenerated = true,
        )

    @Test
    @DisplayName("展示快照保留答案与错因")
    fun payloadKeepsAnswerAndError() {
        val payload = SolutionMapper.fromContent(content(), TeachingMode.DIRECT)
        assertEquals("x=2", payload.finalAnswer)
        assertEquals(2, payload.steps.size)
        assertNotNull(payload.firstError)
        assertEquals("计算失误", payload.firstError?.typeLabel)
        assertEquals("移项没有变号", payload.firstError?.explain)
        assertTrue(payload.fullSolutionRevealed)
        assertEquals("数学", payload.subjectLabel)
    }

    @Test
    @DisplayName("GUIDED 模式默认不展示答案")
    fun guidedModeHidesAnswerByDefault() {
        val payload = SolutionMapper.fromContent(content(), TeachingMode.GUIDED)
        assertFalse(payload.fullSolutionRevealed)
        assertFalse(GuidePolicy.showsFinalAnswer(TeachingMode.GUIDED, payload.fullSolutionRevealed))
        assertTrue(GuidePolicy.showsRevealAction(TeachingMode.GUIDED, payload.fullSolutionRevealed))
    }

    @Test
    @DisplayName("没有错因时快照仍然可用")
    fun payloadWithoutErrorStillUsable() {
        val payload = SolutionMapper.fromContent(content(withError = false), TeachingMode.DIRECT)
        assertNull(payload.firstError)
        assertFalse(payload.hasError)
        assertEquals("x=2", payload.finalAnswer)
    }

    @Test
    @DisplayName("快照能还原成领域聚合且错因类型不丢")
    fun payloadRoundTripKeepsErrorTypeAndKpoint() {
        val payload =
            SolutionMapper.fromContent(
                content(
                    kpoints =
                        listOf(
                            KpointAssignment(
                                kpointId = "KP_MATH_0001",
                                name = "有理数",
                                confidence = 0.9,
                                usage = "化简",
                            ),
                        ),
                ),
                TeachingMode.DIRECT,
            )
        val back = SolutionMapper.toContent(payload)
        assertEquals(ErrorType.CALCULATION_ERROR, back.firstError?.type)
        assertEquals("KP_MATH_0001", back.kpointAssignments.first().kpointId)
        assertEquals("x=2", back.standardSolution.finalAnswer)
    }

    @Test
    @DisplayName("识题结果转题目时字段透传")
    fun questionOfPassesSubjectThrough() {
        val question =
            SolutionMapper.questionOf(
                question = AiQuestion(subject = "高中物理", stem = "求加速度"),
                id = "q-2",
                imagePath = null,
                imageSha256 = null,
                sourceType = com.totop.app.domain.model.QuestionSourceType.CAMERA,
                createdAtUtc = 1L,
            )
        // 归一发生在网关层，这里确认透传不丢字段
        assertEquals("高中物理", question.subject)
        assertEquals("求加速度", question.stemText)
    }

    // ---------- 会话记录 ----------

    @Test
    @DisplayName("展示消息转持久化消息保留关键字段")
    fun toTurnKeepsPagesAndRole() {
        val message =
            ConversationMessage(
                id = "m-1",
                fromStudent = true,
                text = "但是搜作业帮这题选 ab 啊",
                pages = listOf("/a.jpg", "/b.jpg"),
                createdAtUtc = 100L,
            )
        val turn = SessionTurns.toTurn(message)
        assertEquals("m-1", turn.id)
        assertTrue(turn.fromStudent)
        assertEquals(2, turn.pages.size)
        assertEquals(ChatTurn.KIND_CHAT, turn.kind)
    }

    @Test
    @DisplayName("解题消息会被标记为 solution 类型")
    fun solutionMessageIsMarkedAsSolution() {
        val message =
            ConversationMessage(
                id = "m-2",
                fromStudent = false,
                text = "解答摘要",
                solution = SolutionMapper.fromContent(content(), TeachingMode.DIRECT),
                createdAtUtc = 100L,
            )
        assertEquals(ChatTurn.KIND_SOLUTION, SessionTurns.toTurn(message).kind)
    }

    @Test
    @DisplayName("会话标题优先取学生说的第一句")
    fun sessionTitleUsesFirstStudentLine() {
        val turns =
            listOf(
                ConversationMessage(id = "1", fromStudent = false, text = "AI 先说"),
                ConversationMessage(id = "2", fromStudent = true, text = "这道题为什么选 A"),
            )
        assertEquals("这道题为什么选 A", SessionTurns.titleOf(turns))
    }

    @Test
    @DisplayName("没有学生发言时标题回退到题干首行")
    fun sessionTitleFallsBackToStemFirstLine() {
        val turns = listOf(ConversationMessage(id = "1", fromStudent = false, text = ""))
        val stem = "解方程 2x-1=3" + System.lineSeparator() + "第二行"
        assertEquals("解方程 2x-1=3", SessionTurns.titleOf(turns, fallbackStem = stem))
    }

    @Test
    @DisplayName("标题全空时给兜底文案")
    fun sessionTitleUsesDefaultWhenEmpty() {
        assertEquals("新对话", SessionTurns.titleOf(emptyList()))
    }

    @Test
    @DisplayName("副标题包含科目与轮次")
    fun subtitleContainsSubjectAndRounds() {
        assertEquals("物理 · 3 轮对话", SessionTurns.subtitleOf("physics", 3))
    }

    @Test
    @DisplayName("会话还原成消息时保留文本与错误")
    fun toMessagesKeepsTextAndError() {
        val session =
            SolveSession(
                id = "s-1",
                turns =
                    listOf(
                        ChatTurn(id = "t1", fromStudent = true, text = "问", createdAtUtc = 1L),
                        ChatTurn(id = "t2", fromStudent = false, text = "", errorText = "网络断了", createdAtUtc = 2L),
                    ),
            )
        val messages = SessionTurns.toMessages(session)
        assertEquals(2, messages.size)
        assertEquals("问", messages[0].text)
        assertEquals("网络断了", messages[1].errorText)
    }

    @Test
    @DisplayName("会话轮次统计只数学生发言")
    fun roundCountOnlyCountsStudentTurns() {
        val session =
            SolveSession(
                id = "s-1",
                turns =
                    listOf(
                        ChatTurn(id = "t1", fromStudent = true, text = "问1", createdAtUtc = 1L),
                        ChatTurn(id = "t2", fromStudent = false, text = "答1", createdAtUtc = 2L),
                        ChatTurn(id = "t3", fromStudent = true, text = "问2", createdAtUtc = 3L),
                    ),
            )
        assertEquals(2, session.roundCount)
    }

    @Test
    @DisplayName("空标题会话有兜底展示名")
    fun emptyTitleSessionHasFallbackName() {
        assertEquals("未命名对话", SolveSession(id = "x").displayTitle)
        assertEquals("有标题", SolveSession(id = "x", title = "有标题").displayTitle)
    }

    // ---------- 追问上下文 ----------

    @Test
    @DisplayName("追问上下文包含题干步骤与错因")
    fun followUpContextContainsStemStepsAndError() {
        val payload = SolutionMapper.fromContent(content(), TeachingMode.DIRECT)
        val context =
            com.totop.app.data.remote.SolveContextBuilder.build(
                stem = payload.stem,
                known = payload.known,
                ask = payload.ask,
                steps = payload.steps.map { it.content },
                finalAnswer = payload.finalAnswer,
                method = payload.method,
                errorTypeLabel = payload.firstError?.typeLabel.orEmpty(),
                errorExplain = payload.firstError?.explain.orEmpty(),
            )
        assertTrue(context.contains("解方程 2x-1=3"))
        assertTrue(context.contains("移项得 2x=4"))
        assertTrue(context.contains("x=2"))
        assertTrue(context.contains("计算失误"))
    }

    @Test
    @DisplayName("追问历史区分学生与助手")
    fun followUpTurnDistinguishesStudent() {
        val turns =
            listOf(
                FollowUpTurn(text = "为什么不能直接移项", fromStudent = true),
                FollowUpTurn(text = "因为移项要变号", fromStudent = false),
            )
        assertTrue(turns.first().fromStudent)
        assertFalse(turns.last().fromStudent)
    }

    @Test
    @DisplayName("追问系统提示会嵌入已知信息但不超过上限")
    fun followUpSystemPromptTruncatesLongContext() {
        val longContext = "题干".repeat(3000)
        val prompt = com.totop.app.data.remote.FollowUpGateway.systemPrompt(longContext)
        assertTrue(prompt.contains("已知信息"))
        assertTrue(prompt.length < longContext.length)
    }
}
