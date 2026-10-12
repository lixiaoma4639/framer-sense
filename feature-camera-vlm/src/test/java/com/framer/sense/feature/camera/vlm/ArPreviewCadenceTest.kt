package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.ArPreviewCadence
import com.framer.sense.feature.camera.vlm.ar.ArUiState
import com.framer.sense.feature.camera.vlm.ar.ArSource
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.VlmStage
import com.framer.sense.feature.camera.vlm.ui.VlmUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ArPreviewCadenceTest {
    @Test fun missingGroundKeepsArUpdatesFastWhileHiddenRenderingRemainsThrottled() {
        assertEquals(28L, ArPreviewCadence.delayMillis(5_000_000, false, true))
        assertEquals(95L, ArPreviewCadence.delayMillis(5_000_000, false, false))
        assertTrue(ArPreviewCadence.shouldRender(0, null, false))
        assertFalse(ArPreviewCadence.shouldRender(33_000_000, 0, false))
        assertTrue(ArPreviewCadence.shouldRender(100_000_000, 0, false))
        assertTrue(ArPreviewCadence.shouldRender(33_000_000, 0, true))
    }

    @Test fun waitingForGroundShowsLiveViewWithoutChangingSelectedCompositionOrResult() {
        val state = VlmUiState(stage = VlmStage.READY, cameraMode = CameraMode.AR,
            ar = ArUiState(ArSource.SHARED_CAMERA), selectedId = "selected",
            result = CompositionResult(emptyList(), emptyList(), emptyList(), emptyList()),
            spatialScaleStatus = SpatialScaleStatus.WAITING_GROUND)
        assertTrue(state.needsGroundObservation)
        assertTrue(state.requiresLivePreview)
        val measured = state.copy(hasReliableArGround = true, spatialScaleStatus = SpatialScaleStatus.CALIBRATED)
        assertFalse(measured.needsGroundObservation)
        assertEquals(state.selectedId, measured.selectedId)
        assertSame(state.result, measured.result)
        assertFalse(state.copy(cameraMode = CameraMode.NORMAL).needsGroundObservation)
        assertFalse(state.copy(stage = VlmStage.GENERATING).needsGroundObservation)
    }

    @Test fun modelLoadingNeverLowersVisiblePreviewRate() {
        for (stage in listOf(VlmStage.LIVE,VlmStage.SCANNING,VlmStage.FREEZING)) {
            assertTrue(VlmUiState(stage=stage,modelBusy=true).requiresLivePreview)
            assertTrue(VlmUiState(stage=stage,modelBusy=false).requiresLivePreview)
        }
        for (stage in listOf(VlmStage.GENERATING,VlmStage.MODIFYING,VlmStage.VIEW_SELECTING))
            assertFalse(VlmUiState(stage=stage,modelBusy=true).requiresLivePreview)
    }
    @Test fun processingTimeConsumesThePreviewPeriod() {
        assertEquals(28L, ArPreviewCadence.delayMillis(5_000_000L, true))
        assertEquals(3L, ArPreviewCadence.delayMillis(30_000_000L, true))
        assertEquals(1L, ArPreviewCadence.delayMillis(90_000_000L, true))
    }
    @Test fun analysisModeKeepsTheReducedRenderingRateWithoutExtraFullPeriodDelay() {
        assertEquals(60L, ArPreviewCadence.delayMillis(40_000_000L, false))
        assertEquals(1L, ArPreviewCadence.delayMillis(200_000_000L, false))
    }
}
