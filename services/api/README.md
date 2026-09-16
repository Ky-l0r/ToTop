# ToTop 后端 API（`services/api`）

面向安卓 / iOS / 鸿蒙三端「考试失分分析」工具的后端服务：**上传试卷与答题卡 → 多模态 AI 归因 → 趋势跟踪 → 点数计费**。

技术栈：Python 3.13 + FastAPI + SQLAlchemy 2.0（async）+ Pydantic v2；本地开发默认 SQLite，Redis 可选（缺失时自动降级）。

---

## 1. 快速开始

```powershell
cd D:\Subject\ToTop\services\api

# 1) 虚拟环境（仓库中已存在 .venv，可跳过创建）
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt

# 2) 配置（可选；不创建 .env 也能直接跑，全部使用默认值）
Copy-Item .env.example .env

# 3) 建表 + 灌演示数据（1 用户 / 6 次考试 / 多学科成绩 / 1 条完成的分析）
.\.venv\Scripts\python.exe scripts\seed.py

# 4) 启动服务
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000

# 5) 另开一个终端跑端到端冒烟测试（69 项）
.\.venv\Scripts\python.exe scripts\smoke_test.py
```

- 接口文档（仅 `ENV=dev`）：<http://127.0.0.1:8000/docs>
- 健康检查：<http://127.0.0.1:8000/health>
- 演示账号：手机号 `13800008888`，密码 `a123456`，初始 5 点数

常用可选参数：

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
.\.venv\Scripts\python.exe scripts\seed.py --reset          # 清空演示数据后重灌
.\.venv\Scripts\python.exe scripts\smoke_test.py --base-url http://127.0.0.1:8000 --phone 13900001111
```

> 手机真机联调：把 `.env` 里的 `PUBLIC_BASE_URL` 改成本机局域网地址（如 `http://192.168.1.10:8000`），
> 否则上传返回的图片 URL 在手机上不可访问。

---

## 2. 目录结构

```
services/api/
├── app/
│   ├── main.py                FastAPI 实例：中间件、异常处理器、/static 挂载、/health、路由汇总、lifespan
│   ├── api/
│   │   ├── deps.py            公共依赖：DbSession / CurrentUser / PageParams / ip_rate_limit
│   │   └── v1/
│   │       ├── router.py      汇总全部子路由（统一 EnvelopeRoute）
│   │       └── endpoints/     auth users credits orders exams uploads analyses trends meta
│   ├── core/                  配置、错误码、JWT/密码、响应信封、限流、工具
│   ├── db/                    async engine / sessionmaker / get_db / 建表
│   ├── models/                SQLAlchemy 2.0 模型（user / credits / exam / analysis / enums）
│   ├── schemas/               Pydantic v2 出入参（含前端契约结构 AnalysisDetail）
│   ├── providers/
│   │   ├── ai/                base（抽象）+ qwen（百炼）+ mock（无 Key 时的假数据）
│   │   └── storage/           base + local（默认）+ s3（S3/OSS 兼容，未实测）
│   ├── services/              credits（账本）/ sms（验证码）/ analysis（分析编排）
│   └── tasks/queue.py         asyncio 后台任务调度 + ImportTask 进度
├── scripts/
│   ├── seed.py                演示数据（幂等）
│   └── smoke_test.py          端到端冒烟测试（69 项断言）
├── storage/                   本地上传目录（通过 /static 访问）
├── .env.example               全部配置项示例
└── requirements.txt
```

---

## 3. 接口速查

