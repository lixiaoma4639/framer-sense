package com.framer.sense.feature.camera.vlm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** 弹窗使用自身返回处理；保存期间拦截退出，其他阶段保持原流程返回顺序。 */
internal enum class CameraBackAction { NONE, BLOCKED, COLLAPSE, CANCEL, RESUME, EXIT }

internal fun cameraBackAction(state: VlmUiState, hasExit: Boolean): CameraBackAction = when {
    state.settingsVisible || state.photoSettingsVisible || (state.compositionDetailsVisible && state.stage == VlmStage.SCAN_REVIEW) || state.photoSample != null -> CameraBackAction.NONE
    state.saving -> CameraBackAction.BLOCKED
    state.stage in listOf(VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.FREEZING,
        VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING) -> CameraBackAction.CANCEL
    state.compositionDetailsVisible && state.result != null -> CameraBackAction.COLLAPSE
    state.stage != VlmStage.LIVE -> CameraBackAction.RESUME
    hasExit -> CameraBackAction.EXIT
    else -> CameraBackAction.NONE
}

@Composable
internal fun CameraBackHandler(state: VlmUiState, onEvent: (VlmIntent) -> Unit, onExit: (() -> Unit)?) {
    val action = cameraBackAction(state, onExit != null)
    BackHandler(enabled = action != CameraBackAction.NONE) {
        when (action) {
            CameraBackAction.COLLAPSE -> onEvent(VlmIntent.CompositionDetailsVisible(false))
            CameraBackAction.CANCEL -> onEvent(VlmIntent.Cancel)
            CameraBackAction.RESUME -> onEvent(VlmIntent.ResumeCamera)
            CameraBackAction.EXIT -> onExit?.invoke()
            CameraBackAction.NONE, CameraBackAction.BLOCKED -> Unit
        }
    }
}
