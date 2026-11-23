package com.totop.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 阶段 A 新增 `title` 字段的兼容性契约。
 *
 * 这条字段影响两件事：会话标题的取值优先级，以及老数据的解析。
 * 老会话文件、老缓存里都没有 `title`，解析必须照常成功并得到 null；
 * 否则一次提示词升级会把历史对话读崩。
 */
@DisplayName("题目概括字段")
class QuestionTitleContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    @DisplayName("新 JSON 能读出题目概括")
    fun readsTitle() {
        val question =
            json.decodeFromString(
                AiQuestion.serializer(),
                """{"subject":"math","title":"二次函数在闭区间上的最值","stem":"求最小值"}""",
            )
        assertEquals("二次函数在闭区间上的最值", question.title)
    }

    @Test
    @DisplayName("老 JSON 没有 title 时解析为 null")
    fun oldJsonWithoutTitleParsesAsNull() {
        val question =
            json.decodeFromString(
                AiQuestion.serializer(),
                """{"subject":"math","stem":"求最小值"}""",
            )
        assertNull(question.title)
    }
}
