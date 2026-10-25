"""双候选、幂等与原生图层协议；只使用模拟上游，不调用真实计费接口。"""
import asyncio
import base64
import json
import httpx
import pytest
from fastapi import HTTPException
from gateway.portraits import IMAGE_CONFIG, PortraitJobs, PortraitRequest, image_payload, trusted_asset


PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==")


def body(job_id="candidate-one", prompt="one", model=None):
    return PortraitRequest(requestId=job_id, prompt=prompt,
        model=model or IMAGE_CONFIG["models"]["flash"]["id"],
        imageBase64=base64.b64encode(b"\xff\xd8\xfffixture").decode())


def test_payload_has_one_documented_layer_request_and_supports_both_models():
    for model in IMAGE_CONFIG["models"].values():
        payload = image_payload(body(model=model["id"]))
        assert payload["model"] == model["id"]
        assert payload["layer_decomposition"] is True
        assert payload["output_format"] == "png"
        assert "sequential_image_generation" not in payload and "n" not in payload
    with pytest.raises(HTTPException) as error:
        image_payload(body(model="unconfigured-model"))
    assert error.value.status_code == 400


def test_two_jobs_complete_independently_and_duplicate_submit_is_not_billed_twice(tmp_path, monkeypatch):
    monkeypatch.setenv("SEEDREAM_API_KEY", "fixture-key")

    async def exercise():
        gates = {name: asyncio.Event() for name in ("one", "two")}
        posts = []

        async def handle(request):
            if request.method == "POST":
                payload = json.loads(request.content)
                posts.append(payload)
                await gates[payload["prompt"]].wait()
                return httpx.Response(200, json={"model": payload["model"], "data": [
                    {"url": "https://image.volces.com/base.png", "z_index": 0},
                    {"url": "https://image.volces.com/person.png", "z_index": 1, "name": "reference_person",
                     "bounding_box": {"normalized": [200, 300, 800, 1000]}}], "usage": {"generated_images": 2}})
            assert "authorization" not in request.headers
            return httpx.Response(200, content=PNG)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            jobs = PortraitJobs(client, tmp_path)
            try:
                jobs.submit(body())
                jobs.submit(body())
                jobs.submit(body("candidate-two", "two"))
                for _ in range(100):
                    if len(posts) == 2:
                        break
                    await asyncio.sleep(.001)
                assert len(posts) == 2
                gates["two"].set()
                for _ in range(100):
                    if jobs.status("candidate-two")["status"] == "READY":
                        break
                    await asyncio.sleep(.001)
                result = jobs.status("candidate-two")
                assert result["status"] == "READY"
                assert jobs.status("candidate-one")["status"] == "RUNNING"
                assert result["result"]["data"][1]["url"].endswith("/candidate-two/assets/1")
                assert jobs.asset("candidate-two", 1).read_bytes() == PNG
                assert jobs.submit(body("candidate-two", "two"))["status"] == "READY"
                assert len(posts) == 2
                with pytest.raises(HTTPException) as error:
                    jobs.submit(body(prompt="different"))
                assert error.value.status_code == 409
            finally:
                gates["one"].set()
                await jobs.close()

    asyncio.run(exercise())


def test_restart_marks_unknown_tasks_interrupted_instead_of_resubmitting(tmp_path):
    async def exercise():
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(500))) as client:
            jobs = PortraitJobs(client, tmp_path)
            with jobs.connect() as db:
                db.execute("INSERT INTO jobs VALUES ('old-job','fingerprint','RUNNING',NULL,?)", (9999999999,))
            restored = PortraitJobs(client, tmp_path)
            assert restored.status("old-job")["status"] == "INTERRUPTED"
            assert not restored.tasks
    asyncio.run(exercise())


def test_signed_asset_sources_are_restricted_to_provider_https():
    assert trusted_asset("https://ark.tos-cn-beijing.volces.com/image.png")
    for url in ("http://127.0.0.1/image", "https://volces.com.evil.test/image", "https://user:key@image.volces.com/image"):
        assert not trusted_asset(url)


def test_failed_job_reports_only_safe_diagnostics_and_does_not_retry(tmp_path, monkeypatch, caplog):
    monkeypatch.setenv("SEEDREAM_API_KEY", "sk-private-key")

    async def exercise():
        calls = []

        async def handle(request):
            calls.append(request)
            return httpx.Response(400, headers={"x-request-id": "req-safe-123"}, json={"error": {
                "code": "InvalidParameter", "message": "PRIVATE_PROMPT sk-private-key https://private.invalid?signature=secret"}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            jobs = PortraitJobs(client, tmp_path)
            try:
                jobs.submit(body(prompt="PRIVATE_PROMPT"))
                for _ in range(100):
                    if jobs.status("candidate-one")["status"] == "FAILED":
                        break
                    await asyncio.sleep(.001)
                result = jobs.status("candidate-one")
                assert result["status"] == "FAILED"
                assert result["result"]["failure"]["http_status"] == 400
                assert result["result"]["failure"]["code"] == "InvalidParameter"
                assert result["result"]["failure"]["request_id"] == "req-safe-123"
                assert len(calls) == 1
                diagnostic = json.dumps(result) + " ".join(record.getMessage() for record in caplog.records if "VlmImageGen" in record.getMessage())
                assert "PRIVATE_PROMPT" not in diagnostic and "sk-private-key" not in diagnostic
                assert "private.invalid" not in diagnostic and "signature=secret" not in diagnostic
            finally:
                await jobs.close()

    asyncio.run(exercise())
