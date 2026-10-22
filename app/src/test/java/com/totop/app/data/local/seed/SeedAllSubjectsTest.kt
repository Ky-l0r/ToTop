package com.totop.app.data.local.seed

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 十科种子资产的完整性测试。
 *
 * 为什么必须有这个测试：
 * 知识点资产是「数据」，编译器管不到它。少一个文件、ID 重复、边指向不存在的节点、
 * 前置关系成环——这些问题在运行期只会表现为「某科的知识点突然空了」，很难排查。
 * 这里在 JVM 单测里把十科全部读一遍并跑完整校验，构建期就能拦住。
 */
class SeedAllSubjectsTest {
    private val assetsRoot = File("src/main/assets")

    private fun readAsset(path: String): String? {
        val file = File(assetsRoot, path)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    @DisplayName("十科资产文件齐全")
    fun everySubjectHasAssetFiles() {
        val missing = mutableListOf<String>()
        SeedSpec.ALL.forEach { spec ->
            if (readAsset(spec.pointsAsset) == null) missing += spec.pointsAsset
            if (readAsset(spec.edgesAsset) == null) missing += spec.edgesAsset
        }
        assertTrue(missing.isEmpty(), "以下资产缺失：" + missing.joinToString("、"))
    }

    @Test
    @DisplayName("十科全部通过结构与环检测校验")
    fun everySubjectPassesValidation() {
        val problems = mutableListOf<String>()
        SeedSpec.ALL.forEach { spec ->
            val pointsRaw = readAsset(spec.pointsAsset)
            val edgesRaw = readAsset(spec.edgesAsset)
            if (pointsRaw == null || edgesRaw == null) {
                problems += spec.subject + "：资产缺失"
                return@forEach
            }
            val points = json.decodeFromString<List<SeedKnowledgePoint>>(pointsRaw)
            val edges = json.decodeFromString<List<SeedEdge>>(edgesRaw)
            when (val result = SeedValidator.validate(points, edges, spec.subject)) {
                is SeedValidation.Invalid -> problems += spec.subject + "：" + result.reason + " " + result.detail
                is SeedValidation.Valid -> {
                    if (result.points.size < MIN_POINTS) {
                        problems += spec.subject + "：知识点过少（" + result.points.size + "）"
                    }
                    if (result.edges.isEmpty()) {
                        problems += spec.subject + "：边表为空"
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "以下科目未通过校验：" + problems.joinToString("；"))
    }

    @Test
    @DisplayName("知识点总数与叶子数满足阶段 D 需求")
    fun totalPointsCoverAllTenSubjects() {
        var total = 0
        var leaves = 0
        SeedSpec.ALL.forEach { spec ->
            val raw = readAsset(spec.pointsAsset) ?: return@forEach
            val points = json.decodeFromString<List<SeedKnowledgePoint>>(raw)
            total += points.size
            leaves += points.count { it.parentId != null }
        }
        // 阶段 D 的候选只从叶子节点里挑，叶子太少会导致几乎选不到匹配项
        assertTrue(total >= 600, "知识点总数应覆盖十科，实际：" + total)
        assertTrue(leaves >= 500, "叶子节点应足够多，实际：" + leaves)
    }

    @Test
    @DisplayName("文件内 subject 与规格一致")
    fun subjectCodesMatchSpec() {
        SeedSpec.ALL.forEach { spec ->
            val raw = readAsset(spec.pointsAsset) ?: return@forEach
            val points = json.decodeFromString<List<SeedKnowledgePoint>>(raw)
            val mismatched = points.filter { it.subject != spec.subject }
            assertTrue(
                mismatched.isEmpty(),
                spec.subject + " 内有 subject 不一致的节点：" + mismatched.take(3).map { it.id },
            )
        }
    }

    @Test
    @DisplayName("所有节点 ID 满足受控约定")
    fun knownIdConventionHoldsForEverySubject() {
        SeedSpec.ALL.forEach { spec ->
            val raw = readAsset(spec.pointsAsset) ?: return@forEach
            val points = json.decodeFromString<List<SeedKnowledgePoint>>(raw)
            val bad = points.filterNot { com.totop.app.domain.model.KnowledgeIdConvention.isValid(it.id) }
            assertTrue(bad.isEmpty(), spec.subject + " 存在非法受控 ID：" + bad.take(3).map { it.id })
        }
    }

    @Test
    @DisplayName("每个叶子节点都有可读内容")
    fun everyLeafHasReadableContent() {
        // 知识点详情页要展示定义与方法，空字段会让页面看起来像坏掉了
        SeedSpec.ALL.forEach { spec ->
            val raw = readAsset(spec.pointsAsset) ?: return@forEach
            val points = json.decodeFromString<List<SeedKnowledgePoint>>(raw)
            val empty = points.filter { it.parentId != null && it.definition.isBlank() }
            assertTrue(empty.isEmpty(), spec.subject + " 有叶子节点缺少 definition：" + empty.take(3).map { it.id })
        }
    }

    @Test
    @DisplayName("种子规格覆盖十个科目")
    fun specCountIsTen() {
        assertEquals(10, SeedSpec.ALL.size)
        val subjects = SeedSpec.ALL.map { it.subject }.toSet()
        val expected =
            setOf(
                "chinese",
                "math",
                "english",
                "physics",
                "chemistry",
                "biology",
                "politics",
                "history",
                "geography",
                "technology",
            )
        assertEquals(expected, subjects, "科目集合应恰好是这十科")
    }

    private companion object {
        /** 单科最少知识点数，低于此值视为内容不足。 */
        const val MIN_POINTS: Int = 30
    }
}
