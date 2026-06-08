package com.framer.sense.feature.camera.pytorch.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class CameraViewModel @Inject constructor() : ViewModel() {

    // MutableStateFlow：保存当前状态 的状态流
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // MutableSharedFlow：发送一次性事件 的状态流, 1默认不需要初始值, 2默认不保存最新值,
    // 3适合发送一次性动作, 4新观察者不会自动拿到之前的事件
    // extraBufferCapacity = 1,表示可以额外缓存 1 个事件，降低事件发出时没有立即被消费导致丢失的概率。
    private val _effects = MutableSharedFlow<CameraEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<CameraEffect> = _effects.asSharedFlow()

    fun onIntent(intent: CameraIntent) {
        when (intent) {
            is CameraIntent.PageStarted -> onPageStarted(intent.hasCameraPermission)
            is CameraIntent.RequestPermissionClicked -> onRequestPermissionClicked(
                intent.hasCameraPermission
            )
            is CameraIntent.CameraPermissionResult -> onCameraPermissionResult(intent.granted)
            is CameraIntent.GuideStateChanged -> onGuideStateChanged(intent.guideState)
            is CameraIntent.CaptureClicked -> onCaptureClicked(intent.needsLegacyStoragePermission)
            is CameraIntent.LegacyStoragePermissionResult -> onLegacyStoragePermissionResult(
                intent.granted
            )
            CameraIntent.PhotoSaved -> onPhotoSaved()
            is CameraIntent.PhotoCaptureFailed -> onPhotoCaptureFailed(intent.message)
            is CameraIntent.CameraFailed -> onCameraFailed(intent.message)
        }
    }

    private fun onPageStarted(hasCameraPermission: Boolean) {
        _uiState.update { state ->
            state.copy(
                screenState = if (hasCameraPermission) {
                    CameraScreenState.Ready
                } else {
                    CameraScreenState.PermissionDenied
                }
            )
        }
    }

    private fun onRequestPermissionClicked(hasCameraPermission: Boolean) {
        if (hasCameraPermission) {
            _uiState.update { it.copy(screenState = CameraScreenState.Ready) }
        } else {
            //_effects.tryEmit 适合发送一次性动作,通知 UI 去弹一次系统权限请求框。
            _effects.tryEmit(CameraEffect.RequestCameraPermission)
        }
    }

    private fun onCameraPermissionResult(granted: Boolean) {
        _uiState.update { state ->
            state.copy(
                screenState = if (granted) {
                    CameraScreenState.Ready
                } else {
                    CameraScreenState.PermissionDenied
                }
            )
        }
    }

    private fun onGuideStateChanged(guideState: CameraGuideState) {
        _uiState.update { state ->
            state.copy(
                screenState = CameraScreenState.Ready,
                guideState = guideState
            )
        }
    }

    private fun onCaptureClicked(needsLegacyStoragePermission: Boolean) {
        if (needsLegacyStoragePermission) {
            _effects.tryEmit(CameraEffect.RequestLegacyStoragePermission)
        } else {
            startPhotoCapture()
        }
    }

    private fun onLegacyStoragePermissionResult(granted: Boolean) {
        if (granted) {
            startPhotoCapture()
        } else {
            _uiState.update {
                it.copy(
                    captureStatus = PhotoCaptureStatus.Error("需要存储权限才能保存到系统相册")
                )
            }
        }
    }

    private fun startPhotoCapture() {
        _uiState.update { state ->
            state.copy(
                captureStatus = PhotoCaptureStatus.Saving,
                captureRequestId = state.captureRequestId + 1
            )
        }
    }

    private fun onPhotoSaved() {
        _uiState.update { it.copy(captureStatus = PhotoCaptureStatus.Saved) }
    }

    private fun onPhotoCaptureFailed(message: String?) {
        _uiState.update {
            it.copy(captureStatus = PhotoCaptureStatus.Error(message ?: "照片保存失败"))
        }
    }

    private fun onCameraFailed(message: String?) {
        _uiState.update {
            it.copy(screenState = CameraScreenState.Error(message ?: "无法启动 ONNX 相机引导"))
        }
    }
}
