"""端到端冒烟测试。

对**真实运行的服务**发起 HTTP 请求，覆盖从注册到 AI 分析、趋势、点数、广告、充值的完整链路，
最后打印 PASS / FAIL 汇总（任一项失败则脚本退出码为 1）。

用法（先启动服务，再执行）::

    .\\.venv\\Scripts\\python.exe -m uvicorn app.main:app --port 8000      # 终端 A
    .\\.venv\\Scripts\\python.exe scripts\\smoke_test.py                    # 终端 B

可选参数：

    --base-url  服务地址（默认 http://127.0.0.1:8000）
    --phone     指定测试手机号（默认每次随机生成，便于重复执行）
    --timeout   单请求超时秒数（默认 60）

覆盖的用例见 `docs` 与本仓库 `README.md`「冒烟测试」一节。
"""

from __future__ import annotations

import argparse
import base64
import json
import random
import sys
import time
import traceback
import uuid
from datetime import date, timedelta
from typing import Any

import httpx

API = "/api/v1"

GREEN = "\033[32m"
RED = "\033[31m"
YELLOW = "\033[33m"
DIM = "\033[2m"
RESET = "\033[0m"

# 1x1 的合法 PNG（89 50 4E 47 开头），用于验证上传 → /static 访问链路
PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
)

_results: list[tuple[str, bool, str]] = []


class Abort(Exception):
    """关键步骤失败：终止后续用例（已记录的 PASS / FAIL 仍会汇总）。"""


def check(name: str, ok: bool, detail: str = "") -> bool:
    """记录并打印一个检查项。"""
    _results.append((name, bool(ok), detail))
    flag = f"{GREEN}PASS{RESET}" if ok else f"{RED}FAIL{RESET}"
    suffix = f"  {DIM}{detail}{RESET}" if detail else ""
    print(f"[{flag}] {len(_results):02d}. {name}{suffix}", flush=True)
    return bool(ok)


def require(ok: bool, name: str, detail: str = "") -> None:
    """关键检查项，失败即终止。"""
    if not check(name, ok, detail):
        raise Abort(name)


def section(title: str) -> None:
    """打印分节标题。"""
    print(f"\n{YELLOW}── {title} {'─' * max(0, 56 - len(title))}{RESET}", flush=True)


def request(client: httpx.Client, method: str, path: str, **kwargs: Any) -> tuple[int, Any]:
    """发起请求并返回 `(HTTP 状态码, JSON 响应体)`。"""
    try:
        response = client.request(method, path, **kwargs)
    except httpx.HTTPError as exc:
        raise Abort(f"请求 {method} {path} 失败：{exc}") from exc
    try:
        body: Any = response.json()
    except ValueError:
        body = {"_raw": response.text[:300]}
    return response.status_code, body


def payload(body: Any) -> Any:
    """取出信封里的 `data`。"""
    return body.get("data") if isinstance(body, dict) else None


def data_of(body: Any) -> dict[str, Any]:
    """取出信封里的 `data`（对象）。"""
    value = payload(body)
    return value if isinstance(value, dict) else {}


def list_of(body: Any) -> list[Any]:
    """取出信封里的 `data`（数组）。"""
    value = payload(body)
    return value if isinstance(value, list) else []


def poll(
    client: httpx.Client,
    path: str,
    *,
    headers: dict[str, str],
    timeout: float = 60.0,
    interval: float = 0.4,
) -> dict[str, Any]:
    """轮询直到任务 / 分析进入终态。"""
    deadline = time.time() + timeout
    last: Any = None
    while time.time() < deadline:
        status, body = request(client, "GET", path, headers=headers)
        if status == 200 and isinstance(body, dict) and body.get("code") == 0:
            current = data_of(body)
            last = current
            if current.get("status") in ("succeeded", "failed"):
                return current
        time.sleep(interval)
    raise Abort(f"轮询 {path} 超时（{timeout:.0f}s），最后响应：{json.dumps(last, ensure_ascii=False)[:300]}")


