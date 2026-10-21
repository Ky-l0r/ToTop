package com.totop.app.data.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 真实 assets 提示词的契约测试（开发方案 6.3 / 12.1 第 3 条）。
 *
 * 提示词文件归 assets 成员所有，本测试只读不写：
 * 目的是在提示词升级（promptVersion 变化）或文件改名时立刻报警，
 * 避免「客户端读不到模板」这种只在上线后才暴露的问题。
 *
 * 单测工作目录为模块根目录（app/），因此按 src/main/assets 定位；
 * 若在其他工作目录下运行则自动跳过，不会误报失败。
 */
class AssetsPromptContractTest {
    private fun assetsDir(): File? {
        val candidates =
            listOf(
                File("src/main/assets/prompts"),
                File("app/src/main/assets/prompts"),
            )
        return candidates.firstOrNull { it.isDirectory }
    }

    @Test
    @DisplayName("真实 prompt_version.json 可被 PromptRepository 解析，四阶段文件齐全")
    fun realAssetsReadable() {
        val dir = assetsDir()
        assumeTrue(dir != null, "当前工作目录下找不到 src/main/assets/prompts，跳过")

        val reader = AssetReader { path -> File(dir!!.parentFile, path).readText(Charsets.UTF_8) }
        val repository = PromptRepository(reader)

        val version = repository.promptVersion()
        assertTrue(version.isNotBlank(), "promptVersion 不得为空")
        assertTrue(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")), "提示词版本应为语义化版本：" + version)

        val files = repository.stageFiles()
        assertEquals(4, files.size, "四阶段模板必须全部登记")
        files.forEach { (stage, file) ->
            val template = repository.template(stage)
            assertTrue(template.isNotBlank(), "阶段 " + stage.wire + " 模板为空：" + file)
            assertTrue(template.contains("## "), "阶段 " + stage.wire + " 模板缺少章节结构")
        }
    }

    @Test
    @DisplayName("真实模板拼装后：阶段 B 不含学生过程，阶段 C 含学生过程，阶段 D 含候选清单")
    fun realAssetsBoundaries() {
        val dir = assetsDir()
        assumeTrue(dir != null, "当前工作目录下找不到 src/main/assets/prompts，跳过")

        val reader = AssetReader { path -> File(dir!!.parentFile, path).readText(Charsets.UTF_8) }
        val repository = PromptRepository(reader)
        val question =
            com.totop.app.domain.model.AiQuestion(
                subject = "math",
                grade = "高一",
                stem = "求 f(x)=x^2-2x+1 在 [0,3] 上的最小值",
            )
        val solution =
            com.totop.app.domain.model.AiStandardSolution(
                steps = listOf(com.totop.app.domain.model.AiSolutionStep(1, "配方", "顶点式")),
                finalAnswer = "0",
                method = "配方法",
            )

        val stageB = repository.assemble(StageInput.Solve(question))
        assertTrue(PromptGuard.STUDENT_PROCESS_KEYS.none { stageB.payloadKeys.contains(it) })

        val stageC = repository.assemble(StageInput.LocateError(question, "我直接代入 x=0", solution))
        assertTrue(stageC.userPrompt.contains("我直接代入 x=0"))

        val stageD =
            repository.assemble(
                StageInput.AssignKpoints(question, solution, listOf(CandidateKpoint("KP_MATH_0001", "二次函数", "函数"))),
            )
        assertTrue(stageD.userPrompt.contains("KP_MATH_0001"))
    }
}
