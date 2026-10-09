package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.SceneSnapshot
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Test

class FlowRestartPolicyTest {
    @Test fun generatingAndScanningCanBeRestartedButResourceOperationsCannot() {
        val scene = SceneSnapshot("scene",640,480,1f,"/scene","/input")
        val generating = VlmUiState(stage = VlmStage.GENERATING,scene = scene)
        assertTrue(generating.canGenerate)
        assertTrue(generating.canRestartScan)
        assertFalse(generating.copy(modelBusy = true).canGenerate)
        assertFalse(generating.copy(saving = true).canRestartScan)
        assertFalse(generating.copy(settingsVisible = true).canRestartScan)
        assertFalse(generating.copy(photoSample = PhotoSampleState("sample")).canRestartScan)
        assertTrue(VlmUiState(stage = VlmStage.SCANNING).canRestartScan)
        assertFalse(VlmUiState(stage = VlmStage.SCANNING,scene = scene).canGenerate)
        assertFalse(VlmUiState().canRestartScan)
    }
}
