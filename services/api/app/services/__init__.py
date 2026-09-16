"""业务服务层。

端点（`app.api.v1.endpoints`）、后台任务（`app.tasks.queue`）与脚本（`scripts/`）
都复用这里的实现，保证同一业务规则只有一份代码：

- `app.services.credits`：点数账本（原子扣减 + 幂等键）；
- `app.services.sms`：短信验证码（Redis 存储，不可用时降级数据库）；
- `app.services.analysis`：AI 分析编排（扣费 → 建记录 → 后台任务 → 落库 → 回写）。
"""
