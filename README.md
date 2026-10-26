# To Top

> **简体中文** | [English](#english-overview)

面向中学生的 Android 错题辅导应用。拍下一道做错的题，得到结构化识题、独立求解、错因定位与知识点归纳，
并在本机形成错题本与掌握度画像，长期指导复习。

- **平台**：Android 原生，Kotlin + Jetpack Compose，minSdk 24 / targetSdk 34
- **无后端**：没有账号体系，没有自建服务器。题目图片、解答与全部统计数据只存在本机
- **BYOK**：用户自带模型 API Key 直连厂商，开发者不收取、不转发、不存储任何 Key
- **开源非商业**：MIT 许可证，不含任何商业 SDK、统计 SDK、广告 SDK
- **不做判卷**：不统计正确率。所有指标建立在错题侧——错题频次、复发率、错因分布、复习自评

---

## 目录

- [一、它能做什么](#一它能做什么)
- [二、设计上的三个取舍](#二设计上的三个取舍)
- [三、快速开始](#三快速开始)
- [四、配置模型](#四配置模型)
- [五、成本控制](#五成本控制)
- [六、本地模式](#六本地模式)
- [七、项目结构](#七项目结构)
- [八、技术栈](#八技术栈)
- [九、开发与验证](#九开发与验证)
- [十、已知限制](#十已知限制)
- [十一、隐私](#十一隐私)
- [十二、免责声明](#十二免责声明)
- [十三、许可证](#十三许可证)

---

## 一、它能做什么

### 解题：四阶段模型链路

一道题会经过四个独立阶段，每个阶段用不同提示词调用一次模型：

| 阶段 | 名称 | 作用 |
| --- | --- | --- |
| A | 识题 | 从图片或文字中还原题干、学生作答、图形描述 |
| B | 独立求解 | **只看题干**，不看学生作答，独立给出标准解与步骤 |
| C | 错因定位 | 对照标准解与学生作答，指出第一处出错的位置与原因 |
| D | 知识点抽取 | 把题目映射到知识点树上的具体节点 |

四个阶段的提示词都**按题目实际科目**作答，覆盖语文、数学、英语、物理、化学、生物、
政治、历史、地理与技术（浙江选考）。阶段 A 输出十科枚举，阶段 B 要求用该科目的规范
表述与推理方式（物理写受力分析与单位、化学配平并注明条件、语文落到文本依据），
阶段 D 的 ID 正则是通用的 `^KP_[A-Z]{2,12}_[0-9]{4}$`，不再写死数学前缀。

关键设计：**阶段 B 永远看不到学生的作答**。否则模型会被学生的错误思路带偏，
给出一个"和学生的错法相符"的解答，错因定位就失去意义了。

阶段 D 在候选知识点清单为空时**直接跳过**，不发起请求——这是正确行为，不是失败。

识别出科目后，阶段 D 的候选清单会**收敛到同一科目**。知识点树共 740 个节点、
十个科目，若不按科目收敛，一道物理题的候选里会混进语文、英语的节点，
模型很容易给出跨科目的错误归属，而错误归属会直接污染掌握度统计。

### 拍题：全屏相机与多页

- 全屏取景，圆形快门，支持前后摄像头切换
- 拍完可**拖动框选**只保留题目区域，框选坐标归一化为 0~1 保存，与屏幕尺寸无关
- 支持**连续多页**：可添加、删除、调整顺序，每页独立框选，最后一次性提交
- 图片上传前裁剪并压缩（长边 1280、JPEG 0.78），既省钱也提高识别准确率

### 对话式界面

采用对话式布局：**图片条固定在输入框正上方**，输入框用于写解题思路而不是写题干。
OCR 识别结果**不在界面上显示**——它只用于路由判断，把识别文本摆给人看只会造成困惑。

四阶段进度压缩成一行状态文字，结果按「原题 → 提示 / 错因 → 标准解答 → 考点」分区呈现。

### 讲解模式：两档

| 模式 | 首屏给什么 | 步骤与答案 |
| --- | --- | --- |
| 直接讲解 | 完整步骤 + 最终答案，一次给全 | 立刻可见 |
| 提示引导（默认） | 只给方向与一个引导性问题 | 需逐级解锁提示，或点「我已想通，看全解」 |

0.2.0 把原「提示引导」与「纯引导」合并为一档：旧 GUIDED 会直接渲染整份标准步骤，
观感与「直接讲解」没有差别，而 SOCRATIC 又不给任何可操作的抓手。
现在两档的差别只落在「给不给最终解答」这一件学生真正在意的事上。

### 多对话管理

每次拍题是一个**会话**，落盘保存在本机；左上角菜单可展开左侧会话列表，
随时切回之前任何一次问答继续追问。新对话显式开始，不会因为再拍一张就覆盖上一题。

会话在**第一轮落盘时按需创建**，所以历史列表随对话实时增长，不会出现"列表永远空着"，
也不会因为没点过"新建对话"而丢记录。

### 数学表达式排版

中文正文里夹的朴素记号（`x^2`、`2^x`、`x^{n+1}`、`a_1`）会渲染成真正的上下标；
带 `$...$` 定界符的内容走 KaTeX 位图渲染。两者都不会出现 `x^2` 这种半个符号的裸文本。

### 陪伴作业

写作业时用的第二入口：每道题先给自己一段时间（3 / 5 / 8 / 10 分钟可选，默认 5 分钟），
**到点之前不提供求助入口**，拦住"一卡住就看答案"的习惯；时间到点后解锁「问一下 AI」，
并记一次求助。一轮结束给一份朴素复盘：自己写完几题、求助几次、累计思考多久。

刻意不做排行榜、连续打卡与到点通知——它的定位是"有人在旁边看着时间"，
不是又一个游戏化模块。

### 其余能力

| 能力 | 说明 |
| --- | --- |
| 首次使用引导 | 六页翻页式引导，讲清怎么拍、怎么问、为什么要填 Key；可从设置重看 |
| 错题本 | 入库、按科目筛选、搜索、复发提示 |
| 掌握度 | 按科目分组的雷达图与章节热力、知识点详情、复习自评、简化 SM-2 间隔重复 |
| 学情报告 | 日汇总、周报模板与 AI 解读（可关闭）、系统 TTS 朗读题干 |
| 知识图谱 | 知识点关系边与环检测、推荐学习路径 |
| 导出 | Markdown、CSV、Anki（TSV）、本地长图 PDF |
| 备份 | 全量备份与恢复，通过 SAF 写入 |
| 离线记题 | 无网络或无 Key 时手工记录题目与思路 |
| 后台任务 | WorkManager 四类任务：日汇总、失败重试、缓存清理、复习提醒 |

---

## 二、设计上的三个取舍

这一节记录的是**明知有替代方案而没有采用**的决定，便于理解代码为什么长这样。

**一、框选在本地做，不在服务端做。**
OpenAI 兼容协议没有标准的图片坐标字段，把整图加上"只看第二题"的指令交给模型既贵又不准。
本地裁剪后上传，省 token 且识别率更高。代价是用户需要手动框一下。

**二、离线记题用 JSON 文件而不是 Room 表。**
新增一张 Room 表意味着版本号加一、写 Migration、补 MigrationTest。为了一个附属功能拖入
数据库迁移风险不划算。代价是离线笔记不能参与 SQL 查询与关联统计。

**三、典型错题由本地数据直接渲染，不交给模型复述。**
模型改写题干时可能把关键条件改错，而这些题恰恰是学生自己认得出来的。
复习资料里模型只负责组织学习建议，题干原文一律用数据库里的原文。

---

## 三、快速开始

### 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 17 或以上（开发时使用 Microsoft OpenJDK 21.0.3） |
| Android SDK | platform 34、build-tools 34.0.0、platform-tools、cmdline-tools |
| Gradle | 8.7，由仓库自带的 Wrapper 固定，无需单独安装 |

### 构建

```bash
git clone https://github.com/Ky-l0r/ToTop.git
cd ToTop

# 1. 生成 local.properties 并填写本机 SDK 路径
cp local.properties.example local.properties
#    Windows 反斜杠需转义：sdk.dir=D\:\\Android\\Sdk
#    或直接用正斜杠：      sdk.dir=D:/Android/Sdk

# 2. 构建调试包
./gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

`local.properties` 已在 `.gitignore` 中排除，**绝不要提交**。

### 常用命令

| 命令 | 作用 |
| --- | --- |
| `./gradlew :app:assembleDebug` | 构建调试包 |
| `./gradlew :app:testDebugUnitTest` | 运行单元测试（579 项） |
| `./gradlew :app:ktlintCheck` | 格式检查 |
| `./gradlew :app:detekt` | 复杂度与潜在缺陷检查 |
| `./gradlew :app:lintDebug` | Android Lint，要求零 error |
| `./gradlew :app:assembleRelease` | R8 与资源压缩，构建发布包 |

**构建必须串行。** 多个 Gradle 进程并行编译同一工程会互删 KSP 中间产物，
表现为 `NoSuchFileException: app/build/kspCaches/...`。

---

## 四、配置模型

应用内路径：**设置 → 模型配置**。三个字段适配所有 OpenAI 兼容厂商：Base URL、API Key、模型名。

保存前会发一次最小化连通性校验，失败时给出具体原因（Key 无效、余额不足、网络不通、
模型名错误、Base URL 错误），不会只提示"请求失败"。

### 预置厂商模板

| 厂商 | Base URL | 文本模型示例 | 视觉模型示例 |
| --- | --- | --- | --- |
| 阿里云百炼（DashScope） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `qwen-vl-plus` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` | 暂无视觉模型 |
| Moonshot（Kimi） | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` | `moonshot-v1-8k-vision-preview` |
| SiliconFlow | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-7B-Instruct` | `Qwen/Qwen2-VL-7B-Instruct` |
| 本地 Ollama | `http://127.0.0.1:11434/v1` | `qwen2.5` | `qwen2-vl` |

说明：

1. Base URL 只填到 `/v1` 一级即可，应用会自动拼接 `/chat/completions`。
   重复填写或漏写斜杠都能正确归一化。
2. 模型名必须与厂商当前发布的名称完全一致，厂商改名后需在设置页手动同步。
3. 本地 Ollama 属于明文 HTTP 端点，应用默认只对 `localhost`、`127.0.0.1`、`10.0.2.2`
   放行明文流量，其余地址一律要求 HTTPS。
4. 应用不内置任何 Key，仓库中也不存在任何真实 Key。
   密钥使用 Android Keystore 加密后存于 EncryptedSharedPreferences。

---

## 五、成本控制

成本由用户直接向厂商结算。本项目只记录每次调用的 token 用量，不参与计价。
**具体价格以各厂商官网计价页为准**，本文不复制价目表，避免价格变动后误导。

| 场景 | 路由 | 单次调用 token 量级 |
| --- | --- | --- |
| 纯印刷体、置信度高、无图形 | 纯文本模型 | 约 1.5K ~ 3K |
| 手写体、含几何或函数图、置信度低 | 视觉模型 | 约 3K ~ 8K |
| 同一道题二次打开 | 本地缓存 | 0 |
| 知识点查询、公式朗读、图表统计 | 纯本地 | 0 |

上表按**整题**口径给出（含四个阶段），不是单阶段数值。

已有的省钱机制：

- **图片压缩**：上传前裁剪，长边不超过 1280、JPEG 质量 0.78
- **双层缓存**：内存 LRU + Room 持久缓存，缓存键为 图片 sha256 + 提示词版本 + 模型名
- **上下文极简**：每次只发当前题目，不携带历史对话
- **离开即取消**：页面退出后请求随之取消，不会继续计费
- **空清单跳过**：知识点候选为空时不发起阶段 D 请求
- **未配置即免费**：本地模式不产生任何网络请求

---

## 六、本地模式

未配置 Key 或无网络时，应用退化为本地工具，而不是变成一个空壳。

**仍然可用：**

- 拍照与相册导入，图片在本机压缩保存
- 本地离线 OCR 提取题干（ML Kit bundled 版本，不依赖 Google Play 服务，国行无 GMS 机型可用）
- 手工撰写解题笔记，归入错题本与知识点
- 查看知识点、掌握度与图表
- 导出与备份
- 系统 TTS 朗读题干

**不可用，且会明确提示原因：**

- AI 求解与错因定位
- 知识点自动抽取
- AI 生成练习题
- 周报的 AI 解读（本地统计版周报仍可生成）

引导只出现一次，直接跳转设置页，不反复弹窗。

---

## 七、项目结构

```
app/src/main/java/com/totop/app/
├── data/
│   ├── local/          Room：15 张表、13 个 DAO、种子数据装载、环检测校验
│   ├── remote/         OpenAI 兼容 SSE 客户端、四阶段编排、退避重试、错误分类
│   ├── repo/           仓储实现，对上暴露领域端口
│   └── prefs/          基于 Android Keystore 的加密密钥存储
├── domain/
│   ├── model/          纯数据模型与契约校验
│   └── usecase/        7 个用例 + 领域端口层（port/）
├── ml/                 离线 OCR 与图片压缩
├── ui/
│   ├── solve/          对话式解题：对话页 / 全屏相机 / 框选预览
│   ├── chat/           解题状态机与 ViewModel
│   ├── mistakes/       错题本
│   ├── knowledge/      知识点与掌握度
│   ├── report/         学情报告
│   ├── reviewmaterial/ 复习资料生成
│   ├── offline/        离线记题
│   ├── settings/       模型配置与偏好
│   └── theme/          主题（淡紫主色 + 白色背景）
├── navigation/         路由表与导航图
├── work/               WorkManager 四类后台任务
└── util/               通用工具

app/src/main/assets/
├── prompts/            四阶段提示词模板
├── seed/               知识点树、关系边、通用易错点
├── katex/              自研 LaTeX 兜底渲染器
└── graph_layout/       图谱预计算坐标
```

文档位于 `docs/`，其中：

- [docs/架构.md](docs/架构.md) —— 分层与依赖方向
- [docs/API契约.md](docs/API契约.md) —— 阶段间数据契约
- [docs/提示词规范.md](docs/提示词规范.md) —— 提示词编写约定
- [docs/偏离记录.md](docs/偏离记录.md) —— **所有偏离原方案的决定及原因**，最有参考价值
- [docs/构建与验证清单.md](docs/构建与验证清单.md) —— 构建环境与实测记录
- [docs/工程规范与构建说明.md](docs/工程规范与构建说明.md) —— 编码与构建规范

---

## 八、技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Kotlin 1.9.25 |
| UI | Jetpack Compose（BOM 2024.09.03）、Material 3 |
| 依赖注入 | Hilt 2.51.1 |
| 本地库 | Room 2.6.1 |
| 网络 | OkHttp 4.12.0、kotlinx.serialization 1.6.3 |
| 相机 | CameraX 1.3.4 |
| OCR | ML Kit Text Recognition（中文 bundled）16.0.0 |
| 后台 | WorkManager 2.9.0 |
| 密钥 | Security Crypto 1.1.0-alpha06 |
| 测试 | JUnit 5、MockK、Turbine、MockWebServer |

版本统一在 `gradle/libs.versions.toml` 管理。变更任何既有版本必须在该文件顶部记录原因——
例如 Kotlin 从 1.9.24 升到 1.9.25，是因为 1.9.24 后端在特定代码组合下必然抛
`AnalyzerException`，属上游缺陷。

---

## 九、开发与验证

### 测试

```bash
./gradlew :app:testDebugUnitTest
```

当前 **579 项单元测试全部通过**，覆盖契约校验、缓存键、掌握度算法、排期、环检测、
导出格式、错误分类、SSE 解析、离线笔记持久化等。

仪器测试需要真机或模拟器：

```bash
./gradlew :app:connectedDebugAndroidTest
```

### 提交前门禁

```bash
./gradlew :app:ktlintCheck :app:detekt :app:testDebugUnitTest
```

CI（`.github/workflows/ci.yml`）在每次 push 与 PR 时执行：密钥泄露扫描（gitleaks）、
ktlint、detekt、单元测试、Lint。

### 发布

打 `v*` 标签触发 `.github/workflows/release.yml`。签名材料只从 GitHub Secrets 读取，
落盘后立即在后续步骤删除。

---

## 十、已知限制

诚实列出尚未完成的部分，避免被进度表误导。

| 项目 | 状态 |
| --- | --- |
| 图谱可视化 UI | 未做。关系边、环检测与路径推荐算法已完成，但图谱没有渲染界面 |
| Baseline Profile | 未生成 |
| 真机性能实测 | 未完成。低端机 FPS、内存、冷启动的实测清单已写好，尚无完整实测数据 |
| 知识点树粒度 | 覆盖十科共 740 个节点，粒度为「章 → 节」两级。数学最细（170 节点），其余科目按高考/选考主干梳理 |
| 界面截图 | 仓库内暂无截图，需在真机拍摄后补充 |
| 相机、框选、PDF 打印 | 依赖真实硬件与系统服务，仅能在真机验证 |
| 陪伴作业的通知提醒 | 未做。写作业时弹通知本身就是干扰，当前只在应用内计时 |
| 陪伴作业的跨会话统计 | 未做。复盘只覆盖当前这一轮，关掉页面即清零（不做打卡与排行） |

更多细节见 [docs/偏离记录.md](docs/偏离记录.md)。

---

## 十一、隐私

- 题目图片、识别文本、解题结果、错题本与统计结果默认**仅保存在本机**数据库与私有目录
- 应用没有开发者服务器，不上传任何数据到开发者可控的地址
- 唯一的网络出口是用户自己填写的模型厂商端点，由用户设备直连厂商
- API Key 使用 Android Keystore 加密后存储，不写入日志、不进入崩溃上报、不随导出文件外泄
- 不采集身份信息，不需要手机号、邮箱或第三方账号登录
- 已关闭系统云备份与设备迁移对自身数据的收集
- 提供一键清除全部数据入口，清除后应用从零可用

**需要知悉**：题目内容会随请求发送给你选择的模型厂商，是否记录、如何留存由该厂商的隐私政策决定。
若对此敏感，请使用本地 Ollama 等自建端点，此时数据不离开设备。

---

## 十二、免责声明

- 解题步骤、错因分析、知识点归纳与学习建议由 AI 生成，**可能存在错误，不替代老师**
- 所有 AI 生成内容在界面上带有"AI 生成"标识，导出文件中同样保留
- 应用不提供判卷与正确率，复习自评由用户自己填写，不代表真实掌握水平
- 请勿把应用输出直接作为考试或作业的唯一依据，发现错误请以教材与教师讲解为准
- 本项目为个人学习与非商业开源项目，按 MIT 许可证"按现状"提供，不对使用后果承担责任

---

## 十三、许可证

MIT，见 [LICENSE](LICENSE)。

第三方组件与数据资产的来源与许可见 [THIRD_PARTY.md](THIRD_PARTY.md)。

---

## English Overview

**To Top** is a native Android app for Chinese middle-school students to learn from the problems
they got wrong.

Photograph a problem you answered incorrectly, and the app runs a four-stage LLM pipeline —
recognize → **solve independently** → locate the first error → map to knowledge points — then
builds a local mistake notebook and mastery profile over time.

The key design decision: **the solving stage never sees the student's work.** If it did, the
model would be anchored by the student's mistake and produce a solution matching that mistake,
making error localization meaningless.

- **Android native** — Kotlin + Jetpack Compose, minSdk 24 / targetSdk 34
- **No backend** — no accounts, no developer server. All data stays on device
- **BYOK** — bring your own OpenAI-compatible API key; the developer never receives or stores it
- **Not a grader** — deliberately no accuracy scoring. Every metric is built on the mistake side
  (frequency, recurrence, error-type distribution, self-rated review)
- **MIT licensed**, no ads, no analytics, no tracking SDKs

**Contents:** [Features](#一它能做什么) · [Quick start](#三快速开始) ·
[Model setup](#四配置模型) · [Known limitations](#十已知限制) · [Privacy](#十一隐私)

Built with Kotlin · Jetpack Compose · Room · Hilt · CameraX · ML Kit · OkHttp.
