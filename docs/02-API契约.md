# API 契约（v1）

- **Base URL**：`https://<你的域名>/api/v1`
- **鉴权**：`Authorization: Bearer <access_token>`（JWT，有效期 2 小时；`refresh_token` 有效期 30 天）
- **统一响应体**：

```json
{ "code": 0, "message": "ok", "data": { } }
```

`code = 0` 表示成功；非 0 时 HTTP 状态码同步（4xx/5xx），`message` 为可直接展示给用户的中文文案。

## 错误码

| code | HTTP | 含义 | 前端处理 |
| --- | --- | --- | --- |
| 0 | 200 | 成功 | — |
| 40001 | 400 | 参数校验失败 | toast `message` |
| 40101 | 401 | 未登录 / token 过期 | 清 token → 跳账号页 |
| 40102 | 401 | 密码错误 | toast |
| 40103 | 403 | 无权访问该资源（非本人考试） | toast |
| 40401 | 404 | 资源不存在（含"手机号未注册"） | 验证码登录时自动转注册 |
| 40201 | 402 | **点数不足** | 弹窗引导去账号页获取点数 |
| 40901 | 409 | 重复操作（幂等键冲突） | 忽略，按成功处理 |
| 42901 | 429 | 触发限流 | toast「操作过于频繁」 |
| 50001 | 500 | 服务端异常 | toast「服务开小差了」 |
| 50002 | 500 | 模型调用失败 | 提示更换更清晰的图片后重试 |

---

## 1. 健康检查与字典

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | 否 | 返回 `{status, version, ai_provider, db}` |
| GET | `/meta/subjects` | 否 | 学科与知识模块字典（学科→模块→章节三级） |

## 2. 认证 `/auth`

| 方法 | 路径 | 请求体 | 返回 |
| --- | --- | --- | --- |
| POST | `/auth/sms/send` | `{phone, scene: register\|login\|reset}` | `{sent:true, dev_code?}` |
| POST | `/auth/register` | `{phone, sms_code, password?}` | `{access_token, refresh_token, user}` |
| POST | `/auth/login/password` | `{phone, password}` | 同上 |
| POST | `/auth/login/sms` | `{phone, sms_code}` | 同上 |
| POST | `/auth/refresh` | `{refresh_token}` | `{access_token, refresh_token}` |
| POST | `/auth/password` | `{old_password?, new_password}` | `{ok:true}` |

约定：

- 注册成功**自动赠送 1 点数**并写一条 `reason=register_bonus` 的账本记录
- 开发模式（`SMS_DEV_MODE=true`）验证码固定 `123456` 并在响应里回传 `dev_code`，生产必须关闭
- 手机号在响应中一律脱敏为 `138****8888`

## 3. 用户 `/users`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/users/me` | `{id, phone, nickname, avatar_url, credits, invite_code, created_at}` |
| PATCH | `/users/me` | `{nickname?, avatar_url?}` |

## 4. 点数 `/credits`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/credits/balance` | `{credits}` |
| GET | `/credits/ledger?page=1&size=20` | `{items:[{id, delta, reason, balance_after, remark, created_at}], total}` |
| GET | `/credits/ad-progress` | `{watched, needed, per_reward, credits}` |
| POST | `/credits/ad-reward` | 入参 `{platform, event_id, watched_seconds}`，返回 `{credited, credits, progress:{watched,needed}}` |

`reason` 枚举：`register_bonus` 注册赠送 / `analysis` 分析消耗 / `ad_reward` 广告奖励 / `purchase` 充值 / `admin_adjust` 系统调整 / `refund` 退还。

**广告奖励语义**：每上报一次有效观看，`watched + 1`；达到 `needed`（默认 10）时发放 `per_reward`（默认 1）点数并把进度清零。同一 `event_id` 重复上报只计一次（返回 `credited:false` 且进度不变）。

