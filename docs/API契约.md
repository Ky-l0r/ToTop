# API 契约

本文定义 To Top 与模型厂商之间的接口契约，以及四个阶段的输出结构。
实现依据为《AI 辅助学习 App 开发方案》6.2 至 6.4 节；客户端 DTO 位于
`app/src/main/java/com/totop/app/domain/model/AiSolveResult.kt`。

## 一、端点

全部请求走 OpenAI 兼容的 `POST {baseUrl}/chat/completions`。

Base URL 归一化规则（见 `LlmEndpoints`）：

1. 去除首尾空白
2. 收敛重复斜杠（不触碰 `https://` 中的双斜杠）
3. 去掉结尾斜杠
4. 若已以 `/chat/completions` 结尾则不再拼接

因此 `https://host/v1`、`https://host/v1/`、`https://host/v1/chat/completions`
三种写法都能正确工作。

### 1.1 预置端点

| 厂商 | Base URL | 文本模型 | 视觉模型 |
| --- | --- | --- | --- |
| DashScope | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `qwen-vl-plus` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` | 无 |
| Moonshot | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` | `moonshot-v1-8k-vision-preview` |
| SiliconFlow | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-7B-Instruct` | `Qwen/Qwen2-VL-7B-Instruct` |
| 本地 Ollama | `http://127.0.0.1:11434/v1` | `qwen2.5` | `qwen2-vl` |

## 二、请求

### 2.1 请求头

| 头部 | 值 | 说明 |
| --- | --- | --- |
| Content-Type | `application/json` | 固定 |
| Accept | `text/event-stream` 或 `application/json` | 流式或非流式 |
| Authorization | `Bearer <用户 Key>` | Key 为空时不发送，供本地 Ollama 使用 |
| X-Client-Request-Id | 客户端幂等 ID | 每次业务请求唯一，重试沿用同一个 |
| X-Client-App | `ToTop` | 应用标识，不含任何用户信息 |

**安全约束**：Authorization 头是全项目唯一出现明文 Key 的位置。
任何日志、错误信息、崩溃上报、导出文件都必须屏蔽该头。

### 2.2 请求体

    {
      "model": "qwen-plus",
      "messages": [
        { "role": "system", "content": "<对应阶段的提示词模板>" },
        { "role": "user", "content": "<阶段输入，通常为 JSON 字符串>" }
      ],
      "stream": true,
      "temperature": 0.2,
      "max_tokens": 2048,
      "response_format": { "type": "json_object" }
    }

字段说明：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| model | 用户填写 | 必须与厂商当前发布名一致 |
| stream | true | 流式；关闭流式时走一次性响应 |
| temperature | 0.2 | 解题需要稳定，不追求多样性 |
| max_tokens | 阶段相关 | 阶段 A 约 800，阶段 B 约 2048，阶段 C 约 2048，阶段 D 约 512 |
| response_format | json_object | 多数厂商支持；不支持时依靠提示词约束并在客户端严格校验 |

### 2.3 超时与重试

| 项 | 值 |
| --- | --- |
| 连接超时 | 15 秒 |
| 读超时 | 120 秒 |
| 写超时 | 120 秒 |
| 退避策略 | 1 秒、2 秒、4 秒 |
| 最大尝试次数 | 3 次 |
| 重试范围 | 仅幂等请求，且尚未向下游发出任何内容 |

页面销毁、用户点停止、切换题目时立即取消进行中的请求。

### 2.4 流式响应解析

服务端返回 SSE，逐行解析：

    data: {"choices":[{"delta":{"content":"..."}}]}

    data: [DONE]

客户端规则：

- 无 `data: ` 前缀的行忽略
- `[DONE]` 表示流结束
- 部分厂商不发 `[DONE]`，需用残留缓冲兜底收尾
- 每收到一个增量立即交给界面渲染，保证首字尽快出现
- 解析异常的单个帧丢弃并计数，不中断整个流

## 三、响应与错误

### 3.1 用量

若厂商返回 `usage` 字段，客户端读取并通过 `ai_trace` 表记录，同时在界面上显示本次消耗。
厂商未返回时不估算，界面上显示为未知，避免给出虚假数字。

### 3.2 错误映射

错误必须给出具体原因，禁止笼统提示「请求失败」：

| 情形 | 判定依据 | 面向用户的文案 |
| --- | --- | --- |
| Key 无效 | 401 | API Key 无效或已过期，请到厂商控制台确认 |
| 余额不足 | 402 或响应体含余额关键字 | 账户余额不足，请到厂商控制台充值 |
| 模型名错误 | 404 或响应体指明模型不存在 | 模型名不存在，请检查设置页的模型名称 |
| Base URL 错误 | 404 且路径不匹配、DNS 失败 | Base URL 不正确，请确认是否漏写 /v1 |
| 网络不通 | 连接超时、DNS 失败、无网络 | 网络不可用，请检查网络或切换本地模型 |
| 限流 | 429 | 请求过于频繁，请稍后重试 |
| 服务端错误 | 5xx | 厂商服务异常，请稍后重试 |
| 内容不合规 | 响应体含内容审核关键字 | 内容被厂商拦截，请调整题目描述后重试 |
| 响应格式错误 | JSON 反序列化失败 | 模型返回格式异常，已自动重试一次 |