**统一响应体**（除 `/health`）：`{"code": 0, "message": "ok", "data": {...}}`；`code != 0` 时 HTTP 状态码同步（400 / 401 / 402 / 404 / 429 / 500），`message` 可直接展示。

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | 否 | `{status, version, ai_provider, db, db_ok, env}`（**不包信封**） |
| GET | `/api/v1/meta/subjects` | 否 | 学科字典：学科 → 模块 → 章节（首次访问自动灌入 9 个学科） |
| POST | `/api/v1/auth/sms/send` | 否 | `{phone, scene}` → `{sent, dev_code?, expires_in}` |
| POST | `/api/v1/auth/register` | 否 | `{phone, sms_code, password?}` → `{access_token, refresh_token, user}` |
| POST | `/api/v1/auth/login/password` | 否 | 手机号不存在 → 40014 |
| POST | `/api/v1/auth/login/sms` | 否 | 手机号不存在 → 40014（前端据此转注册） |
| POST | `/api/v1/auth/refresh` | 否 | `{refresh_token}` → 新令牌对（旧的立即作废） |
| POST | `/api/v1/auth/password` | 是 | `{old_password?, new_password}` → `{ok:true}` |
| GET / PATCH | `/api/v1/users/me` | 是 | 用户信息（手机号脱敏）/ 修改昵称头像 |
| GET | `/api/v1/credits/balance` | 是 | `{credits}` |
| GET | `/api/v1/credits/ledger` | 是 | `{items:[{id,delta,reason,balance_after,remark,created_at}], total}` |
| GET | `/api/v1/credits/ad-progress` | 是 | `{watched, needed, per_reward, credits}` |
| POST | `/api/v1/credits/ad-reward` | 是 | `{platform, event_id, watched_seconds}` → `{credited, credits, progress, duplicated}` |
| GET | `/api/v1/orders/plans` | 否 | 套餐列表（表为空时自动灌入 9 个默认套餐） |
| POST | `/api/v1/orders` | 是 | `{plan_id, channel}` → `{order_id, order_no, amount_cents, pay_params}` |
| POST | `/api/v1/orders/{order_no}/pay` | 是 | 模拟支付（仅 dev，开关 `ENABLE_MOCK_PAY`） |
| POST | `/api/v1/orders/notify/{channel}` | 否 | 支付回调**占位**，验签未实现 |
| GET | `/api/v1/orders` | 是 | 我的订单（分页） |
| GET / POST | `/api/v1/exams` | 是 | 考试列表 / 新建考试 |
| GET / DELETE | `/api/v1/exams/{exam_id}` | 是 | 详情（含 `papers[]`、`answer_sheets[]`、`latest_analysis`）/ 级联删除 |
| POST | `/api/v1/exams/{exam_id}/papers` | 是 | `{subject_code,title,source_type,object_keys[]}` → `{paper_id, task_id}` |
| POST | `/api/v1/exams/{exam_id}/answer-sheets` | 是 | `{...,is_graded,object_keys[],score?,full_score?}` → `{answer_sheet_id, task_id}` |
| POST | `/api/v1/uploads/direct` | 是 | multipart `file` + `kind` → `{object_key, url, size, content_type, kind}` |
| GET | `/api/v1/tasks/{task_id}` | 是 | `{id,status,progress,message,error,result_ref}` |
| POST | `/api/v1/analyses` | 是 | `{exam_id, answer_sheet_id?, force?}` → `{analysis_id, status, credits_cost, credits_balance}` |
| GET | `/api/v1/analyses/{id}` | 是 | 完整分析结果（见第 4.6 节） |
| GET | `/api/v1/analyses` | 是 | 分析历史（`exam_id` / `page` / `size`） |
| GET | `/api/v1/trends` | 是 | `limit` / `subjects=math,chinese` |

### 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| 40001 / 40002 | 400 | 参数校验失败 / 缺少参数 |
| 40010 / 40011 / 40012 | 400 | 验证码错误 / 已过期 / 发送过于频繁（含每日上限） |
| 40013 / 40014 | 400 | 手机号已注册 / 手机号未注册 |
| 40015 / 40016 | 400 | 密码错误 / 账号未设置密码 |
| 40020 / 40021 | 400 | 广告事件重复（当前不返回，见 4.5）/ 观看时长过短（预留未启用） |
| 40030 / 40031 | 400 | 上传文件过大 / 文件类型不允许 |
| 40040 / 40050 | 400 | 订单状态不允许支付 / 考试没有答题卡 |
| 40100–40105 | 401 / 403 | 未登录 / 令牌过期 / 令牌无效 / 刷新令牌失效 / 无权访问 / 账号禁用 |
| 40200 | 402 | **点数不足**（前端弹窗引导获取点数） |
| 40400 | 404 | 资源不存在 |
| 42900 | 429 | 触发限流 |
| 50000–50003 | 500 | 服务端异常 / 上游（模型）异常 / 存储异常 / 数据库异常 |

---

## 4. 关键实现约定

### 4.1 响应信封与异常

- `/api/v1/**` 的所有路由都用 `EnvelopeRoute`，端点返回的业务对象自动包成 `{code, message, data}`；
- `/health` 与 `/` 走普通 `APIRoute`，**不包信封**（契约要求）；
- 任何异常（含未捕获的 500）都返回统一 JSON 信封，堆栈只写服务端日志（`app` 日志）；
- 每个响应都带 `X-Trace-Id`（等于错误体里的 `trace_id`），便于前后端对日志。

