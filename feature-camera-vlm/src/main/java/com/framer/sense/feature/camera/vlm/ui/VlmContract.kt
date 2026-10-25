package com.framer.sense.feature.camera.vlm.ui

import android.graphics.Bitmap
import android.net.Uri
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.data.ModelDownloadState
import com.framer.sense.feature.camera.vlm.data.CameraReferenceRouting
import com.framer.sense.feature.camera.vlm.avatar.PlanPreview
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionDefinition
import com.framer.sense.feature.camera.vlm.model.*

enum class VlmStage { LIVE, SCANNING, SCAN_PROCESSING, SCAN_REVIEW, VIEW_SELECTING, FREEZING, FROZEN, GENERATING, READY, MODIFYING }

/** 样片独立于扫描/构图会话，关闭即失效，不修改选中方案。 */
data class PhotoSampleState(
    val token: String,
    val expression: ExpressionId = ExpressionId.SMILE,
    val body: Bitmap? = null,
    val face: Bitmap? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val ethnicity: AvatarEthnicity = AvatarEthnicity.WHITE,
    val actionId: String = com.framer.sense.feature.camera.vlm.avatar.PhotoActionCatalog.SAMPLE_ID
)

data class VlmUiState(
    val stage: VlmStage = VlmStage.LIVE,
    val ar: com.framer.sense.feature.camera.vlm.ar.ArUiState = com.framer.sense.feature.camera.vlm.ar.ArUiState(source = com.framer.sense.feature.camera.vlm.ar.ArSource.SCREEN),
    val scanSession: ScanSession? = null,
    val scanProgress: ScanProgress = ScanProgress(),
    val scanSnapshot: ScanSnapshot? = null,
    val viewCandidates: List<ViewCandidate> = emptyList(),
    val selectedFrameId: String? = null,
    val comparedFrames: Int = 0,
    val manualScanSelectionVisible: Boolean = false,
    val cameraConfigVersion: Int = 0,
    val photoOrientation: PhotoOrientation = PhotoOrientation(),
    val background: BackgroundGuidance = BackgroundGuidance(),
    val scene: SceneSnapshot? = null,
    val settings: ModelSettings = ModelSettings(),
    val camera: CameraCapabilities = CameraCapabilities(),
    val cameraReady: Boolean = false,
    val zoom: Float = 1f,
    val actualZoom: Float = 1f,
    val normalZoom: Float = 1f,
    val photoPreferences: PhotoPreferences = PhotoPreferences(),
    val cameraMode: CameraMode = CameraMode.NORMAL,
    val cameraEpoch: Int = 0,
    val photoSettingsVisible: Boolean = false,
    val instructionEditorVisible: Boolean = false,
    val instructionDraft: String = "",
    val instructionDraftFromRecommendation: Boolean = false,
    val instructionDraftRevision: Int = 0,
    val instructionHistory: List<String> = emptyList(),
    val compositionDetailsVisible: Boolean = false,
    val compositionDetailsExpanded: Boolean = false,
    val instruction: String = "自然一些，保留环境主体",
    val result: CompositionResult? = null,
    val compositionBatchId: String = INITIAL_COMPOSITION_BATCH,
    val compositionCache: Map<String, BackgroundComposition> = emptyMap(),
    val diagnostics: CompositionResult? = null,
    val selectedId: String? = null,
    val selectedAvatar: AvatarId = AvatarId.ADULT_FEMALE,
    val selectedEthnicity: AvatarEthnicity = AvatarEthnicity.WHITE,
    val previews: Map<String, PlanPreview> = emptyMap(),
    val availableActions: Map<String, List<PhotoActionDefinition>> = emptyMap(),
    val availableExpressions: Map<String, List<ExpressionId>> = emptyMap(),
    val faceVisible: Boolean = false,
    val facePreview: Bitmap? = null,
    val faceLoading: Boolean = false,
    val reference: PlanPreview? = null,
    val referencePlan: CompositionPlan? = null,
    val error: String? = null,
    val notice: String? = null,
    val modelStatus: String = "尚未导入离线模型",
    val modelBusy: Boolean = false,
    val modelInventoryVersion: Long = 0,
    val localModelName: String? = null,
    val download: ModelDownloadState = ModelDownloadState(),
    val saving: Boolean = false,
    val capturedWorkflow: CameraWorkflowKey? = null,
    val settingsVisible: Boolean = false,
    val fromImportedImage: Boolean = false,
    val directorProgress: DirectorProgress? = null,
    val photoSample: PhotoSampleState? = null,
    val generated: GeneratedReferenceState = GeneratedReferenceState(),
    val automaticReferenceSource: ReferenceSource = CameraReferenceRouting.source(settings, networkAvailable = true)
) {
    val referenceSource: ReferenceSource get() = when (settings.mode) {
        ModelMode.OFFLINE -> ReferenceSource.LEGACY_GLB
        ModelMode.CLOUD -> ReferenceSource.GENERATED_IMAGE
        ModelMode.AUTO -> automaticReferenceSource
    }
    val usesGeneratedReference: Boolean get() = referenceSource == ReferenceSource.GENERATED_IMAGE
    val activeBackgroundTarget: BackgroundReferenceTarget?
        get() = generated.applied?.backgroundTarget() ?: referencePlan?.backgroundTarget()
    /** 收起构图编辑时显示当前参考的顶部提示，两组界面始终互斥。 */
    val showReferenceGuidanceOverlay: Boolean
        get() = stage == VlmStage.LIVE && activeBackgroundTarget != null && !compositionDetailsVisible

    val showBackgroundAlignmentControls: Boolean
        get() = showReferenceGuidanceOverlay && activeBackgroundTarget?.viewBinding != null

    val operationBusy: Boolean
        get() = modelBusy || saving || photoSample != null || stage in listOf(VlmStage.FREEZING,
            VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)
    val framingUnlocked: Boolean
        get() = stage == VlmStage.LIVE && !modelBusy && !saving && photoSample == null && !instructionEditorVisible
    val canEditInstruction: Boolean
        get() = !operationBusy && !settingsVisible && !photoSettingsVisible
    val canCapture: Boolean
        get() = framingUnlocked && cameraReady && !settingsVisible && !photoSettingsVisible
    val canOpenGallery: Boolean
        get() = !operationBusy && !instructionEditorVisible && !settingsVisible && !photoSettingsVisible
    val canZoom: Boolean
        get() = framingUnlocked && cameraReady && cameraMode == CameraMode.NORMAL

    /** 扫描／单张冻结仍属于第一步；有背景或构图后必须显式重置流程。 */
    val isFirstCaptureStep: Boolean
        get() = stage in listOf(VlmStage.LIVE, VlmStage.SCANNING, VlmStage.FREEZING) &&
            scene == null && scanSnapshot == null && result == null && compositionCache.isEmpty() && referencePlan == null && generated.candidates.isEmpty()
    /** 可取消本轮工作回到第一步，但不能打断模型管理、保存或样片资源操作。 */
    val canRestartFlow: Boolean
        get() = !modelBusy && !saving && !settingsVisible && !photoSettingsVisible && !instructionEditorVisible && photoSample == null
    val canRestartScan: Boolean
        get() = isFirstCaptureStep && stage == VlmStage.LIVE && canRestartFlow && cameraReady
    val canStartSingleComposition: Boolean
        get() = isFirstCaptureStep && stage in listOf(VlmStage.LIVE, VlmStage.FREEZING) && cameraReady && canRestartFlow
    val canGenerate: Boolean
        get() = !modelBusy && !saving && !settingsVisible && !photoSettingsVisible && !instructionEditorVisible && photoSample == null && scene != null &&
            stage in listOf(VlmStage.FROZEN, VlmStage.READY, VlmStage.GENERATING, VlmStage.MODIFYING)

    /** 实时画面不因离线模型加载而降帧；冻结分析时才降低后台绘制频率。 */
    val requiresLivePreview: Boolean
        get() = photoSample == null && stage in listOf(VlmStage.LIVE, VlmStage.SCANNING, VlmStage.FREEZING)

    /** 仅显示扫描预览，不能作为已选背景、构图输入或应用参考。 */
    val displayScene: SceneSnapshot?
        get() = scene ?: if (stage in listOf(VlmStage.SCAN_PROCESSING, VlmStage.SCAN_REVIEW, VlmStage.VIEW_SELECTING))
            scanSnapshot?.frames?.firstOrNull()?.scene else null
}

