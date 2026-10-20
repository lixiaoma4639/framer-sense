package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Test

class CameraGalleryPolicyTest {
    @Test fun galleryCannotInterruptScanningGenerationSavingOrInput() {
        val state = VlmUiState()
        assertTrue(state.canOpenGallery)
        listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING,
            VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING).forEach { stage ->
            assertFalse(state.copy(stage = stage).canOpenGallery)
        }
        assertFalse(state.copy(saving = true).canOpenGallery)
        assertFalse(state.copy(modelBusy = true).canOpenGallery)
        assertFalse(state.copy(instructionEditorVisible = true).canOpenGallery)
        assertFalse(state.copy(settingsVisible = true).canOpenGallery)
        assertFalse(state.copy(photoSettingsVisible = true).canOpenGallery)
        assertFalse(state.copy(photoSample = PhotoSampleState("sample")).canOpenGallery)
    }
}
