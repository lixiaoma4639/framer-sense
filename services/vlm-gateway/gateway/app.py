"""本地可运行的单步 VLM 网关。"""
import base64
import binascii
import os
import secrets
import time
from contextlib import asynccontextmanager
from pathlib import Path
import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, FileResponse
from .models import PhotoStepRequest, PhotoReferenceDecision, Decision, Provider, StepReply, StepRequest, ViewRequest, ViewReply, ViewSelection
from .providers import ProviderFailure, config_for, invoke, multi_image_limit
from .config import GATEWAY
from .portraits import IMAGE_CONFIG, PortraitJobs, PortraitRequest


@asynccontextmanager
async def lifespan(app: FastAPI):
    """管理共享 HTTP 连接池。参数 app 为 FastAPI 实例；退出时关闭连接，不保留图片。"""
    async with httpx.AsyncClient(timeout=httpx.Timeout(60, connect=10), follow_redirects=False) as client:
        app.state.client = client
        app.state.portraits = PortraitJobs(client, Path(os.getenv("VLM_PORTRAIT_DIR", ".vlm-portraits")))
        try:
            yield
        finally:
            await app.state.portraits.close()


app = FastAPI(title="Framer Sense VLM Gateway", lifespan=lifespan)


async def authorize(authorization: str | None = Header(default=None)) -> None:
    """校验网关令牌。参数 authorization 为 Bearer 请求头；未配置时拒绝受保护接口。"""
    expected = os.getenv("VLM_GATEWAY_TOKEN", "")
    if not expected:
        raise HTTPException(503, detail={"code": "UNAVAILABLE", "message": "网关令牌尚未配置"})
    if not secrets.compare_digest((authorization or "").encode(), ("Bearer " + expected).encode()):
        raise HTTPException(401, detail={"code": "AUTH", "message": "网关令牌无效"})


@app.post(IMAGE_CONFIG["gateway_path"], dependencies=[Depends(authorize)])
async def submit_portrait(body: PortraitRequest, request: Request):
    return request.app.state.portraits.submit(body)


@app.get(IMAGE_CONFIG["gateway_path"] + "/{job_id}", dependencies=[Depends(authorize)])
async def portrait_status(job_id: str, request: Request):
    return request.app.state.portraits.status(job_id)


@app.get(IMAGE_CONFIG["gateway_path"] + "/{job_id}/assets/{index}", dependencies=[Depends(authorize)])
async def portrait_asset(job_id: str, index: int, request: Request):
    if len(job_id) > 80 or not all(c.isalnum() or c == "-" for c in job_id) or not 0 <= index < 17:
        raise HTTPException(404, detail={"code": "NOT_FOUND"})
    return FileResponse(request.app.state.portraits.asset(job_id, index), media_type="image/png")


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    """返回不包含原始输入的错误。参数 request 为 HTTP 请求，exc 为校验错误，均不记录其敏感内容。"""
    return JSONResponse(status_code=422, content={"detail": {"code": "BAD_REQUEST", "message": "请求字段不符合构图协议"}})


@app.get(GATEWAY["health_path"])
async def health() -> dict:
    """返回进程存活状态；无参数，不暴露模型配置。"""
    return {"status": "ok"}


@app.get(GATEWAY["capabilities_v1_path"], dependencies=[Depends(authorize)])
async def capabilities() -> dict:
    """返回供应商配置状态；无参数，不返回任何密钥。"""
    return {"providers": [{"provider": p.value, "model": config_for(p).model, "configured": bool(config_for(p).key and config_for(p).model), "vision": True, "outputMode": "json_schema" if p in (Provider.GPT, Provider.GEMINI) else "json_object" if p == Provider.QWEN else "prompt_json"} for p in Provider]}


@app.post(GATEWAY["director_v1_path"], response_model=StepReply, dependencies=[Depends(authorize)])
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


