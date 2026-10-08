"""P4 多图接口与实际视觉通道验证，仅使用模拟上游。"""
import asyncio
import base64
import importlib
import json

import httpx
import pytest
from fastapi.testclient import TestClient
from gateway.models import Provider, ViewRequest
from gateway.providers import ProviderFailure, invoke, request_payload

module = importlib.import_module("gateway.app")
JPEG = base64.b64encode(b"\xff\xd8\xfffixture").decode()
AUTH = {"Authorization": "Bearer gateway-test"}


def body(provider=Provider.QWEN):
    return {"version": 2, "provider": provider.value, "requestId": "request", "scanId": "scan",
            "prompt": "选景", "images": [{"frameId": f"frame-{i}", "imageBase64": JPEG,
              "preferred": i == 0, "width": 640, "height": 480} for i in range(3)]}


def selection(frame="frame-0"):
    return {"version": 2, "scanId": "scan", "primary": {"frameId": frame, "subject": "山体",
        "reason": "视野开阔", "backgroundRegions": [{"left": 0, "top": 0, "right": 1, "bottom": .5}],
        "subjectRegions": [], "uncertainties": ["地面待确认"]}, "alternatives": []}


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv("VLM_GATEWAY_TOKEN", "gateway-test")
    for provider in Provider:
        monkeypatch.setenv(provider.value + "_API_KEY", "mock")
        monkeypatch.setenv(provider.value + "_MODEL", "mock-model")
        monkeypatch.setenv(provider.value + "_MULTI_IMAGE_MODEL", "mock-model")
        monkeypatch.setenv(provider.value + "_MULTI_IMAGE_MAX_IMAGES", "8")
    return monkeypatch


@pytest.mark.parametrize("provider", list(Provider))
def test_every_image_has_visual_channel_and_preceding_id(provider):
    _, payload = request_payload(provider, "mock-model", ViewRequest.model_validate(body(provider)))
    if provider == Provider.GEMINI:
        parts = payload["contents"][0]["parts"]
        for i in range(3):
            assert parts[1 + i * 2] == {"text": f"frameId=frame-{i}"}
            assert parts[2 + i * 2]["inlineData"]["data"] == JPEG
    else:
        parts = payload["input"][0]["content"] if provider == Provider.GPT else payload["messages"][0]["content"]
        for i in range(3):
            assert parts[1 + i * 2]["text"] == f"frameId=frame-{i}"
            assert parts[2 + i * 2]["type"] == ("input_image" if provider == Provider.GPT else "image_url")
        if provider == Provider.GPT:
            assert payload["store"] is False


@pytest.mark.parametrize("provider", list(Provider))
def test_multiview_invoke_uses_mock_upstream(provider, configured):
    raw = json.dumps(selection())
    responses = {
        Provider.GPT: {"output": [{"content": [{"type": "output_text", "text": raw}]}]},
        Provider.GEMINI: {"candidates": [{"content": {"parts": [{"text": raw}]}}]},
        Provider.QWEN: {"choices": [{"message": {"content": raw}}]},
        Provider.SEED: {"choices": [{"message": {"content": raw}}]},
    }
    def handle(request):
        payload = json.loads(request.content)
        assert "frameId=frame-2" in request.content.decode()
        assert "mock-model" in request.url.path or payload.get("model") == "mock-model"
        return httpx.Response(200, json=responses[provider])
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            model, result = await invoke(ViewRequest.model_validate(body(provider)), client)
            assert model == "mock-model" and json.loads(result)["primary"]["frameId"] == "frame-0"
    asyncio.run(run())


def test_capability_requires_matching_actual_model(configured):
    with TestClient(module.app) as client:
        assert client.get("/v2/capabilities").status_code == 401
        data = client.get("/v2/capabilities", headers=AUTH).json()
        assert all(p["multiImage"] and p["maxImages"] == 8 for p in data["providers"])
        configured.setenv("QWEN_MODEL", "new-unverified-model")
        qwen = client.get("/v2/capabilities", headers=AUTH).json()["providers"][0]
        assert qwen["model"] == "new-unverified-model" and qwen["multiImage"] is False
        assert client.post("/v2/views/select", headers=AUTH, json=body()).status_code == 400


@pytest.mark.parametrize("mutation", ["scan", "frame", "duplicate", "region", "nan"])
def test_invalid_upstream_references_are_not_guessed(configured, mutation):
    result = selection()
    if mutation == "scan": result["scanId"] = "other"
    if mutation == "frame": result["primary"]["frameId"] = "not-submitted"
    if mutation == "duplicate": result["alternatives"] = [result["primary"].copy()]
    if mutation == "region": result["primary"]["backgroundRegions"][0]["right"] = 0
    if mutation == "nan": result["primary"]["backgroundRegions"][0]["left"] = float("nan")
    async def fake(*args): return "mock-model", json.dumps(result)
    configured.setattr(module, "invoke", fake)
    with TestClient(module.app) as client:
        response = client.post("/v2/views/select", headers=AUTH, json=body())
        assert response.status_code == 200 and response.json()["selection"] is None


def test_primary_and_alternatives_keep_coordinates_bound_to_frame(configured):
    result = selection(); result["alternatives"] = [selection("frame-2")["primary"]]
    async def fake(*args): return "mock-model", json.dumps(result)
    configured.setattr(module, "invoke", fake)
    with TestClient(module.app) as client:
        response = client.post("/v2/views/select", headers=AUTH, json=body())
        assert response.json()["selection"] == result


@pytest.mark.parametrize("case", ["count", "bytes", "duplicate", "jpeg", "dimensions"])
def test_input_limits_before_upstream(configured, case):
    async def never(*args): pytest.fail("非法输入不得调用上游")
    configured.setattr(module, "invoke", never)
    request = body()
    if case == "count": request["images"] *= 3
    if case == "duplicate": request["images"][1]["frameId"] = "frame-0"
    if case == "jpeg": request["images"][0]["imageBase64"] = base64.b64encode(b"invalid").decode()
    if case == "dimensions": request["images"][0]["width"] = 1000
    if case == "bytes":
        encoded = base64.b64encode(b"\xff\xd8\xff" + b"x" * 1100000).decode()
        for image in request["images"]: image["imageBase64"] = encoded
    with TestClient(module.app) as client:
        assert client.post("/v2/views/select", headers=AUTH, json=request).status_code in (400, 422)


@pytest.mark.parametrize("status,code", [(403, "REFUSED"), (429, "RATE_LIMITED"), (400, "BAD_REQUEST")])
def test_selection_errors_preserve_codes_without_server_retry(configured, status, code):
    calls = []
    async def fake(*args):
        calls.append(1)
        raise ProviderFailure(status, code, "测试错误")
    configured.setattr(module, "invoke", fake)
    with TestClient(module.app) as client:
        response = client.post("/v2/views/select", headers=AUTH, json=body())
        assert response.status_code == status and response.json()["detail"]["code"] == code
    assert len(calls) == 1


def test_cancellation_propagates_without_retry(configured):
    calls = []
    async def run():
        async def handle(request):
            calls.append(1)
            raise asyncio.CancelledError()
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            with pytest.raises(asyncio.CancelledError):
                await invoke(ViewRequest.model_validate(body()), client)
    asyncio.run(run()); assert len(calls) == 1
