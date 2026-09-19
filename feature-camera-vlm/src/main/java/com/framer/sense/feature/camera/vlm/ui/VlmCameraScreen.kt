package com.framer.sense.feature.camera.vlm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.framer.sense.core.ui.MyApplicationTheme
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.data.ModelDownloadService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.framer.sense.feature.camera.vlm.BuildConfig
import com.framer.sense.feature.camera.vlm.camera.VlmCameraController
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** VLM 拍照模块公开入口。
 * @param onCaptureActionChanged 向主导航报告拍摄是否可用和拍摄回调，离开时清空。
 * @param modifier 页面布局修饰符。
 * @param viewModel MVI 状态机，由 Hilt 提供。
 */
@Composable
fun VlmCameraScreen(
    onCaptureActionChanged: (VlmCaptureAction?) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VlmCameraViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val controller = remember { VlmCameraController(context.applicationContext) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onIntent(VlmIntent.Capture)
        else viewModel.onIntent(VlmIntent.UserMessage("保存照片需要存储权限"))
    }
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.onIntent(VlmIntent.ImportModel(it)) } }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.onIntent(VlmIntent.ImportModelDirectory(it)) } }
    var requestedDownload by remember { mutableStateOf<VlmEffect.DownloadModel?>(null) }
    val startDownload: (VlmEffect.DownloadModel) -> Unit = { request ->
        try { ModelDownloadService.start(context, request.source, request.allowMetered) }
        catch (_: Exception) { viewModel.onIntent(VlmIntent.UserMessage(stringResource(R.string.vlm_download_service_failed))) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 用户拒绝通知权限仍可使用系统允许的前台服务，进度继续在 App 内展示。
        requestedDownload?.let(startDownload)
        requestedDownload = null
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, _ -> viewModel.onIntent(VlmIntent.CameraForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) }
        lifecycle.addObserver(observer)
        viewModel.onIntent(VlmIntent.CameraForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))
        onDispose { lifecycle.removeObserver(observer); viewModel.onIntent(VlmIntent.CameraForeground(false)) }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.onIntent(VlmIntent.ImportImage(it)) } }
    val onEvent = remember(viewModel) { { event: VlmIntent -> viewModel.onIntent(event) } }
    val latestAction by rememberUpdatedState(onCaptureActionChanged)
    val captureClick = {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else onEvent(VlmIntent.Capture)
    }
    val latestCapture by rememberUpdatedState(captureClick)
    LaunchedEffect(state.stage, state.cameraReady, state.saving, state.modelBusy, state.settingsVisible, permission) {
        latestAction(VlmCaptureAction(permission && state.cameraReady && state.stage == VlmStage.LIVE && !state.saving && !state.modelBusy && !state.settingsVisible) { latestCapture() })
    }
    DisposableEffect(viewModel, controller) {
        onDispose { controller.unbind(); viewModel.onLeave(); latestAction(null) }
    }
    LaunchedEffect(controller, viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is VlmEffect.DownloadModel -> {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestedDownload = effect
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else startDownload(effect)
                }
                is VlmEffect.Freeze -> try {
                    val (bitmap, zoom) = controller.freeze()
                    onEvent(VlmIntent.FrameReady(effect.token, bitmap, zoom))
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { onEvent(VlmIntent.PlatformFailed(effect.token, e.message ?: "无法冻结画面")) }
                is VlmEffect.Capture -> try {
                    controller.capturePhoto()
                    onEvent(VlmIntent.CaptureFinished(effect.token))
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { onEvent(VlmIntent.CaptureFinished(effect.token, e.message ?: "保存失败")) }
            }
        }
    }
    BackHandler(state.stage != VlmStage.LIVE && !state.settingsVisible) {
        onEvent(if (state.stage in listOf(VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.FREEZING)) VlmIntent.Cancel else VlmIntent.ResumeCamera)
    }
    Column(modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 构图导演", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onEvent(VlmIntent.SettingsVisible(true)) }) { Text("模型设置") }
        }
        Text("${state.settings.mode} · ${state.settings.region}", style = MaterialTheme.typography.labelSmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth > maxHeight
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CameraPane(state, controller, permission, { permissionLauncher.launch(Manifest.permission.CAMERA) }, onEvent, Modifier.weight(1f).fillMaxHeight())
                    Controls(state, onEvent, { imageLauncher.launch("image/*") }, Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraPane(state, controller, permission, { permissionLauncher.launch(Manifest.permission.CAMERA) }, onEvent, Modifier.weight(1f).fillMaxWidth())
                    Controls(state, onEvent, { imageLauncher.launch("image/*") }, Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
    }
    if (state.settingsVisible) ModelSettingsDialog(state, onEvent, { modelLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }, { directoryLauncher.launch(null) })
}

/** 展示严格匹配画幅的实时预览或冻结方案。
 * @param state 当前 MVI 状态。
 * @param controller 当前界面的相机控制器。
 * @param permission 相机权限是否已授予。
 * @param requestPermission 权限申请动作。
 * @param onEvent 事件发送器。
 * @param modifier 可用布局区域。
 */
@Composable
private fun CameraPane(state: VlmUiState, controller: VlmCameraController, permission: Boolean, requestPermission: () -> Unit, onEvent: (VlmIntent) -> Unit, modifier: Modifier) {
    val live = state.stage == VlmStage.LIVE || state.stage == VlmStage.FREEZING
    val scene = state.scene
    val selected = state.result?.plans?.find { it.id == state.selectedId }
    val defaultRatio = if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4f / 3f else 3f / 4f
    val ratio = if (!live && scene != null) scene.width.toFloat() / scene.height else if (state.reference != null) state.reference.avatar.width.toFloat() / state.reference.avatar.height else defaultRatio
    Box(modifier.background(Color(0xFF101B20)), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val fitted = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
            Box(fitted) {
                if (live) {
                    if (permission) CameraSurface(controller, state.zoom, onEvent, Modifier.fillMaxSize())
                    else Button(onClick = requestPermission, modifier = Modifier.align(Alignment.Center)) { Text("允许使用相机") }
                    state.reference?.let { Image(it.avatar.asImageBitmap(), "构图参考人偶", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = .65f) }
                } else if (scene != null) {
                    val preview = selected?.let { state.previews[it.id] }
                    if (preview != null) {
                        Image(preview.composite.asImageBitmap(), "选中构图方案", Modifier.fillMaxSize().testTag("vlm_selected_preview")
                            .pointerInput(selected?.id, state.stage) {
                                if (state.stage == VlmStage.READY) detectDragGestures { change, amount ->
                                    change.consume()
                                    onEvent(VlmIntent.Move(amount.x / size.width, amount.y / size.height))
                                }
                            }, contentScale = ContentScale.FillBounds)
                    } else AsyncImage(File(scene.imagePath), "冻结场景", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                if (state.stage in listOf(VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING)) {
                    VlmLoadingOverlay(state.stage, state.directorProgress, { onEvent(VlmIntent.Cancel) }, Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/** 展示冻结或导演执行时的当前步骤；不保留已经完成的历史步骤。 */
@Composable
internal fun VlmLoadingOverlay(
    stage: VlmStage,
    progress: DirectorProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.background(Color.Black.copy(alpha = .72f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            if (stage == VlmStage.FREEZING) stringResource(R.string.vlm_camera_freezing)
            else directorProgressLabel(progress),
            color = Color.White,
            modifier = Modifier.testTag("vlm_director_progress")
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.vlm_action_cancel)) }
    }
}

/** 将结构化进度映射为用户可见的本地化文字。 */
@Composable
private fun directorProgressLabel(progress: DirectorProgress?): String = when (progress) {
    DirectorProgress.PreparingRequest, null -> stringResource(R.string.vlm_director_preparing)
    is DirectorProgress.AnalyzingScene -> if (progress.revisingFromFeedback) {
        stringResource(R.string.vlm_director_refining, progress.turn)
    } else {
        stringResource(R.string.vlm_director_analyzing, progress.turn)
    }
    is DirectorProgress.SwitchingProvider -> stringResource(R.string.vlm_director_switching_provider, progress.turn)
    is DirectorProgress.ValidatingResponse -> stringResource(R.string.vlm_director_validating, progress.turn)
    is DirectorProgress.InspectingProjection -> stringResource(R.string.vlm_director_inspecting_projection, progress.planIndex, progress.planCount)
    is DirectorProgress.RenderingPreview -> stringResource(R.string.vlm_director_rendering_preview, progress.planIndex, progress.planCount)
}

/** 将 CameraX 控件绑定到 Compose 生命周期。
 * @param controller 相机控制器。
 * @param zoom 当前目标倍率。
 * @param onEvent 相机状态及错误接收器。
 * @param modifier 精确预览尺寸。
 */
@Composable
private fun CameraSurface(controller: VlmCameraController, zoom: Float, onEvent: (VlmIntent) -> Unit, modifier: Modifier) {
    key(LocalConfiguration.current.orientation, LocalView.current.display?.rotation) {
        val owner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val latestEvent by rememberUpdatedState(onEvent)
        val latestZoom by rememberUpdatedState(zoom)
        DisposableEffect(controller, owner) {
            onDispose { controller.unbind(); latestEvent(VlmIntent.CameraStopped) }
        }
        AndroidView(modifier = modifier, factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) { controller.focus(this, event.x, event.y); performClick() }
                    true
                }
                doOnLayout {
                    scope.launch {
                        try { latestEvent(VlmIntent.CameraReady(controller.bind(this@apply, owner, latestZoom))) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { latestEvent(VlmIntent.PlatformFailed(null, e.message ?: "相机启动失败")) }
                    }
                }
            }
        })
        LaunchedEffect(zoom) { controller.zoom(zoom) }
    }
}

/** 展示方案卡片、文字修改和拍摄提示。
 * @param state 页面状态。
 * @param onEvent 用户操作发送器。
 * @param importImage 固定图片调试入口。
 * @param modifier 控件区域及滚动设置。
 */
@Composable
private fun Controls(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importImage: () -> Unit, modifier: Modifier) {
    val busy = state.modelBusy || state.saving || state.stage in listOf(VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vlm_error")) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.stage == VlmStage.LIVE) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onEvent(VlmIntent.StartComposition) }, enabled = state.cameraReady && !busy, modifier = Modifier.testTag("vlm_start")) { Text("开始构图") }
                if (BuildConfig.DEBUG) TextButton(onClick = importImage, enabled = !busy) { Text("测试图片") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                state.camera.zoomOptions.filter { it <= 5f }.forEach { ratio ->
                    TextButton(onClick = { onEvent(VlmIntent.ZoomChanged(ratio)) }, enabled = !busy && state.cameraReady) { Text("${"%.1f".format(ratio)}×") }
                }
            }
            state.referencePlan?.let { Text(it.guidance, style = MaterialTheme.typography.bodyMedium) }
        } else {
            state.result?.let { result ->
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(result.plans, key = { it.id }) { plan ->
                        Card(onClick = { onEvent(VlmIntent.Select(plan.id)) }, enabled = !busy,
                            modifier = Modifier.width(130.dp).border(if (plan.id == state.selectedId) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)) {
                            state.previews[plan.id]?.let { Image(it.composite.asImageBitmap(), plan.title, Modifier.fillMaxWidth().height(98.dp), contentScale = ContentScale.Fit) }
                            Text(plan.title, Modifier.padding(horizontal = 6.dp), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            Text("${poseLabel(plan.avatar.pose)} · ${expressionLabel(plan.avatar.expression)}", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall)
                            Text("${shotLabel(plan.shot)} · ${"%.1f".format(plan.zoom)}×", Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                val plan = result.plans.find { it.id == state.selectedId }
                if (plan != null) {
                    Text(plan.guidance, style = MaterialTheme.typography.bodySmall)
                    Text(plan.reason, style = MaterialTheme.typography.labelSmall)
                    if (plan.needsRetake) Text("需重新取景 · 预览仅供构图参考", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                    if (plan.uncertainties.isNotEmpty()) Text(plan.uncertainties.joinToString("；"), style = MaterialTheme.typography.labelSmall)
                    Slider(value = plan.avatar.height.coerceIn(.08f, 4f), onValueChange = { onEvent(VlmIntent.Resize(it)) }, valueRange = .08f..4f, enabled = !busy)
                }

            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onEvent(VlmIntent.Generate) }, enabled = !busy) { Text(if (state.result == null) "生成方案" else "重新生成") }
                if (state.result != null) Button(onClick = { onEvent(VlmIntent.Apply) }, enabled = !busy) { Text("应用方案") }
                TextButton(onClick = { onEvent(VlmIntent.ResumeCamera) }, enabled = !state.saving) { Text("返回相机") }
            }
        }
        (state.diagnostics ?: state.result)?.let { DebugDetails(it) }
        OutlinedTextField(value = state.instruction, onValueChange = { onEvent(VlmIntent.InstructionChanged(it)) }, label = { Text(if (state.result == null) "拍摄要求" else "修改要求，例如人物小一点") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, maxLines = 2)
        if (state.stage == VlmStage.READY) Button(onClick = { onEvent(VlmIntent.Revise) }, enabled = !busy && state.instruction.isNotBlank()) { Text("让导演修改选中方案") }
    }
}

/** 展示实际模型调用和按需展开的诊断，失败时同样可查看。
 * @param result 成功或失败时保留的调用、工具观察与原始输出。
 */
@Composable
private fun DebugDetails(result: CompositionResult) {
    Text(result.traces.joinToString(" → ") { "${it.provider}/${it.model} ${it.elapsedMs}ms · ${it.detail}" }, style = MaterialTheme.typography.labelSmall)
    if (BuildConfig.DEBUG) {
        var expanded by remember(result) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text("调试详情") }
        if (expanded) Text(VlmJson.encodeToString(result.observations) + "\n" + result.rawOutputs.joinToString("\n"), style = MaterialTheme.typography.labelSmall)
    }
}

/** 将人偶姿态编号映射为用户名称。
 * @param pose 公共姿态编号。
 * @return 简体中文姿态说明。
 */
private fun poseLabel(pose: PoseId): String = when (pose) {
    PoseId.RELAXED -> "自然站立"; PoseId.SIDE -> "侧身"; PoseId.LOOK_BACK -> "回眸"
    PoseId.HANDS_FRONT -> "身前交叠"; PoseId.HAND_HIP -> "单手叉腰"; PoseId.HANDS_HIPS -> "双手叉腰"
    PoseId.WAVE -> "挥手"; PoseId.POINT -> "抬手指向"; PoseId.HAT -> "扶帽"
    PoseId.LOOK_UP -> "抬头"; PoseId.LOOK_DOWN -> "低头"; PoseId.ARMS_OPEN -> "双臂展开"
}

/** 将面部表情编号映射为用户名称。
 * @param expression 公共表情编号。
 * @return 简体中文表情名称。
 */
private fun expressionLabel(expression: ExpressionId): String = when (expression) {
    ExpressionId.NEUTRAL -> "平静"; ExpressionId.SMILE -> "微笑"
    ExpressionId.HAPPY -> "开心"; ExpressionId.SURPRISED -> "惊讶"
}

/** 将景别编号转换为简体中文。
 * @param shot 公共协议中的景别。
 * @return 用户可理解的名称。
 */
private fun shotLabel(shot: ShotType): String = when (shot) {
    ShotType.ENVIRONMENT -> "环境人像"
    ShotType.FULL -> "全身"
    ShotType.HALF -> "半身"
    ShotType.CLOSE_UP -> "特写"
}

/** 模型配置和离线包管理弹窗。
 * @param state 当前设置及操作进度。
 * @param onEvent 配置与管理操作接收器。
 * @param importModel 系统 ZIP 文件选择动作。
 * @param importDirectory 官方模型文件夹选择动作。
 */
@Composable
private fun ModelSettingsDialog(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importModel: () -> Unit, importDirectory: () -> Unit) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    AlertDialog(onDismissRequest = { if (!state.modelBusy) onEvent(VlmIntent.SettingsVisible(false)) }, title = { Text("模型设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Region.entries.forEach { region -> TextButton(onClick = { draft = draft.copy(region = region, preferred = if (region == Region.CN) ProviderId.QWEN else ProviderId.GPT) }) { Text(if (draft.region == region) "✓ ${region.name}" else region.name) } }
                }
                ModelMode.entries.forEach { mode ->
                    FilterChip(selected = draft.mode == mode, onClick = { draft = draft.copy(mode = mode) }, label = { Text(when (mode) { ModelMode.OFFLINE -> "强制离线"; ModelMode.CLOUD -> "指定云端"; ModelMode.AUTO -> "自动切换" }) })
                }
                val providers = if (draft.region == Region.CN) listOf(ProviderId.QWEN, ProviderId.SEED) else listOf(ProviderId.GPT, ProviderId.GEMINI)
                Row { providers.forEach { p -> TextButton(onClick = { draft = draft.copy(preferred = p) }) { Text(if (draft.preferred == p) "✓ $p" else "$p") } } }
                OutlinedTextField(draft.gateway, { draft = draft.copy(gateway = it) }, label = { Text("网关地址") }, singleLine = true)
                OutlinedTextField(draft.gatewayToken, { draft = draft.copy(gatewayToken = it) }, label = { Text("独立网关令牌") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("供应商 API 密钥仅配置在服务端。检查网关使用已保存的设置。", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { onEvent(VlmIntent.CheckGateway) }, enabled = !state.modelBusy) { Text("检查已保存网关") }
                OfflineModelPanel(state, onEvent, importDirectory, importModel)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = { onEvent(VlmIntent.SaveSettings(draft)) }, enabled = !state.modelBusy) { Text("保存") } },
        dismissButton = { TextButton(onClick = { onEvent(VlmIntent.SettingsVisible(false)) }, enabled = !state.modelBusy) { Text("关闭") } }
    )
}

@Preview(showBackground = true)
@Composable
private fun VlmLoadingOverlayPreview() {
    MyApplicationTheme(dynamicColor = false) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101B20)), contentAlignment = Alignment.Center) {
            VlmLoadingOverlay(
                stage = VlmStage.GENERATING,
                progress = DirectorProgress.InspectingProjection(planIndex = 2, planCount = 3),
                onCancel = {}
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun VlmControlsReadyPreview() {
    MyApplicationTheme(dynamicColor = false) {
        Surface {
            Controls(
                state = previewVlmState(),
                onEvent = {},
                importImage = {},
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun VlmModelSettingsDialogPreview() {
    MyApplicationTheme(dynamicColor = false) {
        ModelSettingsDialog(
            state = previewVlmState().copy(settingsVisible = true, modelStatus = "离线模型已加载，可以开始构图"),
            onEvent = {},
            importModel = {},
            importDirectory = {}
        )
    }
}

private fun previewVlmState(): VlmUiState {
    val plans = listOf(
        CompositionPlan("preview-environment", "保留室内环境", ShotType.ENVIRONMENT, CropRect(), 1f, AvatarPlacement(Point2(.5f, .9f), .3f), "人物位于画面下方，保留环境信息。", "环境与人物关系清晰。"),
        CompositionPlan("preview-full", "自然全身站立", ShotType.FULL, CropRect(), 1f, AvatarPlacement(Point2(.5f, .92f), .65f, pose = PoseId.SIDE), "完整保留人物姿态。", "适合展示全身比例。"),
        CompositionPlan("preview-half", "突出人物表情", ShotType.HALF, CropRect(), 1f, AvatarPlacement(Point2(.5f, 1.4f), 1.25f, pose = PoseId.LOOK_BACK), "裁切至半身，突出表情。", "减少背景干扰。")
    )
    return VlmUiState(
        stage = VlmStage.READY,
        result = CompositionResult(plans, emptyList(), emptyList(), emptyList()),
        selectedId = plans.first().id,
        modelStatus = "离线模型已加载，可以开始构图"
    )
}
