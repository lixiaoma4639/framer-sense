package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.VlmUiState
import com.framer.sense.feature.camera.vlm.ui.VlmStage
import org.junit.Assert.*
import org.junit.Test

class CameraFramingTest {
    @Test fun cropKeepsAspectCenterAndNormalizedCoordinatesInBothOrientations() {
        CaptureAspectRatio.entries.forEach { aspect ->
            listOf(false, true).forEach { landscape ->
                listOf(4000 to 3000, 3000 to 4000, 640 to 480).forEach { (width, height) ->
                    val crop = aspect.centerCrop(width, height, landscape)
                    val w = (crop.right - crop.left) * width
                    val h = (crop.bottom - crop.top) * height
                    assertEquals(aspect.ratio(landscape), w / h, .00001f)
                    assertEquals(.5f, (crop.left + crop.right) / 2, .00001f)
                    assertEquals(.5f, (crop.top + crop.bottom) / 2, .00001f)
                    assertTrue(crop.left >= 0 && crop.top >= 0 && crop.right <= 1 && crop.bottom <= 1)

                }
            }
        }
    }

    @Test fun viewportFitsCompactPortraitAndLandscapeWithoutChangingRatio() {
        listOf(240f to 320f, 400f to 600f, 720f to 180f).forEach { (width, height) ->
            CaptureAspectRatio.entries.forEach { aspect ->
                val ratio = aspect.ratio(width > height)
                val size = fitCaptureViewport(width, height, ratio)
                assertTrue(size.width <= width + .0001f && size.height <= height + .0001f)
                assertEquals(ratio, size.width / size.height, .00001f)
            }
        }
    }

    @Test fun fullWidthViewportKeepsBothRatiosEvenWhenAvailableHeightIsSmall() {
        listOf(240f, 400f, 720f).forEach { width ->
            CaptureAspectRatio.entries.forEach { aspect ->
                val ratio = aspect.ratio(false)
                val size = widthCaptureViewport(width, ratio)
                assertEquals(width, size.width, 0f)
                assertEquals(width / ratio, size.height, .0001f)
            }
        }
    }

    @Test fun knownLandscapeImageCropsToPortraitWithoutStretchingScene() {
        val crop = CaptureAspectRatio.NINE_SIXTEEN.centerCrop(4000, 3000, false)
        assertEquals(.2890625f, crop.left, .00001f)
        assertEquals(.7109375f, crop.right, .00001f)
        assertEquals(0f, crop.top, 0f)
        assertEquals(1f, crop.bottom, 0f)
        // 原图中间四分之一处的物体，在裁切后应位于画面左侧而非镜像右侧。
        val originalX = .4f
        val projectedX = (originalX - crop.left) / (crop.right - crop.left)
        assertEquals(.26296297f, projectedX, .00001f)
    }

    @Test fun presetsUseOneTwoThreeAndActualMaximumWithinDeviceRange() {
        assertEquals(listOf(1f, 2f, 3f, 8f), CameraZoomPolicy.presets(1f, 8f))
        assertEquals(listOf(1f, 2f, 3f, 10f), CameraZoomPolicy.presets(.6f, 10f))
        assertEquals(listOf(1f, 2f, 2.5f), CameraZoomPolicy.presets(1f, 2.5f))
        assertEquals(listOf(1f, 2f), CameraZoomPolicy.presets(1f, 2f))
        assertEquals(3.5f, CameraZoomPolicy.lensRatio(7f, 4f, 3f, 4f, 8f, 6f)!!, .0001f)
        assertNull(CameraZoomPolicy.lensRatio(4f, 0f, 3f, 4f, 8f, 6f))
        assertEquals(listOf(1f, 2f, 3f, 30f), CameraZoomPolicy.presets(1f, 30f))
        assertEquals(listOf(1f), CameraZoomPolicy.presets(1f, 1f))
        assertEquals(10f, CameraZoomPolicy.clamp(20f, .6f, 10f), 0f)
        assertEquals(.6f, CameraZoomPolicy.clamp(.1f, .6f, 10f), 0f)
        assertEquals(.6f, CameraZoomPolicy.clamp(Float.NaN, .6f, 10f), 0f)
    }

    @Test fun maximumIsNotDuplicatedOrRoundedAndOutOfRangeStopsAreOmitted() {
        assertEquals(listOf(1f, 2f, 3f), CameraZoomPolicy.presets(1f, 3f))
        assertEquals(listOf(1f, 2f, 3f, 3.49f), CameraZoomPolicy.presets(.54f, 3.49f))
        assertEquals(listOf(2f, 3f, 6f), CameraZoomPolicy.presets(2f, 6f))
        assertEquals(listOf(.8f), CameraZoomPolicy.presets(.5f, .8f))
    }

    @Test fun liftingSecondFingerCannotProduceClickAndNextSingleTapWorks() {
        val gate = CameraGestureGate()
        gate.begin(); gate.pointers(1); assertTrue(gate.canClick())
        gate.pointers(2); gate.pointers(1); assertFalse(gate.canClick())
        gate.begin(); gate.pointers(1); assertTrue(gate.canClick())
    }

    @Test fun framingAndShutterAreLockedDuringScanInferenceAndSave() {
        val normal = VlmUiState(cameraReady = true)
        assertEquals(CameraMode.NORMAL, normal.cameraMode)
        assertEquals(CaptureAspectRatio.THREE_FOUR, normal.photoPreferences.aspectRatio)
        assertTrue(normal.canCapture); assertTrue(normal.canZoom)
        listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING,
            VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING).forEach { stage ->
            val state = normal.copy(stage = stage)
            assertFalse(state.framingUnlocked); assertFalse(state.canCapture); assertFalse(state.canZoom)
        }
        assertFalse(normal.copy(saving = true).framingUnlocked)
        assertFalse(normal.copy(cameraMode = CameraMode.AR).canZoom)
        assertFalse(normal.copy(photoSettingsVisible = true).canCapture)
        assertFalse(normal.copy(modelBusy = true).canCapture)
    }
}
