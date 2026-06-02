package com.framer.sense.feature.camera.pytorch.ui

sealed interface CameraScreenState {
    data object Loading : CameraScreenState
    data object PermissionDenied : CameraScreenState
    data object Ready : CameraScreenState
    data class Error(val message: String) : CameraScreenState
}

data class CameraUiState(
    val screenState: CameraScreenState = CameraScreenState.Loading,
    val guideState: CameraGuideState = CameraGuideState.Initial,
    val captureStatus: PhotoCaptureStatus = PhotoCaptureStatus.Idle,
    val captureRequestId: Int = 0
)

sealed interface CameraIntent {
    data class PageStarted(val hasCameraPermission: Boolean) : CameraIntent
    data class RequestPermissionClicked(val hasCameraPermission: Boolean) : CameraIntent
    data class CameraPermissionResult(val granted: Boolean) : CameraIntent
    data class GuideStateChanged(val guideState: CameraGuideState) : CameraIntent
    data class CaptureClicked(val needsLegacyStoragePermission: Boolean) : CameraIntent
    data class LegacyStoragePermissionResult(val granted: Boolean) : CameraIntent
    data object PhotoSaved : CameraIntent
    data class PhotoCaptureFailed(val message: String?) : CameraIntent
    data class CameraFailed(val message: String?) : CameraIntent
}

sealed interface CameraEffect {
    data object RequestCameraPermission : CameraEffect
    data object RequestLegacyStoragePermission : CameraEffect
}
