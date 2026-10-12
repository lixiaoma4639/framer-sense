"""新版摄影协议回归：模拟上游，禁止真实模型调用。"""
import base64
import asyncio
import json
import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from gateway.app import app
from gateway.models import PhotographyDecision, PhotographyStepRequest, Provider
from gateway.providers import request_payload, invoke


def decision():
    return {"version": 3, "imageId": "frame", "sceneDescription": "门框形成引导线，暖色背景需保留层次。", "plans": [
        {"id": f"p{i}", "title": "环境人像", "shot": "FULL",
         "crop": {"left": 0, "top": 0, "right": 1, "bottom": 1}, "cropReason": "保留纵深",
         "head": {"x": x, "y": .2}, "foot": {"x": x, "y": .8},
         "personRegion": {"left": x-.1, "top": .2, "right": x+.1, "bottom": .8},
         "actionId": "draft.rain_natural_stand", "expression": "SMILE", "expressionIntensity": .8,
         "yaw": 0, "reason": "门框引导视线，人物避开高亮。", "guidance": "人物与门框边缘错开。",
         "backgroundKeepRegions": [], "references": [], "uncertainties": ["地面未知"]}
        for i, x in enumerate([.25, .5, .75], 1)]}


def request(provider=Provider.QWEN):
    return PhotographyStepRequest(version=3, imageId="frame", requestId="photography", provider=provider,
        imageBase64=base64.b64encode(b"\xff\xd8\xfffixture").decode(), prompt="环境美感优先，输出人物头脚位置")


@pytest.mark.parametrize("provider", list(Provider))
def test_all_four_adapters_send_image_and_photography_schema(provider):
    _, payload=request_payload(provider, "fake-vision", request(provider))
    if provider == Provider.GPT:
        assert payload["input"][0]["content"][1]["type"] == "input_image"
        assert "PhotographyIntent" in payload["text"]["format"]["schema"]["$defs"]
    elif provider == Provider.GEMINI:
        assert payload["contents"][0]["parts"][1]["inlineData"]["mimeType"] == "image/jpeg"
        assert "PhotographyIntent" in payload["generationConfig"]["responseJsonSchema"]["$defs"]
    else:
        assert payload["messages"][0]["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,")


@pytest.mark.parametrize("change", ["action", "head", "crop", "background", "reference", "duplicate"])
def test_invalid_geometry_assets_and_ids_are_rejected(change):
    d=decision(); p=d["plans"][0]
    if change == "action": p["actionId"]="draft.rain_hand_hip"
    elif change == "head": p["head"]["y"]=.9
    elif change == "crop": p["crop"]={"left": 0, "top": 0, "right": .7, "bottom": .7}; p["cropReason"]=""
    elif change == "background":
        p["crop"]={"left": .1, "top": .1, "right": .9, "bottom": .9}
        p["backgroundKeepRegions"]=[{"left": .8, "top": .2, "right": 1, "bottom": .8}]
    elif change == "reference":
        p["references"]=[{"objectName": "门", "region": p["crop"], "heightMinMeters": 2, "heightMaxMeters": 1,
            "depthRelation": "SAME", "evidence": "可见门框"}]
    elif change == "duplicate": d["plans"][1]["id"]="p1"
    with pytest.raises(ValidationError): PhotographyDecision.model_validate(d)


def test_capability_is_explicit_and_bound_to_actual_model_and_old_routes_remain(monkeypatch):
    monkeypatch.setenv("VLM_GATEWAY_TOKEN", "test-token")
    monkeypatch.setenv("QWEN_API_KEY", "fake-key")
    monkeypatch.setenv("QWEN_MODEL", "fake-vision")
    monkeypatch.delenv("QWEN_DIRECTOR_V3_MODEL", raising=False)
    calls=[]
    async def fake(body, client):
        calls.append(body.version)
        return "fake-vision", json.dumps(decision(), ensure_ascii=False)
    monkeypatch.setattr("gateway.app.invoke", fake)
    with TestClient(app) as client:
        headers={"Authorization": "Bearer test-token"}
        def enabled():
            return next(p for p in client.get("/v2/capabilities?photography=3",headers=headers).json()["providers"] if p["provider"] == "QWEN")["directorV3"]
        assert not enabled()
        assert all("directorV3" not in p for p in client.get("/v2/capabilities",headers=headers).json()["providers"])
        body=request().model_dump(mode="json")
        assert client.post("/v3/director/step",headers=headers,json=body).status_code == 400
        assert not calls
        monkeypatch.setenv("QWEN_DIRECTOR_V3_MODEL", "fake-vision")
        assert enabled()
        result=client.post("/v3/director/step",headers=headers,json=body)
        assert result.status_code == 200
        assert PhotographyDecision.model_validate_json(result.json()["raw"]).imageId == "frame"
        assert calls == [3]
        assert client.get("/v1/capabilities",headers=headers).status_code == 200
        assert client.post("/v3/director/step",json=body).status_code == 401
        body["imageBase64"]=base64.b64encode(b"wrong").decode()
        assert client.post("/v3/director/step",headers=headers,json=body).status_code == 400
        assert calls == [3]
        monkeypatch.setenv("QWEN_MODEL", "other-model")
        assert not enabled()


@pytest.mark.parametrize("provider", list(Provider))
@pytest.mark.parametrize("outcome", ["refused", "limited", "cancelled"])
def test_v3_upstream_refusal_rate_limit_and_cancel_are_not_retried(monkeypatch, provider, outcome):
    from gateway.providers import ProviderFailure
    monkeypatch.setenv(f"{provider.value}_API_KEY", "fake-key")
    monkeypatch.setenv(f"{provider.value}_MODEL", "fake-vision")
    calls=[]
    def respond(sent):
        calls.append(sent)
        if outcome == "cancelled": raise asyncio.CancelledError()
        return httpx.Response(403 if outcome == "refused" else 429,
            json={"error": "content_filter" if outcome == "refused" else "rate_limit"})
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            return await invoke(request(provider), client)
    if outcome == "cancelled":
        with pytest.raises(asyncio.CancelledError): asyncio.run(run())
    else:
        with pytest.raises(ProviderFailure) as error: asyncio.run(run())
        assert error.value.status == (403 if outcome == "refused" else 429)
    assert len(calls) == 1


def test_v3_bad_source_output_is_preserved_for_client_correction_and_oversize_is_blocked(monkeypatch):
    monkeypatch.setenv("VLM_GATEWAY_TOKEN", "test-token")
    monkeypatch.setenv("QWEN_API_KEY", "fake-key")
    monkeypatch.setenv("QWEN_MODEL", "fake-vision")
    monkeypatch.setenv("QWEN_DIRECTOR_V3_MODEL", "fake-vision")
    calls=[]; wrong=decision(); wrong["imageId"]="foreign-frame"
    async def fake(body, client):
        calls.append(body.version)
        return "fake-vision", json.dumps(wrong)
    monkeypatch.setattr("gateway.app.invoke", fake)
    with TestClient(app) as client:
        headers={"Authorization": "Bearer test-token"}
        body=request().model_dump(mode="json")
        result=client.post("/v3/director/step",headers=headers,json=body)
        assert result.status_code == 200
        assert json.loads(result.json()["raw"])["imageId"] == "foreign-frame"
        body["imageBase64"]=base64.b64encode(b"\xff\xd8\xff"+b"x"*(3*1024*1024)).decode()
        assert client.post("/v3/director/step",headers=headers,json=body).status_code in (400,422)
        assert calls == [3]
