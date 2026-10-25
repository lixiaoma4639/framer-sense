package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.data.GeneratedReferenceFailure
import com.framer.sense.feature.camera.vlm.data.GeneratedReferenceDiagnostics
import com.framer.sense.feature.camera.vlm.data.CameraReferenceRouting
import com.framer.sense.feature.camera.vlm.data.SeedreamImageProtocol
import com.framer.sense.feature.camera.vlm.data.referenceImageSampleSize
import com.framer.sense.feature.camera.vlm.data.SeedreamLayer
import com.framer.sense.feature.camera.vlm.data.SeedreamLayers
import com.framer.sense.feature.camera.vlm.data.position
import com.framer.sense.feature.camera.vlm.agent.GeneratedReferencePrompt
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeneratedReferenceTest {
    private val scene = SceneSnapshot("scene", 1200, 1800, 1f, "source", "small")
    private val one = GeneratedCandidate("one", "batch", scene, SeedreamModel.FLASH, "自然", 0, PhotoViewBinding("scan", "scene", 1))
    private val two = one.copy(id = "two", variation = 1)
    private val assets = GeneratedReferenceAssets("full.png", "human.png", 1200, 1800, "actual-model", 2)

    @Test fun fasterSecondRequestIsDisplayedFirstAndLaterResultNeverChangesSelection() {
        val state = GeneratedReferenceState("epoch", listOf(one, two))
        val first = state.update("epoch", two.copy(status = GeneratedCandidateStatus.READY, assets = assets))
        assertEquals("two", first.selectedId)
        val applied = first.copy(applied = first.selected)
        val later = applied.update("epoch", one.copy(status = GeneratedCandidateStatus.READY, assets = assets))
        assertEquals(listOf("two", "one"), later.ordered.map { it.id })
        assertEquals("two", later.selectedId)
        assertEquals(applied.applied, later.applied)
    }

    @Test fun failureIsIsolatedAndStaleOrDuplicateCompletionCannotOverwriteAssets() {
        val state = GeneratedReferenceState("epoch", listOf(one, two))
            .update("epoch", one.copy(status = GeneratedCandidateStatus.FAILED, errorCode = "NETWORK"))
        val ready = state.update("epoch", two.copy(status = GeneratedCandidateStatus.READY, assets = assets))
        assertTrue(ready.hasReady)
        assertEquals(ready, ready.update("old", one.copy(status = GeneratedCandidateStatus.READY, assets = assets)))
        assertEquals(ready, ready.update("epoch", two.copy(status = GeneratedCandidateStatus.FAILED)))
        assertEquals(state, state.update("epoch", one.copy(status = GeneratedCandidateStatus.READY, assets = assets)))
    }

    @Test fun applyingFirstCandidateAllowsCaptureWhileOtherCandidateIsStillRunning() {
        val generated = GeneratedReferenceState("epoch", listOf(one, two.copy(status = GeneratedCandidateStatus.RUNNING)))
            .update("epoch", one.copy(status = GeneratedCandidateStatus.READY, assets = assets))
        val applied = generated.copy(applied = generated.selected)
        val ui = VlmUiState(stage = VlmStage.LIVE, cameraReady = true, scene = scene,
            settings = ModelSettings(mode = ModelMode.CLOUD), generated = applied,
            background = BackgroundGuidance(BackgroundStatus.ALIGNED_MANUAL))
        assertTrue(ui.generated.running)
        assertFalse(ui.operationBusy)
        assertTrue(ui.canCapture)
        assertEquals(List(4) { CameraStepStatus.COMPLETE }, ui.workflowProgress().take(4))
        val saved = ui.copy(capturedWorkflow = ui.workflowKey)
        val secondReady = saved.copy(generated = saved.generated.update("epoch", two.copy(status = GeneratedCandidateStatus.READY, assets = assets)))
        assertEquals(saved.workflowKey, secondReady.workflowKey)
        assertEquals(List(5) { CameraStepStatus.COMPLETE }, secondReady.workflowProgress())
        assertEquals(BackgroundStatus.SEARCHING, secondReady.withBackgroundGuidance(BackgroundGuidance()).background.status)
        assertNull(secondReady.withBackgroundGuidance(BackgroundGuidance()).capturedWorkflow)
    }

    @Test fun cancelledBatchCannotBecomeReadyFromLateResults() {
        val cancelled = GeneratedReferenceState("epoch", listOf(one, two)).cancelPending()
        assertFalse(cancelled.running)
        assertEquals(cancelled, cancelled.update("epoch", one.copy(status = GeneratedCandidateStatus.READY, assets = assets)))
    }

    @Test fun automaticFallbackWaitsForBothCandidatesAndNeverDiscardsSuccess() {
        val settings = ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.SEED)
        val firstFailed = GeneratedReferenceState("epoch", listOf(one, two))
            .update("epoch", one.copy(status = GeneratedCandidateStatus.FAILED, errorCode = "NETWORK"))
        assertFalse(CameraReferenceRouting.shouldFallback(settings, firstFailed))
        val bothFailed = firstFailed.update("epoch", two.copy(status = GeneratedCandidateStatus.FAILED, errorCode = "SERVICE"))
        assertTrue(CameraReferenceRouting.shouldFallback(settings, bothFailed))
        assertFalse(CameraReferenceRouting.shouldFallback(settings.copy(mode = ModelMode.CLOUD), bothFailed))
        val success = firstFailed.update("epoch", two.copy(status = GeneratedCandidateStatus.READY, assets = assets))
        assertFalse(CameraReferenceRouting.shouldFallback(settings, success.copy(applied = success.selected)))
        val invalidLayers = firstFailed.update("epoch", two.copy(status = GeneratedCandidateStatus.FAILED, errorCode = "ALPHA"))
        assertFalse(CameraReferenceRouting.shouldFallback(settings, invalidLayers))
        assertFalse(CameraReferenceRouting.shouldFallback(settings, firstFailed.cancelPending()))
    }

    @Test fun payloadUsesDocumentedLayerModeAndDoesNotRequestSequentialGeneration() {
        val request = SeedreamImageProtocol.payload("model", "data:image/jpeg;base64,AA==", "要求")
        assertTrue(request["layer_decomposition"]!!.jsonPrimitive.boolean)
        assertEquals("png", request["output_format"]!!.jsonPrimitive.content)
        assertFalse(request.containsKey("sequential_image_generation"))
        assertFalse(request.containsKey("n"))
    }

    @Test fun promptSupportsCreatingAPersonInAnEmptySceneWithoutDiscardingExistingIdentity() {
        val prompt = GeneratedReferencePrompt.build("一位穿汉服的女子", 0)
        assertTrue(prompt.contains("如果没有人像，则由你创造满足用户要求的人像"))
        assertTrue(prompt.contains("保留照片中同一个人物的身份特征"))
        assertTrue(prompt.contains("不把背景路人作为主角"))
        assertTrue(prompt.contains("一位穿汉服的女子"))
        assertTrue(prompt.contains("reference_person"))
        assertTrue(prompt.indexOf("最终输出一张完整、真实的人像摄影参考照片") < prompt.indexOf("补充要求："))
    }

    @Test fun promptPreservesLongUserRequirementsAndMakesVariationSubordinateToThem() {
        // 输入框允许 2000 字；关键要求不能在旧的 1200 字位置被静默截掉。
        val instruction = "希望自然一些。".repeat(180) + "最后要求：半身、安静地看向左侧。"
        repeat(4) { variation ->
            val prompt = GeneratedReferencePrompt.build("  $instruction  ", variation)
            assertTrue(prompt.endsWith("用户要求：$instruction"))
            assertTrue(prompt.contains("不以变化参考覆盖用户要求"))
            assertTrue(prompt.contains("支撑条件无法确认或原图未拍到地面时"))
            assertTrue(prompt.contains("不强行生成全身或补出地面"))
        }
    }

    @Test fun downloadedImageSamplingHandlesReportedSizeAndLargeImageBoundaries() {
        // 用户实际失败的底图尺寸：无需降采样，倍率也必须为 1。
        assertEquals(1, referenceImageSampleSize(1344, 1792))
        assertEquals(1, referenceImageSampleSize(2048, 2048))
        assertEquals(2, referenceImageSampleSize(2049, 1344))
        assertEquals(2, referenceImageSampleSize(1344, 4096))
        assertEquals(4, referenceImageSampleSize(4097, 1344))
        listOf(0 to 1792, 1344 to -1).forEach { (width, height) ->
            try {
                referenceImageSampleSize(width, height)
                fail("不得接受无效图片尺寸")
            } catch (expected: GeneratedReferenceFailure) {
                assertEquals("IMAGE", expected.code)
            }
        }
    }

    @Test fun diagnosticsExposeHttpErrorIdentifiersWithoutPrivateResponseContent() {
        val diagnostic = GeneratedReferenceDiagnostics.response("""{"request_id":"req-123","error":{
            "code":"InvalidParameter","type":"invalid_request","param":"size",
            "message":"size layer_decomposition PRIVATE_PROMPT sk-private-key"},"data":[{
            "z_index":1,"name":"PRIVATE_PERSON","url":"https://example.invalid/image?signature=PRIVATE_SIGNATURE",
            "description":"PRIVATE_DESCRIPTION","bounding_box":{"normalized":[0,0,1000,1000]}}]}""")
        assertTrue(diagnostic.contains("upstream=InvalidParameter"))
        assertTrue(diagnostic.contains("requestId=req-123"))
        assertTrue(diagnostic.contains("param=size"))
        assertTrue(diagnostic.contains("mentioned=size,layer_decomposition"))
        listOf("PRIVATE_", "sk-private", "signature=", "https://").forEach { assertFalse(diagnostic.contains(it)) }
        val failure = GeneratedReferenceFailure("OUTPUT", java.io.IOException("sk-secret https://private.invalid"))
        val summary = GeneratedReferenceDiagnostics.failure(failure)
        assertTrue(summary.contains("IOException"))
        assertFalse(summary.contains("sk-secret"))
        assertFalse(summary.contains("private.invalid"))
        assertEquals("json=false", GeneratedReferenceDiagnostics.response("<html>private</html>"))
    }

    @Test fun imageRejectionDiagnosticsKeepFixedHintsAndComparableInputFingerprints() {
        val diagnostic = GeneratedReferenceDiagnostics.response("""{"error":{
            "code":"InvalidParameter","type":"BadRequest","param":"image",
            "message":"Failed to decode image format. PRIVATE_PROMPT sk-private-key https://private.invalid/image"}}""")
        assertTrue(diagnostic.contains("param=image"))
        assertTrue(diagnostic.contains("reasonHints=DECODE,FORMAT"))
        listOf("PRIVATE_", "sk-private", "https://", "Failed to decode").forEach { assertFalse(diagnostic.contains(it)) }
        val unknown = GeneratedReferenceDiagnostics.response("""{"error":{"message":"PRIVATE_PROMPT"}}""")
        assertTrue(unknown.contains("reasonHints=unknown"))
        val first = "abc".toByteArray()
        assertEquals("ba7816bf8f01cfea", GeneratedReferenceDiagnostics.imageFingerprint(first))
        assertEquals(GeneratedReferenceDiagnostics.imageFingerprint(first), GeneratedReferenceDiagnostics.imageFingerprint(first.copyOf()))
        assertNotEquals(GeneratedReferenceDiagnostics.imageFingerprint(first), GeneratedReferenceDiagnostics.imageFingerprint("abd".toByteArray()))
    }

    @Test fun nativeLayerResponseRequiresBoundsAndIdentifiableMainPerson() {
        val response = """{"model":"actual","data":[
            {"url":"https://image.volces.com/base.png","z_index":0},
            {"url":"https://image.volces.com/person.png","z_index":1,"name":"reference_person","description":"穿汉服的女子",
              "bounding_box":{"normalized":[200,300,800,1000]}}],"usage":{"generated_images":2}}"""
        val parsed = SeedreamImageProtocol.response(200, response, "requested")
        assertEquals("actual", parsed.model)
        assertEquals(1, parsed.personLayers.size)
        assertEquals(2, parsed.outputs)
        try {
            SeedreamImageProtocol.response(200, response.replace("200,300,800,1000", "200,300,1001,1000"), "requested")
            fail("不得接受越界图层")
        } catch (expected: GeneratedReferenceFailure) { assertEquals("BOUNDS", expected.code) }
        try {
            SeedreamImageProtocol.response(200, """{"data":[{"url":"https://image.volces.com/full.png"}]}""", "requested")
            fail("不得把普通生成图片冒充透明人像")
        } catch (expected: GeneratedReferenceFailure) { assertEquals("LAYERS_MISSING", expected.code) }
    }

    @Test fun loneForegroundIsNotAssumedToBeAPersonAndObjectsCannotUsePersonTag() {
        fun response(name: String, description: String) = buildJsonObject {
            putJsonArray("data") {
                addJsonObject { put("url", "https://image.volces.com/base.png"); put("z_index", 0) }
                addJsonObject {
                    put("url", "https://image.volces.com/foreground.png"); put("z_index", 1)
                    put("name", name); put("description", description)
                    putJsonObject("bounding_box") { putJsonArray("normalized") { listOf(200, 300, 800, 1000).forEach { add(it) } } }
                }
            }
        }.toString()
        listOf("木凳" to "一把木制凳子", "bench" to "A wooden bench", "unknown" to "",
            "bench" to "A bench without a person", "木凳" to "凳子旁没有人物",
            "reference_person" to "一把木制凳子", "reference_person" to "",
            "reference_person" to "前景主体").forEach { (name, description) ->
            val parsed = SeedreamImageProtocol.response(200, response(name, description), "requested")
            assertTrue(parsed.personLayers.isEmpty())
            assertEquals(1, parsed.foregroundLayers.size)
        }
        listOf("女子" to "穿汉服的女子", "foreground" to "A woman standing beside a bench").forEach { (name, description) ->
            val parsed = SeedreamImageProtocol.response(200, response(name, description), "requested")
            assertEquals(description, parsed.personLayers.single().description)
        }
        val people = SeedreamLayers("model", listOf(
            SeedreamLayer("unused", 1, "woman", null), SeedreamLayer("unused", 2, "man", null)), 2)
        assertTrue(people.personLayers.isEmpty())
    }

    @Test fun imageParameterRejectionIsDistinctFromGenericServiceFailureAndDoesNotFallback() {
        val body = """{"error":{"code":"InvalidParameter","type":"BadRequest","param":"image"}}"""
        try {
            SeedreamImageProtocol.response(400, body, "requested")
            fail("须保留上游拒绝图片的错误类型")
        } catch (expected: GeneratedReferenceFailure) {
            assertEquals("IMAGE_REJECTED", expected.code)
        }
        val rejected = GeneratedReferenceState("epoch", listOf(one, two).map {
            it.copy(status = GeneratedCandidateStatus.FAILED, errorCode = "IMAGE_REJECTED")
        })
        assertFalse(CameraReferenceRouting.shouldFallback(ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.SEED), rejected))
        listOf("{}", "not-json", body.replace("image", "size")).forEach { other ->
            try {
                SeedreamImageProtocol.response(400, other, "requested")
                fail("错误响应不得当作成功")
            } catch (expected: GeneratedReferenceFailure) {
                assertEquals("SERVICE", expected.code)
            }
        }
    }

    @Test fun allReturnedLayersSurvivePersonSelectionAndUseOfficialCoordinates() {
        val response = """{"data":[
            {"url":"https://image.volces.com/top.png","z_index":2,"name":"木凳","bounding_box":{"absolute":[10,20,70,180],"normalized":[0,0,1000,1000]}},
            {"url":"https://image.volces.com/base.png","z_index":0},
            {"url":"https://image.volces.com/lower.png","z_index":1,"name":"未知前景","bounding_box":{"normalized":[100,200,700,900]}}
        ]}"""
        val parsed = SeedreamImageProtocol.response(200, response, "requested")
        assertTrue(parsed.personLayers.isEmpty())
        assertEquals(listOf(1, 2), parsed.foregroundLayers.map { it.z })
        val absolute = parsed.foregroundLayers.last().position(50, 100, 100, 200)
        assertEquals(5f, absolute.left, .001f)
        assertEquals(10f, absolute.top, .001f)
        assertEquals(35f, absolute.right, .001f)
        assertEquals(90f, absolute.bottom, .001f)
        val normalized = parsed.foregroundLayers.first().position(50, 100, 100, 200)
        assertEquals(20f, normalized.top, .001f)
        assertEquals(90f, normalized.bottom, .001f)
        try {
            parsed.foregroundLayers.last().position(50, 100, 40, 100)
            fail("越界的绝对坐标不得用于拼接")
        } catch (expected: GeneratedReferenceFailure) { assertEquals("BOUNDS", expected.code) }
    }

    @Test fun completeCompositeWithoutPortraitIsSelectableButCannotBeApplied() {
        val compositeOnly = assets.copy(portraitPath = "", sourceLayerCount = 4)
        val ready = one.copy(status = GeneratedCandidateStatus.READY, assets = compositeOnly)
        val state = GeneratedReferenceState("epoch", listOf(one)).update("epoch", ready)
        assertTrue(state.hasReady)
        assertEquals(one.id, state.selectedId)
        assertTrue(state.selected!!.ready)
        assertFalse(state.selected!!.canApply)
        assertFalse(state.selected!!.assets!!.hasPortrait)
        assertTrue(ready.copy(assets = assets).canApply)
    }

    @Test fun debugErrorDetailPreservesExplanationAndRedactsRequestData() {
        val body = buildJsonObject { putJsonObject("error") {
            put("message", "The image content is not suitable for layer decomposition. PRIVATE_PROMPT PRIVATE_KEY sk-secret " +
                "https://private.invalid/photo?signature=SECRET data:image/jpeg;base64,QUJDRA== " +
                "Bearer token-secret " + "A".repeat(64) + "\nINJECTED")
        } }.toString()
        val message = GeneratedReferenceDiagnostics.errorMessage(body, listOf("PRIVATE_PROMPT", "PRIVATE_KEY"))
        assertTrue(message.startsWith("The image content is not suitable for layer decomposition."))
        listOf("PRIVATE_", "sk-secret", "https://", "signature=", "QUJDRA", "token-secret", "AAAA", "\n").forEach {
            assertFalse(message.contains(it))
        }
        assertEquals("unavailable", GeneratedReferenceDiagnostics.errorMessage("not-json", emptyList()))
        assertTrue(GeneratedReferenceDiagnostics.errorMessage(body, emptyList()).length <= 600)
    }

    @Test fun assetDownloadDoesNotAcceptPrivateOrUntrustedUrls() {
        assertTrue(SeedreamImageProtocol.trustedAsset("https://ark.tos-cn-beijing.volces.com/person.png"))
        listOf("http://127.0.0.1/image", "https://volces.com.evil.test/image", "https://user:key@image.volces.com/image", "https://image.volces.com:444/image")
            .forEach { assertFalse(SeedreamImageProtocol.trustedAsset(it)) }
    }
}
