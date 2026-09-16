"""三阶段解锁专项验证。

独立于 `smoke_test.py`，专门跑「分析免费 + 三段看广告解锁」这条链路，
方便在调广告接入时单独验证。

用法：
    .\\.venv\\Scripts\\python.exe scripts\\check_stages.py
"""

from __future__ import annotations

import asyncio
import sys

import httpx

BASE = "http://127.0.0.1:8000/api/v1"
PHONE = "13800008888"
PASSWORD = "a123456"

_results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    """记录一条断言结果。"""
    _results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))


def unwrap(resp: httpx.Response) -> object:
    """拆开统一响应信封，code != 0 时抛错。"""
    body = resp.json()
    if body.get("code") != 0:
        raise RuntimeError(f"HTTP {resp.status_code} code={body.get('code')} {body.get('message')}")
    return body.get("data")


async def main() -> int:
    async with httpx.AsyncClient(timeout=90) as client:
        login = unwrap(
            await client.post(f"{BASE}/auth/login/password", json={"phone": PHONE, "password": PASSWORD})
        )
        token = login["access_token"]
        headers = {"Authorization": f"Bearer {token}"}
        balance0 = login["user"]["credits"]
        check("演示账号登录", bool(token), f"余额 {balance0} 点")

        exams = unwrap(await client.get(f"{BASE}/exams", headers=headers))
        items = exams["items"] if isinstance(exams, dict) else exams
        if not items:
            print("没有可用考试，请先跑 scripts/seed.py")
            return 1
        exam = items[0]

        created = unwrap(
            await client.post(
                f"{BASE}/analyses", json={"exam_id": exam["id"], "force": True}, headers=headers
            )
        )
        check(
            "发起分析不扣点数",
            created["credits_cost"] == 0 and created["credits_balance"] == balance0,
            f"cost={created['credits_cost']} balance={created['credits_balance']}",
        )

        analysis_id = created["analysis_id"]
        detail = None
        for _ in range(60):
            detail = unwrap(await client.get(f"{BASE}/analyses/{analysis_id}", headers=headers))
            if detail["status"] in ("succeeded", "failed"):
                break
            await asyncio.sleep(0.5)
        check("分析完成", detail["status"] == "succeeded", detail.get("error") or "")

        stages = {item["stage"]: item for item in detail["stages"]}
        check(
            "三个阶段默认锁定且不下发内容",
            all(not s["unlocked"] and s["content"] is None for s in stages.values()),
            "需要次数 " + str({n: s["ad_required"] for n, s in stages.items()}),
        )
        check(
            "所需广告次数符合约定 1/2/2",
            [stages[1]["ad_required"], stages[2]["ad_required"], stages[3]["ad_required"]] == [1, 2, 2],
        )

        async def report(stage: int, event_id: str, seconds: int = 30) -> dict:
            return unwrap(
                await client.post(
                    f"{BASE}/analyses/{analysis_id}/stages/{stage}/unlock",
                    json={"platform": "wechat", "event_id": event_id, "watched_seconds": seconds},
                    headers=headers,
                )
            )

        # 阶段 1：1 次即解锁
        first = await report(1, f"e1_{analysis_id}")
        summary = (first.get("content") or {}).get("summary", "")
        check("阶段1 看 1 次广告即解锁", first["unlocked"] is True, f"watched={first['watched']}")
        check("阶段1 学情概述不超过 200 字", 0 < len(summary) <= 200, f"{len(summary)} 字")

        # 阶段 2：需要 2 次
        s2a = await report(2, f"e2a_{analysis_id}")
        check("阶段2 第 1 次后仍未解锁", s2a["unlocked"] is False, f"watched={s2a['watched']}/2")
        dup = await report(2, f"e2a_{analysis_id}")
        check("重复 event_id 幂等且不涨进度", dup["duplicated"] is True and dup["watched"] == 1)
        s2b = await report(2, f"e2b_{analysis_id}")
        weak = (s2b.get("content") or {}).get("weak_questions") or []
        check("阶段2 第 2 次后解锁并给出失分题目", s2b["unlocked"] is True and len(weak) > 0, f"{len(weak)} 道题")
        check("失分题目按失分降序", all(
            weak[i]["lost_score"] >= weak[i + 1]["lost_score"] for i in range(len(weak) - 1)
        ))

        # 广告时长不足
        short = await client.post(
            f"{BASE}/analyses/{analysis_id}/stages/3/unlock",
            json={"platform": "wechat", "event_id": f"short_{analysis_id}", "watched_seconds": 3},
            headers=headers,
        )
        # 观看时长不足属于参数校验失败，统一返回 40001
        check("观看时长不足被拒绝", short.json().get("code") == 40001, f"code={short.json().get('code')}")

        # 阶段 3：用点数跳过广告
        s3 = unwrap(
            await client.post(
                f"{BASE}/analyses/{analysis_id}/stages/3/unlock-by-credit", headers=headers
            )
        )
        plan = s3.get("content") or {}
        check(
            "用点数解锁阶段3 并扣 1 点",
            s3["unlocked"] is True and s3["credits_balance"] == balance0 - 1,
            f"余额 {balance0} → {s3['credits_balance']}",
        )
        check(
            "阶段3 给出知识模块与学习建议",
            len(plan.get("modules") or []) > 0 and len(plan.get("suggestions") or []) > 0,
            f"{len(plan.get('modules') or [])} 个模块 / {len(plan.get('suggestions') or [])} 条建议",
        )

        detail2 = unwrap(await client.get(f"{BASE}/analyses/{analysis_id}", headers=headers))
        check("详情接口三阶段状态与解锁一致", all(s["unlocked"] for s in detail2["stages"]))
        check("已解锁阶段才下发内容", all(s["content"] is not None for s in detail2["stages"]))

        bad = await client.post(f"{BASE}/analyses/{analysis_id}/stages/9/unlock", json={"platform": "wechat", "event_id": "x", "watched_seconds": 30}, headers=headers)
        check("非法阶段号返回 40001", bad.json().get("code") == 40001, f"code={bad.json().get('code')}")

    passed = sum(1 for _, ok, _ in _results if ok)
    failed = len(_results) - passed
    print()
    print("=" * 64)
    print(f"  三阶段解锁验证：PASS {passed} / FAIL {failed}（共 {len(_results)} 项）")
    for name, ok, detail in _results:
        if not ok:
            print(f"    - {name}  {detail}")
    print("=" * 64)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
