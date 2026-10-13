package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.data.ModelFailure
import com.framer.sense.feature.camera.vlm.data.QwenOfficialProvider
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.scan.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 使用临时帧与可注入视觉传输，验证修正、路由、上传预算及取消。 */
class ScanViewAgentTest {
    @Test fun successfulOfficialSelectionNeverWaitsForUnrelatedGateway() = runBlocking {
        withScan { scan ->
            val settings = ModelSettings(mode = ModelMode.AUTO, qwenApiKey = "sk-fake-key")
            val result = ScanViewAgent { "jpeg" }.select(scan, settings, "", PhotoSessionBudget()) { provider ->
                check(provider == ProviderId.QWEN) { "官方选景成功不应查询网关" }
                object : MultiViewProvider {
                    override suspend fun capabilities() = QwenOfficialProvider(settings).capabilities()
                    override suspend fun select(request: ViewSelectionRequest) = ViewSelectionReply(settings.qwenModel, "", valid())
                }
            }
            assertEquals("f", result.selection!!.primary.frameId)
        }
    }

    @Test fun officialCapabilityStillWorksWhenEarlierGatewayProviderIsUnavailable() = runBlocking {
        withScan { scan ->
            val settings = ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.SEED, qwenApiKey = "sk-fake-key")
            val budget = PhotoSessionBudget()
            val selected = mutableListOf<ProviderId>()
            val result = ScanViewAgent { "jpeg" }.select(scan, settings, "", budget) { provider ->
                object : MultiViewProvider {
                    override suspend fun capabilities(): MultiImageCapabilities {
                        if (provider == ProviderId.SEED) throw ModelFailure("UNAVAILABLE", "", true)
                        return QwenOfficialProvider(settings).capabilities()
                    }
                    override suspend fun select(request: ViewSelectionRequest): ViewSelectionReply {
                        selected += provider
                        assertEquals(listOf("f"), request.images.map { it.frameId })
                        return ViewSelectionReply(settings.qwenModel, "", valid())
                    }
                }
            }
            assertEquals(listOf(ProviderId.QWEN), selected)
            assertEquals("f", result.selection!!.primary.frameId)
            assertEquals(1, budget.calls)
        }
    }

    private suspend fun withScan(block: suspend (ScanSnapshot) -> Unit) {
        val file = File.createTempFile("scan-view", ".jpg")
        try {
            file.writeBytes(byteArrayOf(-1, -40, -1, 12))
            val config = ScanCameraConfig(1, 0, 1f, CropRect(), false)
            val frame = ScanFrame("f", "s", 100, 100, config, null, 1f, SceneSnapshot("f", 640, 480, 1f, file.path, file.path), 4, true)
            block(ScanSnapshot(ScanSession("s", 0, 200), listOf(frame), 0f))
        } finally { file.delete() }
    }
    private fun valid() = ViewSelection(scanId = "s", primary = ViewCandidate("f", "山", "开阔", emptyList(), emptyList(), emptyList()), alternatives = emptyList())
    private fun gateway(answer: suspend (ViewSelectionRequest) -> ViewSelectionReply) = object : MultiViewProvider {
        override suspend fun capabilities() = MultiImageCapabilities(listOf(ProviderId.QWEN, ProviderId.SEED).map { MultiImageCapability(it, "mock", true, true, 8) })
        override suspend fun select(request: ViewSelectionRequest) = answer(request)
    }
    @Test fun invalidSelectionGetsOneCorrectionWithSameSubmittedFrame() = runBlocking {
        withScan { scan ->
            val requests = mutableListOf<ViewSelectionRequest>(); val budget = PhotoSessionBudget()
            val result = ScanViewAgent { "mock-base64" }.select(scan, ModelSettings(mode = ModelMode.AUTO), "", budget) {
                gateway { r -> requests += r; ViewSelectionReply("mock", "", if (requests.size == 1) valid().copy(scanId = "other") else valid()) }
            }
            assertEquals("f", result.selection!!.primary.frameId)
            assertEquals(2, requests.size); assertEquals(2, budget.calls); assertEquals(8L, budget.uploadedBytes)
            assertTrue(requests[0].prompt.contains("exactly ONE best frame"))
            assertTrue(requests[0].prompt.contains("alternatives must be []"))
            assertEquals(requests[0].images, requests[1].images); assertTrue(requests[1].prompt.contains("Previous result"))
        }
    }
    @Test fun repeatedInvalidSelectionStopsAfterTwoAttempts() = runBlocking {
        withScan { scan ->
            val logs = mutableListOf<String>()
            val logger = object : DirectorLogger by NoOpDirectorLogger {
                override fun info(message: String) { logs += message }
                override fun warn(message: String, error: Throwable?) { logs += message }
            }
            val budget = PhotoSessionBudget()
            val result = ScanViewAgent(logger) { "mock" }.select(scan, ModelSettings(mode = ModelMode.AUTO), "", budget) { gateway { ViewSelectionReply("mock", "invalid") } }
            assertNull(result.selection); assertEquals(2, budget.calls)
            assertTrue(logs.any { it.contains("stage=选景调用 result=返回成功") && it.contains("model=mock") })
            assertTrue(logs.any { it.contains("stage=选景校验 result=失败") })
            assertFalse(logs.any { it.contains("stage=选景任务 result=成功") })
            assertTrue(logs.any { it.contains("stage=选景任务 result=转人工选景") })
        }
    }
    @Test fun retryableFailureSwitchesWithinSameTwoAttemptBudget() = runBlocking {
        withScan { scan ->
            val requested = mutableListOf<ProviderId>(); val budget = PhotoSessionBudget()
            val result = ScanViewAgent { "mock" }.select(scan, ModelSettings(mode = ModelMode.AUTO, region = Region.CN), "", budget) { provider ->
                gateway { requested += provider; if (provider == ProviderId.QWEN) throw ModelFailure("TIMEOUT", "", true) else ViewSelectionReply("mock", "", valid()) }
            }
            assertNotNull(result.selection); assertEquals(listOf(ProviderId.QWEN, ProviderId.SEED), requested)
            assertEquals(2, budget.calls)
        }
    }
    @Test fun refusalAndParameterErrorsNeverSwitchProviders() = runBlocking {
        withScan { scan ->
            for (code in listOf("REFUSED", "BAD_REQUEST")) {
                val budget = PhotoSessionBudget(); val requested = mutableListOf<ProviderId>()
                try {
                    ScanViewAgent { "mock" }.select(scan, ModelSettings(mode = ModelMode.AUTO), "", budget) { p -> gateway { requested += p; throw ModelFailure(code, "", false) } }
                    fail("必须保留拒绝原因")
                } catch (failure: ModelFailure) { assertEquals(code, failure.code) }
                assertEquals(1, requested.size); assertEquals(1, budget.calls)
            }
        }
    }
    @Test fun cancellationPropagatesWithoutRetry() = runBlocking {
        withScan { scan ->
            val budget = PhotoSessionBudget()
            try { ScanViewAgent { "mock" }.select(scan, ModelSettings(mode = ModelMode.AUTO), "", budget) { gateway { throw CancellationException() } }; fail() }
            catch (_: CancellationException) { assertEquals(1, budget.calls) }
        }
    }
    @Test fun exhaustedSessionCannotSubmitAnotherSelection() = runBlocking {
        withScan { scan ->
            val budget = PhotoSessionBudget(); repeat(6) { budget.consume(0) }
            try { ScanViewAgent { "mock" }.select(scan, ModelSettings(mode = ModelMode.AUTO), "", budget) { gateway { error("不应请求") } }; fail() }
            catch (failure: ModelFailure) { assertEquals("SESSION_BUDGET_EXHAUSTED", failure.code) }
            assertEquals(6, budget.calls)
        }
    }
    @Test fun usedSelectionAttemptsAreAppLimitAndDoNotTriggerAnotherCapabilityOrModelRequest() = runBlocking {
        withScan { scan ->
            val budget = PhotoSessionBudget(); repeat(2) { budget.consume(0, selection = true) }
            try {
                ScanViewAgent { "mock" }.select(scan, ModelSettings(mode = ModelMode.CLOUD), "", budget) { error("不应再请求云端") }
                fail()
            } catch (failure: ModelFailure) { assertEquals("SELECTION_ATTEMPTS_EXHAUSTED", failure.code) }
            assertEquals(2, budget.calls)
        }
    }
    @Test fun oldGateway404FallsBackWithoutUploading() = runBlocking {
        withScan { scan ->
            val budget = PhotoSessionBudget()
            val result = ScanViewAgent { "mock" }.select(scan, ModelSettings(mode = ModelMode.AUTO), "", budget) {
                object : MultiViewProvider {
                    override suspend fun capabilities(): MultiImageCapabilities = throw ModelFailure("HTTP_404", "", true)
                    override suspend fun select(request: ViewSelectionRequest): ViewSelectionReply = error("不应上传")
                }
            }
            assertNull(result.selection); assertEquals(0, budget.calls)
        }
    }
    @Test fun submitsAllEightRefinedFramesAndReturnsOneBoundWinner() = runBlocking {
        withScan { scan ->
            val original = scan.frames.single()
            val frames = (0..11).map { index -> original.copy(id = if (index == 0) "f" else "f$index",
                preferred = index == 0, pose = ScanPose("basis", index.toLong(), index * 30f, 0f)) }
            val budget = PhotoSessionBudget()
            val result = ScanViewAgent { "mock" }.select(scan.copy(frames = frames), ModelSettings(mode = ModelMode.AUTO), "保留山体", budget) {
                gateway { request ->
                    assertEquals(8, request.images.size)
                    assertEquals(8, request.images.map { it.frameId }.distinct().size)
                    assertTrue(request.images.any { it.frameId == "f" && it.preferred })
                    assertTrue(request.images.all { image -> frames.any { it.id == image.frameId } })
                    ViewSelectionReply("mock", "", valid())
                }
            }
            assertEquals("f", result.selection!!.primary.frameId)
            assertTrue(result.selection.alternatives.isEmpty())
            assertEquals(8, result.comparedFrames); assertEquals(32L, budget.uploadedBytes)
        }
    }

}
