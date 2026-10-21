# 第三方组件与数据资产

本文件记录 To Top 使用的第三方组件、数据资产及其许可证与来源，供审阅与二次分发使用。
新增依赖或数据时，必须同步更新本文件。

## 一、运行期依赖

### 1.1 平台与语言

| 组件 | 版本 | 许可证 | 说明 |
| --- | --- | --- | --- |
| Kotlin 标准库与协程 | 1.9.25 | Apache-2.0 | 语言与异步基础 |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | Android 兼容封装 |
| AndroidX Activity Compose | 1.9.2 | Apache-2.0 | Compose 宿主 |
| AndroidX Lifecycle | 2.8.6 | Apache-2.0 | 生命周期与 ViewModel |

### 1.2 界面

| 组件 | 版本 | 许可证 | 说明 |
| --- | --- | --- | --- |
| Jetpack Compose BOM | 2024.09.03 | Apache-2.0 | 声明式界面基础 |
| Compose Material3 | 随 BOM | Apache-2.0 | Material 3 组件 |
| Compose Material Icons Extended | 随 BOM | Apache-2.0 | 图标集 |
| Navigation Compose | 2.8.0 | Apache-2.0 | 页面导航 |
| Coil Compose | 2.5.0 | Apache-2.0 | 图片加载与内存缓存 |

### 1.3 数据与依赖注入

| 组件 | 版本 | 许可证 | 说明 |
| --- | --- | --- | --- |
| Hilt | 2.51.1 | Apache-2.0 | 依赖注入 |
| androidx.hilt.navigation.compose | 1.2.0 | Apache-2.0 | Compose 与 Hilt 集成 |
| Room | 2.6.1 | Apache-2.0 | 本地数据库 |
| androidx.security.crypto | 1.1.0-alpha06 | Apache-2.0 | 基于 Keystore 的加密存储，用于保存 API Key |
| androidx.documentfile | 1.0.1 | Apache-2.0 | SAF 文件访问，用于导出与备份 |
| WorkManager | 2.9.0 | Apache-2.0 | 每日汇总、重试与缓存清理 |

### 1.4 网络与序列化

| 组件 | 版本 | 许可证 | 说明 |
| --- | --- | --- | --- |
| OkHttp | 4.12.0 | Apache-2.0 | HTTP 客户端与 SSE 支持 |
| OkHttp SSE | 4.12.0 | Apache-2.0 | 流式响应解析 |
| Retrofit | 2.11.0 | Apache-2.0 | 接口式网络调用 |
| kotlinx.serialization | 1.6.3 | Apache-2.0 | JSON 严格校验与编解码 |

### 1.5 相机与离线识别

| 组件 | 版本 | 许可证 | 说明 |
| --- | --- | --- | --- |
| CameraX（core、camera2、lifecycle、view） | 1.3.4 | Apache-2.0 | 拍照与生命周期管理 |
| ML Kit Text Recognition v2（bundled） | 16.0.0 | Google ML Kit 使用条款 | 离线文字识别 |

关于 ML Kit 的三点说明：

1. 使用的是 **bundled** 版本，模型随应用打包，不依赖 Google Play 服务，因此国内机型可用。
2. 该组件不是开源软件，适用 Google 的 ML Kit 使用条款，需遵守其再分发要求。
3. 识别全程在设备本地完成，不上传图片。

### 1.6 公式与图表

| 组件 | 计划版本 | 许可证 | 状态 |
| --- | --- | --- | --- |
| KaTeX | 0.16.x，随应用内置 | MIT | 计划中（M2 起用于公式渲染） |
| ECharts | 5.x，随应用内置 | Apache-2.0 | 计划中（M4 起用于图表，WebView + canvas 渲染） |

两者均以静态资源形式内置到 `app/src/main/assets/`，不加载任何远程脚本。
正式引入时须把实际版本号、文件来源与许可证全文补入本节。

图表方案采用 WebView + ECharts（关闭动画、数据点降采样），未采用 Vico。

### 1.7 系统能力（非第三方依赖）

