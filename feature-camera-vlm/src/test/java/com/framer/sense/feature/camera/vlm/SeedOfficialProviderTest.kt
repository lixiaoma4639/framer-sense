package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
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
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅使用拦截器模拟官方 HTTP，不访问真实收费服务。 */
class SeedOfficialProviderTest {
    private val settings = ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.SEED,
        seedApiKey = "fake-seed-key", qwenApiKey = "sk-fake-qwen-key")
    private fun response(raw: String, finish: String = "stop") = buildJsonObject {
        put("model", "seed-actual-model")
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", finish)
            put("message", buildJsonObject { put("content", raw) })
        }) })
    }.toString()
    private fun client(handler: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun payload(request: Request): JsonObject {
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        return VlmJson.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    @Test fun factoriesAndRoutingKeepQwenAndOfflineBehaviorWhileAddingSeed() {
        assertTrue(CloudProviders.vision(settings, ProviderId.SEED) is SeedOfficialProvider)
        assertTrue(CloudProviders.views(settings, ProviderId.SEED) is SeedOfficialProvider)
        assertTrue(CloudProviders.vision(settings.copy(seedAccess = SeedAccess.GATEWAY), ProviderId.SEED) is GatewayProvider)
        assertTrue(CloudProviders.views(settings.copy(seedAccess = SeedAccess.GATEWAY), ProviderId.SEED) is MultiViewGateway)
        assertTrue(CloudProviders.vision(settings, ProviderId.QWEN) is QwenOfficialProvider)
        assertTrue(CloudProviders.views(settings, ProviderId.QWEN) is QwenOfficialProvider)
        assertTrue(CloudProviders.vision(settings.copy(qwenAccess = QwenAccess.GATEWAY), ProviderId.QWEN) is GatewayProvider)
        assertEquals(listOf(ProviderId.LOCAL), ModelRouting.candidates(settings.copy(mode = ModelMode.OFFLINE)))
        assertEquals(listOf(ProviderId.QWEN, ProviderId.SEED, ProviderId.LOCAL), ModelRouting.candidates(ModelSettings(mode = ModelMode.AUTO)))
        val encoded = VlmJson.encodeToString(settings)
        assertFalse(encoded.contains(settings.seedApiKey)); assertFalse(encoded.contains(settings.qwenApiKey))
        assertFalse(encoded.contains("seedApiKey"))
    }

    @Test fun officialAddressCannotSendKeyToOtherHostsOrRedirectPaths() {
        assertEquals(CloudModelConfig.seedBaseUrl + CloudModelConfig.seedVisionPath, SeedOfficialProtocol.validate(settings).toString())
        listOf("http://ark.cn-beijing.volces.com/api/v3", "https://other.invalid/api/v3",
            "https://ark.cn-beijing.volces.com.evil.invalid/api/v3", "https://user@ark.cn-beijing.volces.com/api/v3",
            "https://ark.cn-beijing.volces.com/api/v3?key=secret", CloudModelConfig.seedBaseUrl + CloudModelConfig.seedVisionPath).forEach { base ->
            try { SeedOfficialProtocol.validate(settings, base); fail() }
            catch (failure: ModelFailure) { assertEquals("SEED_URL", failure.code) }
        }
        try { SeedOfficialProtocol.validate(settings.copy(seedApiKey = "")); fail() }
        catch (failure: ModelFailure) { assertEquals("SEED_KEY", failure.code) }
    }

    @Test fun realImageChannelAndThinkingAreSeedSpecificAndQwenPayloadStaysTheSame() {
        val images = listOf(ViewImage("frame-7", "jpeg-one", true, 10, 10), ViewImage("frame-2", "jpeg-two", false, 10, 10))
        val body = SeedOfficialProtocol.payload(CloudModelConfig.seedModel, "选择背景", images, 4096)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("enable_thinking"))
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("frameId=frame-7", "frameId=frame-2"), listOf(1, 3).map { content[it].jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals(listOf("data:image/jpeg;base64,jpeg-one", "data:image/jpeg;base64,jpeg-two"), listOf(2, 4).map {
            assertEquals("image_url", content[it].jsonObject["type"]!!.jsonPrimitive.content)
            content[it].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        })
        val qwen = QwenOfficialProtocol.payload(CloudModelConfig.qwenModel, "JSON", images, 4096)
        assertEquals(false, qwen["enable_thinking"]!!.jsonPrimitive.boolean)
        assertFalse(qwen.containsKey("thinking"))
    }

    @Test fun singlePhotoUsesOwnKeyAndConsumesBudgetExactlyOnce() = runBlocking {
        val file = File.createTempFile("seed-input", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            var calls = 0
            val provider = SeedOfficialProvider(settings, client { request ->
                calls++
                assertEquals("Bearer fake-seed-key", request.header("Authorization"))
                val body = payload(request).toString()
                assertFalse(body.contains(settings.seedApiKey)); assertFalse(body.contains(settings.qwenApiKey))
                response("{\"version\":2,\"imageId\":\"scene\"}")
            }) { "jpeg" }
            val budget = PhotoSessionBudget()
            val input = DirectorInput(SceneSnapshot("scene", 10, 10, 1f, file.path, file.path), CameraCapabilities(), "",
                sessionBudget = budget, photoReferences = true)
            assertTrue(provider.step(ModelCall(input, "JSON", "r")).photoProtocol)
            assertEquals(1, budget.calls); assertEquals(4L, budget.uploadedBytes)
            repeat(5) { budget.consume(0) }
            try { provider.step(ModelCall(input, "JSON", "r2")); fail() }
            catch (failure: ModelFailure) { assertEquals("SESSION_BUDGET_EXHAUSTED", failure.code) }
            assertEquals(1, calls)
        } finally { file.delete() }
    }

    @Test fun oneSixAndEightImagesKeepWinnerBindingAndShareExistingSelectionValidation() = runBlocking {
        listOf(1, 6, 8).forEach { count ->
            val images = (0 until count).map { ViewImage("frame-$it", "jpeg", false, 10, 10) }
            val selected = ViewSelection(scanId = "scan", primary = ViewCandidate(images.last().frameId, "背景", "留白", emptyList(), emptyList(), emptyList()), alternatives = emptyList())
            val provider = SeedOfficialProvider(settings, client { request ->
                val content = payload(request)["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
                assertEquals(count, content.count { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" })
                response(VlmJson.encodeToString(selected))
            }) { "unused" }
            val reply = provider.select(ViewSelectionRequest(ProviderId.SEED, "r", "scan", images, "JSON"))
            assertEquals(images.last().frameId, CloudViewSelectionProtocol.inspect(reply, "scan", images.map { it.frameId }.toSet()).selection!!.primary.frameId)
            assertNull(CloudViewSelectionProtocol.inspect(reply, "scan", setOf("not-sent")).selection)
        }
    }

    @Test fun configurationCheckContainsNoImagesAndInvalidOutputCannotProduceSelection() = runBlocking {
        val checker = SeedOfficialProvider(settings, client { request ->
            val body = payload(request)
            assertEquals(64, body["max_tokens"]!!.jsonPrimitive.int)
            assertEquals(1, body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.size)
            response("{\"ok\":true}")
        }) { "unused" }
        assertEquals("seed-actual-model", checker.checkConfiguration())
        val provider = SeedOfficialProvider(settings, client { response("not-json") }) { "unused" }
        val reply = provider.select(ViewSelectionRequest(ProviderId.SEED, "r", "scan", listOf(ViewImage("f", "jpeg", false, 10, 10)), "JSON"))
        assertNull(reply.selection)
    }

    @Test fun failuresAndRefusalsAreRedactedAndOnlyTemporaryErrorsAllowFallback() {
        mapOf(401 to "SEED_AUTH", 403 to "SEED_PERMISSION", 404 to "SEED_MODEL", 429 to "SEED_LIMIT", 500 to "SEED_SERVICE").forEach { (status, code) ->
            try { SeedOfficialProtocol.response(status, "{\"error\":{\"code\":\"sk-secret\",\"message\":\"private prompt\"}}", "m"); fail() }
            catch (failure: ModelFailure) {
                assertEquals(code, failure.code); assertEquals("", failure.message); assertNull(failure.upstreamErrorCode)
                assertEquals(status, failure.upstreamHttpStatus ?: -1)
                assertEquals(status == 429 || status >= 500, failure.retryable)
            }
        }
        try { SeedOfficialProtocol.response(400, "{\"error\":{\"code\":\"InputImageSensitiveContentDetected.PrivacyInformation\",\"message\":\"secret\"}}", "m"); fail() }
        catch (failure: ModelFailure) { assertEquals("SEED_REFUSED", failure.code); assertFalse(failure.retryable); assertEquals("", failure.message) }
        listOf(response("partial", "length"), "not-json", response("", "stop")).forEach { body ->
            try { SeedOfficialProtocol.response(200, body, "m"); fail() }
            catch (failure: ModelFailure) { assertEquals("SEED_OUTPUT", failure.code) }
        }
        try { SeedOfficialProtocol.response(200, response("filtered", "content_filter"), "m"); fail() }
        catch (failure: ModelFailure) { assertEquals("SEED_REFUSED", failure.code); assertFalse(failure.retryable) }
    }

    @Test fun timeoutIsSafeAndAllowsExistingAutomaticFallback() = runBlocking {
        val http = client { throw SocketTimeoutException("secret transport details") }
        try {
            SeedOfficialProvider(settings, http) { "unused" }.checkConfiguration()
            fail()
        } catch (failure: ModelFailure) {
            assertEquals("SEED_TIMEOUT", failure.code)
            assertTrue(failure.retryable)
            assertEquals("", failure.message)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test fun cancellationCancelsActualHttpCall() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val http = client { entered.complete(Unit); release.await(5, TimeUnit.SECONDS); response("{\"ok\":true}") }
        try {
            val work = launch { SeedOfficialProvider(settings, http) { "unused" }.checkConfiguration() }
            withTimeout(5000) { entered.await() }
            work.cancelAndJoin()
            assertTrue(http.dispatcher.runningCalls().single().isCanceled())
        } finally { release.countDown(); http.dispatcher.executorService.shutdown() }
    }
}