def main() -> int:
    """执行全部冒烟用例。"""
    args = _parse_args()
    base_url = args.base_url.rstrip("/")
    run_id = uuid.uuid4().hex[:8]
    phone = args.phone or f"139{random.randint(0, 99_999_999):08d}"
    initial_password = f"smoke{random.randint(1000, 9999)}"
    new_password = f"newpwd{random.randint(1000, 9999)}"

    print(f"{YELLOW}ToTop API 冒烟测试{RESET}  base_url={base_url}  run_id={run_id}  phone={phone}")

    with httpx.Client(base_url=base_url, timeout=args.timeout) as client:
        # ---------------------------------------------------------- 0. 服务就绪
        section("服务可用性")
        try:
            status, health = request(client, "GET", "/health")
        except Abort as exc:
            print(f"{RED}无法连接 {base_url}：{exc}{RESET}")
            print(f"{YELLOW}请先在另一个终端启动服务：.\\.venv\\Scripts\\python.exe -m uvicorn app.main:app --port 8000{RESET}")
            return 2

        require(status == 200, "GET /health 返回 200", f"HTTP {status}")
        require(
            health.get("status") == "ok",
            "GET /health 状态为 ok",
            json.dumps(health, ensure_ascii=False),
        )
        require(
            bool(health.get("version")) and bool(health.get("ai_provider")) and bool(health.get("db")),
            "GET /health 含 version / ai_provider / db",
            f"version={health.get('version')} ai={health.get('ai_provider')} db={health.get('db')}",
        )

        status, body = request(client, "GET", f"{API}/meta/subjects")
        subjects_meta = list_of(body)
        codes = {item.get("code") for item in subjects_meta if isinstance(item, dict)}
        math_meta = next((item for item in subjects_meta if item.get("code") == "math"), {})
        require(status == 200 and len(subjects_meta) >= 6, "GET /meta/subjects 返回学科字典", f"{len(subjects_meta)} 个学科")
        require(
            {"math", "chinese", "english"} <= codes and bool(math_meta.get("modules", [{}])[0].get("chapters")),
            "字典含主要学科与「模块→章节」结构",
            f"math 模块示例：{math_meta.get('modules', [{}])[0].get('name')}",
        )

        # ---------------------------------------------------------- 1. 认证
        section("认证：发码 / 注册 / 登录 / 刷新 / 改密")
        status, body = request(client, "POST", f"{API}/auth/sms/send", json={"phone": phone, "scene": "register"})
        sms_code = data_of(body).get("dev_code")
        require(
            status == 200 and body.get("code") == 0 and isinstance(sms_code, str) and len(sms_code) >= 4,
            "POST /auth/sms/send 返回 dev_code",
            f"dev_code={sms_code}",
        )

        status, body = request(client, "POST", f"{API}/auth/login/sms", json={"phone": phone, "sms_code": sms_code})
        require(
            status == 400 and body.get("code") == 40014,
            "未注册手机号验证码登录返回 40014",
            f"HTTP {status} code={body.get('code')} {body.get('message')}",
        )

        status, body = request(
            client,
            "POST",
            f"{API}/auth/register",
            json={"phone": phone, "sms_code": sms_code, "password": initial_password},
        )
        registered = data_of(body)
        require(status == 200 and body.get("code") == 0, "POST /auth/register 注册成功", f"user={registered.get('user', {}).get('id')}")
        require(
            bool(registered.get("access_token")) and bool(registered.get("refresh_token")),
            "注册返回 access_token / refresh_token",
        )
        require(
            registered.get("user", {}).get("credits") == 1,
            "注册赠送 1 点数（register_bonus_credits）",
            f"credits={registered.get('user', {}).get('credits')}",
        )
        require(
            "****" in str(registered.get("user", {}).get("phone", "")),
            "注册响应中手机号已脱敏",
            str(registered.get("user", {}).get("phone")),
        )

        access_token = str(registered.get("access_token"))
        refresh_token = str(registered.get("refresh_token"))
        user_id = str(registered.get("user", {}).get("id"))
        auth = {"Authorization": f"Bearer {access_token}"}

        status, body = request(client, "GET", f"{API}/users/me", headers=auth)
        me = data_of(body)
        require(
            status == 200 and me.get("id") == user_id and me.get("credits") == 1,
            "GET /users/me 返回当前用户与余额",
            f"id={me.get('id')} credits={me.get('credits')} phone={me.get('phone')}",
        )

        status, body = request(client, "PATCH", f"{API}/users/me", json={"nickname": "冒烟同学"}, headers=auth)
        require(data_of(body).get("nickname") == "冒烟同学", "PATCH /users/me 修改昵称", str(data_of(body).get("nickname")))

        status, body = request(client, "GET", f"{API}/credits/balance", headers=auth)
        require(status == 200 and data_of(body).get("credits") == 1, "GET /credits/balance 余额为 1", str(data_of(body).get("credits")))

        status, body = request(client, "POST", f"{API}/auth/refresh", json={"refresh_token": refresh_token})
        refreshed = data_of(body)
        require(
            status == 200 and bool(refreshed.get("access_token")) and refreshed.get("refresh_token") != refresh_token,
            "POST /auth/refresh 旋转刷新令牌",
        )
        access_token = str(refreshed.get("access_token"))
        auth = {"Authorization": f"Bearer {access_token}"}

        status, body = request(
            client,
            "POST",
            f"{API}/auth/password",
            json={"old_password": initial_password, "new_password": new_password},
            headers=auth,
        )
        require(status == 200 and data_of(body).get("ok") is True, "POST /auth/password 修改密码")

        status, body = request(client, "POST", f"{API}/auth/login/password", json={"phone": phone, "password": new_password})
        require(status == 200 and body.get("code") == 0, "新密码可登录（POST /auth/login/password）")
        access_token = str(data_of(body).get("access_token"))
        auth = {"Authorization": f"Bearer {access_token}"}

        status, body = request(
            client, "POST", f"{API}/auth/login/password", json={"phone": phone, "password": "definitely-wrong"}
        )
        require(
            status == 400 and body.get("code") == 40015,
            "密码错误返回 40015",
            f"HTTP {status} code={body.get('code')}",
        )

        status, body = request(client, "GET", f"{API}/users/me")
        require(status == 401 and body.get("code") == 40100, "未带令牌访问返回 40100", f"HTTP {status} code={body.get('code')}")

        # ---------------------------------------------------------- 2. 考试与上传
        section("考试、上传与登记")
        status, body = request(
            client,
            "POST",
            f"{API}/exams",
            json={
                "name": f"冒烟测试考试 {run_id}",
                "exam_date": (date.today() - timedelta(days=3)).isoformat(),
                "grade": "高三",
                "exam_type": "月考",
                "total_full_score": 740,
            },
            headers=auth,
        )
        exam = data_of(body)
        require(status == 200 and bool(exam.get("id")), "POST /exams 创建考试", f"id={exam.get('id')} date={exam.get('exam_date')}")
        exam_id = str(exam.get("id"))

        status, body = request(client, "GET", f"{API}/exams?page=1&size=20", headers=auth)
        exam_page = data_of(body)
        require(
            status == 200 and exam_page.get("total", 0) >= 1 and any(item["id"] == exam_id for item in exam_page.get("items", [])),
            "GET /exams 列表包含新建考试",
            f"total={exam_page.get('total')}",
        )

        status, body = request(
            client,
            "POST",
            f"{API}/uploads/direct",
            files={"file": ("answer_sheet.png", PNG_1X1, "image/png")},
            data={"kind": "paper"},
            headers=auth,
        )
        uploaded = data_of(body)
        object_key = str(uploaded.get("object_key") or "")
        file_url = str(uploaded.get("url") or "")
        require(
            status == 200 and object_key and uploaded.get("size") == len(PNG_1X1),
            "POST /uploads/direct 上传成功",
            f"key={object_key} size={uploaded.get('size')}",
        )
        require(
            file_url.startswith(base_url) and "/static/" in file_url,
            "上传结果 url 指向 {public_base_url}/static/...",
            file_url,
        )
        status, _ = request(client, "GET", file_url)
        require(status == 200, "上传后的文件可通过 /static 访问", f"HTTP {status}")

        status, body = request(
            client,
            "POST",
            f"{API}/uploads/direct",
            files={"file": ("not_allowed.txt", b"hello", "text/plain")},
            data={"kind": "paper"},
            headers=auth,
        )
        require(
            status == 400 and body.get("code") == 40031,
            "非法扩展名上传返回 40031",
            f"HTTP {status} code={body.get('code')}",
        )

        subject_inputs = (("math", 95.0, 150.0), ("chinese", 112.0, 150.0), ("english", 118.0, 150.0))
        paper_ids: dict[str, str] = {}
        task_ids: dict[str, str] = {}
        for code, _score, _full in subject_inputs:
            status, body = request(
                client,
                "POST",
                f"{API}/exams/{exam_id}/papers",
                json={"subject_code": code, "title": f"{code} 试卷", "source_type": "photo", "object_keys": [object_key]},
                headers=auth,
            )
            created = data_of(body)
            require(
                status == 200 and bool(created.get("paper_id")) and bool(created.get("task_id")),
                f"POST /exams/{{id}}/papers（{code}）返回 paper_id + task_id",
                f"paper_id={created.get('paper_id')}",
            )
            paper_ids[code] = str(created.get("paper_id"))
            task_ids[code] = str(created.get("task_id"))

        ocr_task = poll(client, f"{API}/tasks/{task_ids['math']}", headers=auth)
        require(
            ocr_task.get("status") == "succeeded",
            "试卷 OCR 后台任务在超时前完成",
            f"status={ocr_task.get('status')} progress={ocr_task.get('progress')} message={ocr_task.get('message')}",
        )

        sheet_ids: dict[str, str] = {}
        for code, score, full in subject_inputs:
            status, body = request(
                client,
                "POST",
                f"{API}/exams/{exam_id}/answer-sheets",
                json={
                    "subject_code": code,
                    "paper_id": paper_ids[code],
                    "source_type": "photo",
                    "is_graded": True,
                    "object_keys": [object_key],
                    "score": score,
                    "full_score": full,
                },
                headers=auth,
            )
            created = data_of(body)
            require(
                status == 200 and bool(created.get("answer_sheet_id")) and bool(created.get("task_id")),
                f"POST /exams/{{id}}/answer-sheets（{code}）返回 answer_sheet_id + task_id",
                f"answer_sheet_id={created.get('answer_sheet_id')}",
            )
            sheet_ids[code] = str(created.get("answer_sheet_id"))

        sheet_task = poll(client, f"{API}/tasks/{task_ids['chinese']}", headers=auth)
        require(
            sheet_task.get("status") == "succeeded",
            "答题卡 OCR 后台任务在超时前完成",
            f"status={sheet_task.get('status')} message={sheet_task.get('message')}",
        )

        status, body = request(client, "GET", f"{API}/exams/{exam_id}", headers=auth)
        detail = data_of(body)
        require(
            status == 200
            and len(detail.get("papers") or []) == 3
            and len(detail.get("answer_sheets") or []) == 3
            and detail.get("subject_count") == 3,
            "GET /exams/{id} 详情含 3 份试卷与 3 张答题卡",
            f"papers={len(detail.get('papers') or [])} sheets={len(detail.get('answer_sheets') or [])} subjects={detail.get('subject_count')}",
        )

        # ---------------------------------------------------------- 3. 分析
        section("AI 失分分析")
        status, body = request(client, "POST", f"{API}/analyses", json={"exam_id": exam_id}, headers=auth)
        created = data_of(body)
        require(
            status == 200 and body.get("code") == 0 and bool(created.get("analysis_id")),
            "POST /analyses 发起分析",
            json.dumps(created, ensure_ascii=False),
        )
        analysis_id = str(created.get("analysis_id"))
        require(
            created.get("credits_cost") == 0 and created.get("credits_balance") == 1,
            "发起分析不消耗点数（余额保持 1）",
            f"cost={created.get('credits_cost')} balance={created.get('credits_balance')}",
        )

        result = poll(client, f"{API}/analyses/{analysis_id}", headers=auth, timeout=120.0)
        require(
            result.get("status") == "succeeded",
            "分析在超时前完成（status=succeeded）",
            f"status={result.get('status')} error={result.get('error')}",
        )

        stages = {item.get("stage"): item for item in (result.get("stages") or [])}
        require(
            len(stages) == 3
            and all(not item.get("unlocked") and item.get("content") is None for item in stages.values()),
            "结果默认三个阶段全部锁定且不下发内容",
            json.dumps({n: [item.get("ad_required"), item.get("unlocked")] for n, item in stages.items()}, ensure_ascii=False),
        )
        require(
            [stages[1].get("ad_required"), stages[2].get("ad_required"), stages[3].get("ad_required")] == [1, 2, 2],
            "三个阶段解锁所需广告次数为 1/2/2",
            str([stages[i].get("ad_required") for i in (1, 2, 3)]),
        )

        result_subjects = result.get("subjects") or []
        weak_points = result.get("weak_points") or []
        plan = result.get("improvement_plan") or {}
        require(
            len(result_subjects) >= 3,
            "结果含 ≥3 个学科",
            str([(s.get("subject_code"), s.get("rate")) for s in result_subjects]),
        )
        require(
            len(weak_points) >= 6,
            "结果含 ≥6 个失分点",
            f"共 {len(weak_points)} 个",
        )
        point_ids = [point.get("id") for point in weak_points]
        require(
            all(point_ids) and len(set(point_ids)) == len(point_ids),
            "失分点 id 存在且唯一（前端用作 key）",
            str(point_ids),
        )
        require(
            all(isinstance(point.get("evidence"), dict) and point["evidence"].get("question_no") for point in weak_points),
            "每个失分点都有 evidence（含题号）",
            str(weak_points[0].get("evidence", {}).get("question_no")) if weak_points else "",
        )
        total_score = result.get("total_score")
        full_score = result.get("full_score")
        score_rate = result.get("score_rate")
        expected_total = round(sum(float(s.get("score") or 0) for s in result_subjects), 2)
        require(
            abs(float(total_score or 0) - expected_total) < 0.05 and 0 < float(score_rate or 0) <= 1,
            "total_score / full_score / score_rate 与各科分数自洽",
            f"total={total_score} full={full_score} rate={score_rate}（各科合计 {expected_total}）",
        )
        require(
            bool(plan.get("summary")) and bool(plan.get("steps")) and bool(plan.get("weekly_plan")),
            "提升方案完整（summary + steps + weekly_plan）",
            f"steps={len(plan.get('steps') or [])} weekly={len(plan.get('weekly_plan') or [])} priority={len(plan.get('priority_order') or [])}",
        )
        require(bool(result.get("model_name")), "结果带 model_name", str(result.get("model_name")))

        status, body = request(client, "GET", f"{API}/analyses?exam_id={exam_id}&page=1&size=10", headers=auth)
        require(
            status == 200 and data_of(body).get("total", 0) >= 1,
            "GET /analyses 列表可查（按考试筛选）",
            f"total={data_of(body).get('total')}",
        )

        # ------------------------------------------------------ 分阶段解锁
        status, body = request(
            client,
            "POST",
            f"{API}/analyses/{analysis_id}/stages/1/unlock",
            json={"platform": "wechat", "event_id": f"smoke_s1_{analysis_id}", "watched_seconds": 30},
            headers=auth,
        )
        stage1 = data_of(body)
        require(
            status == 200 and stage1.get("unlocked") is True,
            "阶段1 看 1 次广告即解锁",
            f"watched={stage1.get('watched')}",
        )
        stage1_summary = (stage1.get("content") or {}).get("summary", "")
        require(
            0 < len(stage1_summary) <= 200,
            "阶段1 学情概述不超过 200 字",
            f"{len(stage1_summary)} 字",
        )

        status, body = request(
            client,
            "POST",
            f"{API}/analyses/{analysis_id}/stages/2/unlock",
            json={"platform": "wechat", "event_id": f"smoke_s2a_{analysis_id}", "watched_seconds": 30},
            headers=auth,
        )
        require(
            data_of(body).get("unlocked") is False and data_of(body).get("watched") == 1,
            "阶段2 第 1 次广告后仍未解锁",
            json.dumps(data_of(body), ensure_ascii=False),
        )

        status, body = request(
            client,
            "POST",
            f"{API}/analyses/{analysis_id}/stages/2/unlock",
            json={"platform": "wechat", "event_id": f"smoke_s2a_{analysis_id}", "watched_seconds": 30},
            headers=auth,
        )
        require(
            data_of(body).get("duplicated") is True and data_of(body).get("watched") == 1,
            "重复 event_id 幂等且不涨进度",
            json.dumps(data_of(body), ensure_ascii=False),
        )

        status, body = request(
            client,
            "POST",
            f"{API}/analyses/{analysis_id}/stages/2/unlock",
            json={"platform": "wechat", "event_id": f"smoke_s2b_{analysis_id}", "watched_seconds": 30},
            headers=auth,
        )
        stage2 = data_of(body)
        weak_questions = (stage2.get("content") or {}).get("weak_questions") or []
        require(
            stage2.get("unlocked") is True and len(weak_questions) > 0,
            "阶段2 第 2 次广告后解锁并给出失分题目",
            f"{len(weak_questions)} 道题",
        )

        status, body = request(
            client,
            "POST",
            f"{API}/analyses/{analysis_id}/stages/3/unlock",
            json={"platform": "wechat", "event_id": f"smoke_s3_short_{analysis_id}", "watched_seconds": 3},
            headers=auth,
        )
        require(
            body.get("code") == 40001,
            "广告观看时长不足被拒绝且不计入进度",
            f"code={body.get('code')} {body.get('message')}",
        )

        status, body = request(
            client, "POST", f"{API}/analyses/{analysis_id}/stages/3/unlock-by-credit", headers=auth
        )
        stage3 = data_of(body)
        stage3_modules = (stage3.get("content") or {}).get("modules") or []
        require(
            status == 200 and stage3.get("unlocked") is True and stage3.get("credits_balance") == 0,
            "用点数跳过广告解锁阶段3（余额 1 → 0）",
            json.dumps(stage3, ensure_ascii=False),
        )
        require(
            len(stage3_modules) > 0,
            "阶段3 给出需要重点补的知识模块",
            f"{len(stage3_modules)} 个模块",
        )

        # 余额已为 0：对一条新的分析用点数解锁应返回 40200 且不产生任何副作用
        status, body = request(client, "POST", f"{API}/analyses", json={"exam_id": exam_id, "force": True}, headers=auth)
        second_analysis_id = str(data_of(body).get("analysis_id") or "")
        require(
            status == 200 and bool(second_analysis_id) and data_of(body).get("credits_cost") == 0,
            "再次发起分析同样不扣费",
            json.dumps(data_of(body), ensure_ascii=False),
        )
        status, body = request(
            client, "POST", f"{API}/analyses/{second_analysis_id}/stages/1/unlock-by-credit", headers=auth
        )
        require(
            status == 402 and body.get("code") == 40200,
            "点数不足时用点数解锁阶段返回 40200（HTTP 402）",
            f"HTTP {status} code={body.get('code')} {body.get('message')}",
        )
        status, body = request(client, "GET", f"{API}/credits/balance", headers=auth)
        require(data_of(body).get("credits") == 0, "点数不足时余额不变（无副作用）", str(data_of(body).get("credits")))
        status, body = request(client, "GET", f"{API}/analyses/{second_analysis_id}", headers=auth)
        second_stages = {item.get("stage"): item for item in (data_of(body).get("stages") or [])}
        require(
            second_stages.get(1, {}).get("unlocked") is False,
            "点数不足时该阶段仍保持锁定",
            json.dumps(second_stages.get(1, {}), ensure_ascii=False),
        )

        # ---------------------------------------------------------- 4. 趋势与账本
        section("趋势与点数账本")
        status, body = request(client, "GET", f"{API}/trends?limit=10&subjects=math,chinese", headers=auth)
        trends = data_of(body)
        points = trends.get("points") or []
        dates = [point.get("exam_date") for point in points]
        require(status == 200 and dates == sorted(dates), "GET /trends 数据点按 exam_date 升序", str(dates))
        target = next((point for point in points if point.get("exam_id") == exam_id), None)
        require(target is not None, "趋势数据包含本次考试")
        target_subjects = (target or {}).get("subjects") or {}
        require(
            set(target_subjects) == {"math", "chinese"},
            "subjects 过滤生效（只含 math/chinese，缺考学科不出现）",
            str(target_subjects),
        )
        require(
            abs(float(target_subjects.get("math", 0)) - 95 / 150) < 0.01,
            "数学得分率正确（95/150≈0.633）",
            f"math={target_subjects.get('math')} total={ (target or {}).get('total_rate') }",
        )
        require(
            {item.get("code") for item in trends.get("subjects") or []} == {"math", "chinese"},
            "trends.subjects 元数据与过滤一致",
            str([(item.get("code"), item.get("name")) for item in trends.get("subjects") or []]),
        )

        status, body = request(client, "GET", f"{API}/credits/ledger?page=1&size=20", headers=auth)
        ledger = data_of(body).get("items") or []
        reasons = [item.get("reason") for item in ledger]
        require(
            status == 200 and "register_bonus" in reasons and "purchase" in reasons,
            "GET /credits/ledger 含注册赠送与点数解锁流水",
            str(reasons),
        )
        purchase_item = next((item for item in ledger if item.get("reason") == "purchase"), {})
        require(
            purchase_item.get("delta") == -1 and purchase_item.get("balance_after") == 0,
            "点数解锁阶段流水 delta=-1、balance_after=0",
            json.dumps(purchase_item, ensure_ascii=False),
        )
        require(
            "analysis" not in reasons,
            "发起分析不产生扣费流水",
            str(reasons),
        )

        # ---------------------------------------------------------- 5. 广告奖励
        section("激励视频广告")
        # 广告进度按「用户」累计，而前面的分阶段解锁也上报过广告事件，
        # 所以这里先读当前进度再决定还差几次，避免用例之间互相污染。
        status, body = request(client, "GET", f"{API}/credits/ad-progress", headers=auth)
        start_progress = data_of(body)
        needed = int(start_progress.get("needed") or 10)
        already = int(start_progress.get("watched") or 0)
        remaining = max(1, needed - already)

        credited_indexes: list[int] = []
        last_reward: dict[str, Any] = {}
        for index in range(remaining):
            event_id = f"smoke-{run_id}-{index:02d}"
            status, body = request(
                client,
                "POST",
                f"{API}/credits/ad-reward",
                json={"platform": "mock", "event_id": event_id, "watched_seconds": 15},
                headers=auth,
            )
            reward = data_of(body)
            if status != 200 or body.get("code") != 0:
                raise Abort(f"广告上报失败：HTTP {status} {json.dumps(body, ensure_ascii=False)[:200]}")
            if reward.get("credited"):
                credited_indexes.append(index)
            last_reward = reward
        reward_event_id = f"smoke-{run_id}-{remaining - 1:02d}"
        require(
            credited_indexes == [remaining - 1],
            f"累计看满 {needed} 次广告后仅最后一次触发发奖",
            f"起始进度 {already}/{needed}，本轮第 {[i + 1 for i in credited_indexes]} 次触发",
        )
        require(
            last_reward.get("credits") == 1 and (last_reward.get("progress") or {}).get("watched") == 0,
            "发奖后余额 1、进度归零",
            json.dumps(last_reward, ensure_ascii=False),
        )

        status, body = request(
            client,
            "POST",
            f"{API}/credits/ad-reward",
            json={"platform": "mock", "event_id": reward_event_id, "watched_seconds": 15},
            headers=auth,
        )
        duplicated = data_of(body)
        require(
            status == 200
            and body.get("code") == 0
            and duplicated.get("credited") is False
            and duplicated.get("credits") == 1
            and (duplicated.get("progress") or {}).get("watched") == 0,
            "重复 event_id 幂等：code=0、credited=false、余额与进度不变",
            json.dumps(duplicated, ensure_ascii=False),
        )

        status, body = request(client, "GET", f"{API}/credits/ad-progress", headers=auth)
        progress = data_of(body)
        require(
            status == 200
            and progress.get("watched") == 0
            and progress.get("needed") == 10
            and progress.get("per_reward") == 1
            and progress.get("credits") == 1,
            "GET /credits/ad-progress 进度与配置一致",
            json.dumps(progress, ensure_ascii=False),
        )

        # ---------------------------------------------------------- 6. 订单与充值
        section("套餐与充值")
        status, body = request(client, "GET", f"{API}/orders/plans", headers=auth)
        plans = list_of(body)
        require(status == 200 and len(plans) == 9, "GET /orders/plans 首次访问自动灌入 9 个套餐", f"{len(plans)} 个")
        plan_index = {(plan.get("channel"), plan.get("credits")): plan for plan in plans}
        require(
            plan_index.get(("wechat", 5), {}).get("price_cents") == 500
            and plan_index.get(("apple_iap", 5), {}).get("price_cents") == 750
            and plan_index.get(("alipay", 1), {}).get("price_cents") == 100,
            "套餐定价符合契约（1 点=100 分 / iOS 1 点=150 分）",
            str({f"{k[0]}-{k[1]}点": v.get("price_cents") for k, v in sorted(plan_index.items())}),
        )
        recommended = [plan for plan in plans if plan.get("recommended")]
        require(
            len(recommended) == 3 and all(plan.get("credits") == 5 for plan in recommended),
            "每个渠道各 1 个推荐套餐（5 点档）",
            str([(plan.get("channel"), plan.get("credits")) for plan in recommended]),
        )

        plan_id = str(plan_index[("wechat", 5)]["id"])
        status, body = request(client, "POST", f"{API}/orders", json={"plan_id": plan_id, "channel": "wechat"}, headers=auth)
        order = data_of(body)
        require(
            status == 200 and order.get("amount_cents") == 500 and bool(order.get("order_no")),
            "POST /orders 创建订单（500 分）",
            json.dumps(order, ensure_ascii=False),
        )
        order_no = str(order.get("order_no"))

        status, body = request(client, "POST", f"{API}/orders/{order_no}/pay", headers=auth)
        paid = data_of(body)
        require(
            status == 200 and paid.get("status") == "paid" and paid.get("credits") == 6,
            "POST /orders/{no}/pay 模拟支付到账（1 + 5 = 6 点）",
            json.dumps(paid, ensure_ascii=False),
        )

        status, body = request(client, "POST", f"{API}/orders/{order_no}/pay", headers=auth)
        require(data_of(body).get("credits") == 6, "重复支付幂等（余额仍为 6）", str(data_of(body).get("credits")))

        status, body = request(client, "GET", f"{API}/orders?page=1&size=10", headers=auth)
        require(
            status == 200 and data_of(body).get("total", 0) >= 1,
            "GET /orders 返回我的订单列表",
            f"total={data_of(body).get('total')}",
        )

        status, body = request(client, "POST", f"{API}/orders/notify/wechat", json={}, headers=auth)
        require(data_of(body).get("ok") is True, "POST /orders/notify/{channel} 占位接口返回 ok", json.dumps(data_of(body)))

        # ---------------------------------------------------------- 7. 清理
        section("删除与异常码")
        status, body = request(
            client,
            "POST",
            f"{API}/exams",
            json={"name": f"待删除考试 {run_id}", "exam_date": date.today().isoformat(), "grade": "高三"},
            headers=auth,
        )
        temp_exam_id = str(data_of(body).get("id"))
        status, body = request(
            client,
            "POST",
            f"{API}/exams/{temp_exam_id}/answer-sheets",
            json={
                "subject_code": "math",
                "source_type": "photo",
                "is_graded": True,
                "object_keys": [object_key],
                "score": 88,
                "full_score": 150,
            },
            headers=auth,
        )
        require(
            status == 200 and bool(data_of(body).get("answer_sheet_id")),
            "为待删除考试登记答题卡",
            f"answer_sheet_id={data_of(body).get('answer_sheet_id')}",
        )

        temp_analysis = data_of(
            request(client, "POST", f"{API}/analyses", json={"exam_id": temp_exam_id}, headers=auth)[1]
        )
        poll(client, f"{API}/analyses/{temp_analysis.get('analysis_id')}", headers=auth, timeout=120.0)
        status, body = request(client, "GET", f"{API}/analyses?exam_id={temp_exam_id}", headers=auth)
        require(
            data_of(body).get("total") == 1,
            "待删除考试已有 1 条分析记录",
            f"total={data_of(body).get('total')}",
        )

        status, body = request(client, "DELETE", f"{API}/exams/{temp_exam_id}", headers=auth)
        require(status == 200 and data_of(body).get("ok") is True, "DELETE /exams/{id} 删除成功")

        status, body = request(client, "GET", f"{API}/analyses?exam_id={temp_exam_id}", headers=auth)
        require(
            data_of(body).get("total") == 0,
            "删除考试后其分析记录一并级联清理",
            f"total={data_of(body).get('total')}",
        )

        status, body = request(client, "GET", f"{API}/exams/{temp_exam_id}", headers=auth)
        require(
            status == 404 and body.get("code") == 40400,
            "删除后查询详情返回 40400",
            f"HTTP {status} code={body.get('code')}",
        )

    return _summary()


