package com.totop.app.data

import com.totop.app.data.local.seed.SeedAssetReader
import com.totop.app.data.local.seed.SeedRejectionReason
import com.totop.app.data.local.seed.SeedResult
import com.totop.app.data.local.seed.SeedSpec
import com.totop.app.data.local.seed.SeedValidation
import com.totop.app.data.local.seed.SeedValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 种子资产真实文件的 JVM 校验测试。
 *
 * 这里不启动 Room（那需要 instrumentation 或 Robolectric，见 androidTest 的 SeedInstrumentationTest），
 * 而是直接读取仓库里的 assets 文件做 **纯 JSON + 校验** 回归：
 * 资产一旦被改出环或改坏 ID，本测试立即失败。
 */
class SeedRepositoryTest {
    private val assetsRoot = File("src/main/assets")

    private fun readAsset(path: String): String? {
        val file = File(assetsRoot, path)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    private fun reader(paths: Map<String, String>): SeedAssetReader = SeedAssetReader { paths[it] }

    @Test
    @DisplayName("数学知识点树与边表真实资产通过校验（无环）")
    fun mathAssetsValid() {
        val pointsRaw = readAsset(SeedSpec.MATH.pointsAsset)
        val edgesRaw = readAsset(SeedSpec.MATH.edgesAsset)
        assertNotNull(pointsRaw, "知识点树资产缺失：" + SeedSpec.MATH.pointsAsset)
        assertNotNull(edgesRaw, "边表资产缺失：" + SeedSpec.MATH.edgesAsset)

        val json =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        val points = json.decodeFromString<List<com.totop.app.data.local.seed.SeedKnowledgePoint>>(pointsRaw!!)
        val edges = json.decodeFromString<List<com.totop.app.data.local.seed.SeedEdge>>(edgesRaw!!)

        val result = SeedValidator.validate(points, edges, "math")
        assertTrue(result is SeedValidation.Valid, "内置资产必须通过环检测与结构校验，实际：" + result)
        val valid = result as SeedValidation.Valid
        assertTrue(valid.points.size >= 100, "知识点数量应达到产品要求，实际：" + valid.points.size)
        assertTrue(valid.edges.isNotEmpty(), "边表不得为空")
        assertTrue(valid.points.all { it.hasControlledId }, "所有知识点 ID 必须是受控 ID")
        assertTrue(valid.edges.all { it.weight > 0.0 })
    }

    @Test
    @DisplayName("资产缺失时 SeedAssetReader 返回 null（不抛异常）")
    fun missingAssetReturnsNull() {
        val reader = reader(emptyMap())
        assertEquals(null, reader.read(SeedSpec.MATH.pointsAsset))
    }

    @Test
    @DisplayName("成环的边表会被拒绝写入（构造资源模拟）")
    fun cyclicAssetRejected() {
        val pointsJson =
            """
            [
              {"id":"KP_MATH_0001","name":"A","subject":"math","chapter":"c","orderIndex":1},
              {"id":"KP_MATH_0002","name":"B","subject":"math","chapter":"c","orderIndex":2},
              {"id":"KP_MATH_0003","name":"C","subject":"math","chapter":"c","orderIndex":3}
            ]
            """.trimIndent()
        val edgesJson =
            """
            [
              {"fromId":"KP_MATH_0001","toId":"KP_MATH_0002","type":"prerequisite","weight":1.0},
              {"fromId":"KP_MATH_0002","toId":"KP_MATH_0003","type":"prerequisite","weight":1.0},
              {"fromId":"KP_MATH_0003","toId":"KP_MATH_0001","type":"prerequisite","weight":1.0}
            ]
            """.trimIndent()
        val json =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        val points = json.decodeFromString<List<com.totop.app.data.local.seed.SeedKnowledgePoint>>(pointsJson)
        val edges = json.decodeFromString<List<com.totop.app.data.local.seed.SeedEdge>>(edgesJson)
        val result = SeedValidator.validate(points, edges, "math")
        assertTrue(result is SeedValidation.Invalid)
        assertEquals(SeedRejectionReason.CYCLE, (result as SeedValidation.Invalid).reason)
    }

    @Test
    @DisplayName("SeedResult 语义：Applied / Skipped / Rejected / Failed 可区分")
    fun seedResultSemantics() {
        val applied: SeedResult = SeedResult.Applied("math", 170, 172, emptyList())
        val skipped: SeedResult = SeedResult.Skipped("math", "abc")
        val rejected: SeedResult =
            SeedResult.Rejected(
                "math",
                SeedRejectionReason.CYCLE,
                "detail",
                listOf("A", "B", "A"),
            )
        val failed: SeedResult = SeedResult.Failed("math", "missing")
        assertTrue(applied is SeedResult.Applied)
        assertTrue(skipped is SeedResult.Skipped)
        assertTrue(rejected is SeedResult.Rejected)
        assertTrue(failed is SeedResult.Failed)
        assertEquals(3, (rejected as SeedResult.Rejected).cycle.size)
    }
}