| 能力 | 说明 |
| --- | --- |
| android.speech.SpeechRecognizer | 系统语音识别，优先离线；不可用时降级 |
| android.speech.tts.TextToSpeech | 系统 TTS 朗读，零服务器成本 |
| Android Keystore | 密钥加密存储的根信任 |

## 二、开发与测试期依赖

| 组件 | 版本 | 许可证 | 说明 |
| --- | --- | --- | --- |
| JUnit 5 | 5.10.2 | EPL-2.0 | 单元测试框架 |
| android-junit5 插件 | 1.9.3.0 | Apache-2.0 | 让 Android 工程使用 JUnit 5 |
| Turbine | 1.1.0 | Apache-2.0 | Flow 测试 |
| MockK | 1.13.11 | Apache-2.0 | 测试替身 |
| kotlinx-coroutines-test | 1.8.1 | Apache-2.0 | 协程测试 |
| MockWebServer | 4.12.0 | Apache-2.0 | 网络层测试 |
| AndroidX Test（junit、runner、core、espresso） | 见版本目录 | Apache-2.0 | 仪器测试 |
| Compose UI Test | 随 BOM | Apache-2.0 | 界面回归测试 |

说明：JUnit 5 本身为 EPL-2.0，仅作为测试期依赖，不进入发布包。

## 三、数据资产

| 资产 | 路径 | 来源与授权 |
| --- | --- | --- |
| 数学知识点树 | `app/src/main/assets/seed/knowledge_point_math.json` | 本项目自编整理。结构参照现行中学数学课程标准的内容主线，节点名称、定义、通用解题思路与易错点均为自行撰写，未复制任何教材原文、教辅题目或商业数据库内容。 |
| 知识点关系边 | `app/src/main/assets/seed/knowledge_point_edge_math.json` | 人工整理的前置、相关、扩展关系，非 AI 生成。按方案要求，前置关系必须人工确认后才生效。 |
| 通用易错点 | `app/src/main/assets/seed/kpoint_generic_pitfalls.json` | 自行整理的教学经验条目，按章节与知识点两级归类。 |
| LaTeX 口语化规则 | `app/src/main/assets/seed/latex_spoken_rules.json` | 自行编写的替换规则，用于把公式读成中文口语。 |
| 图谱预计算坐标 | `app/src/main/assets/graph_layout/graph_layout_math.json` | 由本项目知识点树确定性计算生成，非第三方布局数据。 |
| 提示词模板 | `app/src/main/assets/prompts/` 下四个文件与版本文件 | 本项目原创撰写。 |
| 应用图标 | `app/src/main/res/drawable/` 与 `mipmap-*/` | 本项目自绘的矢量占位图形，无第三方素材。 |

完整的来源与整理说明另见 [data/README.md](data/README.md)。

**本项目不内置任何受版权保护的题库题目、教辅内容或商业知识点数据。**
用户在使用过程中产生的题目图片与解答属于用户自己，不会进入仓库。

## 四、明确不使用的组件

以下组件因隐私、可用性或版权原因明确排除，请勿在后续开发中引入：

| 组件 | 排除原因 |
| --- | --- |
| Firebase、友盟、Bugly、Crashlytics 等统计与崩溃上报 | 违反无埋点与隐私最小化要求 |
| 依赖 Google Play 服务的 ML Kit 变体 | 国内机型大面积不可用 |
| MathJax | 低端机渲染卡顿，必须用 KaTeX |
| 任何需要自建后端的服务 | 项目定位为无后端 |
| 第三方云 OCR、图床 | 禁止上传用户图片 |
| GeoGebra | 许可证需商用核实，暂不引入；若将来引入须在此标注出处与许可确认提示 |

## 五、许可证合规

- 本项目自身以 MIT 许可证发布，见 [LICENSE](LICENSE)。
- 所有 Apache-2.0 依赖在分发时保留其许可证与声明，需在发布包或仓库中可查。
- ML Kit 的使用受 Google 条款约束，分发时不得移除其声明文件。
- 引入新依赖前请确认其许可证与本项目 MIT 兼容，并更新本文件。

## 六、素材版权声明

若你认为本项目中的任何内容侵犯了你的权利，请通过仓库公布的联系方式提出，我们会在核实后移除相关内容。