def _parse_args() -> argparse.Namespace:
    """解析命令行参数。"""
    parser = argparse.ArgumentParser(description="ToTop API 端到端冒烟测试")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000", help="服务地址（默认 http://127.0.0.1:8000）")
    parser.add_argument("--phone", default=None, help="测试手机号（默认随机，避免与已有账号冲突）")
    parser.add_argument("--timeout", type=float, default=60.0, help="单请求超时秒数（默认 60）")
    return parser.parse_args()


def _summary() -> int:
    """打印 PASS / FAIL 汇总并返回退出码。"""
    passed = [item for item in _results if item[1]]
    failed = [item for item in _results if not item[1]]
    print()
    print("=" * 68)
    print(f"  冒烟测试结果：{GREEN}PASS {len(passed)}{RESET} / {RED}FAIL {len(failed)}{RESET}（共 {len(_results)} 项）")
    if failed:
        print(f"  {RED}失败明细：{RESET}")
        for name, _ok, detail in failed:
            print(f"    - {name}" + (f"  {DIM}{detail}{RESET}" if detail else ""))
    print("=" * 68)
    return 0 if not failed else 1


if __name__ == "__main__":
    exit_code = 1
    try:
        exit_code = main()
    except Abort as exc:
        print(f"\n{RED}关键步骤失败，已终止后续用例：{exc}{RESET}")
        _summary()
        exit_code = 1
    except Exception as exc:  # noqa: BLE001 —— 脚本自身异常也要计入失败
        traceback.print_exc()
        check("冒烟脚本执行期间未抛出意外异常", False, f"{type(exc).__name__}: {exc}")
        exit_code = 1
        _summary()
    sys.exit(exit_code)
