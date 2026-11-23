package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 解答来源枚举的取值契约。
 *
 * 这些线格式会写进 `mistake_record.solutionSource`，改名或重复会让旧记录读成「AI 解答」，
 * 表现为「导入的标准答案被当成模型给的」——排查起来很费劲。
 */
@DisplayName("解答来源")
class SolutionSourceTest {
    @Test
    @DisplayName("线格式与实体默认值一致")
    fun wireValuesAreStable() {
        assertEquals("ai", SolutionSource.AI.wire)
        assertEquals("import", SolutionSource.IMPORT.wire)
        assertEquals("check", SolutionSource.CHECK.wire)
        assertEquals("followup", SolutionSource.FOLLOWUP.wire)
        assertEquals(4, SolutionSource.entries.size)
    }

    @Test
    @DisplayName("未知取值回退到 AI")
    fun unknownFallsBackToAi() {
        assertEquals(SolutionSource.IMPORT, SolutionSource.fromWire("import"))
        assertEquals(SolutionSource.FOLLOWUP, SolutionSource.fromWire(" followup "))
        assertEquals(SolutionSource.AI, SolutionSource.fromWire("someone"))
        assertEquals(SolutionSource.AI, SolutionSource.fromWire(null))
    }
}