### 4.2 点数账本（`app/services/credits.py`）

- 余额以 `users.credits` 为准，`credit_ledgers` 只增不改，`balance_after` 为快照；
- 扣减是一条原子语句 `UPDATE users SET credits = credits - :cost WHERE id = :uid AND credits >= :cost`，
  **影响行数为 0 即余额不足**，抛 40200 且不产生任何副作用；
- 扣费与业务写入（建立 `Analysis`）在**同一个事务**内提交：要么都成功，要么都回滚；
- 每次变动都带幂等键：`register:{user_id}` / `analysis:{analysis_id}` / `ad:{event_id}` /
  `order:{order_no}` / `refund:{analysis_id}`，重复调用只生效一次。

### 4.3 分析链路（`app/services/analysis.py`）

1. 校验考试归属（非本人 → 40104）与答题卡（没有 → 40050）；
2. `force=false` 时，若该考试已有 **pending / running / succeeded** 的分析，直接复用**不重复扣费**；
   `force=true` 强制重新分析并**再次扣费**（这也是冒烟测试验证 40200 的方式）；
3. 原子扣费 → 同事务建 `Analysis(pending)` → 提交 → `asyncio.create_task` 派发后台协程；
4. 后台协程（独立会话）：`running` → 组装输入（题目 OCR 文本 + 答题卡图片 data URL，图片张数受
   `AI_MAX_IMAGES` 限制）→ 调 Provider → Pydantic 校验 → 归一化（得分率、失分点编号唯一化）→ 落库
   → 回写答题卡分数（只补空缺，不覆盖老师给分）→ `succeeded`；
5. 失败：`status=failed` + `error`（**异常绝不静默**），并写一条 `reason=refund` 的退还流水；
6. 进程重启时，启动阶段会把遗留的 `pending` / `running` 分析与导入任务标记为失败并退还点数
   （幂等键 `refund:{analysis_id}`），避免用户白扣。

### 4.4 广告奖励（`/credits/ad-reward`）

- 每上报一次有效观看（`event_id` 全局唯一）进度 +1；达到 `AD_WATCH_NEEDED`（默认 10）时发放
  `AD_REWARD_CREDITS`（默认 1）点数并把进度清零；
- 进度不进额外的计数表：`ad_events.credited=true` 的那条记录表示它触发了发奖，
  进度 = 该用户最后一条发奖记录**之后**的观看条数（天然抗回调乱序 / 重发）；
- **重复 `event_id` 的返回约定**：HTTP 200 + `code=0`，但 `credited=false`、`duplicated=true`，
  余额与进度都不变（契约允许 0 或 40020，本实现固定用 **0**，前端按成功处理即可）；
- 桌面/浏览器联调可直接用 `platform=mock`；未知平台名会回退记为 `mock`；
- 当前**不校验** `watched_seconds`（40021 预留未启用），服务端验签也未实现（见第 7 节）。

### 4.5 套餐与支付

- `GET /orders/plans` 首次访问时若 `plans` 表为空，自动灌入默认套餐：
  微信 / 支付宝 `1 点=100 分`、`5 点=500 分`、`20 点=2000 分`；Apple IAP `1 点=150 分`、`5 点=750 分`、`20 点=3000 分`；
- `recommended` 的算法：**同一渠道内单点价格最低者**，并列时取档位居中的那个（因此三个渠道的 5 点档被标记为推荐）；
- `POST /orders/{order_no}/pay` 是**模拟支付**：仅 `ENV=dev|test` 可用（可用 `ENABLE_MOCK_PAY` 覆盖），
  到账走账本幂等键 `order:{order_no}`，重复支付不会重复到账；
- `pay_params` 目前是占位参数（微信 `prepay_id`、支付宝 `orderStr`、IAP `product_id`），真实接入时替换。

### 4.6 `GET /analyses/{id}` 的字段（与前端契约一致）

