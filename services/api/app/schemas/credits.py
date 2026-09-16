"""点数相关 Schema。"""

from __future__ import annotations

from pydantic import Field

from app.models.enums import AdPlatform, CreditReason
from app.schemas.common import Paged, Schema, UtcDatetime


class BalanceResult(Schema):
    """点数余额。"""

    credits: int = Field(..., description="当前点数余额")


class CreditLedgerItem(Schema):
    """一条点数流水。"""

    id: str = Field(..., description="流水 ID")
    delta: int = Field(..., description="变动值，正数为增加、负数为消耗")
    reason: CreditReason = Field(..., description="变动原因")
    balance_after: int = Field(..., description="变动后余额快照")
    remark: str = Field("", description="备注")
    created_at: UtcDatetime = Field(..., description="发生时间")


class CreditLedgerPage(Paged[CreditLedgerItem]):
    """点数流水分页。"""


class AdProgressResult(Schema):
    """广告观看进度。"""

    watched: int = Field(0, description="当前累计周期内已观看次数")
    needed: int = Field(10, description="兑换 1 点数所需次数")
    per_reward: int = Field(1, description="每轮兑换的点数")
    credits: int = Field(0, description="当前点数余额")


class AdRewardRequest(Schema):
    """上报一次激励视频观看。"""

    platform: str = Field(
        AdPlatform.MOCK.value,
        description="广告平台：chuangshanjian / gdt / admob / mock",
    )
    event_id: str = Field(..., min_length=6, max_length=128, description="客户端生成的唯一事件 ID（用于幂等）")
    watched_seconds: int = Field(0, ge=0, le=600, description="本次观看时长（秒）")


class AdRewardProgress(Schema):
    """广告进度简版（回传给客户端刷新 UI）。"""

    watched: int = Field(..., description="当前累计周期内已观看次数")
    needed: int = Field(..., description="兑换 1 点数所需次数")


class AdRewardResult(Schema):
    """广告上报结果。"""

    credited: bool = Field(..., description="本次上报是否触发了点数发放")
    credits: int = Field(..., description="发放后的点数余额")
    progress: AdRewardProgress = Field(..., description="最新观看进度")
    duplicated: bool = Field(False, description="是否为重复上报（event_id 已存在）")
