# ToTop 项目搁置说明

> **搁置于 2026-09-16　　计划 2027-04 恢复**（成年后即可完成小程序备案）
>
> 恢复开发时**先读这一份**，它记录了「做到哪了、接下来按什么顺序做、有哪些坑」。
> 之后再读 [`README.md`](README.md) 了解项目全貌。

---

## 一、为什么搁置

小程序备案要求**负责人年满 18 周岁**（需身份证 + 人脸核验），提交时被系统拦下：

> 身份证格式不正确，或者年龄未满18周岁，请重新填写

这不是技术问题，是工信部的硬性规定，**换平台也躲不掉**（2023 年 8 月起，App、小程序、快应用全部要求备案，
微信/支付宝/抖音/快手各家落实的是同一套规则）。

**代码已经写完了，缺的只是"成年"这一张入场券。**

---

## 二、搁置时做到哪一步

### ✅ 已完成并实测通过

| 部分 | 验证方式 | 结果 |
| --- | --- | --- |
| 微信小程序前端 | `pnpm build:mp-weixin` | ✅ `DONE Build complete.`，产物 188 KB |
| 前端类型检查 | `pnpm type-check` | ✅ 0 错误 |
| 后端端到端测试 | `python scripts/smoke_test.py` | ✅ **PASS 84 / FAIL 0** |
| 三阶段解锁专项 | `python scripts/check_stages.py` | ✅ **PASS 17 / FAIL 0** |
| 微信一键登录 | 专项验证 13 项 | ✅ 通过 |
| 小程序产物校验 | 读 `app.json` | ✅ tabBar `#7C5CFF`、按需注入已开 |

已实现的功能：三个 Tab（趋势 / 导入 / 账号）、自研 Canvas 折线图、拍照与聊天文件导入、
**三阶段看广告解锁**（1 / 2 / 2 次）、激励视频广告封装、微信一键登录、紫色主题与动画。

### ❌ 未完成

1. **H5 网页版适配**（只在开头就停了）
   - `apps/mobile/src/api/config.ts` 里计划加的 `IS_MP_WEIXIN` / `HAS_REWARDED_AD` / `HAS_PAYMENT`
     常量**没有写入**，代码保持原样
   - H5 下的界面适配（隐藏充值入口、把"看视频"文案改成"解锁"）没做
2. **后端邀请码注册 + 每日分析限次** —— 完全没做（原本是为 H5 版控制 AI 成本准备的）
3. **真实凭据接入**（见下面第 3 步清单）
4. **真机验证** —— 从没在真机上跑过

### 当前可用范围

**只有微信小程序形态是完整可用的**。H5 仅能用于浏览器里预览界面，**不能直接部署给同学使用**
（因为缺邀请码注册和限次，AI 额度会被刷爆）。

---

## 三、恢复后的执行顺序（照做）

### 第 0 步：先确认环境还能跑

```powershell
cd D:\Subject\ToTop\services\api
.\.venv\Scripts\python.exe scripts\seed.py --reset
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
# 另开终端
.\.venv\Scripts\python.exe scripts\smoke_test.py     # 期望 PASS 84 / FAIL 0
```

跑通说明代码没坏，可以往下走。

### 第 1 步：注册小程序 + 提交备案（**最耗时，先启动**）

- 微信公众平台注册小程序，需要一个**没注册过公众平台的邮箱**
- 注册后立即提交备案：主体证件、负责人身份证 + **人脸核验**、小程序简介与服务内容
- **审核周期 1~20 个工作日**，这是整条链路上最长的一段

### 第 2 步：服务器 + 域名

- 做小程序**必须**用 https 且**已 ICP 备案**的域名（境内服务器）→ 域名备案**又是** 1~20 个工作日
- 备案可以和第 1 步并行提交，别串行等

### 第 3 步：填入真实凭据

| 文件 | 字段 | 填什么 |
| --- | --- | --- |
| `apps/mobile/src/manifest.json` | `mp-weixin.appid` | 小程序 AppID |
| `apps/mobile/src/api/config.ts` | `USE_MOCK` | 改成 `false` |
| `apps/mobile/src/api/config.ts` | `API_BASE_URL` | https 域名 |
| `apps/mobile/src/utils/ad.ts` | `REWARDED_AD_UNIT_ID` | 流量主激励视频广告位 ID |
| `services/api/.env` | `WECHAT_APPID` / `WECHAT_SECRET` | 微信登录凭据 |
| `services/api/.env` | `ENV` / `SECRET_KEY` | `prod` / 换随机长串（`prod` 会自动关闭 `/docs`） |
| `services/api/.env` | `DATABASE_URL` | PostgreSQL（当前是 SQLite） |
| `services/api/.env` | `DASHSCOPE_API_KEY` | 通义千问 Key（当前是 Mock Provider） |
| `services/api/.env` | `PUBLIC_BASE_URL` | https 域名（否则手机打不开上传的图片） |
| `services/api/.env` | `SMS_PROVIDER` | 真实短信（只在需要手机号登录时才要） |

### 第 4 步：真机验证（重点这三项）

1. 激励视频能否正常播放与发放
2. 拍照、从聊天记录选 PDF/Word
3. 三阶段解锁的进度是否正确累计

