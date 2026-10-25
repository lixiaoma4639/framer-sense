"""Seedream 独立候选任务：一次图层调用、持久化幂等状态、受保护的本地资源。"""
import asyncio
import base64
import binascii
import hashlib
import json
import logging
import os
import re
from pathlib import Path
import sqlite3
import time
from urllib.parse import urlparse

import httpx
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field
from .config import CONFIG


IMAGE_CONFIG = CONFIG["image_generation"]
logger = logging.getLogger("uvicorn.error")


def diagnostic_id(value) -> str:
    value = str(value or "")
    return value if len(value) <= 160 and not value.lower().startswith("sk-") and re.fullmatch(r"[\w.:-]+", value) else "unknown"


def response_diagnostics(response: httpx.Response) -> dict:
    try:
        body = response.json()
        error = body.get("error", {})
        error = error if isinstance(error, dict) else {}
    except (ValueError, AttributeError):
        body, error = {}, {}
    return {"http_status": response.status_code, "code": diagnostic_id(error.get("code")),
            "request_id": diagnostic_id(response.headers.get("x-request-id") or response.headers.get("x-tt-logid") or body.get("request_id"))}


class PortraitRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: str = Field(pattern=r"^[a-zA-Z0-9-]{8,80}$")
    model: str = Field(min_length=1, max_length=160)
    imageBase64: str = Field(min_length=4, max_length=42 * 1024 * 1024)
    prompt: str = Field(min_length=1, max_length=8000)


def image_payload(body: PortraitRequest) -> dict:
    """仅构造已公开的图层接口，不追加第二次生图／VLM 请求。"""
    allowed = {value["id"] for value in IMAGE_CONFIG["models"].values()}
    if body.model not in allowed:
        raise HTTPException(400, detail={"code": "MODEL"})
    try:
        image = base64.b64decode(body.imageBase64, validate=True)
        if not image.startswith(b"\xff\xd8\xff") or len(image) > 30 * 1024 * 1024:
            raise ValueError()
    except (ValueError, binascii.Error):
        raise HTTPException(400, detail={"code": "IMAGE"}) from None
    return {"model": body.model, "image": "data:image/jpeg;base64," + body.imageBase64,
            "prompt": body.prompt, "layer_decomposition": True, "size": "1.5K",
            "output_format": "png", "response_format": "url", "watermark": True}


def trusted_asset(url: str) -> bool:
    parsed = urlparse(url)
    try:
        return parsed.scheme == "https" and parsed.port in (None, 443) and not parsed.username and not parsed.password and any(
            parsed.hostname == suffix or (parsed.hostname or "").endswith("." + suffix)
            for suffix in ("volces.com", "volcengine.com", "volccdn.com", "byteimg.com", "byteplus.com"))
    except ValueError:
        return False