## 5. 订单 `/orders`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/orders/plans` | 套餐列表 `[{id, name, credits, price_cents, currency, channel, recommended}]` |
| POST | `/orders` | `{plan_id, channel}` → `{order_id, order_no, amount_cents, pay_params}` |
| POST | `/orders/{order_no}/pay` | 模拟支付（仅开发环境）→ `{status:"paid"}` |
| POST | `/orders/notify/{channel}` | 支付平台异步回调（验签后发货，需幂等） |
| GET | `/orders` | 我的订单列表 |

`channel` 枚举：`wechat` / `alipay` / `apple_iap` / `mock`。

**定价约定**：Android / 鸿蒙 `¥1.00 = 1 点数`；iOS 走 App Store 内购 `¥1.50 = 1 点数`（覆盖平台佣金）。

## 6. 考试 `/exams`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/exams?page=1&size=20` | `{items:[{id,name,exam_date,grade,total_full_score,subject_count,analysis_status,created_at}], total}` |
| POST | `/exams` | `{name, exam_date, grade, exam_type?, total_full_score?}` |
| GET | `/exams/{id}` | 详情，含 `papers[]`、`answer_sheets[]`、最新分析摘要 |
| DELETE | `/exams/{id}` | 级联删除关联的题目、答题卡、分析记录 |

## 7. 上传与导入 `/uploads` `/exams/{id}/...`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/uploads/direct` | `multipart/form-data`：`file` + `kind=paper\|answer_sheet` → `{object_key, url, size, content_type}` |
| POST | `/exams/{exam_id}/papers` | `{subject_code, title, source_type, object_keys[]}` → `{paper_id, task_id}` |
| POST | `/exams/{exam_id}/answer-sheets` | `{subject_code, paper_id?, source_type, is_graded, object_keys[], score?, full_score?}` → `{answer_sheet_id, task_id}` |
| GET | `/tasks/{task_id}` | `{id, status, progress, message, error, result_ref}` |

`source_type` 枚举：`photo`（拍照）/ `image`（相册图片）/ `pdf` / `docx`。

`is_graded` 语义：

- `true`：老师已批改，AI **只做失分归因**（默认、推荐、准确率最高）
- `false`：未批改，AI 需结合标准答案判分，`papers` 中的标准答案成为必需输入

## 8. 分析 `/analyses`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/analyses` | `{exam_id, answer_sheet_id?, force?}` → `{analysis_id, status, credits_cost, credits_balance}` |
| GET | `/analyses/{id}` | 完整分析结果 + 三阶段解锁状态（见下） |
| GET | `/analyses?exam_id=&page=&size=` | 分析列表 |
| POST | `/analyses/{id}/stages/{stage}/unlock` | 上报一次激励视频观看，累计够次数即解锁该阶段 |
| POST | `/analyses/{id}/stages/{stage}/unlock-by-credit` | 用点数跳过广告、直接解锁该阶段 |

**计费规则（小程序版本）**：`POST /analyses` **不消耗点数**（`ANALYSIS_CREDIT_COST=0`），分析本身免费。
变现来自三阶段解锁：看激励视频可免费解锁，或用点数跳过广告。
把 `ANALYSIS_CREDIT_COST` 配成 `>0` 即可恢复「按次扣费」，扣费与失败退费逻辑完整保留。

`GET /analyses/{id}` 的 `data`：

