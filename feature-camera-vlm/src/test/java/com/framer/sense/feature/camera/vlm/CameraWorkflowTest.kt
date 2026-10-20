package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraWorkflowTest {
    private val scene = SceneSnapshot("scene", 480, 640, 1f, "photo", "model")
    private val plan = CompositionPlan("p1", "", ShotType.FULL, CropRect(), 1f,
        AvatarPlacement(), "", "", viewBinding = PhotoViewBinding("scan", "frame", 1))
    private val result = CompositionResult(listOf(plan), emptyList(), emptyList(), emptyList())
    private val ready = VlmUiState(stage = VlmStage.READY, scene = scene, result = result, selectedId = plan.id)
    private val applied = ready.copy(stage = VlmStage.LIVE, referencePlan = plan)
    private val pending = CameraStepStatus.PENDING
    private val running = CameraStepStatus.RUNNING
    private val complete = CameraStepStatus.COMPLETE

    @Test fun progressFollowsScanGenerateApplyAlignmentAndSuccessfulSave() {
        assertProgress(VlmUiState(), pending, pending, pending, pending, pending)
        assertProgress(VlmUiState(stage = VlmStage.SCANNING), running, pending, pending, pending, pending)
        assertProgress(VlmUiState(stage = VlmStage.SCAN_PROCESSING), running, pending, pending, pending, pending)
        assertProgress(ready.copy(stage = VlmStage.GENERATING), complete, running, pending, pending, pending)
        // 三张卡片默认选中第一张，用户还未应用。
        assertProgress(ready, complete, complete, pending, pending, pending)
        assertProgress(ready.copy(stage = VlmStage.MODIFYING), complete, complete, running, pending, pending)
        assertProgress(applied, complete, complete, complete, running, pending)
        val aligned = applied.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO))
        assertProgress(aligned, complete, complete, complete, complete, pending)
        assertProgress(aligned.copy(saving = true), complete, complete, complete, complete, running)
        assertProgress(aligned.copy(capturedWorkflow = aligned.workflowKey), complete, complete, complete, complete, complete)
    }

    @Test fun finishedScanStartsGenerationAtViewComparisonAndRetainsScanOnFailure() {
        val frame = ScanFrame("frame", "scan", 1, 1,
            ScanCameraConfig(1, 0, 1f, CropRect(), false), null, 1f, scene, 100)
        val scanned = VlmUiState(scanSnapshot = ScanSnapshot(ScanSession("scan", 0), listOf(frame), 10f))
        assertProgress(scanned.copy(stage = VlmStage.SCAN_REVIEW), complete, pending, pending, pending, pending)
        listOf(VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING).forEach { stage ->
            assertProgress(scanned.copy(stage = stage), complete, running, pending, pending, pending)
        }
        assertProgress(scanned.copy(stage = VlmStage.SCAN_REVIEW, error = "failed"),
            complete, pending, pending, pending, pending)
        assertProgress(VlmUiState(error = "cancelled"), pending, pending, pending, pending, pending)
    }

    @Test fun automaticAndManualAlignmentShareProgressAndLossReturnsToSearching() {
        listOf(BackgroundStatus.ALIGNED_AUTO, BackgroundStatus.ALIGNED_MANUAL).forEach { status ->
            assertEquals(complete, applied.copy(background = BackgroundGuidance(status)).workflowProgress()[3])
        }
        assertEquals(running, applied.workflowProgress()[3])
        assertEquals(complete, applied.copy(referencePlan = plan.copy(viewBinding = null)).workflowProgress()[3])
        assertProgress(applied.copy(compositionDetailsVisible = true), complete, complete, pending, pending, pending)
        assertProgress(ready.copy(referencePlan = null), complete, complete, pending, pending, pending)
    }

    @Test fun reviseIsGeneratingWhileApplyingIsSelecting() {
        val revising = ready.copy(stage = VlmStage.MODIFYING, directorProgress = DirectorProgress.PreparingRequest)
        assertProgress(revising, complete, running, pending, pending, pending)
        assertProgress(ready.copy(stage = VlmStage.FREEZING), running, pending, pending, pending, pending)
        assertProgress(ready.copy(stage = VlmStage.FROZEN, result = null, fromImportedImage = true),
            complete, pending, pending, pending, pending)
    }

    @Test fun captureCompletionCannotLeakToAnotherSceneOrPlanAndFailureDoesNotComplete() {
        val saved = applied.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO),
            capturedWorkflow = applied.workflowKey)
        assertEquals(complete, saved.workflowProgress().last())
        assertEquals(pending, saved.copy(scene = scene.copy(id = "next")).workflowProgress().last())
        assertEquals(pending, saved.copy(referencePlan = plan.copy(id = "p2")).workflowProgress().last())
        assertEquals(pending, saved.copy(referencePlan = plan.copy(revision = 1)).workflowProgress().last())
        assertEquals(pending, saved.copy(compositionBatchId = "another-round").workflowProgress().last())
        assertEquals(complete, saved.copy(cameraConfigVersion = 2, cameraEpoch = 2).workflowProgress().last())
        assertEquals(pending, applied.copy(error = "save failed").workflowProgress().last())
        // 普通快门仍可拍摄，但不能跳过未完成的流程节点点亮后续步骤。
        val direct = VlmUiState()
        assertProgress(direct.copy(capturedWorkflow = direct.workflowKey), pending, pending, pending, pending, pending)
    }

    @Test fun returningToAnyEarlierStepClearsEveryFollowingNodeEvenWithOldResults() {
        val saved = applied.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_MANUAL),
            capturedWorkflow = applied.workflowKey)
        assertProgress(saved.copy(stage = VlmStage.SCANNING), running, pending, pending, pending, pending)
        assertProgress(saved.copy(stage = VlmStage.GENERATING), complete, running, pending, pending, pending)
        assertProgress(saved.copy(stage = VlmStage.FROZEN), complete, pending, pending, pending, pending)
        assertProgress(saved.copy(stage = VlmStage.SCAN_REVIEW), complete, pending, pending, pending, pending)
        assertProgress(saved.copy(stage = VlmStage.READY), complete, complete, pending, pending, pending)
        assertProgress(saved.copy(compositionDetailsVisible = true), complete, complete, pending, pending, pending)
        assertProgress(saved.copy(background = BackgroundGuidance()), complete, complete, complete, running, pending)
        val reset = saved.withBackgroundGuidance(BackgroundGuidance())
        assertEquals(null, reset.capturedWorkflow)
        val realigned = reset.withBackgroundGuidance(BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO))
        assertProgress(realigned, complete, complete, complete, complete, pending)
    }

    private fun assertProgress(state: VlmUiState, vararg statuses: CameraStepStatus) {
        assertEquals(statuses.toList(), state.workflowProgress())
    }
}