@app.get(GATEWAY["capabilities_v2_path"], dependencies=[Depends(authorize)])
async def multi_capabilities() -> dict:
    return {"version": 2, "providers": [{"provider": p.value, "model": config_for(p).model,
        "configured": bool(config_for(p).key and config_for(p).model),
        "multiImage": bool(config_for(p).key and config_for(p).model and multi_image_limit(p)),
        "maxImages": multi_image_limit(p), "directorV2": bool(config_for(p).key and config_for(p).model)} for p in Provider]}


@app.post(GATEWAY["views_path"], response_model=ViewReply, dependencies=[Depends(authorize)])
async def select_views(body: ViewRequest, request: Request) -> ViewReply:
    limit = multi_image_limit(body.provider)
    if not limit or len(body.images) > limit:
        raise HTTPException(400, detail={"code": "UNSUPPORTED_MULTI_IMAGE", "message": "当前模型未声明所需多图能力"})
    ids = [image.frameId for image in body.images]
    if len(set(ids)) != len(ids):
        raise HTTPException(400, detail={"code": "BAD_REQUEST", "message": "扫描帧编号重复"})
    total = 0
    try:
        for image in body.images:
            decoded = base64.b64decode(image.imageBase64, validate=True)
            if not decoded.startswith(b"\xff\xd8\xff"):
                raise ValueError()
            total += len(decoded)
        if total > 3 * 1024 * 1024:
            raise ValueError()
    except (ValueError, binascii.Error):
        raise HTTPException(400, detail={"code": "BAD_REQUEST", "message": "扫描图片格式或总字节数不符合要求"}) from None
    started = time.monotonic()
    try:
        model, raw = await invoke(body, request.app.state.client)
    except ProviderFailure as exc:
        raise HTTPException(exc.status, detail={"code": exc.code, "message": exc.message}) from None
    try:
        selection = ViewSelection.model_validate_json(raw)
        candidates = [selection.primary, *selection.alternatives]
        selected_ids = [c.frameId for c in candidates]
        if selection.scanId != body.scanId or len(set(selected_ids)) != len(selected_ids) or any(i not in ids for i in selected_ids):
            raise ValueError()
        for candidate in candidates:
            if any(r.right <= r.left or r.bottom <= r.top for r in [*candidate.backgroundRegions, *candidate.subjectRegions]):
                raise ValueError()
    except ValueError:
        selection = None
    return ViewReply(model=model, raw=raw, selection=selection, elapsedMs=int((time.monotonic() - started) * 1000))


@app.post(GATEWAY["director_v2_path"], response_model=StepReply, dependencies=[Depends(authorize)])
async def photo_step(body: PhotoStepRequest, request: Request) -> StepReply:
    """摄影参考目录协议：保留 v1，拒绝未登记动作、表情与非法保留区域。"""
    try:
        image = base64.b64decode(body.imageBase64, validate=True)
        if not image.startswith(b"\xff\xd8\xff") or len(image) > 3 * 1024 * 1024:
            raise ValueError()
    except (ValueError, binascii.Error):
        raise HTTPException(400, detail={"code": "BAD_REQUEST", "message": "图片必须为有效 JPEG"}) from None
    started = time.monotonic()
    try:
        model, raw = await invoke(body, request.app.state.client)
    except ProviderFailure as exc:
        raise HTTPException(exc.status, detail={"code": exc.code, "message": exc.message}) from None
    # 原始错误输出仍交给有限 Agent 的下一轮；不伪造有效姿态或静默回退。
    try:
        decision = PhotoReferenceDecision.model_validate_json(raw)
        if decision.imageId != body.imageId or len(decision.intents) not in (1, 3):
            raise ValueError()
        if len({item.id for item in decision.intents}) != len(decision.intents):
            raise ValueError()
        for item in decision.intents:
            if any(region.right <= region.left or region.bottom <= region.top for region in item.backgroundKeepRegions):
                raise ValueError()
    except ValueError:
        pass
    return StepReply(model=model, raw=raw, decision=None, elapsedMs=int((time.monotonic() - started) * 1000))
