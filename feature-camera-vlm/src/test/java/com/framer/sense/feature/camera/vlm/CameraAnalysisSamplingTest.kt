package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Test

/** 验证送入 CameraX sampling 回调的状态，避免云端应用后只有对齐界面、没有实时分析帧。 */
class CameraAnalysisSamplingTest {
    private val scene = SceneSnapshot("scene", 480, 640, 1f, "source.jpg", "input.jpg")
    private val binding = PhotoViewBinding("scan", "scene", 1)
    private val assets = GeneratedReferenceAssets("full.png", "person.png", 480, 640, "model", 1)

    @Test fun generatedReferenceStartsSamplingOnlyAfterApplicationForBothBrandsAndCaptureSources() {
        val models: List<ImageGenerationModel> = SeedreamModel.entries + QwenImageModel.entries
        models.forEach { model ->
            // 单张照片、扫描选景及本地历史恢复，均以保存的原图来源进行对齐。
            listOf(binding, binding.copy(scanId = "photo:scene")).forEach { source ->
                val candidate = GeneratedCandidate("candidate", "batch", scene, model, "", 0, source,
                    status = GeneratedCandidateStatus.READY, assets = assets)
                val selecting = VlmUiState(stage = VlmStage.READY, cameraReady = true, scene = scene,
                    generated = GeneratedReferenceState(candidates = listOf(candidate), selectedId = candidate.id))
                assertFalse(selecting.shouldSampleCameraAnalysis)
                val applied = selecting.copy(stage = VlmStage.LIVE,
                    generated = selecting.generated.copy(applied = candidate))
                assertNull(applied.referencePlan)
                assertEquals(candidate.backgroundTarget(), applied.activeBackgroundTarget)
                assertTrue(applied.shouldSampleCameraAnalysis)
                assertFalse(applied.copy(cameraReady = false).shouldSampleCameraAnalysis)
                assertFalse(applied.copy(generated = applied.generated.copy(applied = null)).shouldSampleCameraAnalysis)
                assertFalse(applied.copy(generated = applied.generated.copy(
                    applied = candidate.copy(viewBinding = null))).shouldSampleCameraAnalysis)
                // 已确认后仍继续采样，沿用原有丢失匹配和重新对齐策略。
                assertTrue(applied.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO))
                    .shouldSampleCameraAnalysis)
            }
        }
    }

    @Test fun legacyReferenceStillSamplesAndStopsWhenLeavingLivePreview() {
        val plan = CompositionPlan("plan", "", ShotType.FULL, CropRect(), 1f,
            AvatarPlacement(), "", "", viewBinding = binding)
        val applied = VlmUiState(stage = VlmStage.LIVE, cameraReady = true, scene = scene, referencePlan = plan)
        assertTrue(applied.shouldSampleCameraAnalysis)
        assertFalse(applied.copy(referencePlan = plan.copy(viewBinding = null)).shouldSampleCameraAnalysis)
        listOf(VlmStage.FROZEN, VlmStage.GENERATING, VlmStage.READY).forEach { stage ->
            assertFalse(applied.copy(stage = stage).shouldSampleCameraAnalysis)
        }
    }

    @Test fun scanningKeepsItsSessionReadyAndPauseGates() {
        val scanning = VlmUiState(stage = VlmStage.SCANNING, cameraReady = true,
            scanSession = ScanSession("scan", 0))
        assertTrue(scanning.shouldSampleCameraAnalysis)
        assertFalse(scanning.copy(scanSession = null).shouldSampleCameraAnalysis)
        assertFalse(scanning.copy(cameraReady = false).shouldSampleCameraAnalysis)
        assertFalse(scanning.copy(scanProgress = ScanProgress(paused = true)).shouldSampleCameraAnalysis)
        assertFalse(scanning.copy(stage = VlmStage.LIVE).shouldSampleCameraAnalysis)
    }
}
