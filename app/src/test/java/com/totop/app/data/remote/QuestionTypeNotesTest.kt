package com.totop.app.data.remote

import com.totop.app.domain.model.AiQuestion
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 题型补充要领的拼装规则。
 *
 * 规则有三条，缺一条就会出问题：
 * 1. 有题型且文件里有该小节 -> 追加到阶段 B 模板尾部；
 * 2. 没有题型（老缓存 / 模型没给）-> 不追加，按通用模板作答；
 * 3. 题型不在文件里 -> 同样不追加，不能因此报错。
 */
@DisplayName("题型补充要领")
class QuestionTypeNotesTest {
    private val files =
        TestPrompts.FILES +
            (
                "prompts/type_notes.md" to
                    """
                    # 题型补充要领

                    ## math_choice

                    先逐项判定再回到题干。

                    ## english_continuation

                    每段第一句必须承接段首句。
                    """.trimIndent()
            )

    private val repository = PromptRepository(AssetReader.of(files))

    private fun question(type: String?): AiQuestion =
        AiQuestion(
            subject = "math",
            grade = "高一",
            stem = "求 f(x)=x^2-2x+1 在 [0,3] 上的最小值",
            questionType = type,
        )

    @Test
    @DisplayName("有题型时追加对应小节")
    fun appendsNoteForKnownType() {
        val prompt = repository.assemble(StageInput.Solve(question("math_choice")))
        assertTrue(prompt.systemPrompt.contains("先逐项判定再回到题干"))
        // 只追加命中的那一节，别的题型不能串进来
        assertFalse(prompt.systemPrompt.contains("承接段首句"))
    }

    @Test
    @DisplayName("没有题型时不追加任何内容")
    fun noNoteWithoutType() {
        val prompt = repository.assemble(StageInput.Solve(question(null)))
        assertFalse(prompt.systemPrompt.contains("先逐项判定再回到题干"))
    }

    @Test
    @DisplayName("题型未登记时不追加，也不报错")
    fun unknownTypeDoesNotFail() {
        val prompt = repository.assemble(StageInput.Solve(question("math_unknown_type")))
        assertTrue(prompt.systemPrompt.contains("只做独立求解"))
    }
}
