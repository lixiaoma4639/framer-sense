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
        assertFalse(generating.canRestartScan)
        assertTrue(generating.canRestartFlow)
        assertFalse(generating.copy(modelBusy = true).canGenerate)
        assertFalse(generating.copy(saving = true).canRestartFlow)
        assertFalse(generating.copy(modelBusy = true).canRestartFlow)
        assertFalse(generating.copy(settingsVisible = true).canRestartFlow)
        assertFalse(generating.copy(photoSample = PhotoSampleState("sample")).canRestartFlow)
        assertFalse(VlmUiState(stage = VlmStage.SCANNING).canRestartScan)
        assertTrue(VlmUiState(stage = VlmStage.SCANNING).canRestartFlow)
        assertFalse(VlmUiState(stage = VlmStage.SCANNING,scene = scene).canGenerate)
        assertFalse(VlmUiState().canRestartScan)
    }
}
