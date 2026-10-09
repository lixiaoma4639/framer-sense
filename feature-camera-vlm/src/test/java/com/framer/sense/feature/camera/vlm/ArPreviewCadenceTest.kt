package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.ar.ArPreviewCadence
import com.framer.sense.feature.camera.vlm.ui.VlmStage
import com.framer.sense.feature.camera.vlm.ui.VlmUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ArPreviewCadenceTest {
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
