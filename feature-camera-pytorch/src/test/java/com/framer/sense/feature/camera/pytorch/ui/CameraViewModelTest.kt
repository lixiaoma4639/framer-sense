package com.framer.sense.feature.camera.pytorch.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewModelTest {

    @Test
    fun uiState_defaultsToLoading() {
        val viewModel = CameraViewModel()

        assertEquals(CameraScreenState.Loading, viewModel.uiState.value.screenState)
        assertEquals(PhotoCaptureStatus.Idle, viewModel.uiState.value.captureStatus)
        assertEquals(0, viewModel.uiState.value.captureRequestId)
    }

    @Test
    fun pageStarted_withCameraPermission_entersReady() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.PageStarted(hasCameraPermission = true))

        assertEquals(CameraScreenState.Ready, viewModel.uiState.value.screenState)
    }

    @Test
    fun pageStarted_withoutCameraPermission_entersPermissionDenied() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.PageStarted(hasCameraPermission = false))

        assertEquals(CameraScreenState.PermissionDenied, viewModel.uiState.value.screenState)
    }

    @Test
    fun requestPermissionClicked_withCameraPermission_entersReady() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.RequestPermissionClicked(hasCameraPermission = true))

        assertEquals(CameraScreenState.Ready, viewModel.uiState.value.screenState)
    }

    @Test
    fun requestPermissionClicked_withoutCameraPermission_requestsCameraPermission() = runTest {
        val viewModel = CameraViewModel()
        val effect = async { viewModel.effects.first() }

        viewModel.onIntent(CameraIntent.RequestPermissionClicked(hasCameraPermission = false))

        assertEquals(CameraEffect.RequestCameraPermission, effect.await())
    }

    @Test
    fun cameraPermissionResult_updatesScreenState() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.CameraPermissionResult(granted = true))
        assertEquals(CameraScreenState.Ready, viewModel.uiState.value.screenState)

        viewModel.onIntent(CameraIntent.CameraPermissionResult(granted = false))
        assertEquals(CameraScreenState.PermissionDenied, viewModel.uiState.value.screenState)
    }

    @Test
    fun guideStateChanged_updatesGuideStateAndEntersReady() {
        val viewModel = CameraViewModel()
        val guideState = CameraGuideState.Initial.copy(message = "请向右移动手机")

        viewModel.onIntent(CameraIntent.GuideStateChanged(guideState))

        assertEquals(CameraScreenState.Ready, viewModel.uiState.value.screenState)
        assertEquals(guideState, viewModel.uiState.value.guideState)
    }

    @Test
    fun captureClicked_withoutLegacyStoragePermission_startsSavingAndIncrementsRequestId() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.CaptureClicked(needsLegacyStoragePermission = false))

        assertEquals(PhotoCaptureStatus.Saving, viewModel.uiState.value.captureStatus)
        assertEquals(1, viewModel.uiState.value.captureRequestId)
    }

    @Test
    fun captureClicked_withLegacyStoragePermission_requestsStoragePermission() = runTest {
        val viewModel = CameraViewModel()
        val effect = async { viewModel.effects.first() }

        viewModel.onIntent(CameraIntent.CaptureClicked(needsLegacyStoragePermission = true))

        assertEquals(CameraEffect.RequestLegacyStoragePermission, effect.await())
    }

    @Test
    fun legacyStoragePermissionResult_updatesCaptureState() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.LegacyStoragePermissionResult(granted = false))
        assertTrue(viewModel.uiState.value.captureStatus is PhotoCaptureStatus.Error)

        viewModel.onIntent(CameraIntent.LegacyStoragePermissionResult(granted = true))
        assertEquals(PhotoCaptureStatus.Saving, viewModel.uiState.value.captureStatus)
        assertEquals(1, viewModel.uiState.value.captureRequestId)
    }

    @Test
    fun photoSaved_updatesCaptureStatus() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.PhotoSaved)

        assertEquals(PhotoCaptureStatus.Saved, viewModel.uiState.value.captureStatus)
    }

    @Test
    fun photoCaptureFailed_updatesCaptureError() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.PhotoCaptureFailed("保存失败"))

        assertEquals(
            PhotoCaptureStatus.Error("保存失败"),
            viewModel.uiState.value.captureStatus
        )
    }

    @Test
    fun cameraFailed_updatesScreenError() {
        val viewModel = CameraViewModel()

        viewModel.onIntent(CameraIntent.CameraFailed("无法启动 ONNX 相机引导"))

        assertEquals(
            CameraScreenState.Error("无法启动 ONNX 相机引导"),
            viewModel.uiState.value.screenState
        )
    }
}