```json
{
  "id": "…", "exam_id": "…", "answer_sheet_id": "…", "status": "succeeded",
  "total_score": 579.0, "full_score": 740.0, "score_rate": 0.7824,
  "subjects": [{ "subject_code": "math", "subject_name": "数学", "score": 108.0, "full_score": 150.0,
                 "rate": 0.72, "weak_module_names": ["函数与导数", "解析几何", "立体几何"] }],
  "weak_points": [{ "id": "wp1", "subject_code": "math", "subject_name": "数学",
                    "module": "函数与导数", "chapter": "导数的应用",
                    "knowledge_point": "利用导数求单调区间与含参讨论",
                    "severity": "high", "lost_score": 9.0,
                    "evidence": { "question_no": "18(1)", "question_text": "…",
                                  "student_answer": "…", "correct_answer": "…", "error_type": "含参分类讨论遗漏" },
                    "suggestion": "…" }],
  "improvement_plan": { "summary": "…", "priority_order": ["…"],
                        "steps": [{ "title": "…", "detail": "…", "estimated_hours": 6.0, "resources": ["…"] }],
                        "weekly_plan": [{ "week": 1, "focus": "…", "target": "…" }] },
  "model_name": "mock-qwen-vl-max", "credits_cost": 1,
  "created_at": "2026-09-11T15:59:19.427163Z", "finished_at": "…", "error": null
}
```

- `total_score` / `full_score` 是**参与分析各科**的分数与满分之和；`score_rate = total_score / full_score`（0~1）；
- `weak_points[].id` 由服务层统一编号（`wp1`…，保证唯一），前端可直接用作列表 key；
- 未完成时（pending / running）各字段返回空结构，不报错。

### 4.7 趋势口径（`/trends`）

- 每个点代表一次考试，`points` 按 `exam_date` **升序**；
- 优先用答题卡上已录入的分数（`score / full_score`）；该考试没有分数时回退到最近一次成功分析的各科得分率；
- 各科 rate 与 `total_rate` 都是 0~1 得分率；**缺考学科不出现在 `subjects` 里**；
- 完全没有分数数据的考试不产生数据点（避免折线图上出现误导性的 0）。

### 4.8 验证码

- 存储：Redis（`sms:code:{scene}:{phone}`，`SETEX`）；Redis 不可用时校验自动回落到 `sms_codes` 表，功能不受影响；
- 发送频率与每日上限**始终以数据库记录为准**（Redis 重启不会导致限制失效）；
- 开发环境（`ENV=dev` 且 `SMS_DEV_RETURN_CODE=true`）在响应里回传 `dev_code` 并打印到日志；
  生产必须关闭（`sms_provider=aliyun` 的真实发送尚未实现）；
- `register` 与 `login/sms` 兼容彼此场景的验证码（前端「验证码登录未注册自动转注册」流程），
  且 `login/sms` **先判断手机号是否存在**，不会误消费注册用的验证码。

### 4.9 上传

- 扩展名白名单（`ALLOWED_UPLOAD_EXT`，否则 40031）+ 分块累计大小校验（超过 `MAX_UPLOAD_MB` → 40030，不会先把超大文件读进内存）；
- `kind` 只允许 `paper` / `answer_sheet` / `avatar`，同时作为存储目录名（防目录穿越由 `LocalStorage` 负责）；
- 返回的 `url` = `{PUBLIC_BASE_URL}{STORAGE_URL_PREFIX}/{object_key}`，由 `app.mount` 的 `/static` 提供访问。

### 4.10 后台任务（`app/tasks/queue.py`）

- `asyncio.create_task` 进程内调度，任务状态写 `import_tasks` 表，前端轮询 `GET /tasks/{task_id}`；
- 后台协程**必须自己捕获异常**并写入业务表，队列层只做兜底日志；
- 关闭时先给 2 秒宽限再取消剩余任务，随后释放 Redis 与数据库连接池。

---

## 5. 冒烟测试（`scripts/smoke_test.py`）

对**真实运行的服务**发起 HTTP 请求，共 **72 项断言**，覆盖：

