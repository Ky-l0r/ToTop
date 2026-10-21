# 数据层设计说明（data）

本文件对应开发方案第五章「本地数据模型（Room）」与 FR-7.3 / FR-8.2 / FR-8.4 / FR-8.5 的落地说明。
源码位于 `app/src/main/java/com/totop/app/data/`，schema 导出到 `app/schemas/`。

## 一、表结构对照（15 张表）

| # | 表 | 文档 5.1 字段 | 实际列 | 主键 | 关键索引 / 约束 |
| --- | --- | --- | --- | --- | --- |
| 1 | question | id, subject, grade, stemText, optionsJson, hasFigure, imagePath, imageSha256, sourceType, createdAt | id, subject, grade, stemText, knownJson, ask, optionsJson, hasFigure, imagePath, imageSha256, sourceType, createdAt | id | imageSha256 唯一；(subject)、(createdAt) 普通索引 |
| 2 | mistake_record | id, questionId, studentStepsJson, firstErrorStepIdx, errorType, errorExplain, correctPathJson, finalAnswer, mode, createdAt | 同左 + recurrenceIndex | id | questionId 外键（CASCADE）；(questionId)、(createdAt)、(errorType) |
| 3 | knowledge_point | id, name, subject, parentId, chapter, orderIndex, definition, commonApproach, pitfallJson | 同左 | id | (parentId)、(subject)、(subject, chapter, orderIndex) |
| 4 | kpoint_edge | fromId, toId, type, weight | 同左 | (fromId, toId, type) | (toId)、(fromId)、(type) |
| 5 | mistake_kpoint | mistakeId, kpointId, confidence | 同左 | (mistakeId, kpointId) | **双索引 (kpointId)、(mistakeId)**；mistakeId 外键（CASCADE） |
| 6 | review_log | id, mistakeId, reviewedAt, selfResult | 同左 | id | mistakeId 外键（CASCADE）；(mistakeId)、(reviewedAt) |
| 7 | hint_log | id, mistakeId, level, createdAt | 同左 | id | mistakeId 外键（CASCADE）；(mistakeId)、(createdAt) |
| 8 | exercise | id, kpointId, stem, answer, difficulty, verified, createdAt | 同左 | id | (kpointId)、(kpointId, difficulty, verified)、(createdAt) |
| 9 | mastery | kpointId, score, historyJson, updatedAt | 同左 | kpointId | (score)、(updatedAt) |
| 10 | daily_stats | (date, subject), mistakeCount, kpointCount, hintCount, reviewDoneCount, studySeconds | 同左 | **(date, subject)** | (date) |
| 11 | report | id, type, periodKey, statsJson, aiComment, createdAt | 同左 | id | (type, periodKey) 唯一；(createdAt) |
| 12 | ai_trace | id, requestId, stage, model, promptVer, inputTokens, outputTokens, latencyMs, ok, errorMsg | 同左（+ createdAt） | id | requestId 唯一；(createdAt)、(stage) |
| 13 | settings | key, value | key, value, updatedAt | key | 无 |
| 14 | outbox | id, payloadJson, attempts, nextRetryAt | 同左（+ createdAt） | id | (nextRetryAt)、(createdAt) |
| 15 | cache_entry | (sha256, promptVer, model), resultJson, sizeBytes, lastAccessAt | 同左（+ createdAt） | **(sha256, promptVer, model)** | (lastAccessAt)【LRU 依据】、(createdAt) |

### 相对文档的补充列（均有明确原因）

| 表 | 补充列 | 原因 |
| --- | --- | --- |
| question | knownJson, ask | 领域模型 `Question` 需要「已知条件」与「所求」（阶段 A 必须输出），文档 5.1 未列出，缺这两列会让 AI 输出无处落地 |
| mistake_record | recurrenceIndex | FR-4.5「同知识点第 N 次出错」的复发提示，缺列则每次都要临时聚合 |
| ai_trace | createdAt | 设置页「近 30 天用量趋势」必须有时间维度，文档 5.1 仅列了耗时与 token |
| outbox | createdAt | 补传顺序与「最早入队优先」的审计依据 |
| cache_entry | createdAt | 区分「写入时间」与「最近访问时间」，TTL 与 LRU 语义不混用 |
| settings | updatedAt | 版本号类配置需要可审计的写入时间 |

所有时间戳统一为 **UTC 毫秒 Long**；`daily_stats.date` 与 `MasterySnapshot.dateEpochDayUtc` 为 **UTC 日序号**（自 1970-01-01 起的天数），与时区无关。展示层再转本地时区（文档 5.2）。

## 二、分页方案

全库统一使用 **LIMIT / OFFSET** 分页（`data/repo/PageRequest.kt`），**不引入 Paging3**：

