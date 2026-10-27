package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.GeneratedPortraitStylePolicy
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CameraHistorySnapshotTest {
    @Test fun snapshotExplicitlyPreservesPhotoGeometryAndBinding() {
        val binding = PhotoViewBinding("scan", "frame", 7)
        val scene = HistoryScene("scene", 1280, 960, 2f, "camera-history/s/scene.jpg", "camera-history/s/input.jpg",
            PhotoOrientation(1, 0), binding)
        val context = HistoryContext(aspectRatio = CaptureAspectRatio.THREE_FOUR, imported = false, scenes = listOf(scene),
            scan = null, views = emptyList())
        val decoded = VlmJson.decodeFromString<HistoryContext>(VlmJson.encodeToString(context))
        assertEquals(scene, decoded.scenes.single())
        assertEquals(90, decoded.scenes.single().orientation.previewDegrees)
        assertEquals(binding, decoded.scenes.single().binding)
    }

    @Test fun exactRequestAndStyleTextSurviveCacheWithoutRuntimeModelEnums() {
        val style = GeneratedStyleChoice("old-wardrobe", "old-art")
        val request = GeneratedRequestSnapshot("retired-model-id", "template-v1", "original exact prompt", mapOf("n" to "1"))
        val c = HistoryCandidate("id", "batch", "scene", ProviderId.QWEN, "retired-model-id", "user requirement", 8,
            null, 0, GeneratedReferenceAssets("generated-references/id/composition.png", "", 960, 1280,
                "retired-model-id", 1, requestSnapshot = request), 0, GeneratedPortraitPreferences(), "", 999, style)
        val decoded = VlmJson.decodeFromString<HistoryCandidate>(VlmJson.encodeToString(c))
        assertEquals(request, decoded.assets.requestSnapshot)
        assertEquals(style.key, decoded.style.key)
        assertEquals("retired-model-id", decoded.modelId)
        assertFalse(decoded.assets.hasPortrait)
        val choice = GeneratedPortraitStylePolicy.choice(17)
        val used = setOf(choice.key)
        repeat(100) {
            val next = GeneratedPortraitStylePolicy.nextUnusedKey(used, Random(it))!!
            assertNotEquals(choice.key, GeneratedPortraitStylePolicy.choice(next).key)
        }
    }

    @Test fun offlineHistoryUsesLocalImagesAndTemporaryAspectWithoutOverwritingPreferences() {
        val scene = SceneSnapshot("scene", 960, 1280, 1f, "source", "input")
        val candidate = GeneratedCandidate("id", "batch", scene, HistoricalImageModel(ProviderId.QWEN, "old"), "", 0, null,
            status = GeneratedCandidateStatus.READY, assets = GeneratedReferenceAssets("full", "person", 960, 1280, "old", 1))
        val ui = VlmUiState(settings = ModelSettings(mode = ModelMode.OFFLINE), scene = scene, stage = VlmStage.READY,
            photoPreferences = PhotoPreferences(aspectRatio = CaptureAspectRatio.NINE_SIXTEEN),
            historyAspectRatio = CaptureAspectRatio.THREE_FOUR,
            generated = GeneratedReferenceState(candidates = listOf(candidate), selectedId = candidate.id))
        assertTrue(ui.usesGeneratedReference)
        assertTrue(ui.generated.selected!!.canApply)
        assertEquals(CaptureAspectRatio.NINE_SIXTEEN, ui.photoPreferences.aspectRatio)
        assertEquals(3f / 4f, ui.captureRatio(false), .001f)
        assertEquals(CameraBackAction.CLOSE_HISTORY, cameraBackAction(ui.copy(historyDrawerVisible = true), true))
        assertFalse(ui.copy(stage = VlmStage.LIVE, cameraReady = true, historyDrawerVisible = true).canCapture)
        assertTrue(ui.copy(historySaving = true).historySwitchBlocked)
    }
}
