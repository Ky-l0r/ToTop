# 数据资产说明（data）

本目录记录 To Top 应用内置数据资产的来源、整理方式与授权，供审阅与二次分发使用。
应用运行时实际读取的文件位于 `app/src/main/assets/`，本目录只负责来源与授权说明，不参与打包。

## 一、文件清单

| 资产 | 路径 | 说明 |
| --- | --- | --- |
| 数学知识点树 | `app/src/main/assets/seed/knowledge_point_math.json` | 初中与高中数学知识点树，170 个节点（24 个章节点 + 146 个节节点） |
| 知识点关系边 | `app/src/main/assets/seed/knowledge_point_edge_math.json` | 172 条边，类型 prerequisite / related / extends，入环检测通过 |
| LaTeX 口语化规则 | `app/src/main/assets/seed/latex_spoken_rules.json` | 76 条 LaTeX 到中文口语的替换规则，供 TTS 朗读使用 |
| 图谱坐标说明 | 见下文第二节第 5 条 | 由知识点树确定性计算生成 |
| 通用易错点 | `app/src/main/assets/seed/kpoint_generic_pitfalls.json` | 按章节与知识点两级归类的通用高频易错点 |
| 图谱坐标 | `app/src/main/assets/graph_layout/graph_layout_math.json` | 知识点树前两层的预计算坐标，客户端静态渲染 |
| 提示词模板 | `app/src/main/assets/prompts/stage_a_identify.md` 等四个文件 | 阶段 A 识题、阶段 B 独立求解、阶段 C 错因定位、阶段 D 知识点抽取 |
| 提示词版本 | `app/src/main/assets/prompts/prompt_version.json` | 参与缓存键计算的 promptVersion |

## 二、数据来源

1. **知识点树与关系边：自编整理。**
   结构参照现行初高中数学课程标准中的内容主线（数与代数、图形与几何、函数、统计与概率、立体几何、解析几何、导数等），
   节点名称、定义、通用解题思路与易错点均为本项目作者依据公开教材的通用表述重新撰写，
   **未复制任何教材原文、教辅题目、题库题目或商业数据库内容**。
   节点编号 `KP_MATH_0001` 至 `KP_MATH_0170` 由本项目自行编制，构成受控 ID 体系，AI 只能映射既有编号。

2. **知识点关系边：人工整理。**
   `prerequisite` 边按实际教学顺序人工确定，方向为「前置 → 后继」；
   `related` 为同层横向关联；`extends` 为学段或方法上的扩展关系。
   按方案 4.7 的要求，前置关系必须人工整理入库，**不得由 AI 自动生成或自动生效**；
   AI 如提出补充建议，只能作为待审条目，需人工确认后才能写入本文件。
   入库前执行 DFS 三色标记环检测，当前版本无环。

3. **LaTeX 口语化规则：自编整理。**
   规则覆盖分数、根式、幂与下标、三角函数、关系符号、希腊字母等常见写法，
   pattern 为 Java/Kotlin 正则字面量，spoken 使用 `$1`、`$2` 引用捕获组，
   数组顺序即客户端 `LatexSpokenRuleTable` 的匹配顺序（先原子命令，后结构，最后分数）。
   该表不是任何第三方文库的衍生作品。

4. **通用易错点：自编整理。**
   内容来自对教学实践中高频错误的归纳，按章节与知识点编排，与知识点树共用受控 ID。
   知识点详情页中「内置通用易错点」展示本文件内容，用户个人错因聚合后另行展示。

5. **图谱坐标：本项目计算生成。**
   采用区块网格布局，由知识点树结构确定性计算得到，不含人工取点，
   以便节点增删后可重新生成保持一致。

6. **提示词模板：本项目原创撰写。**
   按方案 6.3 的阶段边界编写，每个阶段独立文件，包含角色、输入、必须输出、禁止事项、JSON Schema 与正误示例。

## 三、授权

本目录与 `app/src/main/assets/` 下的全部数据资产由本项目作者原创整理，
随项目整体以 **MIT** 或 **Apache-2.0**（以仓库根目录 LICENSE 为准）授权发布，
允许修改、再分发与商业使用，保留版权声明即可。

由于不含任何第三方题库、教材原文或商业数据库内容，不存在需要单独声明的第三方著作权；
如需引入第三方素材（字体、图形库等），必须在仓库根目录的 `THIRD_PARTY.md` 中单独登记来源与许可。

## 四、编写约定

1. 所有 JSON 文件使用 UTF-8 编码且不带 BOM，缩进 2 个空格，以换行结尾；
2. 文案中禁止使用 emoji；
3. 所有面向用户的文案与数据必须外置到 assets，禁止硬编码进 Kotlin 源码；
4. 知识点 `id` 一经发布不得复用或重排，新增节点在末尾递增编号，废弃节点保留并从树中移除父级引用；
5. 边表修改后必须重新跑环检测，出现环则拒绝写入并记录日志；
6. 提示词文本任何改动都必须同步升级 `prompt_version.json` 中的 `promptVersion`，否则旧缓存会被错误复用；
7. 学科扩展（物理、化学）沿用完全相同的文件结构与字段命名。

## 五、校验方法

在仓库根目录执行：

```bash
python -m json.tool app/src/main/assets/seed/knowledge_point_math.json > /dev/null
python -m json.tool app/src/main/assets/seed/knowledge_point_edge_math.json > /dev/null
python -m json.tool app/src/main/assets/seed/latex_spoken_rules.json > /dev/null
python -m json.tool app/src/main/assets/seed/kpoint_generic_pitfalls.json > /dev/null
python -m json.tool app/src/main/assets/graph_layout/graph_layout_math.json > /dev/null
python -m json.tool app/src/main/assets/prompts/prompt_version.json > /dev/null
```

约定：`subject` 字段当前固定为 `"math"`；节点 `parentId` 为 `null` 表示该节点是树的第一层（章）。
