"""各供应商真实图像接口；不记录图片、密钥和完整提示词。"""
import json
import os
from dataclasses import dataclass
from urllib.parse import quote
import httpx
from .models import Decision, Provider, StepRequest


@dataclass(frozen=True)
class ProviderConfig:
    """服务端模型配置；密钥不会返回给客户端。"""
    key: str
    model: str
    base_url: str


class ProviderFailure(Exception):
    """可安全返回给客户端的供应商错误。"""
    def __init__(self, status: int, code: str, message: str):
        """保存错误状态。参数 status 为 HTTP 状态，code 为稳定错误码，message 为脱敏说明。"""
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def config_for(provider: Provider) -> ProviderConfig:
    """读取供应商配置。参数 provider 为供应商标识；返回密钥、模型与服务端固定上游。"""
    defaults = {
        Provider.QWEN: "https://dashscope.aliyuncs.com/compatible-mode/v1",
        Provider.SEED: "https://ark.cn-beijing.volces.com/api/v3",
        Provider.GPT: "https://api.openai.com/v1",
        Provider.GEMINI: "https://generativelanguage.googleapis.com/v1beta",
    }
    prefix = provider.value
    return ProviderConfig(os.getenv(f"{prefix}_API_KEY", ""), os.getenv(f"{prefix}_MODEL", ""), os.getenv(f"{prefix}_BASE_URL", defaults[provider]).rstrip("/"))


def request_payload(provider: Provider, model: str, request: StepRequest) -> tuple[str, dict]:
    """构造真实图像请求。参数 provider/model 指定供应商与模型，request 为统一图文输入；返回相对路径和请求体。"""
    schema = Decision.model_json_schema()
    image_uri = "data:image/jpeg;base64," + request.imageBase64
    if provider == Provider.GPT:
        return "/responses", {
            "model": model, "store": False,
            "input": [{"role": "user", "content": [{"type": "input_text", "text": request.prompt}, {"type": "input_image", "image_url": image_uri}]}],
            "text": {"format": {"type": "json_schema", "name": "director", "strict": True, "schema": schema}},
            "max_output_tokens": 4096,
        }
    if provider == Provider.GEMINI:
        return f"/models/{quote(model, safe='')}:generateContent", {
            "contents": [{"role": "user", "parts": [{"text": request.prompt}, {"inlineData": {"mimeType": "image/jpeg", "data": request.imageBase64}}]}],
            "generationConfig": {"responseMimeType": "application/json", "responseJsonSchema": schema, "maxOutputTokens": 4096},
        }
    # 千问、Seed 的 Chat Completions 图像通道；模型特定严格 Schema 不作统一假设。
    return "/chat/completions", {
        "model": model,
        "messages": [{"role": "user", "content": [{"type": "text", "text": request.prompt}, {"type": "image_url", "image_url": {"url": image_uri}}]}],
        "max_tokens": 4096,
        **({"enable_thinking": False, "response_format": {"type": "json_object"}} if provider == Provider.QWEN else {"thinking": {"type": "disabled"}}),
    }


def extract_text(provider: Provider, body: dict) -> str:
    """提取完整输出并识别拒绝/截断。参数 provider 指定响应格式，body 为供应商 JSON；返回文本或脱敏异常。"""
    if provider == Provider.GPT:
        pieces = [part for item in body.get("output", []) for part in item.get("content", [])]
        if any(p.get("type") == "refusal" for p in pieces):
            raise ProviderFailure(403, "REFUSED", "模型拒绝了本次请求")
        text = "".join(p.get("text", "") for p in pieces if p.get("type") == "output_text")
        if body.get("status") == "incomplete":
            raise ProviderFailure(502, "INVALID_OUTPUT", "模型输出被截断")
    elif provider == Provider.GEMINI:
        candidates = body.get("candidates", [])
        if body.get("promptFeedback", {}).get("blockReason") or any(c.get("finishReason") in {"SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII"} for c in candidates):
            raise ProviderFailure(403, "REFUSED", "模型拒绝了本次请求")
        if candidates and candidates[0].get("finishReason") == "MAX_TOKENS":
            raise ProviderFailure(502, "INVALID_OUTPUT", "模型输出被截断")
        text = "".join(p.get("text", "") for c in candidates[:1] for p in c.get("content", {}).get("parts", []) if not p.get("thought"))
    else:
        choices = body.get("choices", [])
        choice = choices[0] if choices else {}
        message = choice.get("message", {})
        if message.get("refusal") or choice.get("finish_reason") == "content_filter":
            raise ProviderFailure(403, "REFUSED", "模型拒绝了本次请求")
        if choice.get("finish_reason") == "length":
            raise ProviderFailure(502, "INVALID_OUTPUT", "模型输出被截断")
        text = message.get("content", "")
    if not isinstance(text, str) or not text.strip() or len(text) > 100_000:
        raise ProviderFailure(502, "INVALID_OUTPUT", "模型没有返回可用文本")
    return text


async def invoke(request: StepRequest, client: httpx.AsyncClient) -> tuple[str, str]:
    """执行一次上游请求，不自动重试。参数 request 为单步输入，client 为可注入 HTTP 客户端；返回模型编号和文本。"""
    config = config_for(request.provider)
    if not config.key or not config.model:
        raise ProviderFailure(503, "UNAVAILABLE", "该供应商尚未配置密钥和模型")
    path, payload = request_payload(request.provider, config.model, request)
    headers = {"x-goog-api-key": config.key} if request.provider == Provider.GEMINI else {"Authorization": f"Bearer {config.key}"}
    try:
        response = await client.post(config.base_url + path, headers=headers, json=payload)
    except httpx.TimeoutException as exc:
        raise ProviderFailure(504, "TIMEOUT", "上游模型响应超时") from exc
    except httpx.RequestError as exc:
        raise ProviderFailure(503, "UNAVAILABLE", "无法连接上游服务") from exc
    if response.status_code >= 400:
        status = response.status_code
        # 内容拒绝不可通过更换模型规避；参数错误也不能盲目重试。
        error_text = response.text[:4096].lower()
        if any(word in error_text for word in ("content_filter", "sensitive", "content_policy", "responsibleaipolicy")):
            raise ProviderFailure(403, "REFUSED", "请求被内容策略拒绝")
        if status == 429:
            raise ProviderFailure(429, "RATE_LIMITED", "上游模型限流")
        if status in (401, 403, 404):
            raise ProviderFailure(503, "UNAVAILABLE", "供应商鉴权或模型配置不可用")
        raise ProviderFailure(502 if status >= 500 else 400, "UNAVAILABLE" if status >= 500 else "BAD_REQUEST", "上游请求失败，请检查模型配置和能力")
    try:
        return config.model, extract_text(request.provider, response.json())
    except (ValueError, KeyError, TypeError) as exc:
        raise ProviderFailure(502, "INVALID_OUTPUT", "上游响应格式不正确") from exc
