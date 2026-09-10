package com.framer.sense.feature.camera.vlm.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.avatar.PreviewStore
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _state = MutableStateFlow(VlmUiState(settings = settingsStore.read(), modelStatus = models.description(),
        notice = if (savedStateHandle.get<Boolean>("vlm_started") == true) "上次会话未保留，请重新构图" else null))
    val state: StateFlow<VlmUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<VlmEffect>(extraBufferCapacity = 4)
    val effects = _effects.asSharedFlow()
    private var requestToken = newVlmId()
    private var captureToken: String? = null
    private var work: Job? = null
    private var renderWork: Job? = null
    private var maintenance: Job? = null
    init { savedStateHandle["vlm_started"] = true }

    /** 接收界面事件并归约状态。
     * @param intent 用户操作或平台异步结果；Bitmap 的所有权随 FrameReady 转交本状态机。
     */
    fun onIntent(intent: VlmIntent) {
        when (intent) {
            is VlmIntent.UserMessage -> _state.update { it.copy(error = intent.message) }
            VlmIntent.StartComposition -> if (_state.value.stage == VlmStage.LIVE && _state.value.cameraReady && !busy()) {
                cancelWork()
                _state.update { it.copy(stage = VlmStage.FREEZING, error = null) }
                _effects.tryEmit(VlmEffect.Freeze(requestToken))
            }
            is VlmIntent.FrameReady -> receiveFrame(intent)
            is VlmIntent.PlatformFailed -> {
                if (intent.token == null || intent.token == requestToken) _state.update { it.copy(stage = if (it.scene == null) VlmStage.LIVE else VlmStage.FROZEN, error = intent.message, cameraReady = false) }
            }
            is VlmIntent.CameraReady -> _state.update { it.copy(camera = intent.capabilities, cameraReady = true) }
            VlmIntent.CameraStopped -> _state.update { it.copy(cameraReady = false) }
            is VlmIntent.InstructionChanged -> _state.update { it.copy(instruction = intent.text.take(2000)) }
            VlmIntent.Generate -> compose(false)
            VlmIntent.Revise -> compose(true)
            VlmIntent.Cancel -> { if (busy() && _state.value.modelBusy) return; cancelWork(); _state.update { it.copy(stage = if (it.scene == null) VlmStage.LIVE else if (it.result == null) VlmStage.FROZEN else VlmStage.READY, error = null) } }
            VlmIntent.ResumeCamera -> { cancelWork(); _state.update { it.copy(stage = VlmStage.LIVE, reference = null, referencePlan = null, error = null, cameraReady = false) } }
            is VlmIntent.Select -> if (!busy()) _state.update { it.copy(selectedId = intent.id) }
            is VlmIntent.Move -> adjust { it.copy(foot = Point2((it.foot.x + intent.dx).coerceIn(0f, 1f), (it.foot.y + intent.dy).coerceIn(0f, 4f))) }
            is VlmIntent.Resize -> adjust { it.copy(height = intent.height.coerceIn(.08f, 4f)) }
            VlmIntent.Apply -> applyPlan()
            is VlmIntent.ZoomChanged -> _state.update { it.copy(zoom = intent.zoom.coerceIn(it.camera.minZoom, it.camera.maxZoom)) }
            is VlmIntent.SettingsVisible -> _state.update { it.copy(settingsVisible = intent.visible) }
            is VlmIntent.SaveSettings -> {
                cancelWork()
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) { settingsStore.save(intent.settings) }
                        _state.update { it.copy(settings = intent.settings, stage = if (it.scene == null || it.stage == VlmStage.LIVE) VlmStage.LIVE else VlmStage.FROZEN, settingsVisible = false, error = null) }
                    } catch (e: Exception) { _state.update { it.copy(error = "设置保存失败：${e.message}") } }
                }
            }
            is VlmIntent.ImportModel -> maintain("导入模型") {
                local.unload()
                var reported = 0L
                models.importPackage(intent.uri) { bytes, message ->
                    val now = System.nanoTime()
                    if (now - reported > 150_000_000L) { reported = now; _state.update { it.copy(modelStatus = "$message · ${bytes / 1048576} MiB") } }
                }
            }
            VlmIntent.LoadModel -> maintain("加载模型") { local.load(); Unit }
            VlmIntent.UnloadModel -> maintain("卸载模型") { local.unload() }
            VlmIntent.DeleteModel -> maintain("删除模型") { local.unload(); models.delete() }
            VlmIntent.CheckGateway -> maintain("检查网关") {
                val caps = GatewayProvider(_state.value.settings, ProviderId.QWEN).capabilities()
                _state.update { it.copy(notice = caps.providers.joinToString("\n") { p -> "${p.provider}: ${if (p.configured) p.model else "未配置"}" }) }
            }
            is VlmIntent.ImportImage -> {
                if (busy()) return
                cancelWork()
                val token = requestToken
                work = viewModelScope.launch {
                    try {
                        _state.update { it.copy(stage = VlmStage.FREEZING, error = null) }
                        val scene = snapshots.importImage(intent.uri)
                        if (token == requestToken) {
                            _state.update { it.copy(scene = scene, stage = VlmStage.FROZEN, result = null, diagnostics = null, previews = emptyMap(), reference = null, selectedId = null, fromImportedImage = true) }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { if (token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, error = e.message) } }
                }
            }
            VlmIntent.Capture -> if (_state.value.stage == VlmStage.LIVE && _state.value.cameraReady && !busy() && !_state.value.settingsVisible) {
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

    /** 判断互斥工作是否运行；无参数，用于限制点击和模型资源操作。 */
    private fun busy(): Boolean = _state.value.let { it.modelBusy || it.saving || it.stage in listOf(VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING) }

    /** 接收相机复制的帧，保存后自动启动构图。
     * @param event 帧所有权和拍摄时对应的请求编号。
     */
    private fun receiveFrame(event: VlmIntent.FrameReady) {
        if (event.token != requestToken) { event.bitmap.recycle(); return }
        work = viewModelScope.launch {
            try {
                val scene = snapshots.save(event.bitmap, event.zoom)
                if (event.token == requestToken) {
                    _state.update { it.copy(scene = scene, stage = VlmStage.FROZEN, result = null, diagnostics = null, previews = emptyMap(), reference = null, selectedId = null, fromImportedImage = false) }
                    compose(false)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (event.token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, error = e.message) } }
            finally { event.bitmap.recycle() }
        }
    }

    /** 启动两轮以内的导演流程。
     * @param revise true 表示只修改当前选中方案，false 表示重新生成三个方案。
     */
    private fun compose(revise: Boolean) {
        val previous = _state.value
        val scene = previous.scene ?: return
        if (previous.modelBusy || previous.saving || previous.stage in listOf(VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.FREEZING)) return
        if (revise && (previous.result == null || previous.selectedId == null)) return
        cancelWork()
        val token = requestToken
        val input = DirectorInput(scene, previous.camera, previous.instruction, if (revise) previous.result!!.plans else emptyList(), if (revise) previous.selectedId else null)
        _state.update { it.copy(stage = if (revise) VlmStage.MODIFYING else VlmStage.GENERATING, error = null, notice = null) }
        work = viewModelScope.launch {
            try {
                val result = repository.compose(input, previous.settings, previews)
                val images = result.plans.associate { it.id to previews.preview(scene, it) }
                if (token == requestToken) _state.update { it.copy(result = result, diagnostics = result, previews = images, selectedId = previous.selectedId?.takeIf { id -> result.plans.any { p -> p.id == id } } ?: result.plans.first().id, stage = VlmStage.READY) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (token == requestToken) _state.update { it.copy(stage = if (it.result == null) VlmStage.FROZEN else VlmStage.READY, error = e.message ?: "构图失败，请重试", diagnostics = (e as? ModelFailure)?.diagnostics) } }
        }
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
        val changed = selected.copy(avatar = update(selected.avatar), revision = selected.revision + 1)
        _state.update { it.copy(result = result.copy(plans = result.plans.map { p -> if (p.id == changed.id) changed else p })) }
        renderWork?.cancel()
        val token = requestToken
        renderWork = viewModelScope.launch {
            delay(120)
            try {
                val image = previews.preview(scene, changed)
                if (token == requestToken && _state.value.result?.plans?.find { it.id == changed.id }?.revision == changed.revision) _state.update { it.copy(previews = it.previews + (changed.id to image)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (token == requestToken) _state.update { it.copy(error = "预览失败：${e.message}") } }
        }
    }

    /** 校验并应用选中方案；无参数，真实空间定位不由冻结图保证。 */
    private fun applyPlan() {
        if (busy()) return
        val s = _state.value
        val result = s.result ?: return
        val plan = result.plans.find { it.id == s.selectedId } ?: return
        val scene = s.scene ?: return
        val errors = PlanValidator().validate(result.plans, DirectorInput(scene, s.camera, s.instruction))
        if (errors.isNotEmpty()) { _state.update { it.copy(error = errors.joinToString("\n")) }; return }
        cancelWork()
        val token = requestToken
        work = viewModelScope.launch {
            try {
                _state.update { it.copy(stage = VlmStage.MODIFYING) }
                val projectionErrors = previews.inspect(plan, scene).errors
                check(projectionErrors.isEmpty()) { projectionErrors.joinToString("\n") }
                val currentPreview = previews.preview(scene, plan)
                if (token == requestToken) _state.update { it.copy(stage = VlmStage.LIVE, reference = currentPreview, referencePlan = plan, zoom = plan.zoom, cameraReady = false, notice = if (plan.needsRetake || s.fromImportedImage) "此方案需要重新取景；参考人偶不是空间锚点" else "按参考调整站位；移动机位后请重新构图") }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (token == requestToken) _state.update { it.copy(stage = VlmStage.READY, error = e.message) } }
        }
    }

    /** 串行执行模型管理工作，不允许与构图并发。
     * @param label 页面展示的操作名称。
     * @param operation 实际导入、加载、卸载或检查任务。
     */
    private fun maintain(label: String, operation: suspend () -> Unit) {
        if (busy()) { _state.update { it.copy(error = "请先结束当前构图或拍摄，再管理模型") }; return }
        maintenance = viewModelScope.launch {
            _state.update { it.copy(modelBusy = true, modelStatus = label, error = null) }
            try { operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { _state.update { it.copy(error = "$label 失败：${e.message}") } }
            finally { _state.update { it.copy(modelBusy = false, modelStatus = models.description()) } }
        }
    }

    /** 取消会话计算并更新令牌；无参数，不能用其并发销毁原生句柄。 */
    private fun cancelWork() {
        requestToken = newVlmId()
        work?.cancel()
        renderWork?.cancel()
    }

    /** UI 离开相机模块时取消平台相关操作；无参数，保留冻结会话供返回查看。 */
    fun onLeave() {
        cancelWork()
        captureToken = null
        _state.update { it.copy(cameraReady = false, saving = false, stage = if (it.scene != null && it.stage != VlmStage.LIVE) { if (it.result == null) VlmStage.FROZEN else VlmStage.READY } else VlmStage.LIVE) }
    }
}
