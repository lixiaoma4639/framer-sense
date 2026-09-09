package com.framer.sense.feature.camera.pytorch.v2.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraV2ViewModelTest {

    @Test
    fun entered_withPermission_entersStreamingAndStoresProfile() {
        val viewModel = CameraV2ViewModel()

        viewModel.onIntent(CameraV2Intent.Entered(true, heightCm = 182, weightKg = 74))

        assertEquals(CameraV2ScreenState.Streaming, viewModel.state.value.screenState)
        assertEquals(182, viewModel.state.value.bodyProfile.safeHeightCm)
        assertEquals(74, viewModel.state.value.bodyProfile.safeWeightKg)
    }

    @Test
    fun entered_withoutPermission_entersPermissionRequired() {
        val viewModel = CameraV2ViewModel()

        viewModel.onIntent(CameraV2Intent.Entered(false, heightCm = 170, weightKg = 60))

        assertEquals(CameraV2ScreenState.PermissionRequired, viewModel.state.value.screenState)
    }

    @Test
    fun permissionButton_withoutPermission_requestsCameraPermission() = runTest {
        val viewModel = CameraV2ViewModel()
        val effect = async { viewModel.effects.first() }

        viewModel.onIntent(CameraV2Intent.PermissionButtonClicked(false))

        assertEquals(CameraV2Effect.RequestCameraPermission, effect.await())
    }

    @Test
    fun capturePressed_withoutStorageNeed_incrementsCaptureToken() {
        val viewModel = CameraV2ViewModel()

        viewModel.onIntent(CameraV2Intent.CapturePressed(needsLegacyStoragePermission = false))

        assertEquals(PhotoV2CaptureState.Saving, viewModel.state.value.captureState)
        assertEquals(1, viewModel.state.value.captureToken)
    }

    @Test
    fun cameraIssue_entersFailureState() {
        val viewModel = CameraV2ViewModel()

        viewModel.onIntent(CameraV2Intent.CameraIssue(IllegalStateException("camera unavailable")))

        assertTrue(viewModel.state.value.screenState is CameraV2ScreenState.Failure)
    }

    @Test
    fun onnxLoadStateChanged_updatesMviState() {
        val viewModel = CameraV2ViewModel()

        viewModel.onIntent(CameraV2Intent.OnnxLoadStateChanged(OnnxSessionLoadState.LOADING))

        assertEquals(OnnxSessionLoadState.LOADING, viewModel.state.value.onnxLoadState)
    }

    @Test
    fun nextTargetPose_cyclesCompatibleCandidatesAndLocksSelection() {
        val viewModel = CameraV2ViewModel()
        val guide = CameraV2CompositionEngine().buildGuide(
            CameraV2Analysis(
                people = emptyList(),
                objects = emptyList(),
                personSegments = emptyList(),
                pose = PoseEstimate.Empty,
                semanticScene = SemanticScene("street", SceneGroup.URBAN, 0.9f),
                luminance = 128.0,
                modelAvailability = ModelAvailability(true, false, wholeBodyPoseReady = true)
            ),
            BodyProfile(170, 60)
        )
        viewModel.onIntent(CameraV2Intent.GuideProduced(guide))
        val firstId = viewModel.state.value.guide.targetPose?.id

        viewModel.onIntent(CameraV2Intent.NextTargetPose)

        assertTrue(viewModel.state.value.guide.isPoseSelectionLocked)
        assertTrue(viewModel.state.value.guide.targetPose?.id != firstId)
        assertTrue(viewModel.state.value.guide.targetPose in viewModel.state.value.guide.poseCandidates)
    }
}
