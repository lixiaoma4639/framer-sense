package com.framer.sense.feature.camera.vlm.ui

import android.graphics.Bitmap
import android.net.Uri
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.data.ModelDownloadState
import com.framer.sense.feature.camera.vlm.avatar.PlanPreview
import com.framer.sense.feature.camera.vlm.model.*

enum class VlmStage { LIVE, FREEZING, FROZEN, GENERATING, READY, MODIFYING }

data class VlmUiState(
    val stage: VlmStage = VlmStage.LIVE,
    val scene: SceneSnapshot? = null,
    val settings: ModelSettings = ModelSettings(),
    val camera: CameraCapabilities = CameraCapabilities(),
    val cameraReady: Boolean = false,
    val zoom: Float = 1f,
    val instruction: String = "自然一些，保留环境主体",
    val result: CompositionResult? = null,
    val diagnostics: CompositionResult? = null,
    val selectedId: String? = null,
    val previews: Map<String, PlanPreview> = emptyMap(),
    val reference: PlanPreview? = null,
    val referencePlan: CompositionPlan? = null,
    val error: String? = null,
    val notice: String? = null,
    val modelStatus: String = "尚未导入离线模型",
    val modelBusy: Boolean = false,
    val download: ModelDownloadState = ModelDownloadState(),
    val saving: Boolean = false,
    val settingsVisible: Boolean = false,
    val fromImportedImage: Boolean = false,
    val directorProgress: DirectorProgress? = null
)

sealed interface VlmIntent {
    data class UserMessage(val message: String) : VlmIntent
    data object StartComposition : VlmIntent
    data class FrameReady(val token: String, val bitmap: Bitmap, val zoom: Float) : VlmIntent
    data class PlatformFailed(val token: String?, val message: String) : VlmIntent
    data class CameraReady(val capabilities: CameraCapabilities) : VlmIntent
    data object CameraStopped : VlmIntent
    data class InstructionChanged(val text: String) : VlmIntent
    data object Generate : VlmIntent
    data object Revise : VlmIntent
    data object Cancel : VlmIntent
    data object ResumeCamera : VlmIntent
    data class Select(val id: String) : VlmIntent
    data class Move(val dx: Float, val dy: Float) : VlmIntent
    data class Resize(val height: Float) : VlmIntent
    data object Apply : VlmIntent
    data class ZoomChanged(val zoom: Float) : VlmIntent
    data class SettingsVisible(val visible: Boolean) : VlmIntent
    data class SaveSettings(val settings: ModelSettings) : VlmIntent
    data class ImportModel(val uri: Uri) : VlmIntent
    data class ImportModelDirectory(val uri: Uri) : VlmIntent
    data class StartModelDownload(val source: String, val allowMetered: Boolean) : VlmIntent
    data object PauseModelDownload : VlmIntent
    data object CancelModelDownload : VlmIntent
    data class CameraForeground(val visible: Boolean) : VlmIntent
    data object LoadModel : VlmIntent
    data object UnloadModel : VlmIntent
    data object DeleteModel : VlmIntent
    data object CheckGateway : VlmIntent
    data class ImportImage(val uri: Uri) : VlmIntent
    data object Capture : VlmIntent
    data class CaptureFinished(val token: String, val error: String? = null) : VlmIntent
}
sealed interface VlmEffect {
    data class DownloadModel(val source: String, val allowMetered: Boolean) : VlmEffect
    data class Freeze(val token: String) : VlmEffect
    data class Capture(val token: String) : VlmEffect
}

data class VlmCaptureAction(val enabled: Boolean, val onClick: () -> Unit)