| 分组 | 覆盖点 |
| --- | --- |
| 服务可用性（5） | `/health` 状态与字段、`/meta/subjects` 字典结构 |
| 认证（14） | 发码 + `dev_code`、未注册登录 40014、注册赠送 1 点、手机号脱敏、`/users/me`、改昵称、余额、刷新令牌旋转、改密码、新密码登录、密码错误 40015、无令牌 40100 |
| 考试 / 上传 / 登记（15） | 建考试、列表、真实 PNG 上传 + `/static` 回读、非法扩展名 40031、3 份试卷 + 3 张答题卡、两个 OCR 后台任务轮询到 succeeded、考试详情（3 试卷 / 3 答题卡 / 3 学科） |
| 分析（14） | 发起分析扣 1 点、轮询到 succeeded、≥3 学科、≥6 失分点、失分点 id 唯一、evidence 齐全、总分/满分/得分率自洽、提升方案完整、model_name、列表查询、**点数不足 40200 且余额/记录数不变** |
| 趋势与账本（7） | 数据点升序、包含本次考试、学科过滤、数学得分率 0.633、`subjects` 元数据、账本含 register_bonus 与 analysis（delta=-1、balance_after=0） |
| 广告（4） | 连打 10 次仅第 10 次发奖、发奖后进度归零、重复 `event_id` 幂等、`ad-progress` 与配置一致 |
| 套餐与充值（8） | 9 个套餐、定价、每渠道 1 个推荐、下单 500 分、模拟支付到账（1→6 点）、重复支付幂等、订单列表、回调占位 |
| 删除与异常码（5） | 登记答题卡 + 触发分析、**删除考试后其分析记录被级联清理**、删除后详情 40400 |

最近一次运行结果：

```
  冒烟测试结果：PASS 72 / FAIL 0（共 72 项）
```

> 测试使用**随机手机号**，可反复执行；`--phone` 可指定号码。测试数据会真实落库（不影响演示数据）。

---

## 6. 配置

全部配置项及中文说明见 [`.env.example`](.env.example)。最常改的几项：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `ENV` | `dev` | `prod` 会关闭 `/docs`、禁用 `dev_code`、关闭模拟支付 |
| `DATABASE_URL` | `sqlite+aiosqlite:///./dev.db` | 生产换 PostgreSQL（`postgresql+asyncpg://…`） |
| `DASHSCOPE_API_KEY` | 空 | 留空自动使用 Mock Provider |
| `PUBLIC_BASE_URL` | `http://127.0.0.1:8000` | 影响上传返回的图片 URL |
| `ANALYSIS_CREDIT_COST` / `REGISTER_BONUS_CREDITS` | 1 / 1 | 计费与赠送 |
| `AD_WATCH_NEEDED` / `AD_REWARD_CREDITS` | 10 / 1 | 广告兑换规则 |
| `RATE_LIMIT_PER_MINUTE` | 240 | 单 IP 每分钟请求上限 |

---

## 7. 待实现 / 已知风险（诚实清单）

**未实现的功能（按优先级）**

1. **支付验签与真实支付**：`POST /orders/notify/{channel}` 是占位接口，任何请求都返回 `{ok:true}`，
   没有验签、没有幂等发货；微信 / 支付宝 / App Store 内购的真实接入与票据校验均未实现。
   生产上线前必须补齐，否则任何人都能伪造到账。
2. **短信通道**：`SMS_PROVIDER=aliyun` 未实现真实发送（只写库 + 日志），上线需接入阿里云短信并在发送接口加图形验证码。
3. **广告回调验签**：`/credits/ad-reward` 直接信任客户端上报的 `event_id`，服务端未校验广告平台回调，
   点数可被脚本刷（上线前需接入穿山甲/优量汇的服务端回调校验，并加风控）。
4. **图片预处理与文档解析**：压缩到 1600px、EXIF 校正、模糊度检测、PDF/Word 文本抽取（PyMuPDF / python-docx）都未实现；
   目前 `source_type=pdf/docx` 只是登记文件，OCR 依旧走视觉模型，成本更高。
5. **两阶段调用与缓存复用**（`docs/03` 第 2 节）：题目结构化结果（`papers.ocr_text`）已落库，
   但分析时仍会把图片重新送给模型，未做「同一试卷只识别一次」的成本优化。
6. **失败重试与分批降级**（`docs/03` 第 4 节）：当前只有「调用失败 → 标记 failed + 退费」，
   没有按科目分批重试、没有 JSON 修正重试、没有 `confidence` 低置信度标注。
7. **对象存储未实测**：`S3Storage` 按 S3 兼容协议编写但未在真实 OSS/S3 验证；
   `boto3` 也不在 `requirements.txt` 中（要用需自行安装）。
8. **alembic 迁移**：`alembic` 已在依赖里，但没有迁移脚本，建表依赖 `AUTO_CREATE_TABLES=true`（生产应改为迁移）。
9. **自动化测试**：只有 `scripts/smoke_test.py`（端到端），没有 pytest 单元测试；`RateLimit` 类依赖在
   `from __future__ import annotations` 下无法直接用作 FastAPI 依赖（已在 `app.api.deps.ip_rate_limit` 中包装），缺少回归测试保护。
