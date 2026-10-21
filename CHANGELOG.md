# Changelog

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 第二轮真机反馈修复（本次）

#### Fixed

- **框选一松手就消失**：`CropOverlay` 用 `pointerInput(Unit)` 捕获了首次组合时的 `rect`
  （几乎总是 null），`onDragEnd` 读到的永远是 null，于是判定「没框」并立刻回调清空。
  现改为拖动过程只维护本地像素矩形，松手时用本地结果提交，并支持拖四角调整、拖内部平移。
- **框选时图片太糊**：预览用的是列表缩略图（长边 320），铺满全屏必然糊。
  现单独解码长边 1280 的预览图，与上传图同分辨率。
- **拍完照不进入框选**：新增 `pendingPreview` 状态，拍照或相册导入完成后自动打开框选页。
- **流程顺序错误**：框选页「确认」原本直接发送题目。现改为回到对话页让用户写思路，
  是否发送由用户决定。
- **发送后输入框残留文字**：`updateStudentProcess` 与发送逻辑未清空草稿。
  现发送即清空，文字已进入消息流。
- **追问变成重新解题**：旧实现每次发送都重跑四阶段，得到一套全新解答。
  现新增 `FollowUpGateway`，已有解答后输入的文字带上下文走追问通道。
- **切换页面回来对话消失**：消息只在内存里。现新增 `SolveSessionRepository`
  把会话落盘，冷启动自动恢复最近一条；并提供历史对话列表可回看。
- **错题本与薄弱知识点不同步**：`persist` 在 `firstError == null` 时整体 return，
  且从不重算掌握度。现题目总是保存、无错因也写一条待复习记录，
  并在写入后调用 `MasteryRecorder` 立即重算相关知识点。
- **导出后找不到文件**：PDF 写在 `cacheDir`（应用私有临时目录，文件管理器不可见）。
  现发布到系统「下载/ToTop」，并新增「打开」「分享 / 打印」两个真实出口。
- **只有数学有知识点**：`SeedRepository` 每次加载都 `deleteAllKnowledgePoints()` 整表清空，
  多科目会互相删除。现改为按科目替换（新增 `deleteKnowledgePointsOfSubject` /
  `deleteEdgesOfSubject`）。
- **学情报告右上角按钮奇怪**：原为一个不可点击的 16dp Refresh 图标。现改为真正的
  「重新生成」文字按钮；AI 解读开关补足宽度，避免被 Row 挤压后裁切。
- **复习资料科目选不全**：十个科目在 `Row` 里横向溢出，屏幕外的点不到。
  现改用 `FlowRow` 换行排布。
- **日统计科目串味**：`DailyStatsWorker` 把当天全部错题都记在 `math` 科目下。
  现按科目分行统计，并单列合计行。
- **科目归一错误**：识别不了科目时一律回退 `math`，使综合题悄悄计入数学。
  现新增 `Subjects.normalize`，兜底落到「通用」。

#### Added

- **十科知识点树**：语文 / 数学 / 英语 / 物理 / 化学 / 生物 / 政治 / 历史 / 地理 / 技术（浙江选考）。
  共 740 个知识点、668 条前置边，全部通过环检测与受控 ID 校验。
- `MasteryRecorder`：解题落库后即时重算掌握度，与每日 Worker 共用同一套公式。
- `SolveSessionRepository`：多对话本地持久化（原子写、单条损坏不影响其余）。
- `FollowUpGateway`：基于既有解答上下文的追问通道。
- `ExportSink`：导出产物发布到系统下载目录并提供打开/分享/打印意图。
- 阶段 A 识别出科目后，阶段 D 的候选知识点清单收敛到同一科目，避免跨科错误归属。
- 单测 `SolveSessionTest`（科目归一、快照往返、会话标题、追问上下文）与
  `SeedAllSubjectsTest`（十科资产完整性、受控 ID、叶节点内容非空）。

### 第一轮修复

#### Fixed

- 解题失败误报：契约校验把「学生未填写解题思路导致 studentSteps 为空」和「非数学科目无知识点树
  导致 knowledgePoints 为空」当作致命缺失，使不填思路的题与所有非数学题必然失败。
  现仅 `question` 与 `standardSolution` 缺失算致命，其余降级为提示。
- 知识点候选清单为空时不再空耗一次阶段 D 请求，直接跳过并给出说明。
- 合并阶段校验失败时统一误报为「阶段 D 失败」，掩盖真实原因；现按实际阶段报告。
- 拍题界面未接线：全屏相机与框选组件已实现但路由仍指向旧页面，现由 `SolveRoute` 统一承载。
- 多页拍摄失效：每次导入新页都会覆盖前一页的状态，现改为页列表累积。
- 拍完照直接点发送按钮无响应：状态为空时 `send()` 静默返回，现回落到草稿组装。
- 解题思路不同步：`updateStudentProcess` 只写草稿不写状态，导致阶段 C 收不到学生作答，
  错因永远无法定位。