1. 数据规模为个体学生的错题本，千级以内，OFFSET 扫描成本可忽略；
2. Paging3 需要额外依赖与整套 PagingSource 样板，与工程规范第 7 章 30MB 包体门禁冲突；
3. 同一套 DAO 方法同时提供 suspend 快照与 Flow 观察，导出、备份与测试可直接复用。

`PageRequest(page, size)` 校验参数，`offset = page * size`，单页上限 100，默认 20。

## 三、种子数据加载

`data/local/seed/`：

- `SeedModels.kt`：assets JSON 的反序列化模型与 `SeedSpec`（学科规格表，新增学科只加一行）；
- `SeedValidator.kt`：纯 Kotlin 校验，顺序为「受控 ID -> 学科一致 -> 父节点引用 -> 边类型与端点 -> 自环 -> 环检测」，
  环检测直接调用 domain 的 `CycleDetector`（DFS 三色标记）；`related` 横向环只告警不阻断；
- `SeedRepository.kt`：读取 assets -> 计算 sha256 指纹 -> 与 `settings` 中的版本号比对 -> 校验 -> **单事务整树替换** -> 写版本号。
  校验失败时**拒绝写入**、写 `settings.seed.rejected` 并打 `Log.e`（FR-7.3）。

幂等判据：`settings["seed.version.<subject>"]` 与「资产路径 + 内容的 sha256 指纹」一致即跳过。

## 四、缓存与 LRU

- 缓存键 = `sha256` + `promptVer` + `model`（方案 6.3），三者同为 `cache_entry` 联合主键；
- 磁盘上限 100MB（`CacheRepository.MAX_BYTES`），写入超限时按 `lastAccessAt` 升序淘汰到上限的 90%；
- 淘汰计划由纯函数 `LruEvictionPlan.plan` 计算，可脱离数据库单测；
- TTL 7 天（`CacheRepository.TTL_MS`），`purgeExpired` 清理；提示词版本升级用 `purgeOtherPromptVersions` 批量失效。

## 五、Repository 与「屏蔽本地/远端」

To Top 无自建后端（方案 C2），题目、错题、知识点、缓存都只有**本地单一数据源**。
因此仓库层不使用「远端 + 本地」双源合并，而是：

- 对上层只暴露领域模型与 `Flow`，Room 实体、JSON 列、枚举 wire 值全部封在 data 层；
- 未来若接入云同步，只在仓库内部增加「远端 -> 本地」写入路径，上层接口不变。

| 仓库 | 职责 |
| --- | --- |
| `QuestionRepository` | 题目 CRUD、imageSha256 去重保存、分页 |
| `MistakeRepository` | 错题 + 知识点归属（事务）、复习记录、提示记录、统计聚合 |
| `KnowledgeRepository` | 知识点树 / 边 / 掌握度；`catalog()` 提供 domain `KnowledgeCatalog` 的同步快照；90 天掌握度裁剪 |
| `CacheRepository` | AI 结果缓存、TTL、LRU |
| `SettingsRepository` | 非敏感键值配置（**禁止存 API Key**） |
| `AiTraceRepository` | 调用追踪与用量汇总 |
| `DailyStatsRepository` | 每日汇总 UPSERT / 累加 |
| `ReportRepository` | 周报 / 月报存取 |
| `OutboxRepository` | 离线补传队列与指数退避（1s / 2s / 4s，最多 3 次） |

## 六、测试

- JVM 单测（`app/src/test/java/com/totop/app/data/`，JUnit5）：
  `ConvertersTest`、`EntityMappersTest`、`SeedValidatorTest`、`SeedRepositoryTest`、
  `MasteryHistoryTest`、`LruEvictionPlanTest`、`PageRequestTest`、`OutboxBackoffTest`。
- Instrumentation（`app/src/androidTest/java/com/totop/app/data/`）：
  `MigrationTest`（MigrationTestHelper，校验 v1 全表与关键索引）、
  `DaoInstrumentationTest`（内存库：唯一索引、复合主键、外键级联、Flow、种子幂等）、
  `RepositoryInstrumentationTest`（事务、去重、90 天裁剪、分页）。

不使用 Robolectric：工程固定 JUnit5 单测框架（`de.mannodermaus.android-junit5`），Robolectric 仅支持 JUnit4 runner，
混用需改 Gradle，超出本任务范围；因此真实 SQLite 行为放在 androidTest，与 Robolectric 等价且零新增依赖。

## 七、迁移策略

`AppDatabase.VERSION = 1` 为首发版本，`exportSchema = true`，schema JSON 在 `app/schemas/com.totop.app.data.local.AppDatabase/1.json`。
后续任何结构变更必须：升 version、在 `DatabaseModule.MIGRATIONS` 追加 `Migration`、提交新 schema JSON、在 `MigrationTest` 补一条用例。
**不开启 `fallbackToDestructiveMigration`**：升级不得静默清空用户错题。
