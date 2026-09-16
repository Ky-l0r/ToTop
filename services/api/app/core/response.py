"""统一响应信封。

全部 `/api/v1` 接口最终返回 `{"code": 0, "message": "ok", "data": <payload>}`。

实现方式是自定义 APIRoute（`EnvelopeRoute`）：端点的处理函数照常写自己的返回类型
（这样 FastAPI 仍会做 response_model 校验、OpenAPI 里也展示 data 的真实结构），
路由层再把校验后的 JSON 包进信封。
错误响应不走这里，由 `app.core.errors` 的异常处理器产出（code 非 0）。
"""

from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from fastapi import Request, Response
from fastapi.routing import APIRoute

OK_CODE = 0
OK_MESSAGE = "ok"


def envelope(data: Any, *, code: int = OK_CODE, message: str = OK_MESSAGE) -> dict[str, Any]:
    """手工构造信封（少数需要绕过路由包装的场景使用）。"""
    return {"code": code, "message": message, "data": data}


class EnvelopeRoute(APIRoute):
    """把端点返回的 JSON 自动包装成统一响应体的路由类。"""

    def get_route_handler(self) -> Callable[[Request], Any]:
        original_handler = super().get_route_handler()

        async def wrapped_handler(request: Request) -> Response:
            response = await original_handler(request)

            # 非 2xx（如 304）或非 JSON 响应（文件流等）原样返回
            if not isinstance(response, Response) or response.status_code >= 300:
                return response
            content_type = response.headers.get("content-type", "")
            if "application/json" not in content_type:
                return response

            body = bytes(response.body)
            if not body:
                return response

            try:
                payload = json.loads(body)
            except (ValueError, UnicodeDecodeError):
                return response

            # 已经是信封结构（例如 handler 手工用 envelope() 返回）则不重复包装
            if isinstance(payload, dict) and {"code", "message", "data"} <= set(payload.keys()):
                return response

            new_body = json.dumps(
                {"code": OK_CODE, "message": OK_MESSAGE, "data": payload},
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
            headers = dict(response.headers)
            headers["content-length"] = str(len(new_body))
            return Response(
                content=new_body,
                status_code=response.status_code,
                headers=headers,
                media_type="application/json",
                background=response.background,
            )

        return wrapped_handler