10. **邀请码奖励**：只生成邀请码，邀请关系与奖励逻辑未实现。

**风险与部署注意**

1. **后台任务只在单进程内**：`asyncio.create_task` 调度意味着 `uvicorn --workers N` 多进程部署时，
   任务只跑在接收请求的那个进程；状态虽在数据库，但「启动时清理遗留任务」会被多个进程重复触发
   （退费是幂等的，但会打日志）。建议单 worker 部署，或改造成 Celery/RQ。
2. **`/static` 直接暴露 storage 目录**：没有任何鉴权，任何人知道 URL 即可访问试卷、答题卡图片。
   生产应使用私有对象存储 + 预签名 URL，或给静态路由加鉴权。
3. **SQLite 仅适合开发**：并发写入弱、无 JSONB 索引；生产必须换 PostgreSQL。
4. **限流/验证码降级**：Redis 未启动时限流走进程内内存、验证码走数据库，多进程下不准确；
   生产必须保证 Redis 可用。
5. **CORS 默认 `*`**：生产必须收紧到具体域名。
6. **`refresh_tokens` 无清理任务**：过期/已撤销记录会一直保留，建议加定时清理。
7. **`sms_codes`、`ad_events` 会持续增长**：需要归档策略（当前仅靠索引）。
8. **`SECRET_KEY` 默认值**：生产必须替换，否则令牌可被伪造。
9. **Windows 开发环境**：服务通过 `uvicorn` 启动时用的是 Proactor 事件循环，后台协程与 SQLite
   （WAL + `busy_timeout=5000`）配合正常；但不要把 uvicorn 与长事务脚本混跑（`scripts/seed.py` 请在服务空闲时执行）。
10. **mock 数据**：未配置 `DASHSCOPE_API_KEY` 时分析结果来自 `app/providers/ai/mock_sample.json`，
   仅用于联调演示，不能用于真实教学结论。

---

## 8. 与 `docs/` 的差异说明

以下处本实现与仓库内文档不一致，**以代码为准**（代码即当前契约），建议后续同步文档：

| 项 | 文档（`docs/`） | 实现 |
| --- | --- | --- |
| 错误码分段 | `docs/02-API契约.md` 用 40102=密码错误、40103=无权访问、40401=未注册、40201=点数不足、40901=重复、42901=限流、50001=服务端异常 | `app/core/errors.py` 实际为 40010–40051 / 40100–40105 / **40200** / 40400 / 42900 / 50000–50003（与任务要求一致） |
| dev 验证码 | 固定 `123456` | 随机 6 位数字，通过 `dev_code` 回传并打印日志（更安全，允许 `SMS_DEV_RETURN_CODE=false` 关闭） |
| 令牌有效期 | access 2 小时 / refresh 30 天 | 默认 24 小时 / 14 天，可用 `ACCESS_TOKEN_EXPIRE_MINUTES`、`REFRESH_TOKEN_EXPIRE_DAYS` 调整 |
| `/health` 字段 | `{status, version, ai_provider, db}` | 额外返回 `db_ok`、`env`（超集，前端可忽略） |
| 广告重复上报 | 未明确 | 固定返回 `code=0` + `duplicated=true`（见 4.4） |
| 分析入口参数 | `{exam_id, answer_sheet_id?, force?}` | 一致；补充约定「`force=false` 且已有分析时复用且不扣费」 |
| 后台任务 | `docs/01` 说「不引入 Celery」 | 一致（`asyncio.create_task` + `import_tasks` 表） |

---

## 9. 接口自测小抄

```powershell
# 健康检查
curl.exe http://127.0.0.1:8000/health

# 发验证码（dev 模式会回传 dev_code）
curl.exe -X POST http://127.0.0.1:8000/api/v1/auth/sms/send -H "Content-Type: application/json" -d "{\"phone\":\"13800008888\",\"scene\":\"login\"}"

# 密码登录
curl.exe -X POST http://127.0.0.1:8000/api/v1/auth/login/password -H "Content-Type: application/json" -d "{\"phone\":\"13800008888\",\"password\":\"a123456\"}"

# 带上 access_token 后查趋势
curl.exe "http://127.0.0.1:8000/api/v1/trends?limit=10" -H "Authorization: Bearer <access_token>"
```
