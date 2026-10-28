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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
    private val sessions: PhotoSessionController,
    private val marketDownloads: com.framer.sense.feature.camera.vlm.market.MarketDownloadManager? = null,
    private val generatedRepository: GeneratedReferenceGenerator? = null,
    private val referenceNetwork: ReferenceNetworkState = ReferenceNetworkState { true },
    private val historyRepository: CameraCompositionHistory? = null
) : ViewModel() {
    private val initialPhotoPreferences = settingsStore.readPhotoPreferences()
    private val initialSettings = settingsStore.read()
    private val _state = MutableStateFlow(VlmUiState(settings = initialSettings,
        automaticReferenceSource = resolveReferenceSource(initialSettings), modelStatus = modelDescription(),
        photoPreferences = initialPhotoPreferences, selectedAvatar = initialPhotoPreferences.avatar,
        instructionHistory = settingsStore.readInstructionHistory(),
        faceVisible = initialPhotoPreferences.showFace,
        instruction = initialPhotoPreferences.instruction,
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
    private var pendingCaptureWorkflow: CameraWorkflowKey? = null
    private var work: Job? = null
    private val settingsWrites = Mutex()
    private var generationModelRevision = 0L
    private val generatedJobs = mutableMapOf<String, Job>()
    private val generatedPermits = Semaphore(1)
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
    private var lastGuidanceRejectedLogNs = 0L
    private var modelLoaded = local.loadState.value.phase == LocalModelPhase.READY
    private var maintenanceBusy = false
    private var attemptedPath: String? = null
    private var failedAutoLoadPath: String? = null
    private data class HistoryWrite(val capture: HistoryCapture, val candidate: GeneratedCandidate, val title: String, val modelName: String)
    private val failedHistoryWrites = mutableMapOf<String, HistoryWrite>()

    init {
        historyRepository?.let { history ->
            viewModelScope.launch {
                history.entries.catch { _state.update { it.copy(error = models.message(R.string.vlm_history_load_failed)) } }
                    .collect { rows -> _state.update { it.copy(historyEntries = rows) } }
            }
        }
        guideWork = viewModelScope.launch {
            for (sample in guideQueue) {
                val s = _state.value
                val plan = s.activeBackgroundTarget ?: continue
                val scene = s.scene ?: continue
                val rejection = when {
                    s.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA && s.ar.tracking in listOf(com.framer.sense.feature.camera.vlm.ar.ArTrackingState.LOST, com.framer.sense.feature.camera.vlm.ar.ArTrackingState.PAUSED) -> "tracking"
                    !foreground -> "background"
                    s.photoSample != null -> "photoSample"
                    sample.receivedNs < foregroundSinceNs -> "oldFrame"
                    s.stage != VlmStage.LIVE -> "stage"
                    sample.targetId != guidanceTarget(plan) -> "target"
                    sample.config.version != s.cameraConfigVersion -> "config"
                    sample.config.orientation.deviceRotation != s.photoOrientation.deviceRotation -> "orientation"
                    else -> null
                }
                if (rejection != null) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (now - lastGuidanceRejectedLogNs >= 2_000_000_000L) {
                        Log.i("VlmScan", "背景对齐帧未接收 reason=$rejection frameConfig=${sample.config.version} activeConfig=${s.cameraConfigVersion} frameDirection=${sample.config.orientation.deviceRotation} activeDirection=${s.photoOrientation.deviceRotation}")
                        lastGuidanceRejectedLogNs = now
                    }
                    continue
                }
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
                        if (!foreground || epoch != guidanceEpoch || token != requestToken || current.activeBackgroundTarget != plan || current.cameraConfigVersion != sample.config.version || current.stage != VlmStage.LIVE) current
                        else current.withBackgroundGuidance(mergeBackgroundAlignment(current.background, aligned))
                    }
                    if (s.background.status != BackgroundStatus.ALIGNED_AUTO && aligned.status == BackgroundStatus.ALIGNED_AUTO) {
                        val committed = _state.value
                        Log.i("VlmScan", "自动对齐结果提交 observed=${aligned.status} applied=${committed.background.status} sameReference=${committed.activeBackgroundTarget == plan} sameEpoch=${epoch == guidanceEpoch} sameRequest=${token == requestToken} foreground=$foreground stage=${committed.stage}")
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    Log.w("VlmScan", "背景对齐处理失败", failure)
                    if (token == requestToken) _state.update { it.withBackgroundGuidance(BackgroundGuidance()) }
                }
            }
        }
        savedStateHandle["vlm_started"] = true
        viewModelScope.launch { (marketDownloads?.summary ?: downloads.state).collect { value -> _state.update { it.copy(download = value) } } }
        viewModelScope.launch { local.loadState.collect { value ->
            modelLoaded = value.phase == LocalModelPhase.READY
            value.path?.let { attemptedPath = it }
            if (modelLoaded && models.modelId() == downloads.state.value.modelId && downloads.state.value.phase == DownloadPhase.DOWNLOADED && models.pendingDirectory() == null)
                downloads.loadingState(DownloadPhase.READY)
            _state.update { it.copy(modelBusy = maintenanceBusy || value.phase == LocalModelPhase.LOADING,
                modelStatus = if (maintenanceBusy && value.phase != LocalModelPhase.LOADING) it.modelStatus else modelDescription(value)) }
        } }
        viewModelScope.launch { models.changes.collect { version ->
            _state.update { it.copy(modelInventoryVersion = version, localModelName = models.activeDirectory()?.let { models.modelName() }) }
        } }
        viewModelScope.launch { state.collect { maybeAutoLoad() } }
    }

    private fun updatePhotoPreferences(change: (PhotoPreferences) -> PhotoPreferences) {
        val value = change(_state.value.photoPreferences)
        settingsStore.savePhotoPreferences(value)
        _state.update { it.copy(photoPreferences = value) }
    }

    private fun resolveReferenceSource(settings: ModelSettings): ReferenceSource =
        CameraReferenceRouting.source(settings, settings.mode != ModelMode.AUTO || referenceNetwork.isAvailable())

    /** 仅在尚无构图任务的新流程重新判断网络；在途或已生成参考不随网络抖动切换。 */
    private fun refreshAutomaticReferenceRoute() {
        val current = _state.value
        if (current.settings.mode != ModelMode.AUTO || current.operationBusy || current.result != null ||
            current.compositionCache.isNotEmpty() || current.generated.candidates.isNotEmpty()) return
        val source = resolveReferenceSource(current.settings)
        _state.update { it.copy(automaticReferenceSource = source) }
        if (source == ReferenceSource.GENERATED_IMAGE && current.cameraMode == CameraMode.AR)
            switchCameraMode(CameraMode.NORMAL)
    }

    private fun switchCameraMode(mode: CameraMode) {
        val old = _state.value
        if (old.usesGeneratedReference && mode == CameraMode.AR) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_normal_required)) }; return
        }
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

    /** 非指定云端模式在前台空闲时自动加载目录；失败或卸载后不循环重试。 */
    private fun maybeAutoLoad() {
        if (_state.value.usesGeneratedReference || _state.value.settings.mode == ModelMode.CLOUD || !foreground || busy() || _state.value.instructionEditorVisible) return
        // 候选指针只在下载校验完成后发布；队列中其他模型下载不影响已完成候选。
        val dir = models.pendingDirectory() ?: models.activeDirectory() ?: return
        if (modelLoaded && local.loadState.value.path == dir.path) return
        if (attemptedPath == dir.path || failedAutoLoadPath == dir.path) return
        attemptedPath = dir.path
        maintain(models.message(R.string.vlm_model_loading)) { loadDirectory(dir) }
    }

    /** 加载候选或现有安装，加载失败明确保留文件以便重试。
     * @param dir 已校验模型所在目录。
     */
    private suspend fun loadDirectory(dir: File) {
        modelLoaded = false
        val id = models.modelId(dir)
        val legacyDownload = id == null || id.startsWith("local-") || id == downloads.state.value.modelId
        if (legacyDownload) downloads.loadingState(DownloadPhase.LOADING)
        try {
            if (models.activeDirectory() == dir) {
                local.load()
                if (models.pendingDirectory() == dir) models.activate(dir)
            } else local.install(dir)
            modelLoaded = true
            failedAutoLoadPath = null
            if (legacyDownload) downloads.loadingState(DownloadPhase.READY)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { if (legacyDownload) downloads.loadingState(DownloadPhase.DOWNLOADED) }
            throw cancel
        } catch (error: Exception) {
            modelLoaded = local.loadState.value.phase == LocalModelPhase.READY
            failedAutoLoadPath = dir.path
            if (legacyDownload) downloads.loadingState(DownloadPhase.LOAD_FAILED, models.message(R.string.vlm_model_load_failed))
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
            is VlmIntent.SelectGenerated -> selectGenerated(intent.id)
            is VlmIntent.RetryGenerated -> composeGenerated(intent.id)
            VlmIntent.ReplaceGenerated -> composeGenerated(_state.value.generated.selectedId)
            is VlmIntent.PhotoOrientationChanged -> {
                if (intent.orientation == _state.value.photoOrientation) return
                // 方向不是相机重绑：保留扫描、已冻结照片、请求令牌及生成结果。
                guidanceEpoch++
                sessions.resetReference()
                _state.update { it.withBackgroundGuidance(BackgroundGuidance()).copy(photoOrientation = intent.orientation) }
            }
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
            VlmIntent.RestartFlow -> restartFlow()
            VlmIntent.StopScan -> stopScan(true)
            VlmIntent.CancelScan -> cancelScan()
            VlmIntent.PreferScanView -> if (_state.value.stage == VlmStage.SCANNING) {
                val now = SystemClock.elapsedRealtimeNanos()
                val preferred = sessions.prefer(now)
                _state.update { it.copy(scanProgress = sessions.progress(now), notice = models.message(if (preferred) R.string.vlm_scan_preferred else R.string.vlm_scan_prefer_unavailable)) }
            }
            VlmIntent.AnalyzeScan -> analyzeScan()
            is VlmIntent.PreviewScanView -> previewScanFrame(intent.frameId)
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
                current.withBackgroundGuidance(if (current.ar.sessionId != intent.state.sessionId || intent.state.tracking in listOf(com.framer.sense.feature.camera.vlm.ar.ArTrackingState.LOST, com.framer.sense.feature.camera.vlm.ar.ArTrackingState.PAUSED)) BackgroundGuidance() else current.background)
                    .copy(ar = intent.state, zoom = if (intent.state.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA) 1f else current.zoom)
                }
            }
            is VlmIntent.CameraConfigured -> {
                if (intent.epoch != null && intent.epoch != _state.value.cameraEpoch) return
                guidanceEpoch++
                if (intent.version <= _state.value.cameraConfigVersion) return
                sessions.configure(intent.version)
                _state.update { it.withBackgroundGuidance(BackgroundGuidance()).copy(cameraConfigVersion = intent.version) }
            }
            is VlmIntent.AnalysisReceived -> {
                if (intent.epoch != null && intent.epoch != _state.value.cameraEpoch) return
                if (_state.value.stage == VlmStage.SCANNING) scanQueue?.trySend(intent.sample)
                else if (_state.value.stage == VlmStage.LIVE) guideQueue.trySend(intent.sample)
            }
            VlmIntent.ConfirmBackground -> if (_state.value.stage == VlmStage.LIVE && _state.value.activeBackgroundTarget?.viewBinding != null) {
                _state.update { it.copy(background = it.background.copy(status = BackgroundStatus.ALIGNED_MANUAL)) }
                val confirmed = _state.value
                Log.i("VlmScan", "手动对齐结果提交 applied=${confirmed.background.status} referenceReady=${confirmed.reference != null && confirmed.reference.appliedPlan == confirmed.referencePlan} presentation=${referencePresentation(confirmed.referencePlan, confirmed.background.status, confirmed.ar)}")
            }
            is VlmIntent.UserMessage -> _state.update { it.copy(error = intent.message) }
            VlmIntent.StartComposition -> if (_state.value.canStartSingleComposition) {
                cancelWork()
                _state.update { it.copy(stage = VlmStage.FREEZING, capturedWorkflow = null, error = null, notice = null, directorProgress = null,
                    scanSession = null, scanSnapshot = null, viewCandidates = emptyList(), selectedFrameId = null,
                    scene = null, result = null, compositionCache = emptyMap(), compositionDetailsExpanded = false, compositionBatchId = INITIAL_COMPOSITION_BATCH, diagnostics = null, previews = emptyMap(), reference = null, referencePlan = null,
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
                _state.update { it.withBackgroundGuidance(BackgroundGuidance()).copy(cameraReady = false) }
            }
            is VlmIntent.ZoomReported -> if (intent.epoch == _state.value.cameraEpoch && _state.value.cameraMode == CameraMode.NORMAL)
                _state.update { it.copy(actualZoom = intent.zoom) }
            is VlmIntent.AspectRatioSelected -> if (_state.value.historyAspectRatio == null && _state.value.framingUnlocked && intent.ratio != _state.value.photoPreferences.aspectRatio) {
                if (_state.value.usesGeneratedReference) cancelScan()
                guidanceEpoch++
                updatePhotoPreferences { it.copy(aspectRatio = intent.ratio) }
                _state.update { it.copy(cameraEpoch = it.cameraEpoch + 1, cameraReady = false,
                    ar = if (it.cameraMode == CameraMode.AR) com.framer.sense.feature.camera.vlm.ar.ArUiState(source = com.framer.sense.feature.camera.vlm.ar.ArSource.CHECKING) else it.ar,
                    reference = null, referencePlan = null, background = BackgroundGuidance()) }
            }
            is VlmIntent.CameraModeSelected -> if (_state.value.canChangeCameraMode) switchCameraMode(intent.mode)
            is VlmIntent.HistoryDrawerVisible -> _state.update { it.copy(historyDrawerVisible = intent.visible,
                instructionEditorVisible = if (intent.visible) false else it.instructionEditorVisible) }
            is VlmIntent.RestoreHistory -> restoreHistory(intent.id)
            is VlmIntent.PinHistory -> historyOperation { historyRepository?.pin(intent.id, intent.pinned) }
            is VlmIntent.DeleteHistoryRequested -> if (intent.id == null || !_state.value.historySwitchBlocked)
                _state.update { it.copy(historyDeleteId = intent.id) }
            VlmIntent.ConfirmDeleteHistory -> deleteHistory()
            VlmIntent.RetryHistorySave -> retryHistorySaves()
            is VlmIntent.PhotoSettingsVisible -> if (!intent.visible || !busy())
                _state.update { it.copy(photoSettingsVisible = intent.visible, instructionEditorVisible = false) }
            is VlmIntent.CompositionDetailsVisible -> _state.update { it.copy(compositionDetailsVisible = intent.visible,
                compositionDetailsExpanded = false,
                capturedWorkflow = if (intent.visible) null else it.capturedWorkflow) }
            is VlmIntent.CompositionDetailsExpanded -> if (!intent.expanded || !busy()) _state.update {
                it.copy(compositionDetailsExpanded = intent.expanded && (it.result != null ||
                    it.usesGeneratedReference && it.generated.selected?.ready == true),
                    capturedWorkflow = if (intent.expanded) null else it.capturedWorkflow)
            }
            is VlmIntent.PreferredEthnicity -> if (!busy()) {
                updatePhotoPreferences { it.copy(ethnicity = intent.ethnicity) }
                _state.update { it.copy(selectedEthnicity = intent.ethnicity) }
            }
            VlmIntent.CameraStopped -> _state.update { it.copy(cameraReady = false) }
            is VlmIntent.InstructionChanged -> if (!busy()) {
                _state.update { it.copy(instruction = intent.text.take(2000)) }
                updatePhotoPreferences { it.copy(instruction = intent.text.take(2000), instructionFromRecommendation = false) }
            }
            is VlmIntent.InstructionEditorVisible -> if (!intent.visible || _state.value.canEditInstruction)
                _state.update { it.copy(instructionEditorVisible = intent.visible,
                    instructionDraft = it.instruction.takeUnless { text ->
                        text.trim() == models.message(R.string.vlm_instruction_no_requirement) ||
                            text.trim() == models.message(R.string.vlm_photo_default_requirement)
                    }.orEmpty(),
                    instructionDraftFromRecommendation = it.photoPreferences.instructionFromRecommendation,
                    instructionDraftRevision = it.instructionDraftRevision + 1) }
            is VlmIntent.InstructionDraftChanged -> if (_state.value.instructionEditorVisible && _state.value.canEditInstruction && intent.text != _state.value.instructionDraft)
                _state.update { it.copy(instructionDraft = intent.text.take(2000), instructionDraftFromRecommendation = false) }
            is VlmIntent.SeedreamModelSelected -> selectImageGenerationModel(intent.model)
            is VlmIntent.QwenImageModelSelected -> selectImageGenerationModel(intent.model)
            is VlmIntent.InstructionRecommendationSelected -> if (_state.value.instructionEditorVisible && _state.value.canEditInstruction)
                _state.update { it.copy(instructionDraft = intent.text.take(2000), instructionDraftFromRecommendation = true,
                    instructionDraftRevision = it.instructionDraftRevision + 1) }
            is VlmIntent.InstructionHistorySelected -> if (_state.value.instructionEditorVisible && _state.value.canEditInstruction && intent.text in _state.value.instructionHistory)
                _state.update { it.copy(instructionDraft = intent.text, instructionDraftFromRecommendation = false,
                    instructionDraftRevision = it.instructionDraftRevision + 1) }
            is VlmIntent.DeleteInstructionHistory -> if (_state.value.instructionEditorVisible && _state.value.canEditInstruction) {
                val history = _state.value.instructionHistory.filterNot { it == intent.text }
                settingsStore.saveInstructionHistory(history)
                _state.update { it.copy(instructionHistory = history) }
            }
            VlmIntent.SubmitInstruction -> if (_state.value.instructionEditorVisible && _state.value.canEditInstruction) {
                val text = _state.value.instructionDraft.trim().take(2000)
                val fromRecommendation = _state.value.instructionDraftFromRecommendation
                val history = InstructionHistory.record(_state.value.instructionHistory, text, fromRecommendation)
                settingsStore.saveInstructionHistory(history)
                _state.update { it.copy(instruction = text, instructionDraft = text, instructionEditorVisible = false, instructionHistory = history) }
                updatePhotoPreferences { it.copy(instruction = text, instructionFromRecommendation = fromRecommendation) }
            }
            is VlmIntent.AvatarSelected -> if (!busy()) {
                updatePhotoPreferences { it.copy(avatar = intent.avatarId) }
                _state.update { it.copy(selectedAvatar = intent.avatarId) }
            }
            VlmIntent.Generate -> compose(false)
            VlmIntent.Revise -> compose(true)
            VlmIntent.Cancel -> { if (_state.value.stage == VlmStage.SCANNING || _state.value.stage == VlmStage.SCAN_PROCESSING) { cancelScan(); return }; if (busy() && _state.value.modelBusy) return; cancelGenerated(); cancelWork(); _state.update { it.copy(stage = if (it.scanSnapshot != null && it.scene == null) VlmStage.SCAN_REVIEW else if (it.scene == null) VlmStage.LIVE else if (it.generated.hasReady || it.result != null) VlmStage.READY else VlmStage.FROZEN, error = null, directorProgress = null) } }
            // 相机是否就绪由绑定生命周期更新；退出构图不能把仍在运行的相机标记为未就绪。
            VlmIntent.ResumeCamera -> { if (_state.value.scanSnapshot != null && !_state.value.usesGeneratedReference) { cancelScan(); return }; cancelWork(); _state.update { it.copy(stage = VlmStage.LIVE, capturedWorkflow = null, reference = null, referencePlan = null, generated = it.generated.copy(applied = null), error = null, directorProgress = null) } }
            is VlmIntent.Select -> if (!busy() &&
                (intent.sceneId != null && intent.sceneId != _state.value.scene?.id ||
                    intent.batchId != null && intent.batchId != _state.value.compositionBatchId)) {
                val sceneId = intent.sceneId ?: _state.value.scene?.id
                val cached = sceneId?.let { _state.value.rememberedBackgrounds()[it] }
                val group = if (intent.batchId != null) cached?.withBatch(intent.batchId) else cached
                if (group != null && group.result.plans.any { it.id == intent.id }) {
                    selectBackground(group, intent.id)
                }
            } else if (!busy() && _state.value.result?.plans?.any { it.id == intent.id } == true) {
                _state.update { it.copy(selectedId = intent.id, capturedWorkflow = null, facePreview = null,
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
                    reference = null, referencePlan = null, generated = it.generated.copy(applied = null), background = BackgroundGuidance()) }
            }
            is VlmIntent.SettingsVisible -> if (!_state.value.historyLoading && !_state.value.historySaving && _state.value.stage !in listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)) _state.update { it.copy(settingsVisible = intent.visible, instructionEditorVisible = false) }
            is VlmIntent.SaveSettings -> {
                if (_state.value.historyLoading || _state.value.historySaving) return
                generationModelRevision++
                cancelWork()
                viewModelScope.launch {
                    try {
                        val settings = CloudModelConfig.applyQwenConfiguration(intent.settings).normalizeReferenceSource()
                        settingsWrites.withLock { withContext(Dispatchers.IO) { settingsStore.save(settings) } }
                        val before = _state.value
                        val source = if (settings.mode == before.settings.mode && settings.preferred == before.settings.preferred &&
                            (before.result != null || before.generated.candidates.isNotEmpty())) before.referenceSource
                            else resolveReferenceSource(settings)
                        val sourceChanged = before.historyAspectRatio == null && (source != before.referenceSource || settings.preferred != before.settings.preferred)
                        if (sourceChanged) cancelGenerated(clear = true)
                        if (source == ReferenceSource.GENERATED_IMAGE && _state.value.cameraMode == CameraMode.AR)
                            switchCameraMode(CameraMode.NORMAL)
                        _state.update { current ->
                            val changed = if (sourceChanged) current.copy(result = null, diagnostics = null, previews = emptyMap(),
                                reference = null, referencePlan = null, compositionCache = emptyMap(), selectedId = null,
                                background = BackgroundGuidance(), capturedWorkflow = null, facePreview = null,
                                compositionDetailsVisible = false) else current
                            changed.copy(settings = settings, automaticReferenceSource = source,
                                stage = if (before.historyAspectRatio != null || !sourceChanged && source == ReferenceSource.GENERATED_IMAGE)
                                changed.stage else if (changed.scanSnapshot != null && changed.scene == null) VlmStage.SCAN_REVIEW
                                else if (changed.scene == null || changed.stage == VlmStage.LIVE) VlmStage.LIVE else VlmStage.FROZEN,
                                settingsVisible = false, error = null)
                        }
                    } catch (e: Exception) { _state.update { it.copy(error = cloudFailureMessage(e) ?: "设置保存失败：${e.message}") } }
                }
            }
            is VlmIntent.CameraForeground -> {
                if (intent.visible && !foreground) foregroundSinceNs = SystemClock.elapsedRealtimeNanos()
                if (!intent.visible) guidanceEpoch++
                foreground = intent.visible
                if (!foreground) closeSample()
                if (!foreground && _state.value.stage == VlmStage.SCANNING) stopScan(false)
                if (!foreground) _state.update { it.withBackgroundGuidance(BackgroundGuidance()) }
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
            is VlmIntent.UseMarketModel -> {
                val installed = models.installedModel(intent.id)
                if (installed != null && (intent.id in com.framer.sense.feature.camera.vlm.market.MarketCatalog.compatibleIds || intent.id.startsWith("local-")) &&
                    _state.value.settings.mode != ModelMode.CLOUD) maintain(models.message(R.string.vlm_model_loading)) {
                    val dir = ModelFiles.child(models.root, installed.directory)
                    attemptedPath = dir.path
                    models.validateDirectory(dir)
                    models.stage(dir)
                    loadDirectory(dir)
                }
            }
            is VlmIntent.DeleteMarketModel -> maintain(models.message(R.string.vlm_model_delete)) {
                val targets = models.installed.value.filter { it.id == intent.id }
                if (targets.any { ModelFiles.child(models.root, it.directory) == models.activeDirectory() }) {
                    local.unload(); modelLoaded = false
                }
                targets.forEach { models.deleteDirectory(ModelFiles.child(models.root, it.directory)) }
                marketDownloads?.forget(intent.id)
                attemptedPath = models.pendingDirectory()?.path
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
                cancelGenerated(clear = true)
                cancelWork()
                val token = requestToken
                work = viewModelScope.launch {
                    try {
                        _state.update { it.copy(stage = VlmStage.FREEZING, compositionCache = emptyMap(), compositionDetailsExpanded = false, compositionBatchId = INITIAL_COMPOSITION_BATCH, capturedWorkflow = null, error = null, scanSession = null, scanSnapshot = null, viewCandidates = emptyList(), selectedFrameId = null, referencePlan = null) }
                        val scene = snapshots.importImage(intent.uri)
                        if (token == requestToken) {
                            _state.update { it.copy(scene = scene, stage = VlmStage.FROZEN, result = null, diagnostics = null, previews = emptyMap(), reference = null, selectedId = null, fromImportedImage = true, facePreview = null, availableActions = emptyMap(), availableExpressions = emptyMap()) }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { if (token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, error = if ((e as? ModelFailure)?.code == "LOCAL_PHOTO_INVALID_OUTPUT") models.message(R.string.vlm_photo_model_invalid) else e.message) } }
                }
            }
            VlmIntent.Capture -> if (_state.value.canCapture) {
                val token = newVlmId()
                captureToken = token
                pendingCaptureWorkflow = _state.value.workflowKey
                _state.update { it.copy(saving = true, capturedWorkflow = null, error = null) }
                _effects.tryEmit(VlmEffect.Capture(token))
            }
            is VlmIntent.CaptureFinished -> if (intent.token == captureToken) {
                val completed = pendingCaptureWorkflow.takeIf { intent.error == null }
                captureToken = null
                pendingCaptureWorkflow = null
                _state.update { it.copy(saving = false,
                    capturedWorkflow = completed?.takeIf { workflow -> workflow == it.workflowKey &&
                        (it.activeBackgroundTarget == null || it.workflowProgress().take(4).all { status -> status == CameraStepStatus.COMPLETE }) },
                    error = intent.error, notice = if (intent.error == null) models.message(R.string.vlm_photo_saved) else null) }
            }
            VlmIntent.OpenGallery -> if (_state.value.canOpenGallery) _effects.tryEmit(VlmEffect.OpenGallery)
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
        if (_state.value.canRestartScan) cancelGenerated(clear = true)
        if (_state.value.canRestartScan) refreshAutomaticReferenceRoute()
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
        _state.update { it.copy(stage = VlmStage.SCANNING, capturedWorkflow = null, scanSession = null, scanSnapshot = null,
            scanProgress = ScanProgress(), viewCandidates = emptyList(), selectedFrameId = null, comparedFrames = 0, manualScanSelectionVisible = false,
            scene = null, result = null, compositionCache = emptyMap(), compositionDetailsExpanded = false, compositionBatchId = INITIAL_COMPOSITION_BATCH, diagnostics = null, previews = emptyMap(), reference = null, referencePlan = null,
            availableActions = emptyMap(), availableExpressions = emptyMap(), facePreview = null,
            background = BackgroundGuidance(), error = null,
            notice = if (local.isInferenceRunning) models.message(R.string.vlm_flow_waiting_native) else null, directorProgress = null) }
        work = viewModelScope.launch {
            try {
                previousWork?.join(); previousRender?.join(); previousFace?.join(); previousScan?.join(); cleanupWork?.join()
                awaitPreviousNative(token)
                historyRepository?.flush()
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

    /** 结束本轮流程，只回到等待扫描的实时取景，不自动创建扫描窗口或冻结照片。 */
    private fun restartFlow() {
        if (!_state.value.canRestartFlow) return
        guidanceEpoch++
        analyzeAfterStop = false
        sessions.stop(SystemClock.elapsedRealtimeNanos())
        sessions.resetReference()
        captureToken = null
        pendingCaptureWorkflow = null
        cancelScan()
        refreshAutomaticReferenceRoute()
        _state.update { current ->
            val initialZoom = CameraZoomPolicy.clamp(1f, current.camera.minZoom, current.camera.maxZoom)
            current.copy(compositionDetailsVisible = false, compositionDetailsExpanded = false,
                selectedId = null, availableActions = emptyMap(), availableExpressions = emptyMap(),
                fromImportedImage = false, directorProgress = null, faceLoading = false,
                zoom = if (current.cameraMode == CameraMode.AR) 1f else initialZoom, normalZoom = initialZoom,
                ar = current.ar.copy(manualAnchor = false, placedReferenceKey = null))
        }
    }

    private fun cancelScan() {
        cancelGenerated(clear = true)
        val previousWork = work; val previousRender = renderWork; val previousFace = faceWork
        cancelWork(); scanQueue?.close(); scanTimer?.cancel()
        val previous = scanWork
        previous?.cancel()
        val precedingCleanup = cleanupWork
        cleanupWork = viewModelScope.launch {
            previous?.join(); previousWork?.join(); previousRender?.join(); previousFace?.join(); precedingCleanup?.join()
            local.awaitIdle(); historyRepository?.flush(); sessions.clear()
        }
        _state.update { it.copy(stage = VlmStage.LIVE, capturedWorkflow = null, scanSession = null, scanSnapshot = null, scene = null,
            scanProgress = ScanProgress(), viewCandidates = emptyList(), selectedFrameId = null, comparedFrames = 0, manualScanSelectionVisible = false,
            result = null, compositionCache = emptyMap(), compositionDetailsExpanded = false, compositionBatchId = INITIAL_COMPOSITION_BATCH, diagnostics = null, previews = emptyMap(), reference = null, referencePlan = null,
            background = BackgroundGuidance(), facePreview = null, error = null, notice = null) }
    }

    private fun analyzeScan() {
        refreshAutomaticReferenceRoute()
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
                if (!s.usesGeneratedReference) awaitPreviousNative(token)
                val selected = if (!s.usesGeneratedReference)
                    OfflineScanViewAgent(scanLogger).select(scan, s.instruction.ifBlank { models.message(R.string.vlm_instruction_no_requirement) }, OfflineViewInference(local::compareViews))
                else ScanViewAgent(logger = scanLogger).select(scan, s.settings, s.instruction.ifBlank { models.message(R.string.vlm_instruction_no_requirement) }, sessions.budget)
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
                    else if (!s.usesGeneratedReference) failure.message?.takeIf { it.isNotBlank() } ?: models.message(R.string.vlm_scan_local_select_failed)
                    else models.message(R.string.vlm_scan_select_failed)) }
                val current = _state.value
                Log.i("VlmScan", "选景失败后的界面状态 scan=${scan.session.id.take(8)} stage=${current.stage} scenePresent=${current.scene != null} displayScene=${current.displayScene?.id?.take(8)} provisional=${current.scene == null && current.displayScene != null} selectedFrame=${current.selectedFrameId?.take(8)} cachedFrames=${current.scanSnapshot?.frames?.size ?: 0} errorPresent=${current.error != null}")
            }
        }
    }

    /** 缩略卡只切换原图；生成由按钮确认后的 Generate 单独触发。 */
    private fun previewScanFrame(frameId: String) {
        if (busy()) return
        val state = _state.value
        val frame = state.scanSnapshot?.frames?.firstOrNull { it.id == frameId } ?: return
        if (state.usesGeneratedReference) {
            state.generated.candidates.firstOrNull { it.scene.id == frame.scene.id && it.ready }?.let { selectGenerated(it.id); return }
            cancelWork()
            _state.update { it.copy(scene = frame.scene, selectedFrameId = frame.id, stage = VlmStage.FROZEN,
                generated = it.generated.copy(selectedId = null, applied = null),
                reference = null, referencePlan = null, background = BackgroundGuidance(), capturedWorkflow = null,
                compositionDetailsVisible = true, manualScanSelectionVisible = false, error = null) }
            return
        }
        state.rememberedBackgrounds()[frame.scene.id]?.let {
            selectBackground(it)
            return
        }
        cancelWork()
        _state.update { it.previewBackground(frame) }
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
        if (s.usesGeneratedReference) {
            previewScanFrame(frameId)
            if (!_state.value.generated.candidates.any { it.scene.id == frame.scene.id && it.ready }) compose(false)
            return
        }
        val saved = s.rememberedBackgrounds()
        saved[frame.scene.id]?.let { group ->
            selectBackground(group)
            return
        }
        if (saved.size >= ScanLimits.MAX_COMPOSITION_BACKGROUNDS) {
            _state.update { it.copy(error = models.message(R.string.vlm_background_cache_limit)) }
            return
        }
        Log.i("VlmScan", "冻结选景来源检查 scan=${frame.scanId.take(8)} frame=${frame.id.take(8)} scene=${frame.scene.id.take(8)} imageExists=${File(frame.scene.imagePath).isFile} modelImageExists=${File(frame.scene.modelImagePath).isFile} config=${frame.config.version}")
        cancelWork()
        _state.update { it.previewBackground(frame) }
        Log.i("VlmScan", "选景已冻结 scan=${frame.scanId.take(8)} frame=${frame.id.take(8)} stage=${_state.value.stage} next=COMPOSE")
        compose(false)
    }

    /** 已生成背景直接恢复；布局调整尚未渲染完就切走时，只补缺失预览，不再请求模型。 */
    private fun selectBackground(group: BackgroundComposition, planId: String? = group.selectedId) {
        cancelWork()
        val token = requestToken
        _state.update { it.restoreBackground(group, planId) }
        Log.i("VlmScan", "复用背景构图 scene=${group.scene.id.take(8)} frame=${group.frameId?.take(8)} selected=${_state.value.selectedId} backgrounds=${_state.value.compositionCache.size} plans=${group.result.plans.size} modelRequested=false")
        requestFace()
        val pending = group.result.plans.filter { group.previews[it.id]?.appliedPlan != it }
        if (pending.isEmpty()) return
        renderWork = viewModelScope.launch {
            try {
                for (plan in pending) {
                    val image = previews.preview(group.scene, plan)
                    _state.update { current ->
                        val result = current.result ?: return@update current
                        if (token != requestToken || current.scene?.id != group.scene.id ||
                            result.plans.find { it.id == plan.id } != plan) current
                        else current.copy(result = result.copy(plans = result.plans.map {
                            if (it.id == plan.id) image.appliedPlan else it
                        }), previews = current.previews + (plan.id to image))
                    }
                }
                if (token == requestToken) requestFace()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (token == requestToken) _state.update { it.copy(error = models.message(R.string.vlm_avatar_preview_failed, failure.message.orEmpty())) }
            }
        }
    }

    private fun inputFor(s: VlmUiState, existing: List<CompositionPlan> = emptyList(), selectedId: String? = null): DirectorInput {
        val scene = requireNotNull(s.scene)
        val frame = s.scanSnapshot?.frames?.firstOrNull { it.scene.id == scene.id && it.id == s.selectedFrameId }
            ?: s.scanSnapshot?.frames?.firstOrNull { it.scene.id == scene.id }
        val actor = existing.firstOrNull()?.avatar?.avatarId ?: s.selectedAvatar
        val ethnicity = existing.firstOrNull()?.avatar?.ethnicity ?: s.selectedEthnicity
        return DirectorInput(scene, s.camera, s.instruction.ifBlank { models.message(R.string.vlm_instruction_no_requirement) }, existing, selectedId, actor,
            frame?.let { PhotoViewBinding(it.scanId, it.id, it.config.version, it.config.arSessionId) } ?: scene.captureBinding,
            s.viewCandidates.firstOrNull { it.frameId == (frame?.id ?: scene.id) }?.backgroundRegions.orEmpty(),
            if (frame != null) sessions.budget else null,
            photoReferences = actor == AvatarId.ADULT_FEMALE, ethnicity = ethnicity, arMode = s.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA)
    }

    private fun guidanceTarget(plan: BackgroundReferenceTarget) = "guide:${plan.viewBinding?.frameId}:${plan.id}:${plan.revision}"
    private fun guidanceTarget(plan: CompositionPlan) = guidanceTarget(plan.backgroundTarget())

    /** 判断互斥工作是否运行；无参数，用于限制点击和模型资源操作。 */
    private fun busy(): Boolean = _state.value.let { it.historyLoading || it.historySaving || it.photoSample != null || it.modelBusy || it.saving || it.stage in listOf(VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING) }

    /** 接收相机复制的帧，保存后自动启动构图。
     * @param event 帧所有权和拍摄时对应的请求编号。
     */
    private fun receiveFrame(event: VlmIntent.FrameReady) {
        if (event.token != requestToken) { event.bitmap.recycle(); return }
        val configVersion = _state.value.cameraConfigVersion
        work = viewModelScope.launch {
            try {
                val saved = snapshots.save(event.bitmap, event.zoom, event.orientation)
                val binding = event.arConfig?.let { PhotoViewBinding("ar:${it.arSessionId}", saved.id, it.version, it.arSessionId) }
                    ?: PhotoViewBinding("single:${saved.id}", saved.id, configVersion)
                val scene = saved.copy(captureBinding = binding)
                if (event.token == requestToken) {
                    _state.update { it.copy(scene = scene, stage = VlmStage.FROZEN, result = null, diagnostics = null, previews = emptyMap(), reference = null, selectedId = null, fromImportedImage = false, directorProgress = null, facePreview = null, availableActions = emptyMap(), availableExpressions = emptyMap()) }
                    compose(false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (event.token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, error = if ((e as? ModelFailure)?.code == "LOCAL_PHOTO_INVALID_OUTPUT") models.message(R.string.vlm_photo_model_invalid) else e.message) } }
            finally { event.bitmap.recycle() }
        }
    }

    private fun historyCapture(state: VlmUiState) = HistoryCapture(state.generated, state.scene, state.scanSnapshot,
        state.viewCandidates, state.captureAspectRatio, state.fromImportedImage)

    private fun historyModelName(model: ImageGenerationModel): String = when (model) {
        is QwenImageModel -> models.message(when (model) {
            QwenImageModel.V21_TURBO -> R.string.vlm_qwen_image_21_turbo
            QwenImageModel.V21_PRO -> R.string.vlm_qwen_image_21_pro
            QwenImageModel.V30 -> R.string.vlm_qwen_image_30
            QwenImageModel.V30_PRO -> R.string.vlm_qwen_image_30_pro
        })
        is SeedreamModel -> models.message(if (model == SeedreamModel.FLASH) R.string.vlm_seedream_flash else R.string.vlm_seedream_pro)
        is HistoricalImageModel -> model.apiId
    }

    private suspend fun persistHistory(candidate: GeneratedCandidate, state: VlmUiState) {
        if (historyRepository == null) return
        val write = HistoryWrite(historyCapture(state), candidate,
            candidate.instruction.trim().ifBlank { models.message(R.string.vlm_photo_default_requirement) }, historyModelName(candidate.model))
        failedHistoryWrites[candidate.id] = write
        saveHistoryWrite(write)
    }

    private suspend fun saveHistoryWrite(write: HistoryWrite) {
        _state.update { it.copy(historySaving = true) }
        try {
            historyRepository?.save(write.capture, write.candidate, write.title, write.modelName)
            failedHistoryWrites.remove(write.candidate.id)
            if (failedHistoryWrites.isEmpty()) _state.update { state ->
                if (state.notice == models.message(R.string.vlm_history_save_failed)) state.copy(notice = null) else state
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            Log.w("VlmHistory", "local_save_failed candidate=${write.candidate.id} type=${failure.javaClass.simpleName}")
            _state.update { it.copy(notice = models.message(R.string.vlm_history_save_failed)) }
        } finally {
            _state.update { it.copy(historySaving = false, historySaveFailed = failedHistoryWrites.isNotEmpty()) }
        }
    }

    private fun retryHistorySaves() {
        if (_state.value.historySwitchBlocked || historyRepository == null) return
        val writes = failedHistoryWrites.values.toList()
        viewModelScope.launch { writes.forEach { saveHistoryWrite(it) } }
    }

    private fun historyOperation(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(error = models.message(R.string.vlm_history_operation_failed)) } }
        }
    }

    private fun restoreHistory(id: String) {
        if (_state.value.historySwitchBlocked || historyRepository == null) return
        cancelWork()
        val token = requestToken
        _state.update { it.copy(historyLoading = true, historyDrawerVisible = false, historyDeleteId = null,
            instructionEditorVisible = false) }
        viewModelScope.launch {
            try {
                val restored = historyRepository.load(id)
                if (requestToken != token) return@launch
                cancelGenerated(clear = true)
                sessions.stop(SystemClock.elapsedRealtimeNanos())
                sessions.resetReference(); guidanceEpoch++
                if (_state.value.cameraMode != CameraMode.NORMAL) switchCameraMode(CameraMode.NORMAL)
                val selected = requireNotNull(restored.generated.selected)
                val instruction = restored.instruction
                _state.update { it.copy(generated = restored.generated, scene = selected.scene, scanSnapshot = restored.scan,
                    scanSession = null, viewCandidates = restored.views, selectedFrameId = selected.viewBinding?.frameId,
                    historyAspectRatio = restored.aspectRatio, fromImportedImage = restored.imported,
                    instruction = instruction, instructionDraft = instruction, instructionDraftFromRecommendation = false,
                    stage = VlmStage.READY, result = null, diagnostics = null, compositionCache = emptyMap(), previews = emptyMap(),
                    selectedId = null, reference = null, referencePlan = null, background = BackgroundGuidance(), capturedWorkflow = null,
                    compositionDetailsVisible = true, compositionDetailsExpanded = false, historyLoading = false,
                    zoom = CameraZoomPolicy.clamp(selected.scene.currentZoom, it.camera.minZoom, it.camera.maxZoom), error = null, notice = null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(error = models.message(R.string.vlm_history_restore_failed)) } }
            finally { _state.update { it.copy(historyLoading = false) } }
        }
    }

    private fun deleteHistory() {
        val state = _state.value
        val id = state.historyDeleteId ?: return
        if (state.historySwitchBlocked || historyRepository == null) return
        _state.update { it.copy(historyLoading = true, historyDeleteId = null) }
        viewModelScope.launch {
            try {
                if (_state.value.generated.historyId == id) {
                    cancelGenerated(clear = true); cancelWork(); sessions.resetReference(); guidanceEpoch++
                    _state.update { it.copy(scene = null, scanSession = null, scanSnapshot = null, viewCandidates = emptyList(),
                        selectedFrameId = null, result = null, previews = emptyMap(), compositionCache = emptyMap(),
                        selectedId = null, reference = null, referencePlan = null, background = BackgroundGuidance(),
                        capturedWorkflow = null, compositionDetailsVisible = false, stage = VlmStage.LIVE) }
                }
                historyRepository.delete(id)
                failedHistoryWrites.entries.removeAll { it.value.capture.generated.historyId == id }
                _state.update { it.copy(historySaveFailed = failedHistoryWrites.isNotEmpty()) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { _state.update { it.copy(error = models.message(R.string.vlm_history_operation_failed)) } }
            finally { _state.update { it.copy(historyLoading = false) } }
        }
    }

    private fun selectImageGenerationModel(model: ImageGenerationModel) {
        val current = _state.value
        if (!current.instructionEditorVisible || !current.canEditInstruction || current.settings.mode == ModelMode.OFFLINE ||
            current.settings.preferred != model.provider || current.settings.imageGenerationModel == model) return
        val before = current.settings
        val revision = ++generationModelRevision
        val settings = when (model) {
            is SeedreamModel -> before.copy(seedreamModel = model)
            is QwenImageModel -> before.copy(qwenImageModel = model)
            is HistoricalImageModel -> return
        }
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch {
            try { settingsWrites.withLock { withContext(Dispatchers.IO) { settingsStore.save(settings) } } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (revision == generationModelRevision) _state.update { it.copy(
                    settings = it.settings.copy(seedreamModel = before.seedreamModel, qwenImageModel = before.qwenImageModel),
                    error = models.message(R.string.vlm_generated_model_save_failed)) }
            }
        }
    }

    /** 每次只提交一个候选，不追加收费请求；保留已应用的参考。 */
    private fun composeGenerated(replacesId: String? = null) {
        val current = _state.value
        Log.i(GeneratedReferenceDiagnostics.TAG, "生成入口 stage=${current.stage} provider=${current.settings.preferred} mode=${current.settings.mode} model=${current.settings.imageGenerationModel.apiId} official=${current.settings.officialImageGeneration} keyConfigured=${current.settings.imageGenerationKey.isNotBlank()} serviceConfigured=${generatedRepository != null}")
        if (!current.usesGeneratedReference || current.historyLoading || current.historySaving || current.historyDrawerVisible ||
            current.modelBusy || current.saving || current.photoSample != null ||
            current.stage !in listOf(VlmStage.FROZEN, VlmStage.READY, VlmStage.LIVE)) return
        val scene = current.scene ?: return
        if (current.settings.mode == ModelMode.OFFLINE || current.historyAspectRatio != null && current.referenceSource != ReferenceSource.GENERATED_IMAGE) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_cloud_required)) }; return
        }
        if (!current.settings.supportsImageGeneration) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_brand_unsupported)) }; return
        }
        if (current.settings.mode == ModelMode.OFFLINE) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_cloud_required)) }; return
        }
        if (current.cameraMode != CameraMode.NORMAL) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_normal_required)) }; return
        }
        if (current.settings.preferred == ProviderId.QWEN && !current.settings.officialImageGeneration) {
            _state.update { it.copy(error = models.message(R.string.vlm_qwen_image_gateway_unsupported)) }; return
        }
        if (current.settings.officialImageGeneration && current.settings.imageGenerationKey.isBlank()) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_key_missing)) }; return
        }
        if (current.generated.running) return
        val replacing = replacesId?.let { id -> current.generated.candidates.find { it.id == id } ?: return }
        val source = replacing?.scene ?: scene
        val backgrounds = current.generated.candidates.map { it.scene.id }.distinct()
        if (source.id !in backgrounds && backgrounds.size >= ScanLimits.MAX_COMPOSITION_BACKGROUNDS) {
            _state.update { it.copy(error = models.message(R.string.vlm_background_cache_limit)) }; return
        }
        val frame = current.scanSnapshot?.frames?.find { it.scene.id == source.id }
        val binding = replacing?.viewBinding ?: frame?.let { PhotoViewBinding(it.scanId, it.id, it.config.version, it.config.arSessionId) }
            ?: source.captureBinding
        val usedKeys = current.generated.usedStyleKeys + current.generated.candidates.map {
            (it.styleChoice ?: GeneratedPortraitStylePolicy.choice(it.styleIndex)).key
        }
        val styleIndex = replacing?.styleIndex ?: GeneratedPortraitStylePolicy.nextUnusedKey(usedKeys)
        if (styleIndex == null) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_styles_exhausted)) }; return
        }
        val batchId = newVlmId()
        val epoch = current.generated.epoch
        val candidates = listOf(
            GeneratedCandidate(newVlmId(), batchId, source, current.settings.imageGenerationModel, current.instruction,
                current.generated.candidates.size, binding, replacesId = replacing?.id,
                portraitPreferences = GeneratedPortraitPreferences(current.selectedAvatar, current.selectedEthnicity),
                sceneDescription = replacing?.sceneDescription?.takeIf(String::isNotBlank)
                    ?: current.viewCandidates.firstOrNull { it.frameId == binding?.frameId }?.let {
                        listOf(it.subject.trim(), it.reason.trim()).filter(String::isNotBlank).distinct().joinToString("\n")
                    }.orEmpty(),
                styleIndex = styleIndex, styleChoice = replacing?.styleChoice ?: GeneratedPortraitStylePolicy.choice(styleIndex))
        )
        _state.update {
            val alreadyVisible = it.generated.hasReady
            it.copy(generated = it.generated.copy(candidates = it.generated.candidates + candidates,
                    usedStyleKeys = usedKeys + candidates.map { c -> requireNotNull(c.styleChoice).key }),
                stage = if (alreadyVisible) it.stage else VlmStage.GENERATING,
                compositionDetailsVisible = if (alreadyVisible) it.compositionDetailsVisible else true,
                error = null, notice = null)
        }
        candidates.forEach { candidate ->
            Log.i(GeneratedReferenceDiagnostics.TAG, "候选提交 candidate=${candidate.id} batch=$batchId scene=${source.id} model=${candidate.model.apiId} provider=${current.settings.preferred} official=${current.settings.officialImageGeneration} styleIndex=${candidate.styleIndex} styleSelection=${if (replacing == null) "SESSION_RANDOM" else "REUSE"}")
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    generatedPermits.withPermit {
                        acceptGenerated(epoch, candidate.copy(status = GeneratedCandidateStatus.RUNNING))
                        try { historyRepository?.styles(current.generated.historyId, _state.value.generated.usedStyleKeys) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { _state.update { it.copy(notice = models.message(R.string.vlm_history_save_failed)) } }
                        val assets = (generatedRepository ?: throw GeneratedReferenceFailure("SERVICE"))
                            .generate(candidate, current.settings)
                        val ready = candidate.copy(status = GeneratedCandidateStatus.READY, assets = assets, completedAt = System.currentTimeMillis())
                        val completedState = _state.value
                        if (completedState.generated.historyId != current.generated.historyId || completedState.generated.epoch != epoch)
                            return@withPermit
                        persistHistory(ready, completedState)
                        acceptGenerated(epoch, ready)
                    }
                } catch (cancel: CancellationException) {
                    acceptGenerated(epoch, candidate.copy(status = GeneratedCandidateStatus.CANCELLED))
                    throw cancel
                } catch (failure: Exception) {
                    val code = (failure as? GeneratedReferenceFailure)?.code ?: "SERVICE"
                    Log.e(GeneratedReferenceDiagnostics.TAG, "候选失败 candidate=${candidate.id} resultCode=$code ${GeneratedReferenceDiagnostics.failure(failure)}")
                    acceptGenerated(epoch, candidate.copy(status = GeneratedCandidateStatus.FAILED, errorCode = code))
                } finally { generatedJobs.remove(candidate.id) }
            }
            generatedJobs[candidate.id] = job
            job.start()
        }
    }

    private fun acceptGenerated(epoch: String, candidate: GeneratedCandidate) {
        _state.update { current ->
            val before = current.generated
            var updated = before.update(epoch, candidate)
            if (before == updated) return@update current
            val sameScene = current.scene?.id == candidate.scene.id
            if (!sameScene && before.selectedId == null) updated = updated.copy(selectedId = null)
            val firstReady = candidate.ready && before.selectedId == null && sameScene
            val stillRunning = updated.candidates.any { it.scene.id == current.scene?.id &&
                it.status in listOf(GeneratedCandidateStatus.QUEUED, GeneratedCandidateStatus.RUNNING) }
            val finishedEmpty = !stillRunning && !updated.hasReady && current.stage == VlmStage.GENERATING
            current.copy(generated = updated,
                stage = when {
                    firstReady && current.stage in listOf(VlmStage.GENERATING, VlmStage.FROZEN) -> VlmStage.READY
                    finishedEmpty -> VlmStage.FROZEN
                    else -> current.stage
                },
                compositionDetailsVisible = if (firstReady && current.stage in listOf(VlmStage.GENERATING, VlmStage.FROZEN)) true else current.compositionDetailsVisible,
                error = if (finishedEmpty) generatedFailureMessage(candidate.errorCode) else current.error)
        }
        val current = _state.value
        Log.i(GeneratedReferenceDiagnostics.TAG, "候选回传 candidate=${candidate.id} status=${candidate.status} epochMatches=${current.generated.epoch == epoch} selected=${current.generated.selectedId} stage=${current.stage}")
        if (current.generated.epoch == epoch && current.usesGeneratedReference &&
            current.stage == VlmStage.FROZEN && CameraReferenceRouting.shouldFallback(current.settings, current.generated)) {
            // 保留失败任务身份，隔离迟到回传；仅切换到本地流程，不再发起收费请求。
            Log.i(GeneratedReferenceDiagnostics.TAG, "候选批次转离线 stage=FALLBACK_OFFLINE batch=${candidate.batchId}")
            _state.update { it.copy(automaticReferenceSource = ReferenceSource.LEGACY_GLB,
                error = null, notice = models.message(R.string.vlm_reference_auto_offline)) }
            compose(false)
        }
    }

    private fun generatedFailureMessage(code: String?): String = models.message(when (code) {
        "UNSUPPORTED" -> R.string.vlm_generated_brand_unsupported
        "AUTH", "KEY" -> R.string.vlm_generated_key_missing
        "QWEN_CONFIG" -> R.string.vlm_qwen_image_config_invalid
        "QWEN_ACCOUNT" -> R.string.vlm_qwen_image_account_invalid
        "QWEN_GATEWAY_UNSUPPORTED" -> R.string.vlm_qwen_image_gateway_unsupported
        "OFFLINE" -> R.string.vlm_generated_cloud_required
        "INPUT" -> R.string.vlm_generated_input_invalid
        "IMAGE_REJECTED" -> R.string.vlm_generated_image_rejected
        "LAYERS_MISSING", "PERSON_AMBIGUOUS", "ALPHA", "BOUNDS", "ASPECT" -> R.string.vlm_generated_layers_invalid
        "LIMIT" -> R.string.vlm_generated_rate_limit
        "NETWORK", "TIMEOUT", "DOWNLOAD" -> R.string.vlm_generated_network_failed
        else -> R.string.vlm_generated_failed
    })

    private fun selectGenerated(id: String) {
        if (busy()) return
        val candidate = _state.value.generated.candidates.find { it.id == id && it.ready } ?: return
        cancelWork()
        guidanceEpoch++
        sessions.resetReference()
        _state.update { it.copy(scene = candidate.scene, selectedFrameId = candidate.viewBinding?.frameId,
            stage = VlmStage.READY, generated = it.generated.copy(selectedId = id, applied = null),
            reference = null, referencePlan = null, background = BackgroundGuidance(), capturedWorkflow = null,
            compositionDetailsVisible = true, error = null, notice = null) }
        val historyId = _state.value.generated.historyId
        historyOperation { historyRepository?.select(historyId, candidate.id) }
    }

    private fun applyGenerated() {
        if (busy()) return
        val candidate = _state.value.generated.selected?.takeIf { it.ready } ?: return
        val assets = candidate.assets ?: return
        if (!candidate.canApply) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_portrait_unavailable)) }; return
        }
        if (!File(assets.compositePath).isFile || !File(assets.portraitPath).isFile) {
            _state.update { it.copy(error = models.message(R.string.vlm_generated_assets_missing)) }; return
        }
        cancelWork()
        guidanceEpoch++
        sessions.resetReference()
        _state.update { it.copy(stage = VlmStage.LIVE, scene = candidate.scene,
            generated = it.generated.copy(applied = candidate), reference = null, referencePlan = null,
            compositionDetailsVisible = false, compositionDetailsExpanded = false, capturedWorkflow = null,
            background = BackgroundGuidance(), zoom = candidate.scene.currentZoom, error = null, notice = null) }
    }

    private fun cancelGenerated(clear: Boolean = false) {
        val pending = generatedJobs.values.toList()
        generatedJobs.clear()
        _state.update { it.copy(generated = if (clear) GeneratedReferenceState(epoch = newVlmId())
            else it.generated.cancelPending().copy(epoch = newVlmId()),
            historyAspectRatio = if (clear) null else it.historyAspectRatio) }
        pending.forEach { it.cancel() }
    }

    /** 启动两轮以内的导演流程。
     * @param revise true 表示只修改当前选中方案，false 表示追加一批三个方案。
     */
    private fun compose(revise: Boolean) {
        refreshAutomaticReferenceRoute()
        if (_state.value.usesGeneratedReference) {
            composeGenerated(if (revise) _state.value.generated.selectedId else null)
            return
        }
        val previous = _state.value
        val scene = previous.scene ?: return
        if (!previous.canGenerate || revise && previous.stage != VlmStage.READY) return
        if (revise && (previous.result == null || previous.selectedId == null)) return
        // 手动列表可以浏览全部扫描帧，但不能绕过既有的三背景保留上限。
        val saved = previous.rememberedBackgrounds()
        if (scene.id !in saved && saved.size >= ScanLimits.MAX_COMPOSITION_BACKGROUNDS) {
            _state.update { it.copy(error = models.message(R.string.vlm_background_cache_limit)) }
            return
        }
        val previousWork = work; val previousRender = renderWork; val previousFace = faceWork
        cancelWork()
        val token = requestToken
        val input = inputFor(previous, if (revise) previous.result!!.plans else emptyList(), if (revise) previous.selectedId else null)
        val offlineSettings = previous.settings.copy(mode = ModelMode.OFFLINE).normalizeReferenceSource()
        Log.i(LOG_TAG, "启动${if (revise) "修改方案" else "生成方案"}任务 token=${token.take(8)} image=${scene.id.take(8)} mode=${previous.settings.mode} modelStatus=${previous.modelStatus}")
        _state.update { it.copy(stage = if (revise) VlmStage.MODIFYING else VlmStage.GENERATING,
            compositionCache = it.rememberedBackgrounds(), capturedWorkflow = null, error = null,
            notice = if (local.isInferenceRunning) models.message(R.string.vlm_flow_waiting_native)
                else if (previous.settings.mode == ModelMode.AUTO) models.message(R.string.vlm_reference_auto_offline) else null,
            directorProgress = if (local.isInferenceRunning) DirectorProgress.WaitingPreviousTask else DirectorProgress.PreparingRequest, facePreview = null) }
        work = viewModelScope.launch {
            var actualTrace: CallTrace? = null
            try {
                previousWork?.join(); previousRender?.join(); previousFace?.join()
                awaitPreviousNative(token)
                val result = repository.compose(input, offlineSettings, previews) { progress ->
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
                    _state.update {
                        val ready = it.copy(compositionDetailsVisible = true, compositionDetailsExpanded = false, result = resolved, diagnostics = resolved,
                            compositionBatchId = if (revise) previous.compositionBatchId else newVlmId(),
                            previews = images, selectedId = previous.selectedId?.takeIf { id -> revise && resolved.plans.any { p -> p.id == id } }
                                ?: resolved.plans.first().id,
                            stage = VlmStage.READY, directorProgress = null, availableActions = actions, availableExpressions = expressions)
                        ready.copy(compositionCache = ready.rememberedBackgrounds())
                    }
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
        if (_state.value.usesGeneratedReference) return
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
        if (_state.value.usesGeneratedReference) {
            applyGenerated()
            return
        }
        if (busy()) return
        val s = _state.value
        val result = s.result ?: return
        val plan = result.plans.find { it.id == s.selectedId } ?: return
        val scene = s.scene ?: return
        val errors = PlanValidator().validate(result.plans, inputFor(s, result.plans, s.selectedId))
        if (errors.isNotEmpty()) { _state.update { it.copy(error = if (errors.any { error -> "RESOLVED_SPEC_MISMATCH" in error || "VIEW_BINDING_MISMATCH" in error }) models.message(R.string.vlm_photo_spec_invalid) else errors.joinToString("\n")) }; return }
        cancelWork()
        // 每次应用都重新确认当前照片，不能沿用同一方案上一次确认的稳定计时。
        guidanceEpoch++
        sessions.resetReference()
        val token = requestToken
        _state.update { it.copy(stage = VlmStage.MODIFYING, capturedWorkflow = null, compositionDetailsVisible = false, compositionDetailsExpanded = false, notice = null) }
        work = viewModelScope.launch {
            try {
                val projectionErrors = previews.inspect(plan, scene).errors
                check(projectionErrors.isEmpty()) { projectionErrors.joinToString("\n") }
                val currentPreview = previews.preview(scene, plan)
                val applied = currentPreview.appliedPlan
                val appliedPlans = result.plans.map { if (it.id == applied.id) applied else it }
                check(PlanValidator().validate(appliedPlans, inputFor(s, result.plans, s.selectedId)).isEmpty()) { models.message(R.string.vlm_avatar_invalid_resolution) }
                if (token == requestToken) Log.i("VlmAr", "应用参考 source=${s.ar.source} plan=${applied.id} revision=${applied.revision} frame=${applied.viewBinding?.frameId} requiresBackgroundConfirmation=${applied.viewBinding != null}")
                if (token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, result = result.copy(plans = appliedPlans),
                    reference = currentPreview, referencePlan = applied, background = BackgroundGuidance(),
                    compositionDetailsVisible = false,
                    zoom = if (s.cameraMode == CameraMode.AR) 1f else applied.zoom,
                    notice = null) }
                if (token == requestToken) requestFace()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (token == requestToken) _state.update { it.copy(stage = VlmStage.READY,
                compositionDetailsVisible = s.compositionDetailsVisible,
                compositionDetailsExpanded = s.compositionDetailsExpanded,
                error = if ((e as? ModelFailure)?.code == "LOCAL_PHOTO_INVALID_OUTPUT") models.message(R.string.vlm_photo_model_invalid) else e.message) } }
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
        pendingCaptureWorkflow = null
        _state.update { it.copy(cameraReady = false, saving = false, photoSettingsVisible = false, instructionEditorVisible = if (resetCameraMode) false else it.instructionEditorVisible, compositionDetailsVisible = false, compositionDetailsExpanded = false, stage = if (it.stage == VlmStage.SCAN_PROCESSING || it.stage == VlmStage.SCAN_REVIEW) it.stage else if (it.scanSnapshot != null && it.scene == null) VlmStage.SCAN_REVIEW else if (it.scene != null && it.stage != VlmStage.LIVE) { if (it.result == null) VlmStage.FROZEN else VlmStage.READY } else VlmStage.LIVE, directorProgress = null) }
    }

    override fun onCleared() {
        cancelGenerated()
        scanQueue?.close(); guideQueue.close(); scanTimer?.cancel()
        val retiring = listOfNotNull(work, renderWork, faceWork, sampleWork, scanWork, guideWork, cleanupWork)
        retiring.forEach { it.cancel() }
        // ViewModel 的作用域已销毁；等文件读取和串行分析退出后删除本地扫描资源。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            retiring.forEach { it.join() }
            local.awaitIdle()
            historyRepository?.flush()
            sessions.dispose()
        }
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "VlmCamera"
    }
}
