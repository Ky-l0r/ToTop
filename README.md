# ToTop · 考试成绩失分分析 App

拍照或导入试卷与答题卡，用多模态 AI 分析**这次考试丢了哪些知识点、属于哪个模块、怎么补**，并用折线图跟踪各科与总分的得分率趋势。

一套代码（uni-app + Vue3 + TypeScript）编译到 **Android / iOS / HarmonyOS** 三端。

---

## 目录结构

```
ToTop/
├── apps/mobile/           # 前端：uni-app (Vue3 + TS)，三端一套代码
│   └── src/
│       ├── pages/
│       │   ├── trend/     # Tab 1 趋势：折线图 + 历次考试 + 概览
│       │   ├── import/    # Tab 2 导入：题目 / 答题卡导入 + AI 分析
│       │   ├── account/   # Tab 3 账号：登录注册、点数、广告、充值
│       │   └── analysis/  # 详情页：失分知识点 + 提升指南（非 Tab）
│       ├── components/    # LineChart 等通用组件
│       ├── api/           # 接口层（含演示模式降级）
│       ├── store/         # Pinia 状态
│       ├── mock/          # 演示数据
│       └── types/         # 与后端契约一致的 TS 类型
├── services/api/          # 后端：FastAPI + PostgreSQL + Redis + 对象存储
└── docs/                  # 技术方案 / API 契约 / AI 链路 / 支付变现 / 部署
```

## 快速开始

### 1. 前端（H5 预览，最快看到界面）

```bash
cd apps/mobile
pnpm install
pnpm dev:h5          # 打开终端提示的 http://localhost:5173
```

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

### 4. 三端打包（需要 HBuilderX）

| 平台 | 命令 | 说明 |
| --- | --- | --- |
| Android | `pnpm build:app` 后由 HBuilderX 云打包 / 本地打包 | 需在 `manifest.json` 填 DCloud appid |
| iOS | 同上 | 需 Apple 开发者账号 + 证书与描述文件 |
| HarmonyOS | `pnpm build:harmony` | 生成鸿蒙工程资源，再用 DevEco Studio / HBuilderX 签名打包（需鸿蒙开发者账号） |

## 核心设计要点

1. **答题卡分「已批改 / 未批改」两种模式**：已批改时 AI 只做归因（准确率最高，推荐默认）；未批改时需同时导入标准答案由 AI 判分，主观题存在误差，界面上明确提示。
2. **点数扣费是原子的**：分析请求先在数据库做 `UPDATE ... WHERE credits >= 1` 的原子扣减，失败即拒绝，绝不出现"扣了点但没分析"。
3. **广告激励幂等**：每次广告回调携带唯一 `event_id`，重复上报只计一次；累计 10 次发 1 点数后进度清零。
4. **AI 输出强结构化**：Prompt 要求模型只返回固定 schema 的 JSON，后端落库后前端直接渲染，避免解析自由文本。
5. **演示模式**：前后端都内置 Mock Provider，无 AI key、无数据库时也能跑通完整链路，便于演示与联调。

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
| 前端依赖安装 | `pnpm install` | ✅ 干净通过（exit 0） |
| 前端 H5 构建 | `pnpm build:h5` | ✅ `DONE Build complete.`，产物在 `dist/build/h5` |
| 前端类型检查 | `pnpm type-check` | ✅ 0 个错误 |
| 前端开发服务器 | `pnpm dev:h5` | ✅ http://127.0.0.1:5173 返回 200 |
| 后端启动 | `uvicorn app.main:app --port 8000` | ✅ `/health` 返回 `ai_provider=mock, db=sqlite, db_ok=true` |
| 后端演示数据 | `python scripts/seed.py --reset` | ✅ 6 次考试 / 36 张答题卡 / 1 条已完成分析 |
| 后端端到端冒烟测试 | `python scripts/smoke_test.py` | ✅ **PASS 72 / FAIL 0**（含扣费原子性、广告幂等、点数不足无副作用、级联删除） |
| 前后端联调 | dev server 的 `/api` 代理 | ✅ `http://127.0.0.1:5173/api/v1/meta/subjects` → 200，演示账号登录成功 |

### 功能完成度

- [x] 前端三 Tab（趋势 / 导入 / 账号）+ 分析详情页，含自研 Canvas 折线图与演示数据
- [x] 后端 FastAPI 骨架（账号、点数、考试、导入、分析、趋势）与自动化冒烟测试
- [ ] 接入真实 Qwen-VL key 与对象存储（替换 Mock Provider）
- [ ] 短信验证码服务商对接（当前开发模式验证码固定 123456）
- [ ] 激励视频广告 SDK 接入（`account/index.vue` 中 `REWARDED_AD_PID` 填广告位 ID）
- [ ] 支付渠道签约与回调验签（方案见 `docs/04-支付与变现.md`）
- [ ] 三端真机打包（需 HBuilderX + 各平台开发者账号）
- [ ] 备案、隐私政策、上架材料

### 已知限制

1. **`USE_MOCK = true` 是默认值**：前端默认不请求后端，用 `src/mock/` 的假数据渲染，界面上会显示「演示数据」角标。联调前请改成 `false`。
2. **鸿蒙端文件选择**：`uni.chooseFile` 在部分鸿蒙版本上不可用，代码已做降级提示（改用拍照/相册）。真机验证后可能需要在 `manifest.json` 中补充权限声明。
3. **Canvas 折线图**为自研实现，未做设备像素比（DPR）适配，高分屏上线条可能略糊，后续可接入 `canvas type="2d"` 优化。
4. **未做 iOS DPR / 深色模式**适配。

