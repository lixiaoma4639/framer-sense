package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Test

class ScanPreviewStateTest {
    private val scene = SceneSnapshot("frame", 640, 480, 1f, "/scene", "/input")
    private val frame = ScanFrame("frame", "scan", 0, 0, ScanCameraConfig(1, 0, 1f, CropRect(), false), null, 1f, scene, 1)
    private val snapshot = ScanSnapshot(ScanSession("scan", 0, 1), listOf(frame), 0f)

    @Test fun unselectedScanCanBeDisplayedWithoutBecomingCompositionInput() {
        for (stage in listOf(VlmStage.SCAN_REVIEW, VlmStage.VIEW_SELECTING, VlmStage.SCAN_PROCESSING)) {
            val state = VlmUiState(stage = stage, scanSnapshot = snapshot)
            assertEquals(scene, state.displayScene)
            assertNull(state.scene); assertNull(state.selectedFrameId); assertNull(state.result); assertNull(state.referencePlan)
        }
        assertNull(VlmUiState(stage = VlmStage.LIVE, scanSnapshot = snapshot).displayScene)
        assertNull(VlmUiState(stage = VlmStage.SCAN_REVIEW).displayScene)
    }

    @Test fun confirmedSceneTakesPriorityOverProvisionalPreview() {
        val selected = scene.copy(id = "chosen", imagePath = "/chosen")
        val state = VlmUiState(stage = VlmStage.GENERATING, scene = selected, scanSnapshot = snapshot, selectedFrameId = "chosen")
        assertEquals(selected, state.displayScene)
        assertEquals("chosen", state.selectedFrameId)
    }
}
