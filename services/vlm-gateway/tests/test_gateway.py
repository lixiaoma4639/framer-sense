"""模拟上游验证协议，绝不调用真实收费模型。"""
import asyncio
import base64
import json
import httpx
import pytest
from fastapi.testclient import TestClient
from gateway.app import app
from gateway.models import Decision, Provider, StepRequest
from gateway.providers import ProviderFailure, extract_text, invoke, request_payload


def request_for(provider: Provider) -> StepRequest:
    """创建测试请求。参数 provider 为测试供应商；图片仅为识别 JPEG 头的测试字节。"""
    return StepRequest(version=1, imageId="frame", provider=provider, requestId="test", imageBase64=base64.b64encode(b"\xff\xd8\xfftest").decode(), prompt="输出构图 JSON")


@pytest.mark.parametrize("provider", list(Provider))
def test_image_channel_and_schema(provider):
    """确认每家供应商使用视觉通道。参数 provider 由 pytest 提供。"""
    path, payload = request_payload(provider, "vision-model", request_for(provider))
    if provider == Provider.GPT:
        assert path == "/responses"
        assert payload["input"][0]["content"][1]["type"] == "input_image"
        assert payload["text"]["format"]["strict"] is True
    elif provider == Provider.GEMINI:
        assert payload["contents"][0]["parts"][1]["inlineData"]["mimeType"] == "image/jpeg"
        assert "responseJsonSchema" in payload["generationConfig"]
    else:
        assert payload["messages"][0]["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,")


@pytest.mark.parametrize("provider", list(Provider))
def test_mock_upstream(provider, monkeypatch):
    """用模拟 HTTP 返回验证真实适配流程。provider 指定供应商，monkeypatch 注入测试环境变量。"""
    monkeypatch.setenv(provider.value + "_API_KEY", "fake-secret")
    monkeypatch.setenv(provider.value + "_MODEL", "fake-model")
    raw = json.dumps({"version": 1, "imageId": "frame", "action": "CAPABILITIES", "plans": []})
    bodies = {
        Provider.QWEN: {"choices": [{"message": {"content": raw}}]},
        Provider.SEED: {"choices": [{"message": {"content": raw}}]},
        Provider.GPT: {"output": [{"content": [{"type": "output_text", "text": raw}]}]},
        Provider.GEMINI: {"candidates": [{"content": {"parts": [{"text": raw}]}}]},
    }

    def handler(request):
        """返回模拟上游。参数 request 用于断言供应商鉴权头。"""
        assert request.headers.get("x-goog-api-key") == "fake-secret" or request.headers.get("authorization") == "Bearer fake-secret"
        return httpx.Response(200, json=bodies[provider])

    async def run():
        """在独立连接池内执行一次模拟请求；无参数，退出关闭客户端。"""
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model, text = await invoke(request_for(provider), client)
            assert model == "fake-model"
            assert Decision.model_validate_json(text).action == "CAPABILITIES"
    asyncio.run(run())


def test_refusal_and_truncation():
    """内容拒绝与截断不能误作为正常方案；无参数。"""
    with pytest.raises(ProviderFailure) as refused:
        extract_text(Provider.GPT, {"output": [{"content": [{"type": "refusal"}]}]})
    assert refused.value.code == "REFUSED"
    with pytest.raises(ProviderFailure):
        extract_text(Provider.GEMINI, {"candidates": [{"finishReason": "MAX_TOKENS"}]})


def test_auth_validation_and_capabilities(monkeypatch):
    """验证网关令牌与脱敏参数错误。参数 monkeypatch 注入独立测试令牌。"""
    monkeypatch.setenv("VLM_GATEWAY_TOKEN", "gateway-test")
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/v1/capabilities").status_code == 401
        response = client.get("/v1/capabilities", headers={"Authorization": "Bearer gateway-test"})
        assert response.status_code == 200
        assert len(response.json()["providers"]) == 4
        assert "API_KEY" not in response.text
        invalid = client.post("/v1/director/step", headers={"Authorization": "Bearer gateway-test"}, json={"secret": "do-not-echo"})
        assert invalid.status_code == 422
        assert "do-not-echo" not in invalid.text


def test_rate_limit_mapping(monkeypatch):
    """验证上游限流返回稳定错误，且只请求一次。参数 monkeypatch 注入测试供应商配置。"""
    monkeypatch.setenv("QWEN_API_KEY", "test")
    monkeypatch.setenv("QWEN_MODEL", "test")
    calls = []

    def handler(request):
        """记录一次请求并返回限流。参数 request 为模拟请求。"""
        calls.append(request)
        return httpx.Response(429, json={"error": "limit"})

    async def run():
        """执行单次模型请求并检查限流类型；无参数。"""
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            with pytest.raises(ProviderFailure) as error:
                await invoke(request_for(Provider.QWEN), client)
            assert error.value.status == 429
    asyncio.run(run())
    assert len(calls) == 1
