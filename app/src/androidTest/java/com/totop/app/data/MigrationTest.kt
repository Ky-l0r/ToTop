package com.totop.app.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.di.DatabaseModule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Room schema 与迁移测试（开发方案第五章 / 工程规范第 4 节）。
 *
 * 当前 version = 1 是首发版本，尚无迁移可跑，因此本文件做三件事：
 * 1. 用 MigrationTestHelper 按 v1 建库，断言 15 张表与关键索引确实存在（schema 契约回归）；
 * 2. 断言 DatabaseModule.MIGRATIONS 与版本号一致——升版本必须补迁移，禁止 destructive migration；
 * 3. 直接读取 app/schemas 导出的 1.json，提前验证「schema 目录 -> androidTest assets」链路可用，
 *    未来 runMigrationsAndValidate 正是依赖这条链路。
 *
 * 后续每次升版本，只需追加一条用例（模式固定）：
 *
 *   @Test
 *   @Throws(IOException::class)
 *   fun migrate1To2() {
 *       helper.createDatabase(TEST_DB, 1).use { db ->
 *           db.execSQL("INSERT INTO settings (key, value, updatedAt) VALUES ('mode', 'GUIDED', 1)")
 *       }
 *       helper.runMigrationsAndValidate(TEST_DB, 2, true, DatabaseModule.MIGRATIONS)
 *   }
 *
 * 运行：./gradlew :app:connectedDebugAndroidTest（需要设备或模拟器）。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Before
    fun setUp() {
        // 每个用例从干净的库文件开始，避免上次运行残留影响 schema 断言
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    /** 读出全部表名。 */
    private fun tableNames(): List<String> {
        val names = mutableListOf<String>()
        helper.createDatabase(TEST_DB, AppDatabase.VERSION).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
                while (cursor.moveToNext()) {
                    names += cursor.getString(0)
                }
            }
        }
        return names
    }

    /** 读出全部索引：表名 -> (索引名, 是否唯一)。 */
    private fun indices(): List<Triple<String, String, Boolean>> {
        val rows = mutableListOf<Triple<String, String, Boolean>>()
        helper.createDatabase(TEST_DB, AppDatabase.VERSION).use { db ->
            db.query("SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'index'").use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val table = cursor.getString(1) ?: continue
                    val sql = cursor.getString(2) ?: ""
                    rows += Triple(table, name, sql.contains("UNIQUE", ignoreCase = true))
                }
            }
        }
        return rows
    }

    @Test
    @Throws(IOException::class)
    fun createsAllFifteenTables() {
        val tables = tableNames()
        AppDatabase.Tables.ALL.forEach { table ->
            assertTrue("v1 应包含表 " + table + "，实际：" + tables, table in tables)
        }
        assertEquals(15, AppDatabase.Tables.ALL.size)
    }

    @Test
    @Throws(IOException::class)
    fun questionImageSha256IsUnique() {
        val rows = indices().filter { it.first == AppDatabase.Tables.QUESTION }
        assertTrue(
            "question 必须有 imageSha256 唯一索引，实际：" + rows.map { it.second },
            rows.any { it.second.contains("imageSha256") && it.third },
        )
    }

    @Test
    @Throws(IOException::class)
    fun mistakeKpointHasDoubleIndices() {
        val rows = indices().filter { it.first == AppDatabase.Tables.MISTAKE_KPOINT }
        assertTrue(
            "mistake_kpoint 必须有 kpointId 索引，实际：" + rows.map { it.second },
            rows.any { it.second.contains("kpointId") },
        )
        assertTrue(
            "mistake_kpoint 必须有 mistakeId 索引，实际：" + rows.map { it.second },
            rows.any { it.second.contains("mistakeId") },
        )
    }

    @Test
    @Throws(IOException::class)
    fun reportPeriodIsUnique() {
        val rows = indices().filter { it.first == AppDatabase.Tables.REPORT }
        assertTrue(
            "report 的 (type, periodKey) 必须唯一，实际：" + rows.map { it.second },
            rows.any { it.second.contains("type") && it.second.contains("periodKey") && it.third },
        )
    }

    @Test
    @Throws(IOException::class)
    fun compositePrimaryKeysDeclaredInSchema() {
        val createStatements = mutableMapOf<String, String>()
        helper.createDatabase(TEST_DB, AppDatabase.VERSION).use { db ->
            db.query("SELECT name, sql FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    createStatements[name] = cursor.getString(1) ?: ""
                }
            }
        }
        val dailyStats = createStatements[AppDatabase.Tables.DAILY_STATS].orEmpty()
        assertTrue("daily_stats 建表语句异常：" + dailyStats, dailyStats.contains("PRIMARY KEY"))
        assertTrue(dailyStats.contains("date") && dailyStats.contains("subject"))

        val cacheEntry = createStatements[AppDatabase.Tables.CACHE_ENTRY].orEmpty()
        assertTrue("cache_entry 建表语句异常：" + cacheEntry, cacheEntry.contains("PRIMARY KEY"))
        assertTrue(
            cacheEntry.contains("sha256") && cacheEntry.contains("promptVer") && cacheEntry.contains("model"),
        )
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To2KeepsOldRows() {
        // v2 给 question 加了两列：selfMasteredAt（可空）与 imagePathsJson（默认空数组）。
        // 断言点不是「列存在」（runMigrationsAndValidate 会校验 schema），
        // 而是「旧记录迁移后仍然读得出来，且两个新列有合理默认值」。
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO question (id, subject, grade, stemText, knownJson, ask, optionsJson, " +
                    "hasFigure, imagePath, imageSha256, sourceType, createdAt) " +
                    "VALUES ('q1', 'math', '高一', '旧题干', '[]', '', '[]', 0, '/tmp/a.jpg', 'sha1', 'MANUAL', 1)",
            )
        }
        @Suppress("SpreadOperator")
        helper.runMigrationsAndValidate(TEST_DB, 2, true, *DatabaseModule.MIGRATIONS).use { db ->
            db.query("SELECT stemText, selfMasteredAt, imagePathsJson FROM question WHERE id = 'q1'").use { cursor ->
                assertTrue("迁移后旧记录必须还在", cursor.moveToFirst())
                assertEquals("旧题干", cursor.getString(0))
                assertTrue("selfMasteredAt 默认为 null", cursor.isNull(1))
                assertEquals("[]", cursor.getString(2))
            }
        }
    }

    @Test
    fun migrationsArrayMatchesVersion() {
        // 首发版本无迁移；升到 v2 时本断言会提醒补迁移（MIGRATIONS 必须非空）
        if (AppDatabase.VERSION == 1) {
            assertEquals(0, DatabaseModule.MIGRATIONS.size)
        } else {
            assertTrue(
                "版本 " + AppDatabase.VERSION + " 必须提供迁移，禁止 destructive migration",
                DatabaseModule.MIGRATIONS.isNotEmpty(),
            )
        }
    }

    @Test
    fun exportedSchemaV1IsReadableAndComplete() {
        // app/build.gradle.kts 已把 schemas 目录追加为 androidTest assets，
        // 这里提前验证链路，未来 runMigrationsAndValidate 依赖同一条路径。
        val path = "com.totop.app.data.local.AppDatabase/1.json"
        val raw = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val databaseJson = JSONObject(raw).getJSONObject("database")
        val entities = databaseJson.getJSONArray("entities")
        assertEquals("schema v1 应包含 15 张表", 15, entities.length())
        assertEquals(1, databaseJson.getInt("version"))
        val names = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
        AppDatabase.Tables.ALL.forEach { table ->
            assertTrue("schema v1 缺少表 " + table + "，实际：" + names, table in names)
        }
    }

    private companion object {
        const val TEST_DB = "totop-migration-test.db"
    }
}
