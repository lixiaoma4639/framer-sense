package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.GeneratedReferencePrompt
import com.framer.sense.feature.camera.vlm.agent.QwenImageReferencePrompt
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QwenImageTest {
    @Test fun imageSelectionPersistsWithoutChangingVlmOrSeedModelAndLegacySettingsStillLoad() {
        val settings = ModelSettings(preferred = ProviderId.QWEN, seedreamModel = SeedreamModel.PRO,
            qwenImageModel = QwenImageModel.V30_PRO, qwenApiKey = "qwen-secret", seedApiKey = "seed-secret")
        val saved = VlmJson.encodeToString(settings)
        val restored = VlmJson.decodeFromString<ModelSettings>(saved)
        assertEquals(QwenImageModel.V30_PRO, restored.imageGenerationModel)
        assertEquals(SeedreamModel.PRO, restored.copy(preferred = ProviderId.SEED).imageGenerationModel)
        assertEquals(settings.qwenModel, restored.qwenModel)
        assertFalse(saved.contains("secret"))
        assertEquals(QwenImageModel.V21_TURBO, VlmJson.decodeFromString<ModelSettings>("{}").qwenImageModel)
        assertEquals(setOf("qwen-image-2.1-turbo", "qwen-image-2.1-pro", "qwen-image-3.0", "qwen-image-3.0-pro"),
            QwenImageModel.entries.map { it.apiId }.toSet())
    }

    @Test fun qwenRouteUsesOnlyQwenKeyAndNeverBorrowsSeedCredentials() {
        val settings = ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.QWEN, qwenApiKey = "qwen", seedApiKey = "seed")
        assertEquals("qwen", settings.imageGenerationKey)
        assertEquals(ReferenceSource.GENERATED_IMAGE, CameraReferenceRouting.source(settings, true))
        assertEquals(ReferenceSource.LEGACY_GLB, CameraReferenceRouting.source(settings.copy(qwenApiKey = ""), true))
        assertEquals(ReferenceSource.LEGACY_GLB, CameraReferenceRouting.source(settings, false))
        val missing = runCatching { QwenImageProtocol.endpoint(settings.copy(qwenApiKey = "")) }.exceptionOrNull()
        assertEquals("KEY", (missing as GeneratedReferenceFailure).code)
        assertTrue(QwenImageProtocol.endpoint(settings).encodedPath.endsWith("/compatible-mode/v1/images/generations"))
        assertEquals(listOf(ProviderId.QWEN), ModelRouting.candidates(settings.copy(mode = ModelMode.CLOUD)))
    }

    @Test fun requestUsesOneOutputAndExactCanvasWithoutSeedLayerOrUndocumentedTransparencyFlags() {
        val prompt = QwenImageReferencePrompt.build("舞姿", 3)
        QwenImageModel.entries.forEach { model ->
            val payload = QwenImageProtocol.payload(model, "data:image/jpeg;base64,AA==", prompt, 960, 1280)
            assertEquals(model.apiId, payload["model"]!!.jsonPrimitive.content)
            assertEquals("960x1280", payload["size"]!!.jsonPrimitive.content)
            assertEquals(1, payload["n"]!!.jsonPrimitive.int)
            assertEquals(setOf("model", "prompt", "image", "size", "n", "prompt_extend"), payload.keys)
            assertFalse(payload["prompt_extend"]!!.jsonPrimitive.boolean)
        }
        assertFalse(prompt.contains("Seedream"))
        assertFalse(prompt.contains("reference_person"))
        assertTrue(prompt.contains("Alpha"))
        assertTrue(prompt.contains("舞姿"))
        assertTrue(GeneratedReferencePrompt.build("舞姿", 3).contains("Seedream 原生图层"))
    }

    @Test fun singleHighResolutionPhotoGetsAValidProportionalOutputCanvasWithoutChangingTheInput() {
        val input = "data:image/jpeg;base64,original-photo"
        val cases = listOf(
            Triple(3024, 4032, "1536x2048"),
            Triple(4032, 3024, "2048x1536"),
            Triple(8000, 8000, "2048x2048"),
            Triple(8000, 1000, "2048x256"),
            Triple(1000, 8000, "256x2048"),
            Triple(960, 1280, "960x1280")
        )
        QwenImageModel.entries.forEach { model ->
            cases.forEach { (width, height, expected) ->
                val request = QwenImageProtocol.payload(model, input, "portrait", width, height)
                assertEquals(expected, request["size"]!!.jsonPrimitive.content)
                assertEquals(input, request["image"]!!.jsonPrimitive.content)
                assertEquals(1, request["n"]!!.jsonPrimitive.int)
                val output = QwenImageProtocol.outputSize(width, height)
                assertTrue(output.width.toLong() * output.height in 512L * 512..2048L * 2048)
                assertEquals(width.toDouble() / height, output.width.toDouble() / output.height, .001)
            }
        }
    }

    @Test fun invalidDimensionsRemainRejectedBeforeSendingAPaidRequest() {
        listOf(0 to 1280, -1 to 960, 64 to 64, 8000 to 100).forEach { (width, height) ->
            val error = runCatching { QwenImageProtocol.outputSize(width, height) }.exceptionOrNull()
            assertEquals("INPUT", (error as GeneratedReferenceFailure).code)
        }
    }

    @Test fun bothIndependentPromptsReceivePortraitPreferencesWithoutChangingOutputProtocols() {
        val preferences = GeneratedPortraitPreferences(AvatarId.CHILD_BOY, AvatarEthnicity.ASIAN)
        val qwen = QwenImageReferencePrompt.build("身穿汉服", 0, preferences)
        val seed = GeneratedReferencePrompt.build("身穿汉服", 0, preferences)
        listOf(qwen, seed).forEach { prompt ->
            assertTrue(prompt.contains("指导人像为儿童男，人像种族为亚洲人"))
            assertTrue(prompt.contains("原照片已有明确主角时保留其身份"))
            assertTrue(prompt.contains("用户文字明确指定的人物要求优先"))
            assertTrue(prompt.contains("身穿汉服"))
        }
        assertFalse(qwen.contains("Seedream"))
        assertTrue(qwen.contains("RGBA PNG"))
        assertTrue(seed.contains("Seedream 原生图层"))
    }

    @Test fun resultAcceptsOnlyOneOfficialAssetAndRejectsErrorsAndUntrustedUrls() {
        val url = "https://dashscope-result-sz.oss-cn-shenzhen.aliyuncs.com/a.png?Expires=123"
        assertEquals(url, QwenImageProtocol.response(200, """{"data":[{"url":"$url"}]}"""))
        listOf("http://example.com/a.png", "https://example.com/a.png", "https://bucket.oss-cn-beijing.aliyuncs.com.evil.test/a.png",
            "https://secret@bucket.oss-cn-beijing.aliyuncs.com/a.png").forEach { assertFalse(QwenImageProtocol.trustedAsset(it)) }
        listOf("""{"data":[]}""", """{"data":[{"url":"$url"},{"url":"$url"}]}""", "{}").forEach {
            assertEquals("OUTPUT", (runCatching { QwenImageProtocol.response(200, it) }.exceptionOrNull() as GeneratedReferenceFailure).code)
        }
        val error = runCatching { QwenImageProtocol.response(403, """{"error":{"code":"Arrearage"}}""") }.exceptionOrNull()
        assertEquals("QWEN_ACCOUNT", (error as GeneratedReferenceFailure).code)
    }

    @Test fun unsupportedGatewayStaysExplicitAndIsNotASeedFallback() {
        val settings = ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.QWEN, qwenAccess = QwenAccess.GATEWAY)
        assertEquals(ReferenceSource.GENERATED_IMAGE, CameraReferenceRouting.source(settings, true))
        val scene = SceneSnapshot("scene", 960, 1280, 1f, "source", "thumb")
        val failed = GeneratedCandidate("q", "b", scene, QwenImageModel.V21_TURBO, "", 0, null,
            status = GeneratedCandidateStatus.FAILED, errorCode = "QWEN_GATEWAY_UNSUPPORTED")
        assertFalse(CameraReferenceRouting.shouldFallback(settings, GeneratedReferenceState(candidates = listOf(failed))))
    }

    @Test fun opaqueResultKeepsPreviewAndReasonAcrossCacheAndCannotBeApplied() {
        val assets = GeneratedReferenceAssets("preview.png", "", 960, 1280, QwenImageModel.V21_TURBO.apiId, 1,
            portraitIssue = GeneratedPortraitIssue.NO_TRANSPARENCY)
        val restored = VlmJson.decodeFromString<GeneratedReferenceAssets>(VlmJson.encodeToString(assets))
        assertEquals(GeneratedPortraitIssue.NO_TRANSPARENCY, restored.portraitIssue)
        val scene = SceneSnapshot("scene", 960, 1280, 1f, "source", "thumb")
        val candidate = GeneratedCandidate("q", "b", scene, QwenImageModel.V21_TURBO, "", 0, null,
            status = GeneratedCandidateStatus.READY, assets = restored)
        assertTrue(candidate.ready)
        assertFalse(candidate.canApply)
        val legacy = VlmJson.encodeToString(assets).let { VlmJson.parseToJsonElement(it).jsonObject }
            .filterKeys { it != "portraitIssue" }.let { JsonObject(it).toString() }
        assertNull(VlmJson.decodeFromString<GeneratedReferenceAssets>(legacy).portraitIssue)
    }
}