### 3.3 校验失败处理

AI 输出必须经严格反序列化校验（`AiSolveResultCodec`）：

- 解析配置：忽略未知字段、宽松模式、显式 null 视为缺省
- 结构性缺失（例如没有标准解答步骤）判为失败
- 失败后以更严格的提示词重试 1 次
- 仍失败则提示用户，**绝不写入数据库**

## 四、四阶段输入输出

四个阶段的提示词正文在 `app/src/main/assets/prompts/`，
版本号在 `prompt_version.json`，参与缓存键计算。

### 4.1 阶段 A：结构化识题

输入：OCR 文本或题目图片，可附带学生的一句补充说明。

输出字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| subject | string | 学科，当前固定 math |
| grade | string | 学段年级，无法判断填「未知」 |
| stem | string | 题干正文 |
| known | string[] | 已知条件 |
| ask | string | 所求内容 |
| options | string[] | 选项原文，非选择题为空数组 |
| hasFigure | boolean | 是否含几何图形、函数图象或表格 |
| stemLatex | string | 题干公式的 LaTeX 表示 |
| warnings | string[] | 识别不确定项 |

禁止：解题、推测答案、补写题目中不存在的条件。

    {
      "type": "object",
      "additionalProperties": false,
      "required": ["subject", "grade", "stem", "known", "ask", "options", "hasFigure", "stemLatex", "warnings"],
      "properties": {
        "subject": { "type": "string", "enum": ["math"] },
        "grade": { "type": "string" },
        "stem": { "type": "string", "minLength": 1 },
        "known": { "type": "array", "items": { "type": "string" } },
        "ask": { "type": "string", "minLength": 1 },
        "options": { "type": "array", "items": { "type": "string" } },
        "hasFigure": { "type": "boolean" },
        "stemLatex": { "type": "string" },
        "warnings": { "type": "array", "items": { "type": "string" } }
      }
    }

### 4.2 阶段 B：独立求解

输入：仅结构化题目。**输入结构中不含学生过程字段**，这是代码层面的物理隔离，不是提示词约束。

输出字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| steps | object[] | 至少 2 步，每步含 idx、content、why |
| finalAnswer | string | 最终答案，选择题含选项标记 |
| method | string | 主要方法名 |
| check | string | 回代或范围自检说明 |
| confidence | number | 0 到 1，解答把握度 |

禁止：提及学生、复述学生做法、给多种答案。

    {
      "type": "object",
      "additionalProperties": false,
      "required": ["steps", "finalAnswer", "method", "check", "confidence"],
      "properties": {
        "steps": {
          "type": "array",
          "minItems": 2,
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["idx", "content", "why"],
            "properties": {
              "idx": { "type": "integer", "minimum": 1 },
              "content": { "type": "string", "minLength": 1 },
              "why": { "type": "string", "minLength": 1 }
            }
          }
        },
        "finalAnswer": { "type": "string", "minLength": 1 },
        "method": { "type": "string", "minLength": 1 },
        "check": { "type": "string", "minLength": 1 },
        "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
      }
    }

### 4.3 阶段 C：错因定位

输入：题目、阶段 B 的标准解答、学生解题过程原文。

输出字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| studentSteps | object[] | idx、content、status（correct/wrong/missing）、alignTo |
| firstError | object 或 null | stepIdx、subQuestion、type、explain、correctThinking |
| summaryCard | object | title 与 bullets |

`subQuestion` 是 1 起的小问编号，指 `stepIdx` 那条学生步骤属于第几问，与阶段 B 标准步骤里
`(1)` `(2)` 的编号一致；整题不分问或判断不出时取 `null`。客户端只用它定位「提示从哪一问开始给」，
不再把 `stepIdx`（学生步骤序号）当标准步骤下标使用——两套编号只在同一问内步序大致对应时才碰巧一致。

错因类型为固定七类枚举，禁止扩展：
审题理解、概念混淆、公式误用、计算失误、方法选择不当、步骤缺失、书写规范。

