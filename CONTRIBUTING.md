# 贡献指南

感谢参与 To Top。本文说明分支策略、提交规范、代码要求与扩展方式。
所有参与者请先阅读 [README.md](README.md) 与 [docs/工程规范与构建说明.md](docs/工程规范与构建说明.md)。

## 一、硬性约束（不接受妥协）

以下条目来自《AI 辅助学习 App 开发方案》，任何 PR 触碰都会被拒绝：

1. 仅 Android 原生，Kotlin + Jetpack Compose。禁止 XML View，禁止跨端框架。
2. 禁止引入统计、广告、埋点、崩溃上报类 SDK（Firebase、友盟、Bugly、Crashlytics 等一律不允许）。
3. 禁止引入需要自建后端的依赖（Supabase、云函数、云 OCR 等）。
4. 不采集身份信息，不做账号体系。
5. 不做判卷与正确率统计。所有指标只能建立在错题侧。
6. 代码与文档中禁止使用 emoji。
7. 不在任何位置写入真实 API Key、签名文件或 local.properties 内容。
8. 依赖版本已在 gradle/libs.versions.toml 一次性锁定，不得随意变更；确需变更须单独说明原因。
9. 提示词、口语化规则表、图谱坐标、种子知识点等文案一律放 assets，不得硬编码进 Kotlin 源码。
10. 掌握度算法、推荐路径算法、间隔重复算法必须为纯 Kotlin 且带单元测试。

## 二、分支策略

采用 main + dev 双分支模型：

| 分支 | 用途 | 保护策略 |
| --- | --- | --- |
| main | 可发布分支，每次合并对应一次版本 | 禁止直接推送，只接受来自 dev 或 hotfix 的 PR |
| dev | 集成分支，日常开发目标分支 | 禁止直接推送，只接受来自功能分支的 PR |
| feat/xxx | 功能分支，从 dev 切出 | 合并后删除 |
| fix/xxx | 缺陷修复分支 | 合并后删除 |
| hotfix/xxx | 线上紧急修复，从 main 切出 | 修完同时合回 main 与 dev |
| docs/xxx | 纯文档改动 | 可只合 main |

标准流程：

    git checkout dev
    git pull
    git checkout -b feat/camera-preview
    # 开发与本地自测
    git add -A
    git commit -m "feat(solve): 增加拍照预览与对焦提示"
    git push -u origin feat/camera-preview
    # 在 GitHub 上向 dev 提交 PR

## 三、提交规范（Conventional Commits）

格式：

    <类型>(<范围>): <简述>

    <可选正文>

    <可选脚注>

类型取值：

| 类型 | 含义 | 示例 |
| --- | --- | --- |
| feat | 新增功能 | feat(solve): 支持相册导入单题 |
| fix | 缺陷修复 | fix(llm): 修复 SSE 断线后未释放连接 |
| refactor | 重构，不改变外部行为 | refactor(data): 抽取分页查询参数 |
| perf | 性能优化 | perf(mistakes): 列表改用分页查询 |
| docs | 文档 | docs: 补充提示词版本升级规则 |
| test | 测试 | test(domain): 补掌握度边界用例 |
| build | 构建与依赖 | build: 升级 KSP 至 1.9.25-1.0.20 |
| ci | 流水线 | ci: 增加 gitleaks 扫描任务 |
| chore | 杂项 | chore: 清理临时日志文件 |

范围建议使用模块名：solve、mistakes、knowledge、report、settings、data、domain、llm、assets、docs、ci。

要求：

- 简述用中文，不超过 50 个字符，结尾不加句号
- 一次提交只做一件事，禁止把格式化与功能混在一起
- 正文说明「为什么」，而不是复述「改了什么」

## 四、代码要求

### 4.1 命名约定

- Compose 页面以 `Screen` 结尾，例如 `MistakesScreen`
- UseCase 以 `UseCase` 结尾，例如 `SolveQuestionUseCase`
- DAO 以 `Dao` 结尾，例如 `MistakeDao`
- 纯函数式算法放 domain，返回不可变结果，不依赖 Android 框架

### 4.2 分层约束

- ViewModel 中禁止直接调用 Retrofit 或 OkHttp，网络一律经 Repository
- 界面层禁止直接访问 Room，数据一律经 Repository 或 UseCase
- domain 层不得依赖 Android SDK，保证可在 JVM 单测中运行

### 4.3 网络调用要求

任何新增的网络调用必须同时满足：

1. 携带 `client_request_id`，保证重试幂等
2. 可取消，页面销毁时中断连接
3. 失败走退避重试（1 秒、2 秒、4 秒，最多 3 次，仅对幂等请求）
4. 写入调用留痕表（模型名、token 用量、耗时、成功标志）
5. 对返回内容做严格反序列化校验，失败重试 1 次，仍失败不得入库

### 4.4 AI 输出的处理

- 提示词模板放 `app/src/main/assets/prompts/`，改动文本后必须同步升级 `prompt_version.json`
- 知识点只能映射既有受控 ID，禁止自由造词
- 错因类型只能取固定七类，禁止新增
- 解析失败时记录告警，不得静默丢弃

### 4.5 性能要求