```json
{
  "id": "ana_xxx",
  "exam_id": "exam_xxx",
  "status": "succeeded",
  "total_score": 412.0,
  "full_score": 600.0,
  "score_rate": 0.687,
  "subjects": [
    { "subject_code": "math", "subject_name": "数学", "score": 95, "full_score": 150,
      "rate": 0.633, "weak_module_names": ["函数与导数", "解析几何"] }
  ],
  "weak_points": [
    { "id": "wp_1", "subject_code": "math", "subject_name": "数学",
      "module": "函数与导数", "chapter": "导数应用",
      "knowledge_point": "利用导数求含参函数的单调区间与极值",
      "severity": "high", "lost_score": 10,
      "evidence": { "question_no": "18(2)", "question_text": "...",
                    "student_answer": "...", "correct_answer": "...",
                    "error_type": "分类讨论不完整" },
      "suggestion": "..." }
  ],
  "improvement_plan": {
    "summary": "...",
    "priority_order": ["数学·导数与圆锥曲线（22 分）"],
    "steps": [{ "title": "...", "detail": "...", "estimated_hours": 8, "resources": ["..."] }],
    "weekly_plan": [{ "week": 1, "focus": "...", "target": "..." }]
  },
  "model_name": "qwen-vl-max",
  "created_at": "2026-09-10T21:30:00+08:00",
  "stages": [
    { "stage": 1, "name": "基础分析", "ad_required": 1, "watched": 1,
      "unlocked": true, "unlock_method": "ad", "content": { "...": "..." } },
    { "stage": 2, "name": "进阶分析", "ad_required": 2, "watched": 0,
      "unlocked": false, "unlock_method": null, "content": null },
    { "stage": 3, "name": "归纳总结", "ad_required": 2, "watched": 0,
      "unlocked": false, "unlock_method": null, "content": null }
  ]
}
```

### 8.1 三阶段解锁

| 阶段 | `name` | 默认所需广告次数 | 解锁后的 `content` |
| --- | --- | --- | --- |
| 1 | 基础分析 | 1 | `{summary, score_rate, total_score, full_score, level_text, top_subjects[]}`，`summary` 不超过 200 字 |
| 2 | 进阶分析 | 2 | `{weak_questions[], total_lost}`，按失分降序，最多 10 题 |
| 3 | 归纳总结 | 2 | `{modules[], suggestions[], improvement_plan}`，`modules` 按累计失分降序 |

**关键约定**

- **未解锁阶段的 `content` 一律为 `null`** —— 内容不下发到客户端，无法绕过广告直接读取；
- 三个阶段的内容由**同一次模型调用的结果派生**（图片只送模型一次，成本最低），解锁只控制可见性；
- 所需次数由 `STAGE_AD_REQUIREMENTS` 配置（默认 `1,2,2`），并通过 `ad_required` 下发给前端。

**上报一次观看** `POST /analyses/{id}/stages/{stage}/unlock`

```json
{ "platform": "wechat", "event_id": "ad_xxx_1_1730000000", "watched_seconds": 30 }
```

```json
{ "stage": 1, "watched": 1, "ad_required": 1, "unlocked": true, "credited": true,
  "duplicated": false, "unlock_method": "ad", "content": { "...": "..." } }
```

- `event_id` 由客户端生成、全局唯一，**重复上报幂等**（返回 `duplicated=true`，进度不增长）；
- `watched_seconds` 低于 `AD_REWARD_MIN_SECONDS`（默认 15 秒）返回 `40001`，不计入进度；
- `stage` 只能是 `1` / `2` / `3`，否则 `40001`。

**用点数解锁** `POST /analyses/{id}/stages/{stage}/unlock-by-credit`

消耗 `STAGE_UNLOCK_CREDIT_COST`（默认 1）点数，返回体额外带 `credits_balance`。
点数不足返回 `40200`（HTTP 402）**且不产生任何副作用**（不解锁、不扣费）。

## 9. 趋势 `/trends`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/trends?limit=10&subjects=math,chinese` | 见下 |

```json
{
  "points": [
    { "exam_id": "exam_01", "exam_name": "高二上·开学摸底考", "exam_date": "2026-03-08",
      "total_rate": 0.641, "subjects": { "math": 0.62, "chinese": 0.71 } }
  ],
  "subjects": [{ "code": "math", "name": "数学" }]
}
```

- `points` 按考试时间**正序**，前端直接作为折线图横轴
- 各科 `rate` 与 `total_rate` 均为 **0~1 的得分率**（分数 ÷ 满分），保证不同满分的学科可同图比较
- 缺考学科不出现在 `subjects` 对象里，前端绘制时跳过该点（折线断开）
