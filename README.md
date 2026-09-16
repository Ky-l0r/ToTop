# ToTop · 考试成绩失分分析小程序

拍照或导入试卷与答题卡，用多模态 AI 分析**这次考试丢了哪些知识点、属于哪个模块、怎么补**，并用折线图跟踪各科与总分的得分率趋势。

**产品形态：微信小程序独占**（uni-app + Vue3 + TypeScript 编译到 `mp-weixin`）。

分析结果按三个阶段逐步解锁，每段看完激励视频即可查看：

| 阶段 | 内容 | 解锁条件 |
| --- | --- | --- |
| ① 基础分析 | 200 字以内的学情状况 | 看 **1** 次激励视频 |
| ② 进阶分析 | 在哪些题目上失分严重（按失分降序） | 看 **2** 次激励视频 |
| ③ 归纳总结 | 需要重点补哪些知识模块 + 学习建议 | 看 **2** 次激励视频 |

发起分析本身**不消耗点数**；不愿看广告的用户可以花 1 点数跳过广告、直接解锁某一段。

---

## 目录结构

```
ToTop/
├── apps/mobile/           # 前端：uni-app (Vue3 + TS)，编译为微信小程序
│   └── src/
│       ├── pages/
│       │   ├── trend/     # Tab 1 趋势：折线图 + 历次考试 + 概览
│       │   ├── import/    # Tab 2 导入：拍照 / 聊天文件导入 + 发起分析
│       │   ├── account/   # Tab 3 账号：微信登录、点数、广告、充值
│       │   └── analysis/  # 详情页：三阶段看广告解锁（非 Tab）
│       ├── components/    # LineChart 等通用组件
│       ├── api/           # 接口层（stages.ts 阶段解锁 / wechat.ts 登录 / 演示模式降级）
│       ├── utils/         # ad.ts 激励视频封装、format.ts 等
│       ├── store/         # Pinia 状态
│       ├── mock/          # 演示数据（含三阶段内容）
│       └── types/         # 与后端契约一致的 TS 类型
├── services/api/          # 后端：FastAPI + PostgreSQL + Redis + 对象存储
└── docs/                  # 技术方案 / API 契约 / AI 链路 / 支付变现 / 部署
```

## 快速开始

### 1. 小程序（微信开发者工具）

```bash
cd apps/mobile
pnpm install
pnpm build:mp-weixin   # 产物在 dist/build/mp-weixin
```

用**微信开发者工具**导入 `apps/mobile/dist/build/mp-weixin` 目录即可预览。
开发调试用 `pnpm dev:mp-weixin`（产物在 `dist/dev/mp-weixin`，改动热更新）。

> 想在浏览器里快速看界面（不必开微信开发者工具）也可以用 `pnpm dev:h5`，
> 仅用于本地预览，不是发行形态。

> 默认 `USE_MOCK = true`（见 `src/api/config.ts`）：不发任何网络请求，直接用
> `src/mock/` 的结构化假数据渲染，因此**不启动后端也能完整走通三个页面**。
> 界面上的「演示数据」角标表示当前处于该模式。

### 2. 后端

```bash
cd services/api
python -m venv .venv
.venv\Scripts\python.exe -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
.venv\Scripts\python.exe scripts/seed.py          # 灌入演示数据
.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8000
```

接口文档：<http://127.0.0.1:8000/docs>

### 3. 前后端联调

把 `apps/mobile/src/api/config.ts` 里的 `USE_MOCK` 改成 `false`：

- H5 开发：`API_BASE_URL` 留空，由 `vite.config.ts` 的 proxy 转发到 `127.0.0.1:8000`
- App / 鸿蒙真机：`API_BASE_URL` 填局域网 IP 或公网域名

### 4. 发布到微信

| 步骤 | 操作 |
| --- | --- |
| 1 | 在 `src/manifest.json` 的 `mp-weixin.appid` 填入小程序 AppID |
| 2 | `pnpm build:mp-weixin`，用微信开发者工具打开产物目录 |
| 3 | 开通**流量主**并创建激励视频广告位，把广告位 ID 填到 `src/utils/ad.ts` 的 `REWARDED_AD_UNIT_ID` |
| 4 | 上传代码 → 提交审核 → 发布（个人主体也能注册小程序） |

> ⚠️ **iOS 微信小程序禁止虚拟支付**：iOS 用户看不到充值入口，只能通过看广告获取点数。
> 代码已按 `uni.getSystemInfoSync().platform` 做了区分（见 `pages/account/index.vue`）。
>
> ⚠️ 激励视频需要小程序累计 **1000 独立访客**才能开通流量主。冷启动阶段广告位留空即可，
> 此时 `src/utils/ad.ts` 会走演示流程（模拟 1.2 秒播放），不影响解锁链路自测。

## 核心设计要点

