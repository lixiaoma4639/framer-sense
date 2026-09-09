package com.framer.sense.feature.camera.pytorch.v2.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CameraV2ViewModel : ViewModel() {

    private val _state = MutableStateFlow(CameraV2State())
    val state: StateFlow<CameraV2State> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CameraV2Effect>(extraBufferCapacity = 1)
    val effects: SharedFlow<CameraV2Effect> = _effects.asSharedFlow()

    fun onIntent(intent: CameraV2Intent) {
        when (intent) {
            is CameraV2Intent.Entered -> onEntered(intent)
            is CameraV2Intent.PermissionButtonClicked -> onPermissionButtonClicked(intent.hasCameraPermission)
            is CameraV2Intent.CameraPermissionResult -> onCameraPermissionResult(intent.granted)
            is CameraV2Intent.GuideProduced -> onGuideProduced(intent.guide)
            CameraV2Intent.NextTargetPose -> onNextTargetPose()
            is CameraV2Intent.OnnxLoadStateChanged -> onOnnxLoadStateChanged(intent.state)
            is CameraV2Intent.CapturePressed -> onCapturePressed(intent.needsLegacyStoragePermission)
            is CameraV2Intent.LegacyStoragePermissionResult -> onLegacyStoragePermissionResult(intent.granted)
            CameraV2Intent.PhotoSaved -> onPhotoSaved()
            is CameraV2Intent.PhotoSaveFailed -> onPhotoSaveFailed()
            is CameraV2Intent.CameraIssue -> onCameraIssue(intent.throwable)
            is CameraV2Intent.BodyProfileChanged -> onBodyProfileChanged(intent.heightCm, intent.weightKg)
        }
    }

    private fun onEntered(intent: CameraV2Intent.Entered) {
        val profile = BodyProfile(intent.heightCm, intent.weightKg)
        _state.update {
            it.copy(
                screenState = if (intent.hasCameraPermission) {
                    CameraV2ScreenState.Streaming
                } else {
                    CameraV2ScreenState.PermissionRequired
                },
                bodyProfile = profile,
                guide = CameraV2Guide.initial(profile)
            )
        }
    }

    private fun onPermissionButtonClicked(hasCameraPermission: Boolean) {
        if (hasCameraPermission) {
            _state.update { it.copy(screenState = CameraV2ScreenState.Streaming) }
        } else {
            _effects.tryEmit(CameraV2Effect.RequestCameraPermission)
        }
    }

    private fun onCameraPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                screenState = if (granted) {
                    CameraV2ScreenState.Streaming
                } else {
                    CameraV2ScreenState.PermissionRequired
                }
            )
        }
    }

    private fun onGuideProduced(guide: CameraV2Guide) {
        _state.update {
            val retainedPose = it.guide.targetPose?.takeIf { current ->
                guide.poseCandidates.any { candidate -> candidate.id == current.id }
            }
            val selectedPose = retainedPose ?: guide.targetPose
            val stableGuide = selectedPose?.let { pose ->
                guide.withTargetPose(
                    targetPose = pose,
                    profile = it.bodyProfile,
                    isSelectionLocked = retainedPose != null || guide.isPoseSelectionLocked
                )
            } ?: guide
            it.copy(
                screenState = CameraV2ScreenState.Streaming,
                guide = stableGuide
            )
        }
    }

    private fun onNextTargetPose() {
        _state.update { state ->
            val candidates = state.guide.poseCandidates
            if (candidates.size <= 1) return@update state
            val currentIndex = candidates.indexOfFirst { it.id == state.guide.targetPose?.id }
            val next = candidates[(currentIndex + 1).floorMod(candidates.size)]
            state.copy(guide = state.guide.withTargetPose(next, state.bodyProfile, isSelectionLocked = true))
        }
    }

    private fun onOnnxLoadStateChanged(state: OnnxSessionLoadState) {
        _state.update { it.copy(onnxLoadState = state) }
    }

    private fun onCapturePressed(needsLegacyStoragePermission: Boolean) {
        if (needsLegacyStoragePermission) {
            _effects.tryEmit(CameraV2Effect.RequestLegacyStoragePermission)
        } else {
            startCapture()
        }
    }

    private fun onLegacyStoragePermissionResult(granted: Boolean) {
        if (granted) {
            startCapture()
        } else {
            _state.update {
                it.copy(captureState = PhotoV2CaptureState.Error(CameraV2Hint.STORAGE_PERMISSION_NEEDED))
            }
        }
    }

    private fun startCapture() {
        _state.update {
            it.copy(
                captureState = PhotoV2CaptureState.Saving,
                captureToken = it.captureToken + 1
            )
        }
    }

    private fun onPhotoSaved() {
        _state.update { it.copy(captureState = PhotoV2CaptureState.Saved) }
    }

    private fun onPhotoSaveFailed() {
        _state.update {
            it.copy(captureState = PhotoV2CaptureState.Error(CameraV2Hint.CAPTURE_FAILED))
        }
    }

    private fun onCameraIssue(throwable: Throwable?) {
        _state.update {
            it.copy(
                screenState = CameraV2ScreenState.Failure(
                    hint = CameraV2Hint.CAMERA_ERROR,
                    detail = throwable?.message
                )
            )
        }
    }

    private fun onBodyProfileChanged(heightCm: Int, weightKg: Int) {
        val profile = BodyProfile(heightCm, weightKg)
        _state.update {
            it.copy(
                bodyProfile = profile,
                guide = it.guide.targetPose?.let { targetPose ->
                    it.guide.withTargetPose(targetPose, profile, it.guide.isPoseSelectionLocked)
                } ?: it.guide.copy(
                    virtualHuman = VirtualHumanProjector().project(
                        targetBounds = it.guide.targetBounds,
                        profile = profile,
                        template = it.guide.virtualHuman.template
                    )
                )
            )
        }
    }
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
