package com.framer.sense.feature.camera.vlm.ui

import android.util.Log
import com.framer.sense.feature.camera.vlm.R
import java.io.File
import android.os.SystemClock
import com.framer.sense.feature.camera.vlm.scan.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.avatar.PreviewStore
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionCatalog
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** 相机 MVI 状态机；通过请求令牌阻止取消后或旧场景结果写回。 */
@HiltViewModel
class VlmCameraViewModel @Inject constructor(
    private val repository: CompositionRepository,
    private val snapshots: SnapshotStore,
    private val previews: PreviewStore,
    private val settingsStore: SettingsStore,
    private val models: LocalModelStore,
    private val local: MnnProvider,
    private val downloads: ModelDownloadRepository,
    savedStateHandle: SavedStateHandle,
    private val sessions: PhotoSessionController
) : ViewModel() {
    private val initialPhotoPreferences = settingsStore.readPhotoPreferences()
    private val _state = MutableStateFlow(VlmUiState(settings = settingsStore.read(), modelStatus = modelDescription(),
        photoPreferences = initialPhotoPreferences, selectedAvatar = initialPhotoPreferences.avatar,
        faceVisible = initialPhotoPreferences.showFace,
        instruction = initialPhotoPreferences.instruction.ifBlank { models.message(R.string.vlm_photo_default_requirement) },
        selectedEthnicity = initialPhotoPreferences.ethnicity, modelBusy = local.loadState.value.phase == LocalModelPhase.LOADING,
        notice = if (savedStateHandle.get<Boolean>("vlm_started") == true) models.message(R.string.vlm_scan_restore) else null))
    val state: StateFlow<VlmUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<VlmEffect>(extraBufferCapacity = 4)
    val effects = _effects.asSharedFlow()
    private var requestToken = newVlmId()
    private val scanLogger = object : DirectorLogger {
        override fun debug(message: String) { Log.d("VlmScan", message) }
        override fun info(message: String) { Log.i("VlmScan", message) }
        override fun warn(message: String, error: Throwable?) { Log.w("VlmScan", message, error) }
        override fun error(message: String, error: Throwable?) { Log.e("VlmScan", message, error) }
    }
    private var captureToken: String? = null
    private var work: Job? = null
    private var renderWork: Job? = null
    private var faceWork: Job? = null
    private var sampleWork: Job? = null
    private var maintenance: Job? = null
    private var scanQueue: Channel<AnalysisSample>? = null
    private var scanWork: Job? = null
    private var scanTimer: Job? = null
    private var cleanupWork: Job? = null
    private var analyzeAfterStop = false
    private var guideWork: Job? = null
    private val guideQueue = Channel<AnalysisSample>(1, BufferOverflow.DROP_OLDEST)
    private var foreground = false
    private var foregroundSinceNs = 0L
    private var guidanceEpoch = 0
    private var lastGuidanceLogNs = 0L
    private var modelLoaded = local.loadState.value.phase == LocalModelPhase.READY
    private var maintenanceBusy = false
    private var attemptedPath: String? = null
    init {
        guideWork = viewModelScope.launch {
            for (sample in guideQueue) {
                val s = _state.value
                val plan = s.referencePlan ?: continue
                val scene = s.scene ?: continue
                if (s.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA && s.ar.tracking in listOf(com.framer.sense.feature.camera.vlm.ar.ArTrackingState.LOST, com.framer.sense.feature.camera.vlm.ar.ArTrackingState.PAUSED)) continue
                if (!foreground || s.photoSample != null || sample.receivedNs < foregroundSinceNs || s.stage != VlmStage.LIVE || sample.targetId != guidanceTarget(plan) || sample.config.version != s.cameraConfigVersion) continue
                val token = requestToken
                val epoch = guidanceEpoch
                try {
                    val target = s.scanSnapshot?.frames?.firstOrNull { it.id == plan.viewBinding?.frameId }?.pose
                    val alignStart = SystemClock.elapsedRealtimeNanos()
                    val aligned = sessions.align(sample, plan, scene, target)
                    val alignEnd = SystemClock.elapsedRealtimeNanos()
                    if (alignEnd-lastGuidanceLogNs >= 2_000_000_000L) {
                        lastGuidanceLogNs = alignEnd
                        Log.i("VlmScan", "背景匹配性能 alignMs=${(alignEnd-alignStart)/1_000_000} queueMs=${(alignStart-sample.receivedNs)/1_000_000} status=${aligned.status} config=${sample.config.version}")
                    }
                    _state.update { current ->
                        if (!foreground || epoch != guidanceEpoch || token != requestToken || current.referencePlan != plan || current.cameraConfigVersion != sample.config.version || current.stage != VlmStage.LIVE) current
                        else current.copy(background = if (current.background.status == BackgroundStatus.ALIGNED_MANUAL && (aligned.headingError == null || kotlin.math.abs(aligned.headingError) < 12f) && (aligned.pitchError == null || kotlin.math.abs(aligned.pitchError) < 12f))
                            aligned.copy(status = BackgroundStatus.ALIGNED_MANUAL) else aligned)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { if (token == requestToken) _state.update { it.copy(background = BackgroundGuidance()) } }
            }
        }
        savedStateHandle["vlm_started"] = true
        viewModelScope.launch { downloads.state.collect { value -> _state.update { it.copy(download = value) } } }
        viewModelScope.launch { local.loadState.collect { value ->
            modelLoaded = value.phase == LocalModelPhase.READY
            value.path?.let { attemptedPath = it }
            if (modelLoaded && downloads.state.value.phase == DownloadPhase.DOWNLOADED && models.pendingDirectory() == null)
                downloads.loadingState(DownloadPhase.READY)
            _state.update { it.copy(modelBusy = maintenanceBusy || value.phase == LocalModelPhase.LOADING,
                modelStatus = if (maintenanceBusy && value.phase != LocalModelPhase.LOADING) it.modelStatus else modelDescription(value)) }
        } }
        viewModelScope.launch { state.collect { maybeAutoLoad() } }
    }

    private fun updatePhotoPreferences(change: (PhotoPreferences) -> PhotoPreferences) {
        val value = change(_state.value.photoPreferences)
        settingsStore.savePhotoPreferences(value)
        _state.update { it.copy(photoPreferences = value) }
    }

    private fun switchCameraMode(mode: CameraMode) {
        val old = _state.value
        if (old.cameraMode == mode) return
        guidanceEpoch++
        _state.update { it.copy(cameraMode = mode, cameraEpoch = it.cameraEpoch + 1, cameraReady = false,
            normalZoom = if (mode == CameraMode.AR) it.zoom else it.normalZoom,
            zoom = if (mode == CameraMode.AR) 1f else it.normalZoom,
            actualZoom = if (mode == CameraMode.AR) 1f else it.normalZoom,
            camera = if (mode == CameraMode.AR) CameraCapabilities() else it.camera,
            ar = com.framer.sense.feature.camera.vlm.ar.ArUiState(source = if (mode == CameraMode.AR)
                com.framer.sense.feature.camera.vlm.ar.ArSource.CHECKING else com.framer.sense.feature.camera.vlm.ar.ArSource.SCREEN),
            reference = null, referencePlan = null, background = BackgroundGuidance()) }
    }

    private fun modelDescription(value: LocalModelLoadState = local.loadState.value): String = when (value.phase) {
        LocalModelPhase.READY -> models.message(R.string.vlm_model_loaded)
        LocalModelPhase.LOADING -> models.message(R.string.vlm_model_loading)
        LocalModelPhase.FAILED -> value.error ?: models.message(R.string.vlm_model_load_failed)
        LocalModelPhase.UNLOADED -> models.description()
    }

    /** 前台空闲时自动加载已下载或已安装目录；无参数，失败或卸载后不循环重试。 */
    private fun maybeAutoLoad() {
        if (!foreground || busy()) return
        val phase = downloads.state.value.phase
        // 下载完成时优先尝试候选目录；导入/既有安装没有下载会话，直接加载当前安装。
        val dir = if (phase == DownloadPhase.DOWNLOADED) {
            models.pendingDirectory() ?: models.activeDirectory()
        } else {
            models.activeDirectory()
        } ?: return
        if (modelLoaded && local.loadState.value.path == dir.path) return
        if (attemptedPath == dir.path) return
        attemptedPath = dir.path
        maintain(models.message(R.string.vlm_model_loading)) { loadDirectory(dir) }
    }

    /** 加载候选或现有安装，加载失败明确保留文件以便重试。
     * @param dir 已校验模型所在目录。
     */
    private suspend fun loadDirectory(dir: File) {
        modelLoaded = false
        downloads.loadingState(DownloadPhase.LOADING)
        try {
            if (models.activeDirectory() == dir) local.load() else local.install(dir)
            modelLoaded = true
            downloads.loadingState(DownloadPhase.READY)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { downloads.loadingState(DownloadPhase.DOWNLOADED) }
            throw cancel
        } catch (error: Exception) {
            downloads.loadingState(DownloadPhase.LOAD_FAILED, models.message(R.string.vlm_model_load_failed))
            throw error
        }
    }

    /** 导入文件并共用加载流程。@param uri 用户授权文件地址。@param directory 是否为 SAF 文件夹。 */
    private fun importModel(uri: android.net.Uri, directory: Boolean) {
        if (downloads.state.value.running) return
        maintain(models.message(R.string.vlm_model_copying)) {
            var reported = 0L
            val progress: (Long, String) -> Unit = { bytes, message ->
                val now = System.nanoTime()
                if (now - reported > 150_000_000L) {
                    reported = now
                    _state.update { it.copy(modelStatus = models.message(R.string.vlm_model_copy_progress, message, bytes / 1048576)) }
                }
            }
            val dir = if (directory) models.importDirectory(uri, progress) else models.importPackage(uri, progress)
            downloads.forget()
            attemptedPath = dir.path
            loadDirectory(dir)
        }
    }

    /** 接收界面事件并归约状态。
     * @param intent 用户操作或平台异步结果；Bitmap 的所有权随 FrameReady 转交本状态机。
     */
    fun onIntent(intent: VlmIntent) {
        when (intent) {
            VlmIntent.OpenPhotoSample -> if (!busy() && !_state.value.settingsVisible &&
                _state.value.stage in listOf(VlmStage.LIVE, VlmStage.FROZEN, VlmStage.READY)) requestSample(ExpressionId.SMILE)
            VlmIntent.ClosePhotoSample -> closeSample()
            is VlmIntent.PhotoSampleExpression -> if (_state.value.photoSample != null &&
                _state.value.photoSample?.loading == false &&
                intent.expression in listOf(ExpressionId.NEUTRAL, ExpressionId.SMILE, ExpressionId.HAPPY)) requestSample(intent.expression)
            is VlmIntent.PhotoSampleAction -> _state.value.photoSample?.takeIf { !it.loading }?.let { sample ->
                if (PhotoActionCatalog.isRainAction(intent.actionId) && intent.actionId != sample.actionId)
                    requestSample(sample.expression, sample.ethnicity, intent.actionId)
            }
            is VlmIntent.PhotoSampleEthnicity -> _state.value.photoSample?.takeIf { !it.loading }?.let { sample ->
                if (intent.ethnicity != sample.ethnicity) {
                    settingsStore.saveAvatarEthnicity(intent.ethnicity)
                    _state.update { it.copy(selectedEthnicity = intent.ethnicity) }
                    requestSample(sample.expression, intent.ethnicity)
                }
            }
            VlmIntent.StartScan -> startScan()
            VlmIntent.StopScan -> stopScan(true)
            VlmIntent.CancelScan -> cancelScan()
            VlmIntent.PreferScanView -> if (_state.value.stage == VlmStage.SCANNING) {
                val now = SystemClock.elapsedRealtimeNanos()
                val preferred = sessions.prefer(now)
                _state.update { it.copy(scanProgress = sessions.progress(now), notice = models.message(if (preferred) R.string.vlm_scan_preferred else R.string.vlm_scan_prefer_unavailable)) }
            }
            VlmIntent.AnalyzeScan -> analyzeScan()
            is VlmIntent.ScanViewSelected -> chooseScanFrame(intent.frameId)
            is VlmIntent.ManualScanSelectionVisible -> if (!busy() && _state.value.stage in listOf(VlmStage.SCAN_REVIEW, VlmStage.READY))
                _state.update { it.copy(manualScanSelectionVisible = intent.visible) }
            is VlmIntent.ArUpdated -> {
                if (intent.epoch != null && intent.epoch != _state.value.cameraEpoch) return
                if (_state.value.cameraMode != CameraMode.AR && intent.state.source != com.framer.sense.feature.camera.vlm.ar.ArSource.SCREEN) return
                if (intent.state.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SCREEN && _state.value.cameraMode == CameraMode.AR) {
                    switchCameraMode(CameraMode.NORMAL)
                    _state.update { it.copy(error = models.message(R.string.vlm_ar_install_failed)) }
                    return
                }
                if (_state.value.ar.sessionId != intent.state.sessionId || intent.state.tracking in listOf(com.framer.sense.feature.camera.vlm.ar.ArTrackingState.LOST, com.framer.sense.feature.camera.vlm.ar.ArTrackingState.PAUSED)) guidanceEpoch++
                _state.update { current ->
                current.copy(ar = intent.state, zoom = if (intent.state.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA) 1f else current.zoom,
                    background = if (current.ar.sessionId != intent.state.sessionId || intent.state.tracking in listOf(com.framer.sense.feature.camera.vlm.ar.ArTrackingState.LOST, com.framer.sense.feature.camera.vlm.ar.ArTrackingState.PAUSED)) BackgroundGuidance() else current.background)
                }
            }
            is VlmIntent.CameraConfigured -> {
                if (intent.epoch != null && intent.epoch != _state.value.cameraEpoch) return
                guidanceEpoch++
                if (intent.version <= _state.value.cameraConfigVersion) return
                sessions.configure(intent.version)
                _state.update { it.copy(cameraConfigVersion = intent.version, background = BackgroundGuidance()) }
            }
            is VlmIntent.AnalysisReceived -> {
                if (intent.epoch != null && intent.epoch != _state.value.cameraEpoch) return
                if (_state.value.stage == VlmStage.SCANNING) scanQueue?.trySend(intent.sample)
                else if (_state.value.stage == VlmStage.LIVE) guideQueue.trySend(intent.sample)
            }
            VlmIntent.ConfirmBackground -> if (_state.value.stage == VlmStage.LIVE && _state.value.referencePlan?.viewBinding != null)
                _state.update { it.copy(background = it.background.copy(status = BackgroundStatus.ALIGNED_MANUAL)) }
            is VlmIntent.UserMessage -> _state.update { it.copy(error = intent.message) }
            VlmIntent.StartComposition -> if (_state.value.stage in listOf(VlmStage.LIVE, VlmStage.FREEZING) && _state.value.cameraReady &&
                !_state.value.modelBusy && !_state.value.saving && !_state.value.settingsVisible && !_state.value.photoSettingsVisible && _state.value.photoSample == null) {
                cancelWork()
                _state.update { it.copy(stage = VlmStage.FREEZING, error = null, notice = null, directorProgress = null,
                    scanSession = null, scanSnapshot = null, viewCandidates = emptyList(), selectedFrameId = null,
                    scene = null, result = null, diagnostics = null, previews = emptyMap(), reference = null, referencePlan = null,
                    facePreview = null, availableActions = emptyMap(), availableExpressions = emptyMap()) }
                _effects.tryEmit(VlmEffect.Freeze(requestToken))
            }
            is VlmIntent.FrameReady -> receiveFrame(intent)
            is VlmIntent.PlatformFailed -> {
                if (intent.token != null && intent.token != requestToken) return
                if (_state.value.stage == VlmStage.SCANNING) stopScan(false)
                if (intent.token == null || intent.token == requestToken) _state.update { it.copy(stage = if (it.stage == VlmStage.SCAN_PROCESSING) it.stage else if (it.scene == null) VlmStage.LIVE else VlmStage.FROZEN, error = intent.message, cameraReady = if (intent.token != null && it.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA) it.cameraReady else false) }
            }
            is VlmIntent.CameraReady -> if (intent.epoch == null || intent.epoch == _state.value.cameraEpoch)
                _state.update { it.copy(camera = intent.capabilities, cameraReady = true,
                    zoom = CameraZoomPolicy.clamp(it.zoom, intent.capabilities.minZoom, intent.capabilities.maxZoom)) }
            is VlmIntent.CameraBindingStopped -> if (intent.epoch == _state.value.cameraEpoch) {
                guidanceEpoch++
                _state.update { it.copy(cameraReady = false, background = BackgroundGuidance()) }
            }
            is VlmIntent.ZoomReported -> if (intent.epoch == _state.value.cameraEpoch && _state.value.cameraMode == CameraMode.NORMAL)
                _state.update { it.copy(actualZoom = intent.zoom) }
            is VlmIntent.AspectRatioSelected -> if (_state.value.framingUnlocked && intent.ratio != _state.value.photoPreferences.aspectRatio) {
                guidanceEpoch++
                updatePhotoPreferences { it.copy(aspectRatio = intent.ratio) }
                _state.update { it.copy(cameraEpoch = it.cameraEpoch + 1, cameraReady = false,
                    ar = if (it.cameraMode == CameraMode.AR) com.framer.sense.feature.camera.vlm.ar.ArUiState(source = com.framer.sense.feature.camera.vlm.ar.ArSource.CHECKING) else it.ar,
                    reference = null, referencePlan = null, background = BackgroundGuidance()) }
            }
            is VlmIntent.CameraModeSelected -> if (_state.value.framingUnlocked) switchCameraMode(intent.mode)
            is VlmIntent.PhotoSettingsVisible -> if (!intent.visible || !busy())
                _state.update { it.copy(photoSettingsVisible = intent.visible) }
            is VlmIntent.CompositionDetailsVisible -> _state.update { it.copy(compositionDetailsVisible = intent.visible) }
            is VlmIntent.PreferredEthnicity -> if (!busy()) {
                updatePhotoPreferences { it.copy(ethnicity = intent.ethnicity) }
                _state.update { it.copy(selectedEthnicity = intent.ethnicity) }
            }
            VlmIntent.CameraStopped -> _state.update { it.copy(cameraReady = false) }
            is VlmIntent.InstructionChanged -> if (!busy()) {
                _state.update { it.copy(instruction = intent.text.take(2000)) }
                updatePhotoPreferences { it.copy(instruction = intent.text.take(2000)) }
            }
            is VlmIntent.AvatarSelected -> if (!busy()) {
                updatePhotoPreferences { it.copy(avatar = intent.avatarId) }
                _state.update { it.copy(selectedAvatar = intent.avatarId) }
            }
            VlmIntent.Generate -> compose(false)
            VlmIntent.Revise -> compose(true)
            VlmIntent.Cancel -> { if (_state.value.stage == VlmStage.SCANNING || _state.value.stage == VlmStage.SCAN_PROCESSING) { cancelScan(); return }; if (busy() && _state.value.modelBusy) return; cancelWork(); _state.update { it.copy(stage = if (it.scanSnapshot != null && it.scene == null) VlmStage.SCAN_REVIEW else if (it.scene == null) VlmStage.LIVE else if (it.result == null) VlmStage.FROZEN else VlmStage.READY, error = null, directorProgress = null) } }
            // 相机是否就绪由绑定生命周期更新；退出构图不能把仍在运行的相机标记为未就绪。
            VlmIntent.ResumeCamera -> { if (_state.value.scanSnapshot != null) { cancelScan(); return }; cancelWork(); _state.update { it.copy(stage = VlmStage.LIVE, reference = null, referencePlan = null, error = null, directorProgress = null) } }
            is VlmIntent.Select -> if (!busy() && _state.value.result?.plans?.any { it.id == intent.id } == true) {
                _state.update { it.copy(selectedId = intent.id, facePreview = null,
                    stage = if (it.stage == VlmStage.LIVE) VlmStage.READY else it.stage,
                    reference = if (it.stage == VlmStage.LIVE) null else it.reference,
                    referencePlan = if (it.stage == VlmStage.LIVE) null else it.referencePlan) }
                requestFace()
            }
            is VlmIntent.Move -> adjust { it.copy(foot = Point2((it.foot.x + intent.dx).coerceIn(0f, 1f), (it.foot.y + intent.dy).coerceIn(0f, 4f))) }
            is VlmIntent.Resize -> adjust { it.copy(height = intent.height.coerceIn(.08f, 4f), projectionCalibrated = false) }
            is VlmIntent.ActionSelected -> changeSelection(actionId = intent.actionId)
            is VlmIntent.EthnicitySelected -> changeSelection(ethnicity = intent.ethnicity)
            is VlmIntent.ExpressionSelected -> changeSelection(expression = intent.expression)
            is VlmIntent.ExpressionIntensityChanged -> if (intent.intensity.isFinite()) changeSelection(intensity = intent.intensity.coerceIn(0f, 1f))
            is VlmIntent.FaceVisible -> if (!busy()) {
                updatePhotoPreferences { it.copy(showFace = intent.visible) }
                _state.update { it.copy(faceVisible = intent.visible, facePreview = null, faceLoading = false) }
                faceWork?.cancel()
                requestFace()
            }
            VlmIntent.Apply -> applyPlan()
            is VlmIntent.ZoomChanged -> if (_state.value.canZoom && intent.zoom.isFinite() && kotlin.math.abs(intent.zoom - _state.value.zoom) > .0001f) {
                guidanceEpoch++
                _state.update { it.copy(zoom = CameraZoomPolicy.clamp(intent.zoom, it.camera.minZoom, it.camera.maxZoom),
                    normalZoom = CameraZoomPolicy.clamp(intent.zoom, it.camera.minZoom, it.camera.maxZoom),
                    reference = null, referencePlan = null, background = BackgroundGuidance()) }
            }
            is VlmIntent.SettingsVisible -> if (_state.value.stage !in listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)) _state.update { it.copy(settingsVisible = intent.visible) }
            is VlmIntent.SaveSettings -> {
                cancelWork()
                viewModelScope.launch {
                    try {
                        val settings = CloudModelConfig.applyQwenConfiguration(intent.settings)
                        if (settings.mode == ModelMode.CLOUD && settings.region == Region.CN && settings.preferred == ProviderId.QWEN &&
                            settings.qwenAccess == QwenAccess.OFFICIAL) QwenOfficialProtocol.validate(settings)
                        if (settings.mode == ModelMode.CLOUD && settings.region == Region.CN && settings.preferred == ProviderId.SEED &&
                            settings.seedAccess == SeedAccess.OFFICIAL) SeedOfficialProtocol.validate(settings)
                        withContext(Dispatchers.IO) { settingsStore.save(settings) }
                        _state.update { it.copy(settings = settings, stage = if (it.scanSnapshot != null && it.scene == null) VlmStage.SCAN_REVIEW else if (it.scene == null || it.stage == VlmStage.LIVE) VlmStage.LIVE else VlmStage.FROZEN, settingsVisible = false, error = null) }
                    } catch (e: Exception) { _state.update { it.copy(error = cloudFailureMessage(e) ?: "设置保存失败：${e.message}") } }
                }
            }
            is VlmIntent.CameraForeground -> {
                if (intent.visible && !foreground) foregroundSinceNs = SystemClock.elapsedRealtimeNanos()
                if (!intent.visible) guidanceEpoch++
                foreground = intent.visible
                if (!foreground) closeSample()
                if (!foreground && _state.value.stage == VlmStage.SCANNING) stopScan(false)
                if (!foreground) _state.update { it.copy(background = BackgroundGuidance()) }
                if (foreground) maybeAutoLoad()
            }
            is VlmIntent.StartModelDownload -> if (!busy() && !downloads.state.value.running && foreground) {
                _effects.tryEmit(VlmEffect.DownloadModel(intent.source, intent.allowMetered))
            }
            VlmIntent.PauseModelDownload -> downloads.pause()
            VlmIntent.CancelModelDownload -> if (!_state.value.modelBusy) downloads.cancel()
            is VlmIntent.ImportModel -> importModel(intent.uri, false)
            is VlmIntent.ImportModelDirectory -> importModel(intent.uri, true)
            VlmIntent.LoadModel -> if (!downloads.state.value.running) maintain(models.message(R.string.vlm_model_loading)) {
                val dir = models.pendingDirectory() ?: models.activeDirectory() ?: throw ModelStorageException(R.string.vlm_model_absent)
                attemptedPath = dir.path
                loadDirectory(dir)
            }
            VlmIntent.UnloadModel -> maintain(models.message(R.string.vlm_model_unload)) {
                local.unload(); modelLoaded = false
                downloads.loadingState(DownloadPhase.DOWNLOADED)
            }
            VlmIntent.DeleteModel -> if (!downloads.state.value.running) maintain(models.message(R.string.vlm_model_delete)) {
                local.unload(); modelLoaded = false; models.delete(); downloads.forget(); attemptedPath = null
            }
            VlmIntent.CheckGateway -> maintain("检查网关") {
                val caps = GatewayProvider(_state.value.settings, ProviderId.QWEN).capabilities()
                _state.update { it.copy(notice = caps.providers.joinToString("\n") { p -> "${p.provider}: ${if (p.configured) p.model else "未配置"}" }) }
            }
            VlmIntent.CheckQwen -> maintain(models.message(R.string.vlm_qwen_checking)) {
                val s = _state.value.settings
                val identity = ModelUsageLog.identity(ProviderId.QWEN, s.copy(qwenAccess = QwenAccess.OFFICIAL))
                Log.i("VlmScan", "模型使用 stage=配置检查 result=开始 $identity")
                try {
                    val model = QwenOfficialProvider(s).checkConfiguration()
                    Log.i("VlmScan", "模型使用 stage=配置检查 result=成功 ${ModelUsageLog.identity(ProviderId.QWEN, s.copy(qwenAccess = QwenAccess.OFFICIAL), model)} visionVerified=false")
                    _state.update { it.copy(notice = models.message(R.string.vlm_qwen_check_success, model)) }
                } catch (cancel: CancellationException) {
                    Log.i("VlmScan", "模型使用 stage=配置检查 result=取消 $identity")
                    throw cancel
                } catch (failure: Exception) {
                    Log.e("VlmScan", "模型使用 stage=配置检查 result=失败 $identity ${ModelUsageLog.failure(failure)}")
                    throw failure
                }
            }
            VlmIntent.CheckSeed -> maintain(models.message(R.string.vlm_seed_checking)) {
                val s = _state.value.settings
                val identity = ModelUsageLog.identity(ProviderId.SEED, s.copy(seedAccess = SeedAccess.OFFICIAL))
                Log.i("VlmScan", "模型使用 stage=配置检查 result=开始 $identity")
                try {
                    val model = SeedOfficialProvider(s).checkConfiguration()
                    Log.i("VlmScan", "模型使用 stage=配置检查 result=成功 ${ModelUsageLog.identity(ProviderId.SEED, s.copy(seedAccess = SeedAccess.OFFICIAL), model)} visionVerified=false")
                    _state.update { it.copy(notice = models.message(R.string.vlm_seed_check_success, model)) }
                } catch (cancel: CancellationException) {
                    Log.i("VlmScan", "模型使用 stage=配置检查 result=取消 $identity")
                    throw cancel
                } catch (failure: Exception) {
                    Log.e("VlmScan", "模型使用 stage=配置检查 result=失败 $identity ${ModelUsageLog.failure(failure)}")
                    throw failure
                }
            }
            is VlmIntent.ImportImage -> {
                if (busy()) return
                cancelWork()
                val token = requestToken
                work = viewModelScope.launch {
                    try {
                        _state.update { it.copy(stage = VlmStage.FREEZING, error = null, scanSession = null, scanSnapshot = null, viewCandidates = emptyList(), selectedFrameId = null, referencePlan = null) }
                        val scene = snapshots.importImage(intent.uri)
                        if (token == requestToken) {
                            _state.update { it.copy(scene = scene, stage = VlmStage.FROZEN, result = null, diagnostics = null, previews = emptyMap(), reference = null, selectedId = null, fromImportedImage = true, facePreview = null, availableActions = emptyMap(), availableExpressions = emptyMap()) }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { if (token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, error = if ((e as? ModelFailure)?.code == "LOCAL_PHOTO_INVALID_OUTPUT") models.message(R.string.vlm_photo_model_invalid) else e.message) } }
                }
            }
            VlmIntent.Capture -> if (_state.value.stage == VlmStage.LIVE && _state.value.cameraReady && !busy() && !_state.value.settingsVisible && !_state.value.photoSettingsVisible) {
                val token = newVlmId()
                captureToken = token
                _state.update { it.copy(saving = true, error = null) }
                _effects.tryEmit(VlmEffect.Capture(token))
            }
            is VlmIntent.CaptureFinished -> if (intent.token == captureToken) {
                captureToken = null
                _state.update { it.copy(saving = false, error = intent.error, notice = if (intent.error == null) "照片已保存到系统相册" else null) }
            }
        }
    }

    private fun closeSample() {
        sampleWork?.cancel(); sampleWork = null
        _state.update { it.copy(photoSample = null) }
    }

    private fun requestSample(expression: ExpressionId,
                              ethnicity: AvatarEthnicity = _state.value.photoSample?.ethnicity ?: _state.value.selectedEthnicity,
                              actionId: String = _state.value.photoSample?.actionId ?: PhotoActionCatalog.SAMPLE_ID) {
        sampleWork?.cancel()
        val token = newVlmId()
        _state.update { it.copy(photoSample = PhotoSampleState(token, expression, ethnicity = ethnicity, actionId = actionId)) }
        sampleWork = viewModelScope.launch {
            try {
                val base = CompositionPlan("photo-sample-$token", "", ShotType.FULL, CropRect(), 1f,
                    AvatarPlacement(foot = Point2(.5f, .94f), height = .84f,
                        avatarId = AvatarId.ADULT_FEMALE, pose = PoseId.RELAXED,
                        expression = expression, expressionIntensity = .8f, ethnicity = ethnicity), "", "")
                val resolved = previews.selectAction(base, actionId)
                val selected = resolved.withActualAvatar(resolved.avatar.copy(expressionIntensity =
                    resolved.resolvedSpec?.action?.expressionProfile?.defaultIntensity ?: .8f))
                val body = previews.sample(selected)
                val applied = selected.withActualAvatar(body.appliedAvatar ?: selected.avatar)
                    .copy(resolvedSpec = body.appliedSpec ?: selected.resolvedSpec)
                val face = previews.face(applied)
                _state.update { current ->
                    val sample = current.photoSample
                    if (sample == null || sample.token != token) current else current.copy(
                        photoSample = sample.copy(body = body.bitmap, face = face?.bitmap, loading = false))
                }
                Log.i("VlmAvatarSample", "样片渲染完成 token=$token ethnicity=$ethnicity expression=$expression action=${applied.resolvedSpec?.action?.id}")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                Log.e("VlmAvatarSample", "样片渲染失败 token=$token", failure)
                _state.update { current ->
                    val sample = current.photoSample
                    if (sample == null || sample.token != token) current else current.copy(
                        photoSample = sample.copy(loading = false, error = models.message(R.string.vlm_photo_sample_failed)))
                }
            }
        }
    }

    private fun startScan() {
        val old = _state.value
        if (!old.canRestartScan) return
        val previousWork = work
        val previousRender = renderWork
        val previousFace = faceWork
        val previousScan = scanWork
        sessions.stop(SystemClock.elapsedRealtimeNanos())
        scanQueue?.close(); scanTimer?.cancel(); previousScan?.cancel()
        cancelWork()
        val token = requestToken
        Log.i(LOG_TAG, "重新开始扫描 oldStage=${old.stage} oldScan=${old.scanSession?.id?.take(8)} token=${token.take(8)} nativeDraining=${local.isInferenceRunning}")
        _state.update { it.copy(stage = VlmStage.SCANNING, scanSession = null, scanSnapshot = null,
            scanProgress = ScanProgress(), viewCandidates = emptyList(), selectedFrameId = null, comparedFrames = 0, manualScanSelectionVisible = false,
            scene = null, result = null, diagnostics = null, previews = emptyMap(), reference = null, referencePlan = null,
            availableActions = emptyMap(), availableExpressions = emptyMap(), facePreview = null,
            background = BackgroundGuidance(), error = null,
            notice = if (local.isInferenceRunning) models.message(R.string.vlm_flow_waiting_native) else null, directorProgress = null) }
        work = viewModelScope.launch {
            try {
                previousWork?.join(); previousRender?.join(); previousFace?.join(); previousScan?.join(); cleanupWork?.join()
                awaitPreviousNative(token)
                val started = SystemClock.elapsedRealtimeNanos()
                val session = sessions.start(started)
                if (token != requestToken) return@launch
                Log.i(LOG_TAG, "新扫描窗口已开始 scan=${session.id.take(8)} token=${token.take(8)} nativeBusy=${local.isInferenceRunning}")
                sessions.configure(_state.value.cameraConfigVersion)
                val queue = Channel<AnalysisSample>(1, BufferOverflow.DROP_OLDEST)
                scanQueue = queue
                _state.update { it.copy(scanSession = session, notice = null) }
                scanTimer = viewModelScope.launch {
                    while (isActive) {
                        delay(250)
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (now - started >= ScanLimits.MAX_DURATION_NS) sessions.pause()
                        val progress = sessions.progress(now)
                        _state.update { current -> if (current.scanSession?.id != session.id || current.stage != VlmStage.SCANNING) current
                            else current.copy(scanProgress = progress, notice = if (progress.paused) models.message(R.string.vlm_scan_limit) else current.notice) }
                    }
                }
                scanWork = viewModelScope.launch {
                    var lastProcessingLogNs = 0L
                    try {
                        for (sample in queue) {
                            val processingStart = SystemClock.elapsedRealtimeNanos()
                            val progress = sessions.collect(sample)
                            val processingEnd = SystemClock.elapsedRealtimeNanos()
                            if (processingEnd-lastProcessingLogNs >= 2_000_000_000L) {
                                lastProcessingLogNs = processingEnd
                                Log.i("VlmScan", "扫描处理性能 scan=${session.id.take(8)} collectMs=${(processingEnd-processingStart)/1_000_000} queueMs=${(processingStart-sample.receivedNs)/1_000_000} frames=${progress.frames} config=${sample.config.version}")
                            }
                            _state.update { current -> if (current.scanSession?.id != session.id) current else current.copy(scanProgress = progress) }
                        }
                        val snapshot = sessions.finish()
                        if (_state.value.scanSession?.id != session.id) return@launch
                        _state.update { it.copy(stage = VlmStage.SCAN_REVIEW, scanSnapshot = snapshot,
                            error = if (snapshot.frames.isEmpty()) models.message(R.string.vlm_scan_empty) else null,
                            notice = if (!analyzeAfterStop) models.message(R.string.vlm_scan_interrupted) else it.notice) }
                        if (analyzeAfterStop && foreground && snapshot.frames.isNotEmpty()) analyzeScan()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        sessions.stop(SystemClock.elapsedRealtimeNanos()); queue.close()
                        val snapshot = sessions.finish()
                        if (_state.value.scanSession?.id == session.id) _state.update { it.copy(stage = VlmStage.SCAN_REVIEW, scanSnapshot = snapshot, error = models.message(R.string.vlm_scan_failed)) }
                    } finally { scanTimer?.cancel() }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, error = models.message(R.string.vlm_scan_failed)) } }
        }
    }

    private fun stopScan(analyze: Boolean) {
        if (_state.value.stage != VlmStage.SCANNING) return
        if (_state.value.scanSession == null) { cancelScan(); return }
        analyzeAfterStop = analyze
        sessions.stop(SystemClock.elapsedRealtimeNanos())
        _state.update { it.copy(stage = VlmStage.SCAN_PROCESSING) }
        scanQueue?.close(); scanTimer?.cancel()
    }

    private fun cancelScan() {
        val previousWork = work; val previousRender = renderWork; val previousFace = faceWork
        cancelWork(); scanQueue?.close(); scanTimer?.cancel()
        val previous = scanWork
        previous?.cancel()
        val precedingCleanup = cleanupWork
        cleanupWork = viewModelScope.launch { previous?.join(); previousWork?.join(); previousRender?.join(); previousFace?.join(); precedingCleanup?.join(); local.awaitIdle(); sessions.clear() }
        _state.update { it.copy(stage = VlmStage.LIVE, scanSession = null, scanSnapshot = null, scene = null,
            scanProgress = ScanProgress(), viewCandidates = emptyList(), selectedFrameId = null, comparedFrames = 0, manualScanSelectionVisible = false,
            result = null, diagnostics = null, previews = emptyMap(), reference = null, referencePlan = null,
            background = BackgroundGuidance(), facePreview = null, error = null, notice = null) }
    }

    private fun analyzeScan() {
        val s = _state.value
        val scan = s.scanSnapshot ?: return
        if (busy() || scan.frames.isEmpty()) {
            Log.w("VlmScan", "选景未启动 scan=${scan.session.id.take(8)} stage=${s.stage} busy=${busy()} frames=${scan.frames.size}")
            return
        }
        cancelWork()
        val token = requestToken
        _state.update { it.copy(stage = VlmStage.VIEW_SELECTING, error = null, notice = null, manualScanSelectionVisible = false) }
        Log.i("VlmScan", "选景状态开始 scan=${scan.session.id.take(8)} token=${token.take(8)} mode=${s.settings.mode} stage=${_state.value.stage} cachedFrames=${scan.frames.size} scenePresent=${s.scene != null}")
        work = viewModelScope.launch {
            try {
                if (s.settings.mode == ModelMode.OFFLINE) awaitPreviousNative(token)
                val selected = if (s.settings.mode == ModelMode.OFFLINE)
                    OfflineScanViewAgent(scanLogger).select(scan, s.instruction, OfflineViewInference(local::compareViews))
                else ScanViewAgent(logger = scanLogger).select(scan, s.settings, s.instruction, sessions.budget)
                if (token != requestToken) {
                    Log.w("VlmScan", "丢弃迟到选景 scan=${scan.session.id.take(8)} token=${token.take(8)} currentToken=${requestToken.take(8)}")
                    return@launch
                }
                val selection = selected.selection
                Log.i("VlmScan", "选景结果检查 scan=${scan.session.id.take(8)} token=${token.take(8)} selectionPresent=${selection != null} compared=${selected.comparedFrames} fallback=${selected.fallback}")
                if (selection == null) _state.update { it.copy(stage = VlmStage.SCAN_REVIEW, notice = models.message(when (selected.fallback) {
                    ScanSelectionFallback.OFFLINE -> R.string.vlm_scan_auto_offline
                    ScanSelectionFallback.NO_MULTIVIEW_MODEL -> R.string.vlm_scan_auto_undeclared
                    ScanSelectionFallback.CAPABILITY_UNAVAILABLE -> R.string.vlm_scan_auto_gateway_unavailable
                    else -> R.string.vlm_scan_auto_unavailable
                })) }
                else {
                    _state.update { it.copy(stage = VlmStage.SCAN_REVIEW, viewCandidates = listOf(selection.primary) + selection.alternatives,
                        comparedFrames = selected.comparedFrames, notice = models.message(R.string.vlm_scan_compared, selected.comparedFrames)) }
                    chooseScanFrame(selection.primary.frameId)
                }
            } catch (cancel: CancellationException) {
                Log.i("VlmScan", "选景已取消 scan=${scan.session.id.take(8)} token=${token.take(8)} stage=${_state.value.stage}")
                throw cancel
            }
            catch (failure: Exception) {
                Log.e("VlmScan", "选景流程失败 scan=${scan.session.id.take(8)} token=${token.take(8)} code=${(failure as? ModelFailure)?.code} http=${(failure as? ModelFailure)?.upstreamHttpStatus} upstream=${(failure as? ModelFailure)?.upstreamErrorCode} error=${failure::class.java.simpleName} stale=${token != requestToken}", failure)
                if (token == requestToken) _state.update { it.copy(stage = VlmStage.SCAN_REVIEW,
                    error = cloudFailureMessage(failure) ?: if ((failure as? ModelFailure)?.code == "SELECTION_ATTEMPTS_EXHAUSTED")
                        models.message(R.string.vlm_scan_selection_attempts_exhausted) else if (failure.message == "SESSION_BUDGET_EXHAUSTED" || (failure as? ModelFailure)?.code == "SESSION_BUDGET_EXHAUSTED")
                        models.message(R.string.vlm_scan_budget_exhausted) else if ((failure as? ModelFailure)?.code == "LOCAL_VIEW_INVALID_OUTPUT") models.message(R.string.vlm_scan_local_invalid_output)
                    else if (s.settings.mode == ModelMode.OFFLINE) failure.message?.takeIf { it.isNotBlank() } ?: models.message(R.string.vlm_scan_local_select_failed)
                    else models.message(R.string.vlm_scan_select_failed)) }
                val current = _state.value
                Log.i("VlmScan", "选景失败后的界面状态 scan=${scan.session.id.take(8)} stage=${current.stage} scenePresent=${current.scene != null} displayScene=${current.displayScene?.id?.take(8)} provisional=${current.scene == null && current.displayScene != null} selectedFrame=${current.selectedFrameId?.take(8)} cachedFrames=${current.scanSnapshot?.frames?.size ?: 0} errorPresent=${current.error != null}")
            }
        }
    }

    private fun chooseScanFrame(frameId: String) {
        val s = _state.value
        if (busy()) {
            Log.w("VlmScan", "冻结选景被拦截 frame=${frameId.take(8)} stage=${s.stage} busy=true")
            return
        }
        val frame = s.scanSnapshot?.frames?.firstOrNull { it.id == frameId } ?: run {
            Log.w("VlmScan", "冻结选景失败 frame=${frameId.take(8)} reason=FRAME_NOT_IN_SNAPSHOT cachedFrames=${s.scanSnapshot?.frames?.size ?: 0}")
            return
        }
        Log.i("VlmScan", "冻结选景来源检查 scan=${frame.scanId.take(8)} frame=${frame.id.take(8)} scene=${frame.scene.id.take(8)} imageExists=${File(frame.scene.imagePath).isFile} modelImageExists=${File(frame.scene.modelImagePath).isFile} config=${frame.config.version}")
        cancelWork()
        _state.update { it.copy(scene = frame.scene, selectedFrameId = frame.id, stage = VlmStage.FROZEN, manualScanSelectionVisible = false,
            result = null, diagnostics = null, previews = emptyMap(), reference = null, referencePlan = null,
            availableActions = emptyMap(), availableExpressions = emptyMap(), facePreview = null,
            background = BackgroundGuidance(), selectedId = null, fromImportedImage = false, error = null) }
        Log.i("VlmScan", "选景已冻结 scan=${frame.scanId.take(8)} frame=${frame.id.take(8)} stage=${_state.value.stage} next=COMPOSE")
        compose(false)
    }

    private fun inputFor(s: VlmUiState, existing: List<CompositionPlan> = emptyList(), selectedId: String? = null): DirectorInput {
        val scene = requireNotNull(s.scene)
        val frame = s.scanSnapshot?.frames?.firstOrNull { it.id == scene.id }
        val actor = existing.firstOrNull()?.avatar?.avatarId ?: s.selectedAvatar
        val ethnicity = existing.firstOrNull()?.avatar?.ethnicity ?: s.selectedEthnicity
        return DirectorInput(scene, s.camera, s.instruction, existing, selectedId, actor,
            frame?.let { PhotoViewBinding(it.scanId, it.id, it.config.version, it.config.arSessionId) } ?: scene.arBinding,
            s.viewCandidates.firstOrNull { it.frameId == scene.id }?.backgroundRegions.orEmpty(),
            if (frame != null) sessions.budget else null,
            photoReferences = actor == AvatarId.ADULT_FEMALE, ethnicity = ethnicity, arMode = s.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA)
    }

    private fun guidanceTarget(plan: CompositionPlan) = "guide:${plan.viewBinding?.frameId}:${plan.id}:${plan.revision}"

    /** 判断互斥工作是否运行；无参数，用于限制点击和模型资源操作。 */
    private fun busy(): Boolean = _state.value.let { it.photoSample != null || it.modelBusy || it.saving || it.stage in listOf(VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING) }

    /** 接收相机复制的帧，保存后自动启动构图。
     * @param event 帧所有权和拍摄时对应的请求编号。
     */
    private fun receiveFrame(event: VlmIntent.FrameReady) {
        if (event.token != requestToken) { event.bitmap.recycle(); return }
        work = viewModelScope.launch {
            try {
                val saved = snapshots.save(event.bitmap, event.zoom)
                val scene = saved.copy(arBinding = event.arConfig?.let { PhotoViewBinding("ar:${it.arSessionId}", saved.id, it.version, it.arSessionId) })
                if (event.token == requestToken) {
                    _state.update { it.copy(scene = scene, stage = VlmStage.FROZEN, result = null, diagnostics = null, previews = emptyMap(), reference = null, selectedId = null, fromImportedImage = false, directorProgress = null, facePreview = null, availableActions = emptyMap(), availableExpressions = emptyMap()) }
                    compose(false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (event.token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, error = if ((e as? ModelFailure)?.code == "LOCAL_PHOTO_INVALID_OUTPUT") models.message(R.string.vlm_photo_model_invalid) else e.message) } }
            finally { event.bitmap.recycle() }
        }
    }

    /** 启动两轮以内的导演流程。
     * @param revise true 表示只修改当前选中方案，false 表示重新生成三个方案。
     */
    private fun compose(revise: Boolean) {
        val previous = _state.value
        val scene = previous.scene ?: return
        if (!previous.canGenerate || revise && previous.stage != VlmStage.READY) return
        if (revise && (previous.result == null || previous.selectedId == null)) return
        val previousWork = work; val previousRender = renderWork; val previousFace = faceWork
        cancelWork()
        val token = requestToken
        val input = inputFor(previous, if (revise) previous.result!!.plans else emptyList(), if (revise) previous.selectedId else null)
        Log.i(LOG_TAG, "启动${if (revise) "修改方案" else "生成方案"}任务 token=${token.take(8)} image=${scene.id.take(8)} mode=${previous.settings.mode} modelStatus=${previous.modelStatus}")
        _state.update { it.copy(stage = if (revise) VlmStage.MODIFYING else VlmStage.GENERATING, error = null,
            notice = if (local.isInferenceRunning) models.message(R.string.vlm_flow_waiting_native) else null,
            directorProgress = if (local.isInferenceRunning) DirectorProgress.WaitingPreviousTask else DirectorProgress.PreparingRequest, facePreview = null) }
        work = viewModelScope.launch {
            var actualTrace: CallTrace? = null
            try {
                previousWork?.join(); previousRender?.join(); previousFace?.join()
                awaitPreviousNative(token)
                val result = repository.compose(input, previous.settings, previews) { progress ->
                    if (token == requestToken) {
                        Log.i(LOG_TAG, "构图步骤 token=${token.take(8)} image=${scene.id.take(8)} progress=$progress")
                        _state.update { it.copy(directorProgress = progress) }
                    }
                }
                actualTrace = result.traces.lastOrNull()
                Log.i(LOG_TAG, "构图结果进入预览 token=${token.take(8)} image=${scene.id.take(8)} plans=${result.plans.size}")
                val images = result.plans.mapIndexed { index, plan ->
                    if (token == requestToken) _state.update { it.copy(directorProgress = DirectorProgress.RenderingPreview(index + 1, result.plans.size)) }
                    plan.id to previews.preview(scene, plan)
                }.toMap()
                val resolved = result.copy(plans = result.plans.map { images.getValue(it.id).appliedPlan })
                check(PlanValidator().validate(resolved.plans, input).isEmpty()) { models.message(R.string.vlm_avatar_invalid_resolution) }
                val actions = resolved.plans.associate { it.id to previews.availableActions(it.avatar.avatarId, it.shot) }
                val expressions = resolved.plans.associate { it.id to previews.availableExpressions(it) }
                if (token == requestToken) {
                    val actual = result.traces.lastOrNull()
                    Log.i(LOG_TAG, "模型使用 stage=构图界面 result=成功 ${ModelUsageLog.identity(actual?.provider, previous.settings, actual?.model)} token=${token.take(8)} plans=${result.plans.size} traces=${result.traces.size}")
                    _state.update { it.copy(compositionDetailsVisible = true, result = resolved, diagnostics = resolved, previews = images, selectedId = previous.selectedId?.takeIf { id -> resolved.plans.any { p -> p.id == id } } ?: resolved.plans.first().id, stage = VlmStage.READY, directorProgress = null, availableActions = actions, availableExpressions = expressions) }
                    Log.i(LOG_TAG, "构图界面已就绪 token=${token.take(8)} stage=${_state.value.stage} scenePresent=${_state.value.scene != null} previews=${images.size} selectedPlan=${_state.value.selectedId?.take(8)}")
                    requestFace()
                }
            } catch (cancel: CancellationException) {
                Log.i(LOG_TAG, "模型使用 stage=构图界面 result=取消 ${ModelUsageLog.identity(actualTrace?.provider, previous.settings, actualTrace?.model)} token=${token.take(8)}")
                throw cancel
            }
            catch (e: Exception) {
                val trace = actualTrace ?: (e as? ModelFailure)?.diagnostics?.traces?.lastOrNull()
                Log.e(LOG_TAG, "模型使用 stage=构图界面 result=失败 ${ModelUsageLog.identity(trace?.provider, previous.settings, trace?.model)} token=${token.take(8)} ${ModelUsageLog.failure(e)}")
                Log.e(LOG_TAG, "构图任务失败 token=${token.take(8)} error=${e::class.java.simpleName} http=${(e as? ModelFailure)?.upstreamHttpStatus} upstream=${(e as? ModelFailure)?.upstreamErrorCode}", e)
                val message = cloudFailureMessage(e) ?: if ((e as? ModelFailure)?.code == "SESSION_BUDGET_EXHAUSTED" || e.message == "SESSION_BUDGET_EXHAUSTED") models.message(R.string.vlm_scan_budget_exhausted) else if ((e as? ModelFailure)?.code == "LOCAL_PHOTO_INVALID_OUTPUT") models.message(R.string.vlm_photo_model_invalid) else if ((e as? ModelFailure)?.code == "LOCAL_INVALID_OUTPUT") models.message(R.string.vlm_local_invalid_output) else e.message ?: "构图失败，请重试"
                if (token == requestToken) _state.update { it.copy(stage = if (it.result == null) VlmStage.FROZEN else VlmStage.READY, error = message, diagnostics = (e as? ModelFailure)?.diagnostics, directorProgress = null) }
            }
        }
    }

    private suspend fun awaitPreviousNative(token: String) {
        val waiting = local.isInferenceRunning
        val handoffAt = SystemClock.elapsedRealtime()
        if (waiting && token == requestToken) _state.update { it.copy(notice = models.message(R.string.vlm_flow_waiting_native),
            directorProgress = if (it.stage in listOf(VlmStage.GENERATING, VlmStage.MODIFYING)) DirectorProgress.WaitingPreviousTask else it.directorProgress) }
        local.awaitIdle()
        currentCoroutineContext().ensureActive()
        if (waiting) Log.i(LOG_TAG, "旧推理交接结束 token=${token.take(8)} waitingMs=${SystemClock.elapsedRealtime() - handoffAt} stale=${token != requestToken}")
        if (token == requestToken) _state.update { it.copy(
            notice = it.notice.takeUnless { message -> message == models.message(R.string.vlm_flow_waiting_native) },
            directorProgress = if (it.directorProgress == DirectorProgress.WaitingPreviousTask) DirectorProgress.PreparingRequest else it.directorProgress) }
    }

    /** 修改当前方案布局并节流刷新预览。
     * @param update 只改变人物布局的纯函数。
     */
    private fun adjust(update: (AvatarPlacement) -> AvatarPlacement) {
        if (busy()) return
        val old = _state.value
        val scene = old.scene ?: return
        val result = old.result ?: return
        val selected = result.plans.find { it.id == old.selectedId } ?: return
        val changed = selected.withActualAvatar(update(selected.avatar)).withRevision(selected.revision + 1)
        faceWork?.cancel()
        _state.update { it.copy(result = result.copy(plans = result.plans.map { p -> if (p.id == changed.id) changed else p }), previews = it.previews - changed.id, error = null, facePreview = null, faceLoading = false) }
        renderWork?.cancel()
        val token = requestToken
        renderWork = viewModelScope.launch {
            delay(120)
            try {
                val image = previews.preview(scene, changed)
                _state.update { current ->
                    val currentResult = current.result ?: return@update current
                    if (token != requestToken || currentResult.plans.find { it.id == changed.id }?.revision != changed.revision) current
                    else current.copy(
                        result = currentResult.copy(plans = currentResult.plans.map { if (it.id == changed.id) image.appliedPlan else it }),
                        previews = current.previews + (changed.id to image)
                    )
                }
                if (token == requestToken && _state.value.selectedId == changed.id) requestFace()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                _state.update { current ->
                    if (token == requestToken && current.result?.plans?.find { it.id == changed.id }?.revision == changed.revision)
                        current.copy(error = models.message(R.string.vlm_avatar_preview_failed, e.message.orEmpty()))
                    else current
                }
            }
        }
    }

    /** 动作/表情/角色外观以事务提交，失败保留原方案，且不调用模型。 */
    private fun changeSelection(actionId: String? = null, expression: ExpressionId? = null, intensity: Float? = null,
                                ethnicity: AvatarEthnicity? = null) {
        val original = _state.value
        if (busy() || original.stage != VlmStage.READY) return
        val result = original.result ?: return
        val selected = result.plans.find { it.id == original.selectedId } ?: return
        val scene = original.scene ?: return
        if (ethnicity != null && (!PhotoActionCatalog.isRainAction(selected.resolvedSpec?.action?.id) ||
                selected.avatar.ethnicity == ethnicity)) return
        if (actionId != null && original.availableActions[selected.id].orEmpty().none { it.id == actionId } ||
            expression != null && expression !in original.availableExpressions[selected.id].orEmpty()) {
            _state.update { it.copy(error = models.message(R.string.vlm_photo_action_unavailable)) }
            return
        }
        cancelWork()
        val token = requestToken
        _state.update { it.copy(stage = VlmStage.MODIFYING, directorProgress = DirectorProgress.RenderingPreview(1, 1), error = null, faceLoading = false) }
        work = viewModelScope.launch {
            try {
                val updated = selected.withActualAvatar(selected.avatar.copy(expression = expression ?: selected.avatar.expression,
                    expressionIntensity = intensity ?: selected.avatar.expressionIntensity,
                    ethnicity = ethnicity ?: if (PhotoActionCatalog.isRainAction(actionId))
                        selected.avatar.ethnicity ?: original.selectedEthnicity else selected.avatar.ethnicity,
                    projectionCalibrated = if (ethnicity != null) false else selected.avatar.projectionCalibrated))
                val resolveActionId = actionId ?: if (ethnicity != null) selected.resolvedSpec?.action?.id else null
                val changed = (if (resolveActionId != null) previews.selectAction(updated, resolveActionId) else updated)
                    .withRevision(selected.revision + 1)
                val image = previews.preview(scene, changed)
                check(previews.inspect(image.appliedPlan, scene).errors.isEmpty())
                val replacement = result.plans.map { if (it.id == selected.id) image.appliedPlan else it }
                check(PlanValidator().validate(replacement, inputFor(original, original.result?.plans.orEmpty(), original.selectedId)).isEmpty())
                val actions = previews.availableActions(image.appliedPlan.avatar.avatarId, image.appliedPlan.shot)
                val expressions = previews.availableExpressions(image.appliedPlan)
                _state.update { current ->
                    if (token != requestToken || current.result?.plans?.find { it.id == selected.id }?.revision != selected.revision) current
                    else current.copy(stage = VlmStage.READY, directorProgress = null, result = result.copy(plans = replacement),
                        previews = current.previews + (selected.id to image), facePreview = null,
                        selectedEthnicity = ethnicity ?: current.selectedEthnicity,
                        availableActions = current.availableActions + (selected.id to actions),
                        availableExpressions = current.availableExpressions + (selected.id to expressions))
                }
                if (token == requestToken) {
                    if (ethnicity != null && _state.value.result?.plans?.find { it.id == selected.id } == image.appliedPlan)
                        settingsStore.saveAvatarEthnicity(ethnicity)
                    requestFace()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                Log.w(LOG_TAG, "动作/表情/外观事务失败 plan=${selected.id} revision=${selected.revision} ethnicity=$ethnicity", failure)
                if (token == requestToken) _state.update { it.copy(stage = VlmStage.READY, directorProgress = null,
                    error = models.message(R.string.vlm_photo_change_failed)) }
            }
        }
    }

    private fun requestFace() {
        faceWork?.cancel()
        val s = _state.value
        if (!s.faceVisible || s.stage !in listOf(VlmStage.READY, VlmStage.LIVE) || busy()) return
        val plan = (if (s.stage == VlmStage.LIVE) s.referencePlan else s.result?.plans?.find { it.id == s.selectedId }) ?: return
        // 人物布局刷新前不请求示范，避免使用尚未校准的事实。
        if ((if (s.stage == VlmStage.LIVE) s.reference else s.previews[plan.id])?.appliedPlan != plan) return
        val token = requestToken
        _state.update { it.copy(facePreview = null, faceLoading = true) }
        faceWork = viewModelScope.launch {
            try {
                val face = previews.face(plan) ?: error(models.message(R.string.vlm_photo_face_unavailable))
                _state.update { current ->
                    if (token == requestToken && current.faceVisible && current.selectedId == plan.id &&
                        current.result?.plans?.find { it.id == plan.id }?.revision == plan.revision)
                        current.copy(facePreview = face.bitmap, faceLoading = false)
                    else current
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (token == requestToken) _state.update { current ->
                    if (current.faceVisible && current.selectedId == plan.id && current.result?.plans?.find { it.id == plan.id }?.revision == plan.revision)
                        current.copy(faceLoading = false, error = models.message(R.string.vlm_photo_face_unavailable))
                    else current
                }
            }
        }
    }

    /** 校验并应用选中方案；无参数，真实空间定位不由冻结图保证。 */
    private fun applyPlan() {
        if (busy()) return
        val s = _state.value
        val result = s.result ?: return
        val plan = result.plans.find { it.id == s.selectedId } ?: return
        val scene = s.scene ?: return
        val errors = PlanValidator().validate(result.plans, inputFor(s, result.plans, s.selectedId))
        if (errors.isNotEmpty()) { _state.update { it.copy(error = if (errors.any { error -> "RESOLVED_SPEC_MISMATCH" in error || "VIEW_BINDING_MISMATCH" in error }) models.message(R.string.vlm_photo_spec_invalid) else errors.joinToString("\n")) }; return }
        cancelWork()
        val token = requestToken
        work = viewModelScope.launch {
            try {
                _state.update { it.copy(stage = VlmStage.MODIFYING) }
                val projectionErrors = previews.inspect(plan, scene).errors
                check(projectionErrors.isEmpty()) { projectionErrors.joinToString("\n") }
                val currentPreview = previews.preview(scene, plan)
                val applied = currentPreview.appliedPlan
                val appliedPlans = result.plans.map { if (it.id == applied.id) applied else it }
                check(PlanValidator().validate(appliedPlans, inputFor(s, result.plans, s.selectedId)).isEmpty()) { models.message(R.string.vlm_avatar_invalid_resolution) }
                if (token == requestToken) Log.i("VlmAr", "应用参考 source=${s.ar.source} plan=${applied.id} revision=${applied.revision} frame=${applied.viewBinding?.frameId} requiresBackgroundConfirmation=${applied.viewBinding != null}")
                if (token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, result = result.copy(plans = appliedPlans), reference = currentPreview, referencePlan = applied, background = BackgroundGuidance(), zoom = if (s.cameraMode == CameraMode.AR) 1f else applied.zoom, notice = if (applied.needsRetake || s.fromImportedImage) models.message(R.string.vlm_reference_retake) else models.message(R.string.vlm_reference_applied)) }
                if (token == requestToken) requestFace()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (token == requestToken) _state.update { it.copy(stage = VlmStage.READY, error = if ((e as? ModelFailure)?.code == "LOCAL_PHOTO_INVALID_OUTPUT") models.message(R.string.vlm_photo_model_invalid) else e.message) } }
        }
    }

    /** 串行执行模型管理工作，不允许与构图并发。
     * @param label 页面展示的操作名称。
     * @param operation 实际导入、加载、卸载或检查任务。
     */
    private fun maintain(label: String, operation: suspend () -> Unit) {
        if (busy()) { _state.update { it.copy(error = "请先结束当前构图或拍摄，再管理模型") }; return }
        maintenanceBusy = true
        _state.update { it.copy(modelBusy = true, modelStatus = label, error = null) }
        maintenance = viewModelScope.launch {
            try { operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { _state.update { it.copy(error = cloudFailureMessage(e) ?: if (e is ModelFailure) e.message else models.errorMessage(e)) } }
            finally { maintenanceBusy = false; _state.update { it.copy(modelBusy = local.loadState.value.phase == LocalModelPhase.LOADING, modelStatus = modelDescription()) } }
        }
    }

    private fun cloudFailureMessage(failure: Exception): String? {
        qwenFailureMessage(failure)?.let { return it }
        val resource = seedErrorResource(failure) ?: return null
        val message = models.message(resource)
        val modelFailure = failure as ModelFailure
        val status = modelFailure.upstreamHttpStatus ?: return message
        return models.message(R.string.vlm_seed_error_details, message, status,
            modelFailure.upstreamErrorCode ?: models.message(R.string.vlm_qwen_unknown_error))
    }

    private fun qwenFailureMessage(failure: Exception): String? {
        val resource = qwenErrorResource(failure) ?: return null
        val message = models.message(resource)
        val modelFailure = failure as ModelFailure
        val status = modelFailure.upstreamHttpStatus ?: return message
        return models.message(R.string.vlm_qwen_error_details, message, status,
            modelFailure.upstreamErrorCode ?: models.message(R.string.vlm_qwen_unknown_error))
    }

    /** 取消会话计算并更新令牌；无参数，不能用其并发销毁原生句柄。 */
    private fun cancelWork() {
        requestToken = newVlmId()
        work?.cancel()
        renderWork?.cancel()
        faceWork?.cancel()
        _state.update { it.copy(faceLoading = false) }
    }

    /** UI 离开相机模块时取消平台相关操作；无参数，保留冻结会话供返回查看。 */
    fun onLeave(resetCameraMode: Boolean = true) {
        if (resetCameraMode) switchCameraMode(CameraMode.NORMAL)
        closeSample()
        if (_state.value.stage == VlmStage.SCANNING) stopScan(false)
        foreground = false
        guidanceEpoch++
        cancelWork()
        captureToken = null
        _state.update { it.copy(cameraReady = false, saving = false, photoSettingsVisible = false, compositionDetailsVisible = false, stage = if (it.stage == VlmStage.SCAN_PROCESSING || it.stage == VlmStage.SCAN_REVIEW) it.stage else if (it.scanSnapshot != null && it.scene == null) VlmStage.SCAN_REVIEW else if (it.scene != null && it.stage != VlmStage.LIVE) { if (it.result == null) VlmStage.FROZEN else VlmStage.READY } else VlmStage.LIVE, directorProgress = null) }
    }

    override fun onCleared() {
        scanQueue?.close(); guideQueue.close(); scanTimer?.cancel()
        val retiring = listOfNotNull(work, renderWork, faceWork, sampleWork, scanWork, guideWork, cleanupWork)
        retiring.forEach { it.cancel() }
        // ViewModel 的作用域已销毁；等文件读取和串行分析退出后删除本地扫描资源。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            retiring.forEach { it.join() }
            local.awaitIdle()
            sessions.dispose()
        }
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "VlmCamera"
    }
}
