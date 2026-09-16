"""应用配置。

统一从环境变量 / `services/api/.env` 读取（pydantic-settings），可参考 `.env.example`。

约定：
- 所有配置项都能用同名（大写）环境变量覆盖；
- 本地开发默认使用 SQLite（aiosqlite），无需真实 PostgreSQL；
- 未配置 AI Key 时自动降级为 Mock Provider，保证全链路可跑通（见 `effective_ai_provider`）。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any, Literal

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# services/api 目录（app/core/config.py -> app/core -> app -> services/api）
BASE_DIR = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    """全部运行期配置。字段名即环境变量名（大小写不敏感）。"""

    model_config = SettingsConfigDict(
        env_file=str(BASE_DIR / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ------------------------------------------------------------ 基础
    app_name: str = "ToTop 考试失分分析 API"
    app_version: str = "0.1.0"
    env: Literal["dev", "test", "prod"] = "dev"
    debug: bool = True
    api_v1_prefix: str = "/api/v1"
    host: str = "127.0.0.1"
    port: int = 8000
    """对外可访问的站点根地址，用于拼接静态资源 URL 与支付回调地址。"""
    public_base_url: str = "http://127.0.0.1:8000"
    cors_origins: list[str] = ["*"]

    # ------------------------------------------------------------ 数据库
    database_url: str = "sqlite+aiosqlite:///./dev.db"
    db_echo: bool = False
    auto_create_tables: bool = True
    """启动时自动建表（开发方便）。生产建议关闭并改用 alembic 迁移。"""

    # ------------------------------------------------------------ 安全
    secret_key: str = "dev-secret-key-change-me-in-production"
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 60 * 24
    refresh_token_expire_days: int = 14

    # ------------------------------------------------------------ 限流 / Redis
    redis_url: str = "redis://127.0.0.1:6379/0"
    rate_limit_enabled: bool = True
    rate_limit_per_minute: int = 240
    """单 IP 每分钟请求上限（全局兜底限流）。"""
    sms_send_interval_seconds: int = 60
    """同一手机号两次发送验证码的最小间隔（秒）。"""
    sms_daily_limit_per_phone: int = 10
    """同一手机号每天最多发送验证码次数。"""

    # ------------------------------------------------------------ 短信
    sms_provider: Literal["dev", "aliyun"] = "dev"
    sms_code_ttl_seconds: int = 300
    sms_dev_return_code: bool = True
    """开发模式下把验证码回传到响应体 `dev_code`，便于自测；生产必须为 false。"""

    # ------------------------------------------------------------ AI Provider
    ai_provider: Literal["auto", "mock", "qwen"] = "auto"
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-vl-max"
    ai_timeout_seconds: int = 120
    ai_max_retries: int = 3
    ai_max_images: int = 6
    """单次分析最多送入模型的图片数量。"""

    # ------------------------------------------------------------ 对象存储
    storage_backend: Literal["local", "s3"] = "local"
    storage_dir: Path = BASE_DIR / "storage"
    storage_url_prefix: str = "/static"
    max_upload_mb: int = 20
    allowed_upload_ext: list[str] = [
        ".jpg",
        ".jpeg",
        ".png",
        ".webp",
        ".heic",
        ".bmp",
        ".pdf",
        ".docx",
    ]
    s3_endpoint_url: str = ""
    s3_bucket: str = ""
    s3_access_key: str = ""
    s3_secret_key: str = ""
    s3_region: str = ""
    s3_presign_expire_seconds: int = 900

    # ------------------------------------------------------------ 业务规则
    analysis_credit_cost: int = 0
    """发起一次 AI 分析消耗的点数。

    小程序版本改为「分析免费、结果分三段看广告解锁」，因此默认 0；
    置为 >0 即可恢复按次扣费，扣费与账本逻辑仍然保留。
    """
    register_bonus_credits: int = 1
    ad_watch_needed: int = 10
    """累计看多少次激励视频广告兑换 1 点数。"""
    ad_reward_credits: int = 1
    ad_reward_min_seconds: int = 15
    """单次激励视频的有效观看时长下限（秒），低于该值不计入进度。"""

    # ------------------------------------------------------------ 分阶段解锁
    stage_ad_requirements: list[int] = [1, 2, 2]
    """三个阶段各自需要看完几次激励视频才能解锁（基础分析 / 进阶分析 / 归纳总结）。"""
    stage_unlock_credit_cost: int = 1
    """用点数跳过广告、直接解锁一个阶段的花费。"""
    stage_names: list[str] = ["基础分析", "进阶分析", "归纳总结"]
    """三个阶段的中文名，随接口下发以保证前后端文案一致。"""

    # ------------------------------------------------------------ 微信小程序
    wechat_appid: str = ""
    wechat_secret: str = ""
    """微信小程序 AppID / AppSecret，用于 code2Session 一键登录；留空时走 dev 假 openid。"""

    mock_sample_path: Path = BASE_DIR / "app" / "providers" / "ai" / "mock_sample.json"

    # ------------------------------------------------------------ 校验 / 派生
    @field_validator("cors_origins", "allowed_upload_ext", "stage_names", mode="before")
    @classmethod
    def _split_csv(cls, value: Any) -> Any:
        """允许用逗号分隔的字符串配置列表项（也兼容 JSON 数组写法）。"""
        if isinstance(value, str):
            raw = value.strip()
            if not raw:
                return []
            if raw.startswith("["):
                import json

                return json.loads(raw)
            return [item.strip() for item in raw.split(",") if item.strip()]
        return value

    @field_validator("allowed_upload_ext", mode="after")
    @classmethod
    def _normalize_ext(cls, value: list[str]) -> list[str]:
        """扩展名统一小写并补上前导点。"""
        normalized: list[str] = []
        for item in value:
            ext = item.strip().lower()
            if not ext:
                continue
            normalized.append(ext if ext.startswith(".") else f".{ext}")
        return normalized

    @field_validator("stage_ad_requirements", mode="before")
    @classmethod
    def _parse_stage_ads(cls, value: Any) -> Any:
        """支持 "1,2,2" 与 [1,2,2] 两种写法。"""
        if isinstance(value, str):
            raw = value.strip()
            if not raw:
                return [1, 2, 2]
            if raw.startswith("["):
                import json

                return json.loads(raw)
            return [int(item.strip()) for item in raw.split(",") if item.strip()]
        return value

    @field_validator("stage_ad_requirements", mode="after")
    @classmethod
    def _normalize_stage_ads(cls, value: list[int]) -> list[int]:
        """固定为 3 个阶段，每阶段至少 1 次，缺项用默认值补齐。"""
        defaults = [1, 2, 2]
        result = [max(1, int(item)) for item in value[:3]]
        while len(result) < 3:
            result.append(defaults[len(result)])
        return result

    @property
    def is_dev(self) -> bool:
        """是否为开发环境。"""
        return self.env in ("dev", "test")

    @property
    def effective_ai_provider(self) -> str:
        """实际生效的 AI Provider 名称。

        `auto` 模式下：配了 DashScope Key 就用 qwen，否则用 mock。
        """
        if self.ai_provider == "auto":
            return "qwen" if self.dashscope_api_key.strip() else "mock"
        if self.ai_provider == "qwen" and not self.dashscope_api_key.strip():
            # 配置成 qwen 但没 Key，仍然降级到 mock，避免接口直接 500
            return "mock"
        return self.ai_provider

    @property
    def database_backend(self) -> str:
        """数据库类型：`sqlite` / `postgresql` / 其他。"""
        name = self.database_url.split(":", 1)[0]
        if "sqlite" in name:
            return "sqlite"
        if "postgresql" in name:
            return "postgresql"
        return name

    def stage_ad_required(self, stage: int) -> int:
        """第 stage 阶段（从 1 开始）需要看完的广告次数。"""
        index = stage - 1
        if 0 <= index < len(self.stage_ad_requirements):
            return self.stage_ad_requirements[index]
        return 1

    def stage_name(self, stage: int) -> str:
        """第 stage 阶段的中文名。"""
        index = stage - 1
        if 0 <= index < len(self.stage_names):
            return self.stage_names[index]
        return f"第 {stage} 阶段"

    @property
    def max_upload_bytes(self) -> int:
        """单文件上传大小上限（字节）。"""
        return self.max_upload_mb * 1024 * 1024

    @property
    def static_url_prefix(self) -> str:
        """静态资源 URL 前缀（以 / 开头，不带尾斜杠）。"""
        prefix = self.storage_url_prefix.strip()
        if not prefix.startswith("/"):
            prefix = f"/{prefix}"
        return prefix.rstrip("/")

    @property
    def is_sqlite(self) -> bool:
        """是否使用 SQLite。"""
        return self.database_backend == "sqlite"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局唯一配置实例（带缓存）。"""
    return Settings()


settings = get_settings()