1. **三阶段解锁**：一次模型调用产出完整结果（图片只送模型一次，成本最低），三个阶段的展示内容从**同一份结果**派生；未解锁阶段的 `content` 在服务端就置为 `null`，内容不下发到客户端，无法绕过广告直接读取。
2. **答题卡分「已批改 / 未批改」两种模式**：已批改时 AI 只做归因（准确率最高，推荐默认）；未批改时需同时导入标准答案由 AI 判分，主观题存在误差，界面上明确提示。
3. **解锁幂等**：每次广告上报携带唯一 `event_id`（数据库唯一约束），重复上报只计一次；观看时长低于 15 秒不计入进度。
4. **点数扣费是原子的**：用点数跳过广告时走 `UPDATE ... WHERE credits >= cost` 原子扣减，失败即拒绝，绝不出现"扣了点但没解锁"。
5. **广告激励幂等**：累计 10 次广告换 1 点数，与阶段解锁共用同一张 `ad_events` 表。
6. **AI 输出强结构化**：Prompt 要求模型只返回固定 schema 的 JSON，后端落库后前端直接渲染，避免解析自由文本。
7. **性能与体验**：动画只动 `transform` / `opacity`（不触发重排）；小程序开启 `lazyCodeLoading: requiredComponents` 按需注入；解锁成功只做局部状态合并，不整页重新拉取。
8. **演示模式**：前后端都内置 Mock Provider，无 AI key、无数据库时也能跑通完整链路，便于演示与联调。

## 文档

| 文档 | 内容 |
| --- | --- |
| [01-技术方案](docs/01-技术方案.md) | 架构、选型理由、三端适配、页面与数据流、风险对策 |
| [02-API契约](docs/02-API契约.md) | 全部接口、错误码、鉴权与幂等约定 |
| [03-AI分析链路](docs/03-AI分析链路.md) | Prompt 设计、结构化输出 schema、Qwen-VL 接入、成本估算 |
| [04-支付与变现](docs/04-支付与变现.md) | 个人可开通的支付渠道、苹果抽成、广告平台、合规风险 |
| [05-部署与运维](docs/05-部署与运维.md) | 服务器、域名与备案、对象存储、短信、上线清单 |

## 当前状态

### 已验证通过

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 前端依赖安装 | `pnpm install` | ✅ exit 0，已装入 `@dcloudio/uni-mp-weixin` |
| 小程序构建 | `pnpm build:mp-weixin` | ✅ `DONE Build complete.`，产物在 `dist/build/mp-weixin` |
| 前端类型检查 | `pnpm type-check` | ✅ 0 个错误 |
| 浏览器预览 | `pnpm dev:h5` | ✅ http://localhost:5173 返回 200（仅预览，非发行形态） |
| 后端启动 | `uvicorn app.main:app --port 8000` | ✅ `/health` 返回 `ai_provider=mock, db=sqlite, db_ok=true` |
| 后端演示数据 | `python scripts/seed.py --reset` | ✅ 6 次考试 / 36 张答题卡 / 1 条已完成分析 |
| 后端端到端冒烟测试 | `python scripts/smoke_test.py` | ✅ **PASS 84 / FAIL 0** |
| 三阶段解锁专项 | `python scripts/check_stages.py` | ✅ **PASS 17 / FAIL 0** |

冒烟测试覆盖的关键风险点（均已通过）：**未解锁阶段不下发内容**、`event_id` 重复上报幂等、
广告时长不足不计入进度、用点数解锁的原子性与失败无副作用、删除考试级联清理分析记录。

### 功能完成度

- [x] 小程序三 Tab（趋势 / 导入 / 账号）+ 三阶段解锁详情页
- [x] 三阶段解锁：① 看 1 次广告 ② 看 2 次 ③ 看 2 次，也可用 1 点数跳过广告
- [x] 紫色主题 + 入场/解锁/进度条动画（全部基于 `transform` / `opacity`，不触发重排）
- [x] 小程序 API 适配：`chooseMedia` 拍照选图、`chooseMessageFile` 选 PDF / Word
- [x] 激励视频广告封装（`src/utils/ad.ts`），未配置广告位时自动走演示流程
- [x] 微信一键登录（前端已接入，后端 `POST /auth/wechat/login`）
- [x] 后端 FastAPI 骨架 + 84 项端到端冒烟测试
- [ ] 接入真实 Qwen-VL key 与对象存储（当前为 Mock Provider）
- [ ] 短信验证码服务商对接（开发模式验证码固定 123456）
- [ ] 填入真实激励视频广告位 ID（`src/utils/ad.ts` 的 `REWARDED_AD_UNIT_ID`）
- [ ] 微信支付商户号 + 支付回调验签（见 `docs/04-支付与变现.md` §0.5）
- [ ] 小程序备案、类目审核、隐私协议
- [ ] 真机与线上环境验证（需要小程序 AppID 与 https 域名）

### 已知限制

1. **`USE_MOCK = true` 是默认值**：前端默认不请求后端，用 `src/mock/` 的假数据渲染，界面会显示「演示数据」角标。联调前改成 `false`。
2. **iOS 小程序不能买点数**：受微信平台规则限制，iOS 端已隐藏充值入口，只能看广告获取点数。这是平台规则，不是缺陷。
3. **激励视频需要 1000 独立访客**：流量主未开通前广告位留空，`src/utils/ad.ts` 会走演示流程（模拟 1.2 秒播放），不影响解锁链路自测。
4. **Canvas 折线图**为自研实现，未做设备像素比（DPR）适配，高分屏上线条可能略糊；后续可换成 `canvas type="2d"`。
5. **未做深色模式**适配（小程序端 `darkmode` 已关闭）。
6. **尚未在真机验证**：需要在微信开发者工具导入 `dist/build/mp-weixin` 实测，重点是激励视频与文件选择。

