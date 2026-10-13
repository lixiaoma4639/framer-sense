package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.PhotoSessionBudget
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 使用本地拦截器模拟官方响应，不请求网络或真实计费模型。 */
class QwenOfficialProviderTest {
    private val settings = ModelSettings(mode = ModelMode.CLOUD, qwenApiKey = "sk-fake-key")
    private fun response(raw: String, finish: String = "stop") = buildJsonObject {
        put("model", "qwen3-vl-plus-actual")
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", finish)
            put("message", buildJsonObject { put("content", raw) })
        }) })
    }.toString()
    private fun client(handler: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun error(code: String, block: () -> Unit) {
        try { block(); fail("应拒绝当前输入") }
        catch (failure: ModelFailure) { assertEquals(code, failure.code); assertEquals("", failure.message) }
    }

    @Test fun endpointAllowsOfficialRegionsAndWorkspaceButCannotSendKeysElsewhere() {
        listOf(settings.qwenBaseUrl, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/",
            "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1").forEach { base ->
            assertTrue(QwenOfficialProtocol.validate(settings.copy(qwenBaseUrl = base)).toString().endsWith(CloudModelConfig.qwenVisionPath))
        }
        listOf("http://dashscope.aliyuncs.com/compatible-mode/v1", "https://example.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com.evil.invalid/compatible-mode/v1", "https://user@dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1?key=secret", "${settings.qwenBaseUrl}${CloudModelConfig.qwenVisionPath}").forEach { base ->
            error("QWEN_URL") { QwenOfficialProtocol.validate(settings.copy(qwenBaseUrl = base)) }
        }
        error("QWEN_KEY") { QwenOfficialProtocol.validate(settings.copy(qwenApiKey = "")) }
        error("QWEN_KEY") { QwenOfficialProtocol.validate(settings.copy(qwenApiKey = "sk-bad\nvalue")) }
        error("QWEN_MODEL") { QwenOfficialProtocol.validate(settings.copy(qwenModel = "")) }
    }

    @Test fun serializingConfigurationNeverIncludesOfficialKey() {
        val saved = VlmJson.encodeToString(settings)
        assertFalse(saved.contains(settings.qwenApiKey))
        assertFalse(saved.contains("qwenApiKey"))
        assertEquals("", VlmJson.decodeFromString<ModelSettings>(saved).qwenApiKey)
        assertTrue(CloudProviders.vision(settings, ProviderId.QWEN) is QwenOfficialProvider)
        assertTrue(CloudProviders.vision(settings.copy(qwenAccess = QwenAccess.GATEWAY), ProviderId.QWEN) is GatewayProvider)
        assertTrue(CloudProviders.vision(settings.copy(seedAccess = SeedAccess.GATEWAY), ProviderId.SEED) is GatewayProvider)
    }

    @Test fun photosUseRealImageChannelAndKeepEveryFrameIdInOrder() {
        val images = listOf(ViewImage("frame-7", "jpeg-one", true, 640, 480), ViewImage("frame-2", "jpeg-two", false, 640, 480))
        val payload = QwenOfficialProtocol.payload("qwen3-vl-plus", "选择背景", images, 4096)
        assertEquals(false, payload["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals("json_object", payload["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val content = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertTrue(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("JSON"))
        assertEquals(listOf("frameId=frame-7", "frameId=frame-2"), listOf(1, 3).map { content[it].jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals(listOf("data:image/jpeg;base64,jpeg-one", "data:image/jpeg;base64,jpeg-two"), listOf(2, 4).map {
            assertEquals("image_url", content[it].jsonObject["type"]!!.jsonPrimitive.content)
            content[it].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        })
    }

    @Test fun singlePhotoUsesOfficialEndpointSharedBudgetAndPhotoProtocol() = runBlocking {
        val file = File.createTempFile("qwen-input", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            var requests = 0
            val http = client { request ->
                requests++
                assertEquals(settings.qwenBaseUrl + CloudModelConfig.qwenVisionPath, request.url.toString())
                assertEquals("Bearer sk-fake-key", request.header("Authorization"))
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                assertFalse(buffer.readUtf8().contains("sk-fake-key"))
                response("{\"version\":2,\"imageId\":\"scene\"}")
            }
            val budget = PhotoSessionBudget()
            val input = DirectorInput(SceneSnapshot("scene", 64, 48, 1f, file.path, file.path), CameraCapabilities(), "", sessionBudget = budget, photoReferences = true)
            val provider = QwenOfficialProvider(settings, http) { "encoded-jpeg" }
            val result = provider.step(ModelCall(input, "JSON 构图", "r"))
            assertTrue(result.photoProtocol); assertEquals("qwen3-vl-plus-actual", result.model)
            assertEquals(1, budget.calls); assertEquals(4L, budget.uploadedBytes)
            repeat(5) { budget.consume(0) }
            try { provider.step(ModelCall(input, "JSON", "r2")); fail() }
            catch (failure: ModelFailure) { assertEquals("SESSION_BUDGET_EXHAUSTED", failure.code) }
            assertEquals(1, requests)
        } finally { file.delete() }
    }

    @Test fun malformedSelectionReturnsNoWinnerAndCheckDoesNotUploadAnyPhoto() = runBlocking {
        val provider = QwenOfficialProvider(settings, client { response("not-json") }) { "unused" }
        val reply = provider.select(ViewSelectionRequest(ProviderId.QWEN, "r", "scan", listOf(ViewImage("f", "jpeg", false, 10, 10)), "JSON"))
        assertNull(reply.selection)
        val checker = QwenOfficialProvider(settings, client { request ->
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val content = VlmJson.parseToJsonElement(buffer.readUtf8()).jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            assertTrue(content.all { it.jsonObject["type"]!!.jsonPrimitive.content == "text" })
            response("{\"ok\":true}")
        }) { "unused" }
        assertEquals("qwen3-vl-plus-actual", checker.checkConfiguration())
    }

    @Test fun errorsAreRedactedAndRefusalsCannotTriggerProviderSwitching() {
        error("QWEN_AUTH") { QwenOfficialProtocol.response(401, "sk-sensitive", "m") }
        error("QWEN_MODEL") { QwenOfficialProtocol.response(404, "private response", "m") }
        error("QWEN_OUTPUT") { QwenOfficialProtocol.response(200, response("partial", "length"), "m") }
        error("QWEN_OUTPUT") { QwenOfficialProtocol.response(200, "malformed", "m") }
        try { QwenOfficialProtocol.response(400, "{\"error\":{\"code\":\"DataInspectionFailed\",\"message\":\"secret\"}}", "m"); fail() }
        catch (failure: ModelFailure) { assertEquals("QWEN_REFUSED", failure.code); assertFalse(failure.retryable); assertEquals("", failure.message) }
        try { QwenOfficialProtocol.response(429, "secret", "m"); fail() }
        catch (failure: ModelFailure) { assertEquals("QWEN_LIMIT", failure.code); assertTrue(failure.retryable) }
    }

    @Test fun forbiddenErrorsDistinguishAccountQuotaActivationAndModelPermissionsInBothFormats() {
        val cases = mapOf(
            "Arrearage" to "QWEN_ACCOUNT",
            "AllocationQuota.FreeTierOnly" to "QWEN_FREE_QUOTA",
            "AccessDenied.Unpurchased" to "QWEN_NOT_ACTIVATED",
            "Model.AccessDenied" to "QWEN_MODEL_PERMISSION",
            "Workspace.AccessDenied" to "QWEN_WORKSPACE",
            "AccessDenied" to "QWEN_PERMISSION"
        )
        cases.forEach { (officialCode, expected) ->
            listOf("{\"error\":{\"code\":\"$officialCode\",\"message\":\"sk-secret-key\"}}",
                "{\"code\":\"$officialCode\",\"message\":\"private prompt\"}",
                "{\"error\":{\"type\":\"$officialCode\",\"message\":\"private prompt\"}}").forEach { body ->
                try { QwenOfficialProtocol.response(403, body, "m"); fail() }
                catch (failure: ModelFailure) {
                    assertEquals(expected, failure.code)
                    assertEquals(403, failure.upstreamHttpStatus ?: -1)
                    assertEquals(officialCode, failure.upstreamErrorCode)
                    assertEquals("", failure.message)
                    assertFalse(failure.retryable)
                }
            }
        }
    }

    @Test fun unknownErrorCodesCannotExposeSecretsThroughDiagnosticFields() {
        listOf("{\"error\":{\"code\":\"sk-secret-key\",\"message\":\"private prompt\"}}", "not-json").forEach { body ->
            try { QwenOfficialProtocol.response(403, body, "m"); fail() }
            catch (failure: ModelFailure) {
                assertEquals("QWEN_PERMISSION", failure.code)
                assertEquals(403, failure.upstreamHttpStatus ?: -1)
                assertNull(failure.upstreamErrorCode)
                assertEquals("", failure.message)
            }
        }
    }

    @Test fun cancellationCancelsThePendingOfficialHttpCall() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val http = client { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); response("{\"ok\":true}") }
        try {
            val work = launch { QwenOfficialProvider(settings, http) { "unused" }.checkConfiguration() }
            withTimeout(5000) { entered.await() }
            work.cancelAndJoin()
            assertTrue(http.dispatcher.runningCalls().single().isCanceled())
        } finally { release.countDown(); http.dispatcher.executorService.shutdown() }
    }
}
