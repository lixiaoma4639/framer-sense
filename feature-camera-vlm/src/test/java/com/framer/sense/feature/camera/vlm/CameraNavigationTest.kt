package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraNavigationTest {
    @Test fun backClosesInstructionBeforeExitingOrResumingCamera() {
        val state = VlmUiState(instructionEditorVisible = true)
        assertEquals(CameraBackAction.CLOSE_INSTRUCTION, cameraBackAction(state, true))
        assertEquals(CameraBackAction.CLOSE_INSTRUCTION, cameraBackAction(state.copy(stage = VlmStage.READY), true))
        assertEquals(CameraBackAction.EXIT, cameraBackAction(state.copy(instructionEditorVisible = false), true))
    }

    @Test fun liveExitsWhileExistingWorkflowBackBehaviorIsPreserved() {
        val live = VlmUiState()
        assertEquals(CameraBackAction.EXIT, cameraBackAction(live, true))
        assertEquals(CameraBackAction.NONE, cameraBackAction(live, false))
        listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING,
            VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.FREEZING).forEach { stage ->
            assertEquals(CameraBackAction.CANCEL, cameraBackAction(live.copy(stage = stage), true))
        }
        listOf(VlmStage.FROZEN, VlmStage.READY, VlmStage.SCAN_REVIEW).forEach { stage ->
            assertEquals(CameraBackAction.RESUME, cameraBackAction(live.copy(stage = stage), true))
        }
    }

    @Test fun dialogsHandleBackBeforeWorkflowAndSavingBlocksExit() {
        val busy = VlmUiState(stage = VlmStage.GENERATING)
        assertEquals(CameraBackAction.NONE, cameraBackAction(busy.copy(settingsVisible = true), true))
        assertEquals(CameraBackAction.NONE, cameraBackAction(busy.copy(photoSettingsVisible = true), true))
        assertEquals(CameraBackAction.CANCEL, cameraBackAction(busy.copy(compositionDetailsVisible = true), true))
        assertEquals(CameraBackAction.NONE, cameraBackAction(busy.copy(photoSample = PhotoSampleState("sample")), true))
        assertEquals(CameraBackAction.BLOCKED, cameraBackAction(VlmUiState(saving = true), true))
        val result = com.framer.sense.feature.camera.vlm.model.CompositionResult(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(CameraBackAction.COLLAPSE, cameraBackAction(VlmUiState(stage = VlmStage.READY,
            result = result, compositionDetailsVisible = true), true))
        assertEquals(CameraBackAction.NONE, cameraBackAction(VlmUiState(stage = VlmStage.SCAN_REVIEW,
            compositionDetailsVisible = true), true))
    }
}
