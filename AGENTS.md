# AGENTS.md

本文件是 To Top 仓库对**所有 AI 编码代理**（DSH、Claude Code、Codex、Cursor、Copilot 等）的强制约定。
人类贡献者请读 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 一、这份文件怎么用

1. 每次开工前先读本文件，再按需读：
   - [CONTRIBUTING.md](CONTRIBUTING.md)：硬性约束、提交规范
   - [docs/架构.md](docs/架构.md)：分层与依赖方向
   - [docs/工程规范与构建说明.md](docs/工程规范与构建说明.md)：版本、环境、模块结构
   - [docs/偏离记录.md](docs/偏离记录.md)：**为什么代码长这样**，最有参考价值
2. 冲突时的优先级：**用户当前指令 > 本文件 > CONTRIBUTING.md / docs/* > README.md**。
   发现冲突要报告，不得静默选一边。
3. 本文件不是建议，是门禁。做不到时停下来问，不要绕过。

---

## 二、一次任务的完成定义

"改完了"不等于完成。以下六项全部达成才算完成，缺哪项就在汇报里明说卡在哪：

1. 改动已实现，范围与用户要求一致，没有夹带。
2. 本地验证通过，汇报里贴的是**真实命令输出**。
3. 文档已按需同步（见第八章）。
4. 已提交，提交信息符合 Conventional Commits。
5. 已推送到 GitHub 并确认成功（见第三章）。
6. 已向用户汇报：改了什么、为什么、验证结果、遗留风险。

---

## 三、每次更新完自动推送到 GitHub（硬性要求）

### 3.1 触发条件

- 任何一次代码、资源、文档、构建脚本的改动，**验证通过后立即提交并推送**，不需要用户再提醒。
- 用户说"先别推""只改不提交"时，以用户指令为准，并在汇报中说明未推送。
- 只读任务（答疑、看代码、查资料）不产生提交，不要为了"有产出"去改文件。

### 3.2 推送前自查（任何一条不过就停）

- `git status --short` 里每个文件都是本次预期改动，没有临时脚本、日志、截图、构建产物。
- 没有 `local.properties`、`*.jks`、`secrets.properties`、真实 API Key。
  真实密钥一旦进入工作区或历史，**立即停止并告知用户**，不要自行改写历史。
- 提交信息格式正确：中文、简述不超过 50 字、结尾不加句号。
- 本地验证已按第七章跑过。
- 当前分支就是要推的分支；不确定就先问。

### 3.3 标准命令

```bash
git status --short
git add <明确路径>        # 仅当上面输出里没有意外文件时，才可 git add -A
git commit -m "docs: 新增 AGENTS.md 规范 AI 代理行为"
git fetch origin
git status -sb            # 确认领先/落后关系
git push origin HEAD      # 本机若报证书吊销错误，改用：git -c http.sslBackend=schannel push origin HEAD
git log --oneline -1
git status -sb            # 确认无 ahead / behind
```

- 本机当前的推送通道有已知问题，见 3.5.1；报错先对号入座，不要盲试。
- 远端领先且本地没有未推送冲突时，用 `git pull --rebase origin <branch>` 后再推。
- 推送成功以 `git status -sb` 显示 `## <branch>...origin/<branch>` 且无 ahead 为准。

### 3.4 禁止事项

- 禁止 `git push --force` / `--force-with-lease`，禁止改写已推送的历史（rebase / amend 已推送提交）。
- 禁止 `git commit --no-verify` 跳过钩子。
- 禁止"验证没过先推上去让 CI 看看"。
- 禁止推送 tag、创建 Release、合并分支、删除分支，除非用户明确要求。
- 禁止推送密钥、签名材料、`.agent-teams/` 状态、`build/` 产物。
- 禁止在推送失败时反复重试或换方式绕过；失败就停下来说明原因。

### 3.5 失败处理

| 现象 | 处理 |
| --- | --- |
| push 被拒（non-fast-forward） | 停止，说明本地与远端差异；经用户确认后再 `pull --rebase`；绝不强推 |
| 认证失败 / 无凭据 | 停止，提示用户在本地完成 `git push` 或配置凭据；不要把 token 写进任何文件 |
| 网络不可用 | 停止，说明提交已在本地、网络恢复后再推 |
| 推送后 CI 变红 | 在下一轮优先修复；不得把这轮改动当作已完成 |

### 3.5.1 本机推送通道实测（2026-10-27）

推送失败的报错有**两种**，成因与解法完全不同。先看报错文本再动手，不要一律当成"网络不通"。

**一、证书吊销检查失败（本机当前的主因）**

    fatal: unable to access 'https://github.com/Ky-l0r/ToTop.git/':
    schannel: next InitializeSecurityContext failed: CRYPT_E_NO_REVOCATION_CHECK (0x80092012)

`github.com:443` 的 TCP 是通的，卡在 TLS 握手的证书吊销列表查询。改凭据、改 remote、
配代理、配 SSH key 都无效。本机实测结论：

| 命令 | 结果 |
| --- | --- |
| `git push`（默认） | 失败 |
| `git -c http.schannelCheckRevoke=false push` | 失败（全局 gitconfig 里已有的该项不起作用） |
| `git -c http.sslBackend=schannel push` | **成功**，可稳定复现 |
| 改用系统 `C:\Program Files\Git\cmd\git.exe` | 成功 |

所以本机执行推送用：

    git -c http.sslBackend=schannel push origin HEAD

代价是不再查询证书吊销状态，证书链本身仍会校验。这是本机的临时绕过，
根因应在环境侧修（统一 git 版本或补齐 CA），**不是仓库该承担的配置**，
因此不要把它写进 `.git/config` 提交上去。

**二、连接超时（网络确实不通）**

    error: RPC failed; curl 28 Failed to connect to github.com:443 after 21052 ms
    fatal: expected flush after ref listing

这才是真·网络不通，解法是开代理或配 SSH key。

两种情况的共同要求：**失败就停下来说明原因**，把提交留在本地。
不要反复重试同一命令，不要改 `.git/config` 的 remote、不要写死代理地址、不要把 token 塞进 URL。

### 3.6 分支现状

- 主干只有 `main`，本地与 origin 一致。CONTRIBUTING.md 描述的 main+dev 双分支是**目标模型，尚未落地**。
- origin 上的 `dependabot/*` 分支由机器人自动创建，**不要在上面工作，也不要手动清理**。
- 默认在**当前分支**（通常是 main）提交与推送；不擅自新建分支、切分支、合并分支。
- 要动分支结构（建 dev、开 PR、合并）必须先问。

---

## 四、不要过度发挥（不做"雷霆大思考"）

本章是本文件最重要的部分。项目要的是**准确完成被要求的那一件事**，不是看起来更专业。

### 4.1 默认行为

- 只改与本次要求直接相关的文件。改到第三个无关文件时停下来问。
- 不顺手重构、不顺手格式化全库、不顺手改名、不顺手升级依赖、不顺手补抽象层、不顺手加配置开关。
- 不为"以后可能需要"预留扩展点，需要时再加。
- 不引入新依赖去解决手写十行就能解决的问题。
- 不把简单逻辑包装成 UseCase + Repository + Strategy 三层。
- 不写"为将来的并发优化预留"这类空壳代码。

### 4.2 不许编造

- 不许声称跑过没跑的命令，不许编造测试数字、构建耗时、CI 结论。
- 没验证的写"未执行"或"未验证"，并说明原因（例如需要真机）。
- 不确定 API 是否存在时去读源码或依赖文档，不要凭印象写。

### 4.3 需求缺口：问，不要猜

- 用户只描述了 80% 时，问清剩下 20%，不要用"通常都这么做"补齐。
- 同一需求有多种合理解法且代价不同（改 UI 还是改数据、加字段还是加表、前端算还是落库）时，
  列出 2-3 个选项与各自代价，让用户决定。
- 涉及**产品取舍**的一律问：文案口径、统计口径、功能边界、交互流程。
  本项目的口径写在 docs/偏离记录.md，不许自行发明新口径。
- 用户的要求会触碰 CONTRIBUTING.md 的硬性约束时，先指出冲突，等指示。

### 4.4 什么情况可以自行决定

- 拼写、标点、明显笔误、格式化、import 顺序、注释措辞。
- 编译错误、lint 报错的直接修法。
- 已明确要求改动的必要配套（新增页面要接导航、新增文案要进 assets）。
- 本文件或 docs 已写明处理方式的重复问题。

### 4.5 怎么问

- 一次问完，不要挤牙膏；给出选项与默认建议。
- 问题里包含：你要做什么、卡在哪、可选方案、各自代价。
- 问完就停。不要一边提问一边按自己的猜测把代码改了。

### 4.6 决策先行，不做「半件」

- 一个需求如果只能做一半就会造成前后不一致（例如只换图不换题干、只落一半数据再改表），
  先停下来把另一半的代价说清楚，等指示；不要先落地一半再补。
- 加表、加字段之前先问「用现有表能不能满足」。能复用就复用，但必须在
  `docs/偏离记录.md` 写清复用的代价（例如专注时长写进 `daily_stats` 的保留学科键）。
- 同类问题第二次出现时，优先改规范或抽公共件，而不是再复制一份实现。

---

## 五、代码规范

ktlint 与 detekt 是硬门禁（CI 会拦，`maxIssues: 0`），以下是人读的部分。

### 5.1 风格

- Kotlin 官方代码风格；缩进 4 空格、行宽 120、尾随逗号、UTF-8、LF（见 `.editorconfig`）。
- **禁止 emoji**：代码、注释、提交信息、文档一律禁止（硬性约束第 6 条）。
  唯一例外是 `app/src/main/assets/copy/` 下的用户可见文案，见 5.6。
- 禁止 XML View，界面一律 Jetpack Compose。
- detekt 阈值已定：LongMethod 260、CyclomaticComplexMethod 30、TooManyFunctions 40。
  **超阈值先拆分，不许调高阈值，不许往 lint-baseline.xml 塞新条目**（该文件只登记确认过的误报）。
- 提交前 `./gradlew :app:ktlintCheck :app:detekt` 必须零违规、零问题。

### 5.2 命名

| 类型 | 约定 | 示例 |
| --- | --- | --- |
| Compose 页面 | `XxxScreen` | `SolveScreen` |
| ViewModel | `XxxViewModel` | `SolveViewModel` |
| 用例 | `XxxUseCase` | `GenerateExerciseUseCase` |
| DAO | `XxxDao` | `MistakeDao` |
| Room 实体 | `XxxEntity` | `MistakeEntity` |
| 后台任务 | `XxxWorker` | `RetryWorker` |
| 单元测试 | `XxxTest` | `MasteryCalculatorTest` |

- Composable 函数用 PascalCase（`.editorconfig` 已为 ktlint 放行）。
- 领域模型不带 `Entity` / `Response` 后缀；网络 DTO 以 `Dto` 结尾。
- 路由常量用字符串路径，如 `"kpoint_detail/{kpointId}"`，参数只能是受控 ID。

### 5.3 分层与依赖方向

依赖只能自上而下，禁止反向。

| 层 | 包路径 | 职责 | 禁止 |
| --- | --- | --- | --- |
| 界面层 | `ui/` | Compose 页面、ViewModel、状态流 | 直接访问 Room、直接调用 Retrofit |
| 领域层 | `domain/` | 领域模型、契约、纯算法 | 依赖 Android SDK（必须能在 JVM 单测跑） |
| 数据层 | `data/` | Room、DAO、Repository、网络、加密存储 | 向上返回实体对象（须转领域模型） |
| 识别层 | `ml/` | OCR、图片预处理、模型路由、TTS | 在此发起业务决策 |
| 后台任务 | `work/` | 日汇总、重试、缓存清理、复习提醒 | 高频轮询 |
| 工具 | `util/` | 导出、备份、编码、时间 | 放置业务规则 |

### 5.4 具体规则

- 新增网络调用必须同时满足 CONTRIBUTING 4.3 的五条：`client_request_id`、可取消、
  退避重试（1/2/4 秒，最多 3 次，仅幂等请求）、写 `ai_trace` 留痕、严格反序列化校验。
- AI 输出：知识点只能映射受控 ID（`^KP_[A-Z]{2,12}_[0-9]{4}$`）；错因只能是固定七类；
  解析失败必须记告警，不得静默丢弃、不得入库脏数据。
- 提示词、口语化规则表、图谱坐标、种子知识点一律放 `app/src/main/assets/`，
  **不许硬编码进 Kotlin**；改提示词必须同步升级 `assets/prompts/prompt_version.json`，否则缓存不失效。
- 掌握度、推荐路径、间隔重复、环检测等算法必须纯 Kotlin，且带单元测试。
- 协程用 `viewModelScope` / `repeatOnLifecycle`；页面销毁必须取消请求；禁止 `GlobalScope`。
- 异常处理：不许空 `catch`，不许把异常吞成 null 继续执行；
  日志不得包含 API Key、题干原文、学生作答。
- 依赖只在 `gradle/libs.versions.toml` 增改，模块脚本禁止出现硬编码版本号；
  变更既有版本必须在文件顶部写明原因。
- **不许把 Kotlin 回退到 1.9.24**（后端缺陷，编译必然失败，见 docs/构建与验证清单.md 4.1）。
- 不许删除 `kotlin.compiler.execution.strategy=in-process` 与 `ksp.incremental=false`。

### 5.5 注释

- 注释解释"为什么"，不解释"是什么"。
- 绕过上游缺陷、与直觉相反的写法、有取舍的实现，必须写清原因。
- 用中文；不加装饰性分隔线（源码里已有的 `// ----------` 分组风格可沿用）。

### 5.6 动效与文案口径

动效：

- 只做「位移 + 淡入 + 极轻缩放」，不做毛玻璃、共享元素、循环动画、装饰性动效。
- 时长：二级页进入 260ms、退出 200ms；底栏同级切换只淡入 180ms，不做横移。
- 时长按系统「动画时长缩放」反向补偿，下限 0.8；系统把动画彻底关掉时不补偿（无障碍选择）。
- 位移动画走 `graphicsLayer`，不触发重排；页面第一帧先画背景与标题栏，数据后到再填，
  否则动画会在空白期跑完，观感是「没有动画」。

文案：

- 成组出现的用户可见文案（每日一句、鼓励语、讲解口吻）放 `app/src/main/assets/copy/`，
  **不硬编码进 Kotlin**，也不要塞成 `strings.xml` 里一堆同前缀的键。
- 提示词放 `app/src/main/assets/prompts/`；改阶段提示词必须同步升级 `prompt_version.json`。
- 需要「每天不一样但不能每次刷新都变」的文案，用 UTC 日序号取模，不要用随机。
- `assets/copy/` 允许少量 emoji，仅限面向学生的文案；代码、注释、提交信息、docs 仍然禁止。
- 鼓励语要具体、短、不油腻：不写「太棒了」「真厉害」，写「这题过了，下一道」这类。

---

## 六、文件管理

### 6.1 目录职责

| 目录 | 放什么 |
| --- | --- |
| `app/src/main/java/com/totop/app/ui/` | Compose 页面、ViewModel、组件；页面在功能子目录下 |
| `.../domain/` | 领域模型与纯算法（`model/`、`usecase/`、`usecase/port/`） |
| `.../data/` | `local/`（Room、DAO、实体、映射、种子）、`remote/`（LLM 客户端、提示词仓库）、`repo/`、`prefs/` |
| `.../ml/` | OCR、图片预处理、模型路由、TTS |
| `.../work/` | WorkManager 任务 |
| `.../util/` | 导出、备份、编码、时间等通用工具 |
| `.../navigation/` | 路由常量与导航宿主 |
| `app/src/main/assets/` | 提示词、种子知识点、图谱坐标、LaTeX 兜底渲染器 |
| `app/src/test/`、`app/src/androidTest/` | 单元测试、仪器测试（包结构与主源码一一对应） |
| `docs/` | 架构、契约、提示词、偏离记录、构建验证清单 |
| `config/detekt/` | detekt 规则配置 |
| `.github/` | CI、发布、Issue 与 PR 模板、dependabot |

### 6.2 新文件放哪（按顺序判定）

1. Compose 页面 → `ui/<功能>/`；跨页面复用组件 → `ui/components/`。
2. 纯逻辑、算法、领域模型 → `domain/model/` 或 `domain/usecase/`。
3. Room 实体 / DAO / 迁移 → `data/local/` 对应子目录。
4. 网络协议 → `data/remote/`；仓储实现 → `data/repo/`。
5. OCR、图片、模型路由、TTS → `ml/`。
6. 后台任务 → `work/`。
7. 导出、备份、编码等通用工具 → `util/`。
8. 文案、提示词、种子数据、坐标 → `app/src/main/assets/` 对应子目录。
9. 测试 → 与主源码同包路径。
10. 都不合适 → **先问**，不要新建顶层包。

### 6.3 入库 / 不入库

**不入库**（`.gitignore` 已覆盖，仍要自查）：

- `local.properties`、`*.jks`、`*.keystore`、`signing.properties`、`secrets.properties`
- `build/`、`.gradle/`、`.gradle-local/`、`*.apk`、`*.aab`、`*.idsig`、`*.dex`
- `.agent-teams/`（多智能体本地状态）、`.tmp/`、临时脚本、日志、截图草稿
- `.verify-ai/`（验证用构建副本，靠 `build/` 规则间接排除；不要往里放 `build/` 之外的文件）

**必须入库**：

- `app/schemas/`：Room 导出的 schema 快照，MigrationTest 依赖它；**由 KSP 生成，禁止手改**
- `gradle/wrapper/`（含 `gradle-wrapper.jar`）、`gradlew`、`gradlew.bat`
- 提示词、种子数据、图谱坐标、LaTeX 兜底资源

注意：

- 不要在仓库里放构建产物，产物一律在 `app/build/outputs/`。
  根目录历史遗留的 `ToTop-*.apk` 已被忽略，不要新增同类文件。
- `gradlew` 必须保持可执行位（`git update-index --chmod=+x gradlew`），否则 Linux CI 报 exit 126。
- `app/lint-baseline.xml` 只登记确认过的误报；除非用户认可，不要新增条目。

### 6.4 临时文件

- 需要脚本或中间产物时写到 `.tmp/`（已忽略），任务结束前删除。
- 不在仓库根目录新建 `test.kt`、`debug.py`、`xxx_backup.md` 之类文件。
- 不留 `.bak`、`.orig`、注释掉的旧代码。

### 6.5 命名

- 一个文件一个主类型（少量私有扩展函数除外），文件名与主类型同名。
- 文档用中文名（与 `docs/` 现状一致）；代码与资源用小写或下划线。
- 禁止出现 `新建文本文档.txt`、`副本`、`最终版`、`v2-final` 这类名字。

---

## 七、验证门禁

### 7.1 按改动分级

| 改动内容 | 必跑 |
| --- | --- |
| 纯文档、纯注释 | 不跑构建；检查无 emoji（`assets/copy/` 除外）、链接有效、与代码是否仍一致 |
| 单个 UI / ViewModel 文件 | `:app:compileDebugKotlin` + `:app:ktlintCheck` + `:app:detekt` |
| `domain/` 纯逻辑 | 上项 + `:app:testDebugUnitTest` |
| `data/`、Room 表结构 | 上项 + 单测；改表结构还要迁移测试与 `app/schemas/` 快照 |
| 构建脚本、依赖、CI | `./gradlew clean assembleDebug testDebugUnitTest detekt ktlintCheck lintDebug` |
| 准备发版 | 上项 + `:app:assembleRelease` + 体积门禁（arm64 不超过 30MB） |

### 7.2 常用命令

| 目的 | 命令 |
| --- | --- |
| 快速编译 | `./gradlew :app:compileDebugKotlin` |
| 单元测试 | `./gradlew :app:testDebugUnitTest` |
| 格式检查 / 自动修复 | `./gradlew :app:ktlintCheck` / `:app:ktlintFormat` |
| 静态分析 | `./gradlew :app:detekt` |
| Android Lint | `./gradlew :app:lintDebug` |
| 完整门禁 | `./gradlew clean assembleDebug testDebugUnitTest detekt ktlintCheck lintDebug` |

### 7.3 硬规则

- **构建必须串行**：两个 Gradle 进程同时构建同一工程会破坏 KSP 缓存，清理 `app/build` 后重建。
- 完整门禁必须包含 `:app:lintDebug`。历史上它只在 CI 跑，导致本地看不见 lint error、CI 次次红。
- 结果如实报告：`BUILD SUCCESSFUL`、测试总数与失败数、耗时。没跑就写没跑。
- 只跑了子集时，明确写"未跑完整门禁"以及风险点。
- 真机相关（相机、框选、PDF 打印、TTS）无法在构建中验证，标注"需真机验证"。

---

## 八、文档同步

| 触发 | 必须更新 |
| --- | --- |
| 任何用户可见行为变化 | `CHANGELOG.md`（Keep a Changelog 结构，中文） |
| 与原方案 / 既有设计不一致 | `docs/偏离记录.md`（写原因，不静默省略） |
| 构建环境、实测数据、踩坑修复 | `docs/构建与验证清单.md` |
| 分层、核心链路变化 | `docs/架构.md` |
| 阶段契约、字段变化 | `docs/API契约.md` |
| 提示词改动 | `docs/提示词规范.md` + `assets/prompts/prompt_version.json` |
| 能力或已知限制变化 | `README.md`（含"已知限制"表） |
| 规范本身变化 | 本文件与 `CONTRIBUTING.md` |

- 测试条数、包体积、构建耗时这类易过期数字只写在 `docs/构建与验证清单.md`，并标注测量日期。
- 文档与代码不一致时以代码为准，同时修正文档；判断不了就先问。
- 文档更新与代码改动放在同一次提交，不要留"稍后补文档"。

---

## 九、安全与隐私

- 不写入、不打印、不提交任何真实 API Key；示例一律用占位符。
- 日志、导出文件、崩溃信息里不得出现题干原文、学生作答、Key。
- 不新增统计、广告、埋点、崩溃上报 SDK；不新增需要自建后端的依赖。
- 题目图片只能发往用户自己配置的模型端点，不得发往其它任何地址。
- 签名与密钥只走本机 `local.properties` 或 CI Secrets，落盘后立即清理。

---

## 十、提交前自检清单

- [ ] 改动范围与用户要求一致，没有夹带无关修改
- [ ] `./gradlew :app:ktlintCheck :app:detekt` 通过
- [ ] 相关单元测试通过（或明确说明未跑及原因）
- [ ] 文档已按第八章同步
- [ ] 提交信息符合 `<type>(<scope>): <中文简述>`
- [ ] `git status --short` 无临时文件、无构建产物、无密钥
- [ ] 代码与文档无 emoji（`app/src/main/assets/copy/` 的用户文案除外）
- [ ] 已 push，且 `git status -sb` 无 ahead / behind

---

## 十一、汇报格式

固定四段，简短，不写场面话：

1. **改了什么**：文件清单 + 每个文件一句话。
2. **为什么**：一句话说明动机。
3. **验证**：跑了哪些命令、真实结果是什么。
4. **未做 / 风险**：没有就写"无"。

---

## 十二、常用命令速查

```bash
# 看状态与差异
git status --short && git diff --stat

# 提交并推送（第三章规定流程）
git add <路径> && git commit -m "fix(scope): 中文简述"
git fetch origin && git status -sb
git -c http.sslBackend=schannel push origin HEAD   # 本机必需，见 3.5.1

# 门禁
./gradlew :app:ktlintCheck :app:detekt :app:testDebugUnitTest

# 检查 emoji（无输出即合规）
# 注意：本机 Git Bash 未安装 perl，下面这条会报 xargs: perl: No such file or directory。
# 遇到这种情况用等价的 python 版本（2026-10-29 实测可用），两者结果一致：
#   python - <<'EOF'
#   import io, re, subprocess
#   out = subprocess.run(["git","-c","core.quotepath=false","ls-files","-z","*.kt","*.md","*.kts","*.json"],
#                        capture_output=True).stdout
#   pat = re.compile("[\U0001F300-\U0001FAFF\u2600-\u27BF]")
#   for f in [x.decode() for x in out.split(b"\0") if x]:
#       if f.startswith("app/src/main/assets/copy/"): continue
#       for i, line in enumerate(io.open(f, encoding="utf-8", errors="ignore"), 1):
#           if pat.search(line): print(f, i, line.strip())
#   EOF
# 注意：本机 grep 不支持 \x{...} 码位语法，用 grep -P 会直接报错而不是返回空，必须用 perl。
# 用 -z 加 xargs -0 是为了让 docs/ 下的中文文件名不被 shell 拆开。
git ls-files -z '*.kt' '*.md' '*.kts' '*.json' ':(exclude)app/src/main/assets/copy/*' \
  | xargs -0 perl -CSD -ne 'print "$ARGV:$.: $_" if /[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]/; close ARGV if eof'
```
