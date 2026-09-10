"""本地可运行的单步 VLM 网关。"""
import base64
import binascii
import os
import secrets
import time
from contextlib import asynccontextmanager
import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from .models import Decision, Provider, StepReply, StepRequest
from .providers import ProviderFailure, config_for, invoke


@asynccontextmanager
async def lifespan(app: FastAPI):
    """管理共享 HTTP 连接池。参数 app 为 FastAPI 实例；退出时关闭连接，不保留图片。"""
    async with httpx.AsyncClient(timeout=httpx.Timeout(60, connect=10), follow_redirects=False) as client:
        app.state.client = client
        yield


app = FastAPI(title="Framer Sense VLM Gateway", lifespan=lifespan)


async def authorize(authorization: str | None = Header(default=None)) -> None:
    """校验网关令牌。参数 authorization 为 Bearer 请求头；未配置时拒绝受保护接口。"""
    expected = os.getenv("VLM_GATEWAY_TOKEN", "")
    if not expected:
        raise HTTPException(503, detail={"code": "UNAVAILABLE", "message": "网关令牌尚未配置"})
    if not secrets.compare_digest((authorization or "").encode(), ("Bearer " + expected).encode()):
        raise HTTPException(401, detail={"code": "AUTH", "message": "网关令牌无效"})


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    """返回不包含原始输入的错误。参数 request 为 HTTP 请求，exc 为校验错误，均不记录其敏感内容。"""
    return JSONResponse(status_code=422, content={"detail": {"code": "BAD_REQUEST", "message": "请求字段不符合构图协议"}})


@app.get("/health")
async def health() -> dict:
    """返回进程存活状态；无参数，不暴露模型配置。"""
    return {"status": "ok"}


@app.get("/v1/capabilities", dependencies=[Depends(authorize)])
async def capabilities() -> dict:
    """返回供应商配置状态；无参数，不返回任何密钥。"""
    return {"providers": [{"provider": p.value, "model": config_for(p).model, "configured": bool(config_for(p).key and config_for(p).model), "vision": True, "outputMode": "json_schema" if p in (Provider.GPT, Provider.GEMINI) else "json_object" if p == Provider.QWEN else "prompt_json"} for p in Provider]}


@app.post("/v1/director/step", response_model=StepReply, dependencies=[Depends(authorize)])
async def step(body: StepRequest, request: Request) -> StepReply:
    """执行一轮真实图文推理。参数 body 为公共协议，request 提供连接池；错误输出交给客户端修正或路由。"""
    try:
        image = base64.b64decode(body.imageBase64, validate=True)
        if not image.startswith(b"\xff\xd8\xff"):
            raise ValueError("仅支持 JPEG")
    except (ValueError, binascii.Error):
        raise HTTPException(400, detail={"code": "BAD_REQUEST", "message": "图片必须为有效 JPEG Base64"}) from None
    started = time.monotonic()
    try:
        model, raw = await invoke(body, request.app.state.client)
    except ProviderFailure as exc:
        raise HTTPException(exc.status, detail={"code": exc.code, "message": exc.message}) from None
    try:
        decision = Decision.model_validate_json(raw)
    except ValueError:
        decision = None
    return StepReply(model=model, raw=raw, decision=decision, elapsedMs=int((time.monotonic() - started) * 1000))