- 切换页面卡顿：导航默认过渡动画与目标页首帧数据库查询叠加，现关闭四条过渡。

### Changed

- 解题界面改为对话式布局：图片条位于输入框正上方，输入框用于填写解题思路，
  OCR 识别结果不再显示。
- 四阶段进度由整张卡片压缩为单行状态文字，结果合并为最多三条助手消息。
- 界面文案移除「命中缓存 0 token」「首字 xx ms」等内部实现细节。
- 深色模式默认关闭，固定为淡紫主色 + 白色背景。

### Added

- M2-M7 功能实现：拍照与离线 OCR、四阶段 AI 链路与流式渲染、错题本与复习闭环、
  掌握度可视化（雷达/热力/趋势/错因分布）、周报月报与本地长图 PDF、三档引导模式、系统 TTS、
  知识点受控树与关系边、学习路径推荐、Markdown/CSV/Anki(TSV) 导出、全量备份与恢复、
  WorkManager 四类后台任务（日汇总/重试/缓存清理/复习提醒）。
- 界面全量落地：解题页、错题本、复习页、掌握度页、知识点详情页、周报页、设置页、备份页。
- 领域层用例编排：SolveQuestion / LocateError / BuildMastery / BuildWeeklyReport /
  BuildLearningPath / GenerateExercise / ExportData 七个 UseCase 与领域端口层。
- 本地数据层：Room 15 张表、13 个 DAO、种子数据装载与环检测校验。
- 模型网关：SSE 流式客户端、加密密钥存储、分级路由、双层缓存、调用留痕 ai_trace。
- 文档 docs/偏离记录.md：记录 KaTeX 兜底、Canvas 图表、Anki TSV、Kotlin 版本等九项实现偏离。
- 文档 docs/构建与验证清单.md：真实构建环境与实测记录。

### Changed

- README 里程碑状态表按实际交付更新（M2-M5、M7 完成；M6、M8 部分完成）。

- 仓库文档：README（配 Key 指引、成本估算、支持机型、本地模式、隐私与免责声明）、
  CONTRIBUTING、SECURITY、THIRD_PARTY。
- 架构文档 docs/架构.md、接口契约 docs/API契约.md、提示词规范 docs/提示词规范.md。
- 低端机测试清单扩充至 30 项检查，覆盖启动、滚动、内存、WebView 复用、断网恢复、后台被杀、包体。
- Issue 模板（缺陷报告、功能建议）与 PR 模板，配置了安全披露入口与空白 Issue 禁用。
- Dependabot 每月依赖更新提案，主版本升级不自动合并。
- 引入 ktlint 12.1.1 与 detekt 1.23.6 作为静态检查门禁，配置位于 config/detekt/detekt.yml。
- CI 拆分为密钥扫描、代码规范、构建与测试三个作业。
- Release 工作流增加质量门禁、签名材料清理与产物存在性校验。

### Changed

- CI 在原有的 Android Lint、单元测试、assembleDebug 基础上，增加 ktlintCheck 与 detekt。

## [0.1.0] - 2025-01-01

### Added

- M1 工程骨架：Gradle KTS 单模块工程（Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.25）。
- 统一版本目录 gradle/libs.versions.toml，一次性锁定全部依赖，后续里程碑不得改动既有版本。
- Hilt 2.51.1 + KSP、Room 2.6.1 + KSP 依赖接入。
- Jetpack Compose（BOM 2024.09.03）+ Material3 + Navigation Compose 导航壳。
- 8 条导航路由占位：solve、mistakes、knowledge、report、settings、kpoint_detail、review、backup。
- Compose 主题（Color/Type/Theme），禁用动态取色、毛玻璃与大面积动画。
- R8 与打包配置：arm64-v8a 优先、资源压缩、ProGuard 规则覆盖序列化与 Retrofit。
- MIT 许可证与工程规范文件（.gitignore、.editorconfig、local.properties.example）。
- CI 工作流（PR 触发：gitleaks、lint、单元测试、assembleDebug）与 Release 工作流（tag 触发、R8 构建、30MB 体积门禁）。
- 文档：docs/工程规范与构建说明.md、docs/低端机测试清单.md。

### Changed

- Kotlin 1.9.24 升级至 1.9.25，KSP 同步至 1.9.25-1.0.20，Compose 编译器升级至 1.5.15。
  原因：1.9.24 后端在「挂起函数 + 流式回调 + 内联 lambda + 循环跳出」组合下必然抛出
  编译器内部异常，属上游已修复缺陷。1.9.x 补丁线内 API 一致，无迁移成本。

### Security

- 关闭系统云备份与设备迁移对应用数据的采集。
- 网络默认仅允许 HTTPS，明文仅为本地回环地址放行。
