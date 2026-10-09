"""摄影目录协议；全部使用模拟上游，不访问真实收费模型。"""
import asyncio
import base64
import json

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from gateway.app import app
from gateway.models import PhotoReferenceDecision, PhotoStepRequest, Provider
from gateway.providers import request_payload


def photo_request(provider):
    return PhotoStepRequest(version=2, imageId="scene", provider=provider, requestId="photo",
        imageBase64=base64.b64encode(b"\xff\xd8\xfffixture").decode(), prompt="选择已登记摄影参考初稿")


def photo_decision():
    return {"version": 2, "imageId": "scene", "action": "FINAL", "intents": [
        {"id": f"p{i}", "title": "环境人像", "shot": "ENVIRONMENT", "zone": zone,
         "actionId": "draft.rain_natural_stand", "expression": "SMILE", "expressionIntensity": 1,
         "yaw": 0, "reason": "保留环境", "backgroundKeepRegions": [], "uncertainties": ["地面未知"]}
        for i, zone in enumerate(["LEFT", "CENTER", "RIGHT"], 1)]}


@pytest.mark.parametrize("provider", list(Provider))
def test_photo_schema_uses_real_visual_channels(provider):
    _, payload = request_payload(provider, "configured-model", photo_request(provider))
    if provider == Provider.GPT:
        assert payload["input"][0]["content"][1]["type"] == "input_image"
        schema = payload["text"]["format"]["schema"]
        assert "PhotoReferenceIntent" in schema["$defs"]
    elif provider == Provider.GEMINI:
        assert payload["contents"][0]["parts"][1]["inlineData"]["mimeType"] == "image/jpeg"
        assert "PhotoReferenceIntent" in payload["generationConfig"]["responseJsonSchema"]["$defs"]
    else:
        assert payload["messages"][0]["content"][1]["image_url"]["url"].startswith("data:image/jpeg;base64,")


@pytest.mark.parametrize("field,value", [("actionId", "draft.rain_hand_hip"), ("actionId", "draft.rain_walk_pause"),
    ("expression", "SURPRISED"), ("expressionIntensity", 2), ("yaw", 90)])
def test_unaccepted_actions_and_parameters_are_rejected(field, value):
    decision = photo_decision()
    decision["intents"][0][field] = value
    with pytest.raises(ValidationError):
        PhotoReferenceDecision.model_validate(decision)


def test_capability_and_v2_route_preserve_v1(monkeypatch):
    monkeypatch.setenv("VLM_GATEWAY_TOKEN", "test-token")
    monkeypatch.setenv("QWEN_API_KEY", "fake-key")
    monkeypatch.setenv("QWEN_MODEL", "fake-vision-model")
    calls = []

    async def mock_invoke(body, client):
        calls.append(body.version)
        return "fake-vision-model", json.dumps(photo_decision())

    monkeypatch.setattr("gateway.app.invoke", mock_invoke)
    with TestClient(app) as client:
        headers = {"Authorization": "Bearer test-token"}
        capability = client.get("/v2/capabilities", headers=headers).json()
        qwen = next(p for p in capability["providers"] if p["provider"] == "QWEN")
        assert qwen["directorV2"] and qwen["model"] == "fake-vision-model"
        assert client.get("/v1/capabilities", headers=headers).status_code == 200
        body = photo_request(Provider.QWEN).model_dump(mode="json")
        response = client.post("/v2/director/step", headers=headers, json=body)
        assert response.status_code == 200
        assert PhotoReferenceDecision.model_validate_json(response.json()["raw"]).imageId == "scene"
        assert calls == [2]
        body["imageBase64"] = base64.b64encode(b"not jpeg").decode()
        assert client.post("/v2/director/step", headers=headers, json=body).status_code == 400
        assert calls == [2]
        assert client.post("/v2/director/step", json=photo_request(Provider.QWEN).model_dump(mode="json")).status_code == 401