### 第 5 步：提审 → 发布 → 开通流量主

流量主需**累计 1000 独立访客**才能开通。冷启动期没有广告收入是正常的。

---

## 四、搁置期间可以做的事（**不需要成年**）

1. **参加比赛** —— 对未成年人完全开放，收益上限远高于广告
   - NOIP / 信息学奥赛（升学价值最高）
   - 全国青少年科技创新大赛、NOC
   - 腾讯微信小程序应用开发赛、蓝桥杯青少年组
2. **开源到 GitHub**（注册门槛 13 岁）—— 把 README、架构图、演示补好，
   这个完成度在中学生项目里是能打的
3. **H5 版改造**（见上面"未完成"第 1、2 条）—— 如果想让同学先用起来

---

## 五、命令速查

```powershell
# ---------- 后端 ----------
cd D:\Subject\ToTop\services\api
.\.venv\Scripts\python.exe scripts\seed.py --reset         # 重建演示数据
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
.\.venv\Scripts\python.exe scripts\smoke_test.py           # 84 项端到端
.\.venv\Scripts\python.exe scripts\check_stages.py         # 17 项三阶段专项

# ---------- 前端 ----------
cd D:\Subject\ToTop\apps\mobile
pnpm install
pnpm type-check            # 类型检查
pnpm build:mp-weixin       # 产物：dist/build/mp-weixin（用微信开发者工具打开）
pnpm dev:h5                # 浏览器预览（仅预览，不是发行形态）
```

**演示账号**：手机号 `13800008888`，密码 `a123456`，5 点数。
接口文档（仅 dev）：<http://127.0.0.1:8000/docs>

---

## 六、关键设计决策（别重新发明）

1. **三阶段解锁**：① 基础分析看 1 次广告 ② 进阶分析看 2 次 ③ 归纳总结看 2 次
   - 次数由后端 `STAGE_AD_REQUIREMENTS` 配置（默认 `1,2,2`），改配置即可，不用发版
2. **未解锁的阶段，`content` 在服务端就返回 `null`** —— 内容不下发到客户端，无法绕过广告直接读取
3. **三段内容由同一次模型调用派生**（图片只送模型一次，成本最低），解锁只控制可见性
4. **发起分析免费**（`ANALYSIS_CREDIT_COST=0`），点数改为"跳过广告"的付费路径（1 点 / 段）
5. **iOS 小程序禁止虚拟支付** → iOS 端隐藏充值入口（这是平台规则，不是 bug）
6. 解锁状态存在 `analyses.stage_state`（JSON 字段），广告上报按 `event_id` 幂等
7. 主题色统一在 `apps/mobile/src/uni.scss`，改一处全局生效

---

## 七、踩过的坑（别再踩）

| 坑 | 说明 |
| --- | --- |
| 改了 SQLAlchemy 模型后报错 | 项目**没有 alembic 迁移**，必须删掉 `dev.db` 后重跑 `seed.py --reset` 重建 |
| vite dev server 突然崩 `EBUSY` | 编辑器写文件留下的临时目录会被 vite 的 watcher 监视导致崩溃，删掉 `.tmpdir` 残留再启动 |
| PowerShell 里 Python 输出乱码 | 先执行 `$env:PYTHONIOENCODING="utf-8"` |
| 小程序里文件选择失效 | 小程序**没有**通用文件选择器，`uni.chooseFile` 不可用；图片用 `uni.chooseMedia`，PDF/Word 用 `uni.chooseMessageFile`（从聊天记录选） |
| 小程序里点击没反馈 | `view` 的 `:active` 伪类**无效**，必须用 `hover-class` |
| dev 模式下微信登录每次都是新账号 | 微信 `code` 是一次性的，而 dev 模式由 code 派生 openid；配了真实 AppID 就正常 |
| `wx.getUserProfile` 报错 | 该接口已废弃，现在用头像昵称填写能力 |
| 微信内打开 H5 页面 | 未备案域名会有安全提示，用户点"继续访问"即可；但被判定为诱导分享会被拦 |

---

## 八、搁置时的未提交改动

git 工作树里有 3 个文件未提交（都是补 `hover-class` 点击反馈的改动）：

- `apps/mobile/src/pages/account/index.vue`
- `apps/mobile/src/pages/import/index.vue`
- `apps/mobile/src/pages/trend/index.vue`

恢复时先 `git status` 看一眼，决定提交或丢弃。

---

## 九、恢复时可能需要重新核实的政策

政策和价格会变，动手前建议重新查一遍当期数字：

1. **小程序备案细则**（工信部 / 微信公众平台公告）
2. **微信虚拟支付规则**（iOS 端是否仍禁止卖虚拟商品）
3. **流量主开通门槛**（当前是累计 1000 独立访客）
4. **服务类目审核口径**（K12 学科培训是红线，项目定位为"学习工具"而非"培训"更安全）
5. **通义千问价格**（决定每次分析的成本，进而决定"10 次广告换 1 点"是否划算）

---

**最后一句**：代码是完整的、测试是全绿的，明年回来只需要一把钥匙，不需要重写。