sealed interface VlmIntent {
    data class SelectGenerated(val id: String) : VlmIntent
    data class RetryGenerated(val id: String) : VlmIntent
    data object ReplaceGenerated : VlmIntent
    data class GeneratedZoomVisible(val visible: Boolean) : VlmIntent
    data class PhotoOrientationChanged(val orientation: PhotoOrientation) : VlmIntent
    data class ArUpdated(val state: com.framer.sense.feature.camera.vlm.ar.ArUiState, val epoch: Int? = null) : VlmIntent
    data object OpenPhotoSample : VlmIntent
    data object ClosePhotoSample : VlmIntent
    data class PhotoSampleExpression(val expression: ExpressionId) : VlmIntent
    data class PhotoSampleEthnicity(val ethnicity: AvatarEthnicity) : VlmIntent
    data class PhotoSampleAction(val actionId: String) : VlmIntent
    data class UserMessage(val message: String) : VlmIntent
    data object StartScan : VlmIntent
    data object RestartFlow : VlmIntent
    data object StopScan : VlmIntent
    data object CancelScan : VlmIntent
    data object PreferScanView : VlmIntent
    data object AnalyzeScan : VlmIntent
    data class PreviewScanView(val frameId: String) : VlmIntent
    data class ScanViewSelected(val frameId: String) : VlmIntent
    data class ManualScanSelectionVisible(val visible: Boolean) : VlmIntent
    data class CameraConfigured(val version: Int, val epoch: Int? = null) : VlmIntent
    data class AnalysisReceived(val sample: com.framer.sense.feature.camera.vlm.scan.AnalysisSample, val epoch: Int? = null) : VlmIntent
    data object ConfirmBackground : VlmIntent
    data object StartComposition : VlmIntent
    data class FrameReady(val token: String, val bitmap: Bitmap, val zoom: Float, val arConfig: ScanCameraConfig? = null,
        val orientation: PhotoOrientation = arConfig?.orientation ?: PhotoOrientation()) : VlmIntent
    data class PlatformFailed(val token: String?, val message: String) : VlmIntent
    data class CameraReady(val capabilities: CameraCapabilities, val epoch: Int? = null) : VlmIntent
    data object CameraStopped : VlmIntent
    data class CameraBindingStopped(val epoch: Int) : VlmIntent
    data class ZoomReported(val zoom: Float, val epoch: Int) : VlmIntent
    data class AspectRatioSelected(val ratio: CaptureAspectRatio) : VlmIntent
    data class CameraModeSelected(val mode: CameraMode) : VlmIntent
    data class PhotoSettingsVisible(val visible: Boolean) : VlmIntent
    data class CompositionDetailsVisible(val visible: Boolean) : VlmIntent
    data class CompositionDetailsExpanded(val expanded: Boolean) : VlmIntent
    data class PreferredEthnicity(val ethnicity: AvatarEthnicity) : VlmIntent
    data class InstructionChanged(val text: String) : VlmIntent
    data class InstructionEditorVisible(val visible: Boolean) : VlmIntent
    data class InstructionDraftChanged(val text: String) : VlmIntent
    data class InstructionRecommendationSelected(val text: String) : VlmIntent
    data class InstructionHistorySelected(val text: String) : VlmIntent
    data class DeleteInstructionHistory(val text: String) : VlmIntent
    data object SubmitInstruction : VlmIntent
    data class SeedreamModelSelected(val model: SeedreamModel) : VlmIntent
    data class AvatarSelected(val avatarId: AvatarId) : VlmIntent
    data object Generate : VlmIntent
    data object Revise : VlmIntent
    data object Cancel : VlmIntent
    data object ResumeCamera : VlmIntent
    data class Select(val id: String, val sceneId: String? = null, val batchId: String? = null) : VlmIntent
    data class Move(val dx: Float, val dy: Float) : VlmIntent
    data class Resize(val height: Float) : VlmIntent
    data class ActionSelected(val actionId: String) : VlmIntent
    data class EthnicitySelected(val ethnicity: AvatarEthnicity) : VlmIntent
    data class ExpressionSelected(val expression: ExpressionId) : VlmIntent
    data class ExpressionIntensityChanged(val intensity: Float) : VlmIntent
    data class FaceVisible(val visible: Boolean) : VlmIntent
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
    data class UseMarketModel(val id: String) : VlmIntent
    data class DeleteMarketModel(val id: String) : VlmIntent
    data object CheckGateway : VlmIntent
    data object CheckQwen : VlmIntent
    data object CheckSeed : VlmIntent
    data class ImportImage(val uri: Uri) : VlmIntent
    data object Capture : VlmIntent
    data class CaptureFinished(val token: String, val error: String? = null) : VlmIntent
    data object OpenGallery : VlmIntent
}
sealed interface VlmEffect {
    data class DownloadModel(val source: String, val allowMetered: Boolean) : VlmEffect
    data class Freeze(val token: String) : VlmEffect
    data class Capture(val token: String) : VlmEffect
    data object OpenGallery : VlmEffect
}

data class VlmCaptureAction(val enabled: Boolean, val onClick: () -> Unit)