- 不使用毛玻璃模糊、视差与大面积弹性动画
- 长列表使用分页查询，禁止一次查全表
- 图片一律先压缩再使用，列表使用缩略图
- 图表关闭动画并做数据点降采样（不超过 60 点）
- 图谱使用预计算坐标，禁止运行时力导向计算

### 4.6 测试要求

| 改动类型 | 要求 |
| --- | --- |
| domain 层逻辑 | 必须补单元测试，覆盖边界与异常 |
| 数据层查询 | 必须有 DAO 仪器测试 |
| 数据库结构变更 | 必须补迁移测试与 schema 快照 |
| 网络解析 | 必须用 MockWebServer 覆盖成功与失败分支 |

提交 PR 前请在本地跑通：

    ./gradlew :app:assembleDebug
    ./gradlew :app:testDebugUnitTest
    ./gradlew :app:lintDebug

## 五、PR 与 Issue

### 5.1 Issue

- 缺陷：使用「缺陷报告」模板，必须填写机型、系统版本、复现步骤、期望与实际结果
- 建议：使用「功能建议」模板，先描述真实使用场景，再谈方案
- 安全问题：不要公开提 Issue，按 [SECURITY.md](SECURITY.md) 私下披露

模板位于 `.github/ISSUE_TEMPLATE/`，其中配置了禁止空白 Issue 并给出安全披露入口。

### 5.2 PR

- 目标分支默认 dev；hotfix 才指向 main
- 使用仓库自带的 PR 模板，逐项勾选自查清单
- 涉及界面改动请附截图，注意遮挡个人信息
- 涉及提示词或模型路由的改动，必须填写模板中的「涉及 AI 调用的改动」一节
- 单个 PR 只解决一个问题，改动行数建议控制在 400 行以内

### 5.3 评审关注点

评审者按下列顺序检查：

1. 是否触碰第一节的硬性约束
2. 是否引入新的 token 消耗且无法关闭
3. 是否存在隐私风险（日志、导出、崩溃信息）
4. 是否在低端机上可行（内存、帧率、包体）
5. 是否有对应测试
6. 命名与分层是否符合约定

## 六、如何扩充学科知识点树

知识点树是应用统计与 AI 映射的基础，扩展时必须保持受控 ID 体系，否则 AI 会自由造词导致统计碎片化。

### 6.1 文件位置

| 文件 | 作用 |
| --- | --- |
| `app/src/main/assets/seed/knowledge_point_math.json` | 知识点节点（id、name、subject、parentId、chapter、orderIndex、definition、commonApproach、pitfalls） |
| `app/src/main/assets/seed/knowledge_point_edge_math.json` | 知识点关系边（fromId、toId、type、weight） |
| `app/src/main/assets/seed/kpoint_generic_pitfalls.json` | 通用易错点，按章节与知识点两级归类 |
| `app/src/main/assets/graph_layout/graph_layout_math.json` | 图谱预计算坐标 |
| `data/README.md` | 数据来源与授权说明，新增数据必须同步更新 |

### 6.2 ID 规则

- 格式固定为 `KP_<学科>_<四位序号>`，例如 `KP_MATH_0312`
- 序号一旦分配不得复用，删除节点后该编号作废
- 不同学科使用不同学科段，禁止跨学科共用编号
- 应用启动时会校验 ID 格式，格式非法将拒绝装载

### 6.3 新增一个学科的完整步骤

以新增物理为例：

1. 复制数学节点文件为 `knowledge_point_physics.json`，清空内容保留结构
2. 按章节顺序录入节点，`subject` 统一填 `physics`，ID 从 `KP_PHYS_0001` 开始顺序分配
3. 每个节点必须填写 `definition` 与 `commonApproach`，缺失会被种子校验拒绝
4. 编写关系边文件 `knowledge_point_edge_physics.json`，前置边方向为「前置 → 后继」
5. 前置关系必须人工整理，不得由 AI 生成或自动生效
6. 装载时执行环检测，检测到环会拒绝写入并记录日志，需修正数据后重试
7. 生成图谱坐标并写入 `graph_layout/`，单屏节点数控制在 80 以内
8. 更新 `data/README.md` 的来源与授权说明
9. 补一条种子校验的单元测试用例，确保新学科能被正确装载
10. 在 PR 中说明数据来源，确认未复制任何教材原文、教辅题目或商业题库

### 6.4 内容撰写要求

- 定义与解题思路须自行组织语言，不得直接摘抄教材或教辅原文
- 不得内置受版权保护的题库题目
- 易错点描述要具体到「错在哪一步」，不要写「容易做错」这类空话
- 所有内容禁止使用 emoji

### 6.5 不要做的事

- 不要为了凑数量而拆出无实际教学意义的节点
- 不要把同一个知识点在不同章节重复建节点，用 `related` 边表达关联
- 不要在提示词中列举全部知识点，只传检索到的候选清单，避免 token 膨胀

## 七、发布流程

1. 从 dev 合并到 main，CI 必须全绿
2. 更新 CHANGELOG.md，把 Unreleased 段落改为对应版本号并补日期
3. 打标签 `v<主>.<次>.<修订>`，推送标签触发 Release 工作流
4. Release 工作流使用 Secrets 中的签名材料构建 R8 包，并校验 30MB 体积门禁
5. 构建产物自动附加到 GitHub Release

签名材料只存在于 CI Secrets，禁止提交到仓库。
