"""阿里云百炼（DashScope）Qwen-VL Provider。

通过 DashScope 的 **OpenAI 兼容端点**调用视觉理解模型：

```
POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

要点：
- 图片以 base64 `data:image/...;base64,...` 形式放进 `image_url` 字段；
- 使用 `response_format={"type": "json_object"}` 强制模型输出 JSON；
- 网络异常 / 5xx / 429 会做指数退避重试（默认 3 次），4xx 参数类错误直接失败；
- 单次请求超时默认 120 秒（`AI_TIMEOUT_SECONDS`）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

import httpx

from app.core.config import settings
from app.core.errors import ErrorCode, UpstreamError
from app.providers.ai.base import AIProvider, AnalysisInput
from app.schemas.analysis import AnalysisResult

logger = logging.getLogger("app.providers.ai.qwen")

# 允许重试的 HTTP 状态码
_RETRYABLE_STATUS = {408, 409, 425, 429, 500, 502, 503, 504}

# 送回模型前，单科题目文本的最大字符数（避免 prompt 过长）
_MAX_PAPER_CHARS = 6000

SYSTEM_PROMPT = """你是一位资深中学教研员，擅长根据学生的答卷与题目做失分归因分析。

严格要求：
1. 只输出一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹；
2. JSON 必须符合下面给出的字段结构，不要新增或省略顶层字段；
3. 所有文字内容使用简体中文；学科代码必须使用输入中给出的 subject_code；
4. 若某学科信息不足，也要给出基于常见考情的合理推断，不要留空数组；
5. weak_points 要覆盖主要失分点（建议 6~8 条），每条都要有具体题号、学生作答与正确答案的对比。"""

JSON_SCHEMA_SPEC = """输出 JSON 结构（字段名必须完全一致）：
{
  "subjects": [
    {"subject_code": "math", "subject_name": "数学", "score": 95, "full_score": 150,
     "rate": 0.633, "weak_module_names": ["函数与导数", "解析几何"]}
  ],
  "weak_points": [
    {"subject_code": "math", "subject_name": "数学",
     "module": "函数与导数", "chapter": "导数的应用",
     "knowledge_point": "利用导数求单调区间",
     "severity": "high",              // high / medium / low
     "lost_score": 8,                  // 数字，失分分值
     "evidence": {
       "question_no": "18(1)",
       "question_text": "题干（可适当截断）",
       "student_answer": "学生的实际作答",
       "correct_answer": "正确答案或标准答案要点",
       "error_type": "概念混淆"        // 错误类型
     },
     "suggestion": "可执行的针对性提升建议"
    }
  ],
  "improvement_plan": {
    "summary": "整体诊断结论",
    "priority_order": ["最该先攻的知识点", "其次", "..."],
    "steps": [
      {"title": "步骤标题", "detail": "具体做法", "estimated_hours": 2.5,
       "resources": ["教材必修一 3.2 节", "..."]}
    ],
    "weekly_plan": [
      {"week": 1, "focus": "本周重点", "target": "本周目标"}
    ]
  }
}"""


class QwenProvider(AIProvider):
    """DashScope Qwen-VL 实现。"""

    name = "qwen"
    supports_vision = True

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str | None = None,
        timeout_seconds: int | None = None,
        max_retries: int | None = None,
    ) -> None:
        self._api_key = (api_key if api_key is not None else settings.dashscope_api_key).strip()
        if not self._api_key:
            raise ValueError("未配置 DASHSCOPE_API_KEY，无法使用 QwenProvider")
        self._base_url = (base_url or settings.dashscope_base_url).rstrip("/")
        self.model_name = model or settings.qwen_model
        self._timeout_seconds = timeout_seconds or settings.ai_timeout_seconds
        self._max_retries = max(1, max_retries or settings.ai_max_retries)
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(self._timeout_seconds, connect=15.0),
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            },
        )

    # ------------------------------------------------------------ 对外接口
    async def analyze_exam(self, payload: AnalysisInput) -> AnalysisResult:
        """调用 qwen-vl-max 做失分分析，并把返回 JSON 解析成结构化结果。"""
        body = {
            "model": self.model_name,
            "messages": self._build_messages(payload),
            "response_format": {"type": "json_object"},
            "temperature": 0.2,
            "max_tokens": 4096,
        }
        data = await self._post_chat(body)
        content = self._extract_content(data)
        parsed = self._parse_json(content)

        try:
            result = AnalysisResult.model_validate(parsed)
        except Exception as exc:  # noqa: BLE001 —— 模型输出不符合契约时统一转成上游错误
            logger.error("模型返回的 JSON 无法解析为分析结果：%s", content[:500])
            raise UpstreamError(
                "AI 返回结果格式不正确，请稍后重试",
                code=ErrorCode.UPSTREAM_ERROR,
                detail={"reason": str(exc)[:300]},
            ) from exc

        usage = data.get("usage") or {}
        result.model_name = self.model_name
        result.prompt_tokens = int(usage.get("prompt_tokens") or 0)
        result.completion_tokens = int(usage.get("completion_tokens") or 0)
        return result

    async def extract_text(self, image_urls: list[str], *, subject_name: str = "", hint: str = "") -> str:
        """用视觉模型提取试卷 / 答题卡文本。失败时返回空串（由服务层兜底）。"""
        if not image_urls:
            return ""
        label = subject_name or "试卷"
        instruction = (
            f"请逐张识别下面 {label} 的图片，输出其中的全部文字内容："
            "题干、题号、分值、学生的手写作答都要尽量保留；"
            "只输出识别到的文字，不要总结、不要解释、不要使用 markdown 代码块。"
        )
        if hint:
            instruction += f"\n补充说明：{hint}"

        content: list[dict[str, Any]] = [{"type": "text", "text": instruction}]
        content.extend({"type": "image_url", "image_url": {"url": url}} for url in image_urls[: settings.ai_max_images])

        body = {
            "model": self.model_name,
            "messages": [
                {"role": "system", "content": "你是一个高精度的 OCR 与版面理解助手。"},
                {"role": "user", "content": content},
            ],
            "temperature": 0.0,
            "max_tokens": 4096,
        }
        try:
            data = await self._post_chat(body)
        except UpstreamError as exc:
            logger.warning("OCR 调用失败，返回空文本：%s", exc.message)
            return ""
        return self._extract_content(data).strip()

    async def aclose(self) -> None:
        """关闭 HTTP 连接池。"""
        await self._client.aclose()

    # ------------------------------------------------------------ 内部实现
    def _build_messages(self, payload: AnalysisInput) -> list[dict[str, Any]]:
        """拼装 system + user 消息（文本与图片混排）。"""
        parts: list[str] = [
            "请分析下面这次考试的学生答卷，给出结构化的失分分析与提升建议。",
            "",
            "【考试信息】",
            f"- 名称：{payload.exam.name}",
            f"- 日期：{payload.exam.exam_date}",
            f"- 年级：{payload.exam.grade or '未填写'}",
            f"- 类型：{payload.exam.exam_type or '未填写'}",
            f"- 总分满分：{payload.exam.total_full_score:g}",
            "",
            "【参与分析的学科】",
        ]
        for subject in payload.subjects:
            subject_full = f"{subject.full_score:g}" if subject.full_score else "未知"
            subject_score = f"{subject.score:g}" if subject.score is not None else "未知（未批改）"
            parts.append(f"## {subject.subject_name}（subject_code={subject.subject_code}）")
            parts.append(f"- 满分：{subject_full}；老师给出的得分：{subject_score}")
            parts.append(f"- 是否已由老师批改：{'是，请以老师给分为准做归因' if subject.is_graded else '否，请自行判分'}")
            if subject.weak_module_hint:
                parts.append(f"- 该学科知识模块参考：{'、'.join(subject.weak_module_hint[:20])}")
            if subject.paper_text:
                text = subject.paper_text.strip()[:_MAX_PAPER_CHARS]
                parts.append("- 题目文本：")
                parts.append(text)
            else:
                parts.append("- 题目文本：（未提供，请依据图片内容识别）")
            parts.append("")

        if payload.knowledge_modules:
            parts.append("【知识模块术语参考】")
            for code, modules in payload.knowledge_modules.items():
                if modules:
                    parts.append(f"- {code}：{'、'.join(modules[:30])}")
            parts.append("")

        if payload.extra_note:
            parts.append(f"【用户补充】{payload.extra_note}")
            parts.append("")

        parts.append(JSON_SCHEMA_SPEC)

        content: list[dict[str, Any]] = [{"type": "text", "text": "\n".join(parts)}]

        # 按学科顺序追加答题卡图片（最多 AI_MAX_IMAGES 张）
        image_count = 0
        for subject in payload.subjects:
            for url in subject.image_urls:
                if image_count >= settings.ai_max_images:
                    break
                content.append({"type": "text", "text": f"（以下图片属于：{subject.subject_name} 的答题卡）"})
                content.append({"type": "image_url", "image_url": {"url": url}})
                image_count += 1
            if image_count >= settings.ai_max_images:
                break

        return [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": content},
        ]

    async def _post_chat(self, body: dict[str, Any]) -> dict[str, Any]:
        """POST 兼容端点，带指数退避重试。"""
        url = f"{self._base_url}/chat/completions"
        last_error: Exception | None = None

        for attempt in range(1, self._max_retries + 1):
            try:
                response = await self._client.post(url, json=body)
            except httpx.HTTPError as exc:
                last_error = exc
                logger.warning("调用 DashScope 失败（第 %s/%s 次）：%s", attempt, self._max_retries, exc)
            else:
                if response.status_code < 400:
                    try:
                        return response.json()
                    except ValueError as exc:
                        last_error = exc
                        logger.warning("DashScope 返回非 JSON 响应（第 %s 次）：%s", attempt, response.text[:200])
                elif response.status_code in _RETRYABLE_STATUS:
                    last_error = UpstreamError(
                        f"DashScope 返回 {response.status_code}",
                        code=ErrorCode.UPSTREAM_ERROR,
                        detail={"body": response.text[:300]},
                    )
                    logger.warning(
                        "调用 DashScope 可重试错误（第 %s/%s 次）：HTTP %s %s",
                        attempt,
                        self._max_retries,
                        response.status_code,
                        response.text[:200],
                    )
                else:
                    # 鉴权失败、参数错误等：重试没有意义
                    logger.error("调用 DashScope 失败：HTTP %s %s", response.status_code, response.text[:500])
                    raise UpstreamError(
                        f"AI 服务调用失败（HTTP {response.status_code}）",
                        code=ErrorCode.UPSTREAM_ERROR,
                        detail={"body": response.text[:300]},
                    )

            if attempt < self._max_retries:
                await asyncio.sleep(2 ** (attempt - 1))  # 1s, 2s, 4s ...

        raise UpstreamError(
            "AI 服务暂时不可用，请稍后重试",
            code=ErrorCode.UPSTREAM_ERROR,
            detail={"reason": str(last_error)[:300]} if last_error else None,
        )

    @staticmethod
    def _extract_content(data: dict[str, Any]) -> str:
        """从 OpenAI 兼容响应里取出正文。"""
        choices = data.get("choices") or []
        if not choices:
            raise UpstreamError("AI 返回结果为空", code=ErrorCode.UPSTREAM_ERROR, detail={"body": str(data)[:300]})
        message = choices[0].get("message") or {}
        content = message.get("content")
        if isinstance(content, list):
            # 部分模型返回分段内容数组
            content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
        if not isinstance(content, str) or not content.strip():
            raise UpstreamError("AI 返回内容为空", code=ErrorCode.UPSTREAM_ERROR)
        return content

    @staticmethod
    def _parse_json(content: str) -> dict[str, Any]:
        """从模型输出中提取 JSON（容错 ```` ```json ```` 包裹与前后解释文字）。"""
        text = content.strip()
        fence = re.search(r"```(?:json)?\s*(.+?)```", text, flags=re.DOTALL)
        if fence:
            text = fence.group(1).strip()
        if not text.startswith("{"):
            start, end = text.find("{"), text.rfind("}")
            if start != -1 and end > start:
                text = text[start : end + 1]
        try:
            parsed = json.loads(text)
        except ValueError as exc:
            raise UpstreamError(
                "AI 返回内容不是合法 JSON",
                code=ErrorCode.UPSTREAM_ERROR,
                detail={"preview": content[:300]},
            ) from exc
        if not isinstance(parsed, dict):
            raise UpstreamError("AI 返回内容不是 JSON 对象", code=ErrorCode.UPSTREAM_ERROR)
        return parsed