class PortraitJobs:
    """单进程工作队列。重启后的未决请求标记中断，避免不确定计费时自动重发。"""
    def __init__(self, client: httpx.AsyncClient, root: Path):
        self.client, self.root = client, root
        root.mkdir(parents=True, exist_ok=True)
        self.database = root / "jobs.sqlite3"
        self.tasks: set[asyncio.Task] = set()
        self.permits = asyncio.Semaphore(2)
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, status TEXT NOT NULL, result TEXT, created REAL NOT NULL)")
            db.execute("UPDATE jobs SET status='INTERRUPTED' WHERE status IN ('QUEUED','RUNNING')")
        self.expire()

    def connect(self):
        return sqlite3.connect(self.database, timeout=10)

    def expire(self):
        """默认保留七天；只删除终态任务的资源，不删除正在生成的候选。"""
        cutoff = time.time() - 7 * 24 * 60 * 60
        with self.connect() as db:
            rows = db.execute("SELECT id FROM jobs WHERE created < ? AND status NOT IN ('RUNNING','QUEUED')", (cutoff,)).fetchall()
            for (job_id,) in rows:
                for image in self.root.glob(job_id + "_*.png"):
                    image.unlink(missing_ok=True)
                db.execute("DELETE FROM jobs WHERE id=?", (job_id,))

    def submit(self, body: PortraitRequest) -> dict:
        payload = image_payload(body)
        fingerprint = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT fingerprint, status FROM jobs WHERE id=?", (body.requestId,)).fetchone()
            if previous:
                if previous[0] != fingerprint:
                    raise HTTPException(409, detail={"code": "REQUEST_CONFLICT"})
                return {"id": body.requestId, "status": previous[1]}
            if db.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('QUEUED','RUNNING')").fetchone()[0] >= 16:
                raise HTTPException(429, detail={"code": "BUSY"})
            db.execute("INSERT INTO jobs VALUES (?,?,'QUEUED',NULL,?)", (body.requestId, fingerprint, time.time()))
        task = asyncio.create_task(self.run(body.requestId, payload))
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return {"id": body.requestId, "status": "QUEUED"}

    def status(self, job_id: str) -> dict:
        with self.connect() as db:
            row = db.execute("SELECT status, result FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None:
            raise HTTPException(404, detail={"code": "NOT_FOUND"})
        return {"id": job_id, "status": row[0], "result": json.loads(row[1]) if row[1] else None}

    def update(self, job_id: str, status: str, result: dict | None = None):
        with self.connect() as db:
            db.execute("UPDATE jobs SET status=?, result=? WHERE id=?", (status, json.dumps(result) if result else None, job_id))

    async def run(self, job_id: str, payload: dict):
        stage, diagnostics = "BEGIN", {}
        started = time.monotonic()
        logger.info("VlmImageGen candidate=%s stage=BEGIN model=%s", job_id, diagnostic_id(payload.get("model")))
        try:
            async with self.permits:
                self.update(job_id, "RUNNING")
                key = os.getenv("SEEDREAM_API_KEY", "") or os.getenv("SEED_API_KEY", "")
                if not key:
                    logger.error("VlmImageGen candidate=%s stage=CREDENTIALS code=KEY", job_id)
                    self.update(job_id, "FAILED", {"failure": {"stage": "CREDENTIALS", "code": "KEY"}})
                    return
                # 不使用通用重试器；网络异常后保留终态，由用户主动创建新候选。
                stage = "GENERATE"
                response = await self.client.post(IMAGE_CONFIG["base_url"].rstrip("/") + IMAGE_CONFIG["generation_path"],
                    headers={"Authorization": "Bearer " + key}, json=payload,
                    timeout=httpx.Timeout(240, connect=15))
                diagnostics = response_diagnostics(response)
                logger.info("VlmImageGen candidate=%s stage=GENERATE_HTTP http=%s upstream=%s requestId=%s", job_id,
                            diagnostics["http_status"], diagnostics["code"], diagnostics["request_id"])
                response.raise_for_status()
                stage = "LAYERS"
                result = response.json()
                layers = result.get("data", [])
                logger.info("VlmImageGen candidate=%s stage=LAYERS count=%s", job_id, len(layers))
                if not 1 <= len(layers) <= 17 or sum(layer.get("z_index") == 0 for layer in layers) != 1:
                    raise ValueError()
                copied = []
                for index, layer in enumerate(layers):
                    stage = f"DOWNLOAD_{index}"
                    url = layer.get("url", "")
                    if not trusted_asset(url):
                        raise ValueError()
                    target = self.root / f"{job_id}_{index}.png"
                    pending = target.with_suffix(".pending")
                    async with self.client.stream("GET", url, timeout=60) as image:
                        image.raise_for_status()
                        total = 0
                        with pending.open("wb") as output:
                            async for chunk in image.aiter_bytes():
                                total += len(chunk)
                                if total > 32 * 1024 * 1024:
                                    raise ValueError()
                                output.write(chunk)
                    with pending.open("rb") as source:
                        if source.read(8) != b"\x89PNG\r\n\x1a\n":
                            raise ValueError()
                    pending.replace(target)
                    item = {name: layer[name] for name in ("z_index", "name", "description", "size", "bounding_box") if name in layer}
                    item["url"] = IMAGE_CONFIG["gateway_path"] + f"/{job_id}/assets/{index}"
                    copied.append(item)
                stage = "PERSIST"
                self.update(job_id, "READY", {"model": result.get("model", payload["model"]), "data": copied,
                    "usage": result.get("usage", {})})
                logger.info("VlmImageGen candidate=%s stage=READY layers=%s elapsedMs=%s", job_id, len(copied), int((time.monotonic() - started) * 1000))
        except asyncio.CancelledError:
            self.update(job_id, "INTERRUPTED")
            raise
        except Exception as error:
            # 不返回上游错误正文、提示词、原图、签名链接或密钥。
            if isinstance(error, httpx.HTTPStatusError):
                diagnostics = response_diagnostics(error.response)
            failure = {**diagnostics, "stage": stage, "type": diagnostic_id(type(error).__name__)}
            logger.error("VlmImageGen candidate=%s stage=%s type=%s http=%s upstream=%s requestId=%s elapsedMs=%s", job_id,
                         stage, failure["type"], failure.get("http_status", "unknown"), failure.get("code", "unknown"),
                         failure.get("request_id", "unknown"), int((time.monotonic() - started) * 1000))
            self.update(job_id, "FAILED", {"failure": failure})
        finally:
            for pending in self.root.glob(job_id + "_*.pending"):
                pending.unlink(missing_ok=True)

    def asset(self, job_id: str, index: int) -> Path:
        if self.status(job_id)["status"] != "READY":
            raise HTTPException(404, detail={"code": "NOT_FOUND"})
        path = self.root / f"{job_id}_{index}.png"
        if not path.is_file():
            raise HTTPException(404, detail={"code": "NOT_FOUND"})
        return path

    async def close(self):
        tasks = list(self.tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
