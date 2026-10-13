"""集中配置与环境覆盖的集成检查；仅模拟上游。"""
import asyncio
import base64
import httpx

from gateway import providers
from gateway.models import Provider, StepRequest


def test_invocation_uses_shared_defaults_and_path(monkeypatch):
    """修改配置后，请求实际使用新地址、路径和模型，不回退到散落的常量。"""
    monkeypatch.setitem(providers.PROVIDERS, "qwen", {
        "base_url": "https://models.example.test/compatible/v1",
        "model": "configured-vision-model",
        "vision_path": "/configured-chat",
    })
    monkeypatch.setenv("QWEN_API_KEY", "fake-key")
    monkeypatch.delenv("QWEN_MODEL", raising=False)
    monkeypatch.delenv("QWEN_BASE_URL", raising=False)
    body = StepRequest(version=1, imageId="frame", provider=Provider.QWEN,
                       requestId="config-test", imageBase64=base64.b64encode(b"\xff\xd8\xfftest").decode(), prompt="JSON")

    def handler(request):
        assert str(request.url) == "https://models.example.test/compatible/v1/configured-chat"
        assert request.headers["authorization"] == "Bearer fake-key"
        return httpx.Response(200, json={"choices": [{"message": {"content": "{}"}}]})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            assert await providers.invoke(body, client) == ("configured-vision-model", "{}")

    asyncio.run(run())
    monkeypatch.setenv("QWEN_MODEL", "deployment-model")
    monkeypatch.setenv("QWEN_BASE_URL", "https://override.example.test/v1/")
    config = providers.config_for(Provider.QWEN)
    assert config.model == "deployment-model"
    assert config.base_url == "https://override.example.test/v1"
    # 显式空值保留关闭该供应商的语义，不能意外回退到默认模型。
    monkeypatch.setenv("QWEN_MODEL", "")
    assert providers.config_for(Provider.QWEN).model == ""


def test_gemini_path_template_escapes_model(monkeypatch):
    """路径模板来自配置，但模型不能插入额外路径或查询参数。"""
    monkeypatch.setitem(providers.PROVIDERS, "gemini", {"vision_path": "/custom/{model}:generateContent"})
    body = StepRequest(version=1, imageId="frame", provider=Provider.GEMINI,
                       requestId="config-test", imageBase64=base64.b64encode(b"\xff\xd8\xfftest").decode(), prompt="JSON")
    path, _ = providers.request_payload(Provider.GEMINI, "vision/model?secret", body)
    assert path == "/custom/vision%2Fmodel%3Fsecret:generateContent"
