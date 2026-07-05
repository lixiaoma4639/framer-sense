package com.framer.sense.feature.camera.pytorch.v2.ui

sealed interface CameraV2ScreenState {
    data object Booting : CameraV2ScreenState
    data object PermissionRequired : CameraV2ScreenState
    data object Streaming : CameraV2ScreenState
    data class Failure(val hint: CameraV2Hint, val detail: String? = null) : CameraV2ScreenState
}

data class CameraV2State(
    val screenState: CameraV2ScreenState = CameraV2ScreenState.Booting,
    val guide: CameraV2Guide = CameraV2Guide.initial(),
    val captureState: PhotoV2CaptureState = PhotoV2CaptureState.Idle,
    val captureToken: Int = 0,
    val bodyProfile: BodyProfile = BodyProfile(
        heightCm = BodyProfile.DEFAULT_HEIGHT_CM,
        weightKg = BodyProfile.DEFAULT_WEIGHT_KG
    )
)

sealed interface CameraV2Intent {
    data class Entered(
        val hasCameraPermission: Boolean,
        val heightCm: Int,
        val weightKg: Int
    ) : CameraV2Intent

    data class PermissionButtonClicked(val hasCameraPermission: Boolean) : CameraV2Intent
    data class CameraPermissionResult(val granted: Boolean) : CameraV2Intent
    data class GuideProduced(val guide: CameraV2Guide) : CameraV2Intent
    data class CapturePressed(val needsLegacyStoragePermission: Boolean) : CameraV2Intent
    data class LegacyStoragePermissionResult(val granted: Boolean) : CameraV2Intent
    data object PhotoSaved : CameraV2Intent
    data class PhotoSaveFailed(val throwable: Throwable?) : CameraV2Intent
    data class CameraIssue(val throwable: Throwable?) : CameraV2Intent
    data class BodyProfileChanged(val heightCm: Int, val weightKg: Int) : CameraV2Intent
}

sealed interface CameraV2Effect {
    data object RequestCameraPermission : CameraV2Effect
    data object RequestLegacyStoragePermission : CameraV2Effect
}