学生未提供过程时 firstError 必须为 null，不得凭空编造学生步骤。

    {
      "type": "object",
      "additionalProperties": false,
      "required": ["studentSteps", "firstError", "summaryCard"],
      "properties": {
        "studentSteps": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["idx", "content", "status", "alignTo"],
            "properties": {
              "idx": { "type": "integer", "minimum": 1 },
              "content": { "type": "string", "minLength": 1 },
              "status": { "type": "string", "enum": ["correct", "wrong", "missing"] },
              "alignTo": { "type": ["integer", "null"], "minimum": 1 }
            }
          }
        },
        "firstError": {
          "type": ["object", "null"],
          "additionalProperties": false,
          "required": ["stepIdx", "subQuestion", "type", "explain", "correctThinking"],
          "properties": {
            "stepIdx": { "type": "integer", "minimum": 1 },
            "subQuestion": { "type": ["integer", "null"], "minimum": 1 },
            "type": {
              "type": "string",
              "enum": ["审题理解", "概念混淆", "公式误用", "计算失误", "方法选择不当", "步骤缺失", "书写规范"]
            },
            "explain": { "type": "string", "minLength": 1 },
            "correctThinking": { "type": "string", "minLength": 1 }
          }
        },
        "summaryCard": {
          "type": "object",
          "additionalProperties": false,
          "required": ["title", "bullets"],
          "properties": {
            "title": { "type": "string", "minLength": 1, "maxLength": 30 },
            "bullets": { "type": "array", "minItems": 1, "maxItems": 5, "items": { "type": "string" } }
          }
        }
      }
    }

### 4.4 阶段 D：知识点抽取

输入：题目、解法要点、候选知识点清单（由客户端从内置知识点树检索得到）。

输出字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| kpoints | object[] | 0 到 3 个，含 id、name、confidence、usage |
| primaryId | string 或 null | 主要知识点，必须出现在 kpoints 中 |
| unmatched | boolean | 候选清单无法覆盖时为 true |

硬约束：id 只能取候选清单中出现的受控 ID，格式为 `KP_<学科>_<四位序号>`；
name 必须与清单中该编号的名称完全一致；禁止自造编号。

    {
      "type": "object",
      "additionalProperties": false,
      "required": ["kpoints", "primaryId", "unmatched"],
      "properties": {
        "kpoints": {
          "type": "array",
          "minItems": 0,
          "maxItems": 3,
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["id", "name", "confidence", "usage"],
            "properties": {
              "id": { "type": "string", "pattern": "^KP_MATH_[0-9]{4}$" },
              "name": { "type": "string", "minLength": 1 },
              "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
              "usage": { "type": "string", "minLength": 1, "maxLength": 40 }
            }
          }
        },
        "primaryId": { "type": ["string", "null"] },
        "unmatched": { "type": "boolean" }
      }
    }

## 五、客户端统一 DTO

四阶段结果最终归并为统一的 `AiSolveResult`，字段与方案 6.4 一致：

    {
      "question": {
        "subject": "math", "grade": "高一", "stem": "...",
        "known": ["..."], "ask": "...", "options": [], "hasFigure": false
      },
      "standardSolution": {
        "steps": [{ "idx": 1, "content": "...", "why": "..." }],
        "finalAnswer": "...", "method": "导数法"
      },
      "studentSteps": [{ "idx": 1, "content": "...", "status": "correct" }],
      "firstError": {
        "stepIdx": 3, "subQuestion": 2, "type": "公式误用",
        "explain": "...", "correctThinking": "本题应从...切入，因为..."
      },
      "knowledgePoints": [
        { "id": "KP_MATH_0312", "name": "导数与单调性", "confidence": 0.92, "usage": "用导数符号判断增减区间" }
      ],
      "summaryCard": { "title": "...", "bullets": ["..."] },
      "aiGenerated": true
    }

解析约定：

- 所有字段在 DTO 中可为空缺省，避免厂商漏字段导致整体解析失败
- 解析后做结构性校验，缺失关键结构判为失败并重试一次
- 语义清洗（非法知识点 ID、错因兜底）产生告警，写入日志，绝不静默忽略
- `aiGenerated` 为 true 时界面与导出都必须带「AI 生成」标识

## 六、缓存键

    缓存键 = sha256(图片或题干) + promptVersion + model

任一要素变化即视为不同缓存条目。修改提示词文本必须升级 `promptVersion`，
否则旧缓存会被错误复用（见 `docs/提示词规范.md`）。

## 七、调用留痕

每次调用（含失败）写入 `ai_trace` 表：

| 字段 | 说明 |
| --- | --- |
| requestId | 与请求头一致的幂等 ID |
| stage | A、B、C、D |
| model | 实际使用的模型名 |
| promptVer | 提示词版本 |
| inputTokens / outputTokens | 厂商返回的用量，未知时不估算 |
| latencyMs | 端到端耗时 |
| ok | 是否成功 |
| errorMsg | 失败原因，已脱敏，不含 Key |

设置页可查看近 30 天用量趋势，帮助用户自行判断花费。

## 八、本地模式边界

未配置 Key 或离线时，所有 AI 相关能力不可用，但必须明确提示且不崩溃：

| 能力 | 无 Key 时 |
| --- | --- |
| 拍照与相册导入 | 可用 |
| 本地离线 OCR | 可用 |
| 手工撰写笔记并归入错题本 | 可用 |
| 知识点浏览与图表 | 可用 |
| 导出与备份 | 可用 |
| 系统 TTS 朗读题干 | 可用 |
| AI 求解、错因定位、知识点抽取、生成练习、周报解读 | 不可用，明确提示并一次性引导到设置页 |
