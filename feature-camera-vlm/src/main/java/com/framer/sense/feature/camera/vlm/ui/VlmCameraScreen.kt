package com.framer.sense.feature.camera.vlm.ui

import android.util.Log
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.framer.sense.core.ui.MyApplicationTheme
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.agent.CompositionIntentMapper
import com.framer.sense.feature.camera.vlm.data.ModelDownloadService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.framer.sense.feature.camera.vlm.BuildConfig
import com.framer.sense.feature.camera.vlm.camera.VlmCameraController
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionCatalog
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import android.graphics.Bitmap

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
    LaunchedEffect(state.stage,state.ar.source,state.cameraReady,state.ar.tracking,state.modelBusy) {
        Log.i("VlmCameraUi", "页面状态 stage=${state.stage} source=${state.ar.source} ready=${state.cameraReady} tracking=${state.ar.tracking} modelBusy=${state.modelBusy} background=${state.background.status}")
    }
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
    val downloadServiceFailed = stringResource(R.string.vlm_download_service_failed)
    val startDownload: (VlmEffect.DownloadModel) -> Unit = { request ->
        try { ModelDownloadService.start(context, request.source, request.allowMetered) }
        catch (_: Exception) { viewModel.onIntent(VlmIntent.UserMessage(downloadServiceFailed)) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 用户拒绝通知权限仍可使用系统允许的前台服务，进度继续在 App 内展示。
        requestedDownload?.let(startDownload)
        requestedDownload = null
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val needPose by rememberUpdatedState(state.scanSession != null)
    LaunchedEffect(state.scanSession?.id) { controller.foreground(needPose && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, _ ->
            val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            controller.foreground(visible && needPose)
            controller.cameraForeground(visible)
            viewModel.onIntent(VlmIntent.CameraForeground(visible))
        }
        lifecycle.addObserver(observer)
        viewModel.onIntent(VlmIntent.CameraForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))
        onDispose { lifecycle.removeObserver(observer); controller.foreground(false); controller.cameraForeground(false); viewModel.onIntent(VlmIntent.CameraForeground(false)) }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { viewModel.onIntent(VlmIntent.ImportImage(it)) } }
    val onEvent = remember(viewModel) { { event: VlmIntent -> viewModel.onIntent(event) } }
    val latestAction by rememberUpdatedState(onCaptureActionChanged)
    val captureClick = {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else onEvent(VlmIntent.Capture)
    }
    val latestCapture by rememberUpdatedState(captureClick)
    LaunchedEffect(state.stage, state.cameraReady, state.saving, state.modelBusy, state.settingsVisible, state.photoSample, permission) {
        latestAction(VlmCaptureAction(permission && state.cameraReady && state.stage == VlmStage.LIVE && !state.saving && !state.modelBusy && !state.settingsVisible && state.photoSample == null) { latestCapture() })
    }
    DisposableEffect(viewModel, controller) {
        onDispose { controller.close(); viewModel.onLeave(); latestAction(null) }
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
                    onEvent(VlmIntent.FrameReady(effect.token, bitmap, zoom, controller.ar?.lastFrozenConfig))
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
        onEvent(if (state.stage in listOf(VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.FREEZING, VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)) VlmIntent.Cancel else VlmIntent.ResumeCamera)
    }
    // 页面自己绘制底色，不能依赖 SurfaceView 下方的主导航背景。
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 构图导演", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onEvent(VlmIntent.SettingsVisible(true)) }, enabled = state.stage !in listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)) { Text("模型设置") }
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
        ArSupportControl(controller, state, permission, onEvent)
    }
    if (state.settingsVisible) ModelSettingsDialog(state, onEvent, { modelLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }, { directoryLauncher.launch(null) })
    state.photoSample?.let { PhotoSampleDialog(it, onEvent) }
}

/** 单独检查制作初稿，所有动作走 MVI；不伪造一个模型构图结果。 */
@Composable
internal fun PhotoSampleDialog(sample: PhotoSampleState, onEvent: (VlmIntent) -> Unit) {
    Dialog(onDismissRequest = { onEvent(VlmIntent.ClosePhotoSample) }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.vlm_photo_sample_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.vlm_photo_sample_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.vlm_photo_actions), style = MaterialTheme.typography.labelMedium)
                LazyRow(Modifier.testTag("vlm_sample_actions"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PhotoActionCatalog().definitions.filter { it.id in PhotoActionCatalog.sampleActionIds }, key = { it.id }) { action ->
                        FilterChip(selected = sample.actionId == action.id, enabled = !sample.loading,
                            onClick = { onEvent(VlmIntent.PhotoSampleAction(action.id)) },
                            label = { Text(stringResource(requireNotNull(action.labelResource))) },
                            modifier = Modifier.testTag("vlm_sample_action_${action.id}"))
                    }
                }
                AvatarEthnicitySelector(sample.ethnicity, !sample.loading, "vlm_sample_ethnicity") {
                    onEvent(VlmIntent.PhotoSampleEthnicity(it))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ExpressionId.NEUTRAL, ExpressionId.SMILE, ExpressionId.HAPPY).forEach { expression ->
                        FilterChip(selected = sample.expression == expression, enabled = !sample.loading,
                            onClick = { onEvent(VlmIntent.PhotoSampleExpression(expression)) },
                            label = { Text(expressionLabel(expression)) },
                            modifier = Modifier.testTag("vlm_sample_expression_${expression.name}"))
                    }
                }
                if (sample.loading) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.vlm_photo_sample_loading))
                }
                sample.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onEvent(VlmIntent.PhotoSampleExpression(sample.expression)) }) {
                        Text(stringResource(R.string.vlm_photo_sample_retry))
                    }
                }
                sample.body?.let { bitmap -> Image(bitmap.asImageBitmap(),
                    stringResource(R.string.vlm_photo_sample_body_description), Modifier.height(300.dp).testTag("vlm_sample_body"),
                    contentScale = ContentScale.Fit) }
                sample.face?.let { bitmap -> Image(bitmap.asImageBitmap(),
                    stringResource(R.string.vlm_photo_sample_face_description), Modifier.size(220.dp).testTag("vlm_sample_face"),
                    contentScale = ContentScale.Fit) }
                PhotoActionCatalog().find(sample.actionId).stepResources.forEach { step ->
                    Text(stringResource(step), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onEvent(VlmIntent.ClosePhotoSample) }, modifier = Modifier.testTag("vlm_sample_close")) {
                    Text(stringResource(R.string.vlm_photo_sample_close))
                }
                Text(stringResource(R.string.vlm_photo_sample_credit), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
    val live = state.stage == VlmStage.LIVE || state.stage == VlmStage.FREEZING || state.stage == VlmStage.SCANNING
    val scene = state.displayScene
    val selected = state.result?.plans?.find { it.id == state.selectedId }
    val defaultRatio = if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4f / 3f else 3f / 4f
    val ratio = if (!live && scene != null) scene.width.toFloat() / scene.height else if (state.reference != null) state.reference.avatar.width.toFloat() / state.reference.avatar.height else defaultRatio
    val lastBounds = remember { arrayOfNulls<String>(1) }
    Box(modifier.background(Color(0xFF101B20)), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val fitted = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
            Box(fitted.clipToBounds().onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                val bounds = "x=${rect.left.toInt()} y=${rect.top.toInt()} size=${rect.width.toInt()}x${rect.height.toInt()}"
                if (lastBounds[0] != bounds) { lastBounds[0] = bounds; Log.i("VlmCameraUi", "预览布局 $bounds ratio=$ratio source=${state.ar.source} topAligned=true") }
            }) {
                // 空间会话贯穿扫描、选景和模型分析；冻结图片只是覆盖层。
                if (permission && state.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA)
                    ArCameraSurface(controller, state, onEvent, Modifier.fillMaxSize())
                if (live) {
                    if (permission && state.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SCREEN) CameraSurface(controller, state, onEvent, Modifier.fillMaxSize())
                    else if (!permission) Button(onClick = requestPermission, modifier = Modifier.align(Alignment.Center)) { Text("允许使用相机") }
                    if (state.referencePlan?.viewBinding != null && state.background.status == BackgroundStatus.SEARCHING) {
                        BackgroundReference(state, Modifier.fillMaxSize())
                    }
                    state.reference?.takeIf { state.ar.source != com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA && (state.referencePlan?.viewBinding == null || state.background.status != BackgroundStatus.SEARCHING) }?.let { Image(it.avatar.asImageBitmap(), "构图参考人偶", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = .65f) }
                } else if (scene != null) {
                    val preview = selected?.let { state.previews[it.id] }
                    if (preview != null) {
                        Image(preview.composite.asImageBitmap(), "选中构图方案", Modifier.fillMaxSize().testTag("vlm_selected_preview")
                            .pointerInput(selected.id, state.stage) {
                                if (state.stage == VlmStage.READY) detectDragGestures { change, amount ->
                                    change.consume()
                                    onEvent(VlmIntent.Move(amount.x / size.width, amount.y / size.height))
                                }
                            }, contentScale = ContentScale.FillBounds)
                    } else FrozenSceneImage(scene, state.scene == null, Modifier.fillMaxSize())
                }
                if (state.stage in listOf(VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING)) {
                    VlmLoadingOverlay(state.stage, state.directorProgress, { onEvent(VlmIntent.Cancel) }, Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/** 尚未选景时也显示已保存的扫描画面，并明确其预览身份。 */
@Composable
internal fun FrozenSceneImage(scene: SceneSnapshot, provisional: Boolean, modifier: Modifier = Modifier) {
    Box(modifier) {
        AsyncImage(File(scene.imagePath), stringResource(R.string.vlm_scan_frame),
            Modifier.fillMaxSize().testTag("vlm_frozen_scene"), contentScale = ContentScale.FillBounds)
        if (provisional) Text(stringResource(R.string.vlm_scan_preview_unselected), color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .65f)).padding(8.dp).testTag("vlm_scan_preview_unselected"))
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
private fun CameraSurface(controller: VlmCameraController, state: VlmUiState, onEvent: (VlmIntent) -> Unit, modifier: Modifier) {
    val target = if (state.stage == VlmStage.SCANNING) state.scanSession?.id else state.referencePlan?.takeIf { state.stage == VlmStage.LIVE && it.viewBinding != null }?.let { "guide:${it.viewBinding?.frameId}:${it.id}:${it.revision}" }
    key(LocalConfiguration.current.orientation, LocalView.current.display?.rotation, target, state.zoom) {
        val owner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val latestEvent by rememberUpdatedState(onEvent)
        val latestZoom by rememberUpdatedState(state.zoom)
        val latestSampling by rememberUpdatedState(state.cameraReady && (state.stage == VlmStage.SCANNING && !state.scanProgress.paused || state.stage == VlmStage.LIVE && state.referencePlan?.viewBinding != null))
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
                        try { latestEvent(VlmIntent.CameraReady(controller.bind(this@apply, owner, latestZoom, target, state.scanSession?.startedNs?.takeIf { state.stage == VlmStage.SCANNING },
                            { latestSampling }, { latestEvent(VlmIntent.CameraConfigured(it)) }, { latestEvent(VlmIntent.AnalysisReceived(it)) }))) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { latestEvent(VlmIntent.PlatformFailed(null, e.message ?: "相机启动失败")) }
                    }
                }
            }
        })
        LaunchedEffect(state.zoom) { controller.zoom(state.zoom) }
    }
}

/** 展示方案卡片、文字修改和拍摄提示。
 * @param state 页面状态。
 * @param onEvent 用户操作发送器。
 * @param importImage 固定图片调试入口。
 * @param modifier 控件区域及滚动设置。
 */
@Composable
internal fun Controls(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importImage: () -> Unit, modifier: Modifier) {
    val busy = state.photoSample != null || state.modelBusy || state.saving || state.stage in listOf(VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.stage in listOf(VlmStage.LIVE, VlmStage.FROZEN, VlmStage.READY)) {
            TextButton(onClick = { onEvent(VlmIntent.OpenPhotoSample) }, enabled = !busy,
                modifier = Modifier.testTag("vlm_sample_open")) { Text(stringResource(R.string.vlm_photo_sample_open)) }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vlm_error")) }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.comparedFrames > 0) Text(stringResource(R.string.vlm_scan_compared, state.comparedFrames), style = MaterialTheme.typography.labelSmall)
        if (state.stage == VlmStage.SCANNING) {
            Text(stringResource(R.string.vlm_scan_instructions))
            Text(stringResource(R.string.vlm_scan_progress, state.scanProgress.frames, state.scanProgress.elapsedSeconds))
            if (state.scanProgress.totalTurn > 0) Text(stringResource(R.string.vlm_scan_turn, state.scanProgress.totalTurn))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onEvent(VlmIntent.StopScan) }, enabled = state.scanSession != null, modifier = Modifier.testTag("vlm_scan_stop")) { Text(stringResource(R.string.vlm_scan_stop)) }
                TextButton(onClick = { onEvent(VlmIntent.PreferScanView) }, enabled = !state.scanProgress.paused && state.scanProgress.preferred < ScanLimits.MAX_PREFERRED,
                    modifier = Modifier.testTag("vlm_scan_prefer")) { Text(stringResource(R.string.vlm_scan_prefer)) }
                TextButton(onClick = { onEvent(VlmIntent.CancelScan) }, modifier = Modifier.testTag("vlm_scan_cancel")) { Text(stringResource(R.string.vlm_scan_cancel)) }
            }
        }
        if (state.stage in listOf(VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(when {
                state.stage == VlmStage.SCAN_PROCESSING -> stringResource(R.string.vlm_scan_finishing)
                state.settings.mode == ModelMode.OFFLINE -> stringResource(R.string.vlm_scan_selecting_offline,
                    minOf(state.scanSnapshot?.frames?.size ?: 0, com.framer.sense.feature.camera.vlm.agent.OfflineViewSelectionProtocol.MAX_FRAMES))
                else -> stringResource(R.string.vlm_scan_selecting)
            })
            TextButton(onClick = { onEvent(VlmIntent.Cancel) }) { Text(stringResource(R.string.vlm_scan_cancel)) }
        }
        if (state.stage == VlmStage.SCAN_REVIEW) {
            Text(stringResource(R.string.vlm_scan_review))
            if (state.manualScanSelectionVisible) ScanViews(state, false, onEvent)
            TextButton(onClick = { onEvent(VlmIntent.ManualScanSelectionVisible(!state.manualScanSelectionVisible)) },
                modifier = Modifier.testTag("vlm_scan_manual_open")) {
                Text(stringResource(if (state.manualScanSelectionVisible) R.string.vlm_scan_manual_hide else R.string.vlm_scan_manual_open))
            }
            TextButton(onClick = { onEvent(VlmIntent.SettingsVisible(true)) }) { Text(stringResource(R.string.vlm_scan_model_settings)) }
            Row {
                Button(onClick = { onEvent(VlmIntent.AnalyzeScan) }, enabled = state.scanSnapshot?.frames?.isNotEmpty() == true) { Text(stringResource(R.string.vlm_scan_analyze)) }
                TextButton(onClick = { onEvent(VlmIntent.CancelScan) }) { Text(stringResource(R.string.vlm_scan_restart)) }
            }
        }
        if (state.stage == VlmStage.READY && state.scanSnapshot != null) {
            state.viewCandidates.firstOrNull { it.frameId == state.selectedFrameId }?.let { candidate ->
                val subject = candidate.subject.ifBlank { stringResource(R.string.vlm_scan_view_number,
                    state.scanSnapshot.frames.indexOfFirst { it.id == candidate.frameId } + 1) }
                Text(stringResource(R.string.vlm_scan_agent_background, subject), modifier = Modifier.testTag("vlm_scan_agent_background"))
                Text(candidate.reason.ifBlank { stringResource(R.string.vlm_scan_reason_not_provided) }, style = MaterialTheme.typography.bodySmall)
            }
            if (state.manualScanSelectionVisible) ScanViews(state, busy, onEvent)
            TextButton(onClick = { onEvent(VlmIntent.ManualScanSelectionVisible(!state.manualScanSelectionVisible)) }, enabled = !busy,
                modifier = Modifier.testTag("vlm_scan_change_background")) {
                Text(stringResource(if (state.manualScanSelectionVisible) R.string.vlm_scan_manual_hide else R.string.vlm_scan_change_background))
            }
        }
        if (state.stage == VlmStage.LIVE && state.referencePlan?.viewBinding != null) {
            Text(stringResource(when (state.background.status) {
                BackgroundStatus.ALIGNED_AUTO -> R.string.vlm_background_auto
                BackgroundStatus.ALIGNED_MANUAL -> R.string.vlm_background_manual
                BackgroundStatus.SEARCHING -> R.string.vlm_background_searching
            }), modifier = Modifier.testTag("vlm_background_status"))
            if (state.background.status == BackgroundStatus.SEARCHING) {
                state.background.headingError?.takeIf { kotlin.math.abs(it) > 5f }?.let { delta -> Text(stringResource(if (delta > 0) R.string.vlm_background_right else R.string.vlm_background_left)) }
                state.background.pitchError?.takeIf { kotlin.math.abs(it) > 5f }?.let { delta -> Text(stringResource(if (delta > 0) R.string.vlm_background_up else R.string.vlm_background_down)) }
                TextButton(onClick = { onEvent(VlmIntent.ConfirmBackground) }, modifier = Modifier.testTag("vlm_background_confirm")) { Text(stringResource(R.string.vlm_background_confirm)) }
            } else state.referencePlan.let { Text(compositionGuidanceText(it)) }
        }
        if (state.stage == VlmStage.LIVE) {
            if (state.referencePlan != null) PhotoFacePreview(state,busy,onEvent)
            AvatarSelector(state, onEvent, busy)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onEvent(VlmIntent.StartScan) }, enabled = state.cameraReady && !busy, modifier = Modifier.testTag("vlm_scan_start")) { Text(stringResource(R.string.vlm_scan_start)) }
                TextButton(onClick = { onEvent(VlmIntent.StartComposition) }, enabled = state.cameraReady && !busy, modifier = Modifier.testTag("vlm_start")) { Text(stringResource(R.string.vlm_scan_single)) }
                if (BuildConfig.DEBUG) TextButton(onClick = importImage, enabled = !busy) { Text("测试图片") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                state.camera.zoomOptions.filter { it <= 5f }.forEach { ratio ->
                    TextButton(onClick = { onEvent(VlmIntent.ZoomChanged(ratio)) }, enabled = !busy && state.cameraReady) { Text("${"%.1f".format(ratio)}×") }
                }
            }
            state.referencePlan?.takeIf { it.viewBinding == null }?.let { Text(compositionGuidanceText(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("vlm_reference_guidance")) }
        } else if (state.stage in listOf(VlmStage.FROZEN, VlmStage.READY, VlmStage.GENERATING, VlmStage.MODIFYING, VlmStage.FREEZING)) {
            if (state.stage == VlmStage.FROZEN && state.result == null) {
                AvatarSelector(state, onEvent, busy)
            }
            state.result?.let { result ->
                Row(Modifier.fillMaxWidth().testTag("vlm_composition_choices"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.plans.forEachIndexed { index, plan ->
                        val isSceneFallback = plan.title.isBlank() || plan.localCombination != null || plan.uncertainties.contains(CompositionIntentMapper.SCENE_FALLBACK_MARKER)
                        val displayTitle = if (isSceneFallback) stringResource(R.string.vlm_composition_plan_number, index + 1) else plan.title
                        Card(onClick = { onEvent(VlmIntent.Select(plan.id)) }, enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("vlm_composition_${plan.id}").border(if (plan.id == state.selectedId) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)) {
                            state.previews[plan.id]?.let { Image(it.composite.asImageBitmap(), plan.title, Modifier.fillMaxWidth().height(98.dp), contentScale = ContentScale.Fit) }
                            Text(displayTitle, Modifier.padding(horizontal = 6.dp), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.vlm_composition_pose_expression, resolvedArmLabel(CompositionGuidance.from(plan)), expressionLabel(CompositionGuidance.from(plan).expression)), Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall)
                            Text(stringResource(R.string.vlm_composition_attributes, shotLabel(plan.shot), placementZoneLabel(plan.avatar.foot.x), planFacingLabel(plan)), Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (state.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA)
                    Text(stringResource(R.string.vlm_ar_preview_hint), style = MaterialTheme.typography.bodySmall)
                CompositionDescriptions(result, state.selectedId)
                val plan = result.plans.find { it.id == state.selectedId }
                if (plan != null) {
                    if (plan.resolvedSpec?.action?.readiness == ActionReadiness.DRAFT) Text(stringResource(R.string.vlm_photo_draft_reference), style = MaterialTheme.typography.labelSmall)
                    if (plan.localCombination != null) Text(stringResource(if (plan.localAlternative) R.string.vlm_photo_local_alternative else R.string.vlm_photo_local_choice, plan.localCombination), style = MaterialTheme.typography.labelSmall)
                    if (state.settings.mode != ModelMode.OFFLINE && state.selectedAvatar == AvatarId.ADULT_FEMALE && plan.resolvedSpec?.action?.origin == ActionOrigin.MODEL && plan.resolvedSpec?.action?.readiness == ActionReadiness.BASIC)
                        Text(stringResource(R.string.vlm_photo_legacy_gateway), style = MaterialTheme.typography.labelSmall)
                    PhotoActionControls(state, plan, busy, onEvent)
                    if (plan.needsRetake) Text("需重新取景 · 预览仅供构图参考", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                    val visibleUncertainties = plan.uncertainties.filterNot { it == CompositionIntentMapper.SCENE_FALLBACK_MARKER }
                    if (visibleUncertainties.isNotEmpty()) Text(visibleUncertainties.joinToString("；"), style = MaterialTheme.typography.labelSmall)
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

/** 展示当前选中方案的 VLM 依据与实际采用的人像构图。 */
@Composable
internal fun CompositionDescriptions(result: CompositionResult, selectedId: String?) {
    val index = result.plans.indexOfFirst { it.id == selectedId }
    val plan = result.plans.getOrNull(index) ?: return
    Text(stringResource(R.string.vlm_composition_recommendations), style = MaterialTheme.typography.titleSmall)
    val fallback = plan.title.isBlank() || plan.localCombination != null || plan.uncertainties.contains(CompositionIntentMapper.SCENE_FALLBACK_MARKER)
    val title = if (fallback) stringResource(R.string.vlm_composition_plan_number, index + 1) else plan.title
    val actual = stringResource(
        R.string.vlm_composition_attributes,
        shotLabel(plan.shot), placementZoneLabel(plan.avatar.foot.x), planFacingLabel(plan)
    )
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (fallback) Text(title, style = MaterialTheme.typography.labelLarge)
        else Text(stringResource(R.string.vlm_composition_plan_title, index + 1, title), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.vlm_actual_composition, actual), style = MaterialTheme.typography.labelSmall)
        val guidance = CompositionGuidance.from(plan)
        val directive = guidance.directive
        Text(
            stringResource(
                R.string.vlm_composition_pose_detail,
                bodyLabel(directive.body), resolvedArmLabel(guidance), headLabel(directive.head),
                stanceLabel(directive.stance), expressionLabel(guidance.expression), distanceLabel(guidance.distance)
            ),
            style = MaterialTheme.typography.labelSmall
        )
        Text(stringResource(R.string.vlm_composition_guidance, compositionGuidanceText(plan)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vlm_resolved_guidance"))
        Text(stringResource(R.string.vlm_guidance_size, guidance.heightPercent, guidance.horizontalPercent), style = MaterialTheme.typography.labelSmall)
        // 原文只作为可追溯分析来源，不能与实际动作指令混在同一区域。
        if (fallback && plan.guidance.isNotBlank()) Text(stringResource(R.string.vlm_model_scene_observation, plan.guidance), style = MaterialTheme.typography.bodySmall)
        else if (!fallback && plan.reason.isNotBlank()) Text(stringResource(R.string.vlm_model_scene_observation, plan.reason), style = MaterialTheme.typography.bodySmall)
    }
}

/** 文字由最终布局和渲染动作派生，拖动、缩放或去重后自动跟随。 */
@Composable
internal fun compositionGuidanceText(plan: CompositionPlan): String {
    val guide = CompositionGuidance.from(plan)
    val camera = stringResource(if (guide.needsRetake) R.string.vlm_guidance_camera_retake else R.string.vlm_guidance_camera, shotLabel(guide.shot), guide.zoom)
    val placement = if (guide.shot in listOf(ShotType.FULL, ShotType.ENVIRONMENT))
        stringResource(R.string.vlm_guidance_placement, placementZoneLabel(plan.avatar.foot.x))
    else stringResource(R.string.vlm_guidance_placement_cropped, placementZoneLabel(plan.avatar.foot.x), shotLabel(guide.shot))
    val pose = stringResource(R.string.vlm_guidance_pose, facingLabel(plan.avatar.yaw), bodyLabel(guide.directive.body), resolvedArmLabel(guide), headLabel(guide.directive.head), stanceLabel(guide.directive.stance), expressionLabel(guide.expression))
    val steps = plan.resolvedSpec?.action?.stepResources.orEmpty().map { stringResource(it) }
    // 完整姿态含动作自身的朝向；avatar.yaw 是追加布局旋转，不能冒充最终身体朝向。
    return if (steps.isNotEmpty()) (listOf(camera, placement) + steps +
        stringResource(R.string.vlm_photo_pose_expression, expressionLabel(guide.expression))).joinToString("\n")
    else listOf(camera, placement, pose).joinToString("\n")
}

@Composable
private fun planFacingLabel(plan: CompositionPlan): String =
    plan.resolvedSpec?.action?.takeIf { it.stepResources.isNotEmpty() }?.labelResource?.let { stringResource(it) }
        ?: facingLabel(plan.avatar.yaw)

@Composable
private fun BackgroundReference(state: VlmUiState, modifier: Modifier) {
    val scene = state.scene ?: return
    val plan = state.referencePlan ?: return
    val bitmap by produceState<Bitmap?>(null, scene.id, plan.crop) {
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(scene.imagePath, options)?.let { original ->
                val r = plan.crop
                val x = (r.left * original.width).toInt(); val y = (r.top * original.height).toInt()
                val clipped = Bitmap.createBitmap(original, x, y,
                    ((r.right - r.left) * original.width).toInt().coerceIn(1, original.width - x),
                    ((r.bottom - r.top) * original.height).toInt().coerceIn(1, original.height - y))
                if (clipped !== original) original.recycle()
                clipped
            }
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.vlm_background_reference), modifier,
        contentScale = ContentScale.FillBounds, alpha = .30f) }
}

@Composable
private fun ScanViews(state: VlmUiState, busy: Boolean, onEvent: (VlmIntent) -> Unit) {
    val frames = state.scanSnapshot?.frames.orEmpty()
    val visible = frames
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visible, key = { it.id }) { frame ->
            val bitmap by produceState<Bitmap?>(null, frame.id) {
                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(frame.scene.modelImagePath, BitmapFactory.Options().apply { inSampleSize = 4 }) }
            }
            Column(Modifier.width(112.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.vlm_scan_frame), Modifier.size(112.dp, 84.dp), contentScale = ContentScale.Crop) }
                val label = state.viewCandidates.firstOrNull { it.frameId == frame.id }?.subject?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.vlm_scan_view_number, frames.indexOf(frame) + 1)
                FilterChip(selected = state.selectedFrameId == frame.id, enabled = !busy,
                    onClick = { onEvent(VlmIntent.ScanViewSelected(frame.id)) },
                    label = { Text(label, maxLines = 2) }, modifier = Modifier.testTag("vlm_scan_view_${frame.id}"))
                if (frame.preferred) Text(stringResource(R.string.vlm_scan_preferred), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    state.viewCandidates.firstOrNull { it.frameId == state.selectedFrameId }?.let { candidate ->
        Text(candidate.reason.ifBlank { stringResource(R.string.vlm_scan_reason_not_provided) }, style = MaterialTheme.typography.bodySmall)
        candidate.uncertainties.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** 手动选择仅发 MVI 事件；目录能力由状态机提供，技术版本不进入用户文案。 */
@Composable
internal fun PhotoActionControls(state: VlmUiState, plan: CompositionPlan, busy: Boolean, onEvent: (VlmIntent) -> Unit) {
    Text(stringResource(R.string.vlm_photo_actions), style = MaterialTheme.typography.labelLarge)
    if (plan.resolvedSpec?.action?.readiness == ActionReadiness.DRAFT) {
        Text(stringResource(R.string.vlm_photo_sample_draft_notice), style = MaterialTheme.typography.bodySmall)
    }
    if (PhotoActionCatalog.isRainAction(plan.resolvedSpec?.action?.id)) {
        Text(stringResource(R.string.vlm_photo_sample_credit), style = MaterialTheme.typography.bodySmall)
        AvatarEthnicitySelector(plan.avatar.ethnicity ?: AvatarEthnicity.WHITE, !busy, "vlm_ethnicity") {
            onEvent(VlmIntent.EthnicitySelected(it))
        }
    }
    if (plan.resolvedSpec?.action?.id == PhotoActionCatalog.DIRECTIVE_ID) {
        Text(stringResource(R.string.vlm_photo_model_action), style = MaterialTheme.typography.bodySmall)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.availableActions[plan.id].orEmpty(), key = { it.id }) { action ->
            FilterChip(selected = plan.resolvedSpec?.action?.id == action.id,
                onClick = { onEvent(VlmIntent.ActionSelected(action.id)) }, enabled = !busy,
                label = { Text(action.labelResource?.let { stringResource(it) } ?: poseLabel(action.legacyLabel)) },
                modifier = Modifier.testTag("vlm_action_${action.id}"))
        }
    }
    Text(stringResource(R.string.vlm_photo_expressions), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.availableExpressions[plan.id].orEmpty(), key = { it.name }) { expression ->
            FilterChip(selected = plan.avatar.expression == expression,
                onClick = { onEvent(VlmIntent.ExpressionSelected(expression)) }, enabled = !busy,
                label = { Text(expressionLabel(expression)) }, modifier = Modifier.testTag("vlm_expression_${expression.name}"))
        }
    }
    var intensity by remember(plan.id, plan.revision, busy) { mutableFloatStateOf(plan.avatar.expressionIntensity) }
    Text(stringResource(R.string.vlm_photo_intensity, (intensity * 100f).roundToInt()))
    Slider(value = intensity, onValueChange = { intensity = it }, valueRange = 0f..1f,
        onValueChangeFinished = { onEvent(VlmIntent.ExpressionIntensityChanged(intensity)) },
        enabled = !busy && plan.avatar.expression != ExpressionId.NEUTRAL, modifier = Modifier.testTag("vlm_expression_intensity"))
    PhotoFacePreview(state, busy, onEvent)
}

@Composable
private fun PhotoFacePreview(state: VlmUiState, busy: Boolean, onEvent: (VlmIntent) -> Unit) {
    TextButton(onClick = { onEvent(VlmIntent.FaceVisible(!state.faceVisible)) }, enabled = !busy,
        modifier = Modifier.testTag("vlm_show_face")) {
        Text(stringResource(if (state.faceVisible) R.string.vlm_photo_hide_face else R.string.vlm_photo_show_face))
    }
    if (state.faceVisible) {
        if (state.faceLoading) Text(stringResource(R.string.vlm_photo_face_loading))
        state.facePreview?.let { bitmap ->
            Image(bitmap.asImageBitmap(), stringResource(R.string.vlm_photo_face_description),
                Modifier.size(160.dp).testTag("vlm_face_preview"), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun AvatarEthnicitySelector(selected: AvatarEthnicity, enabled: Boolean, tagPrefix: String,
                                    onSelect: (AvatarEthnicity) -> Unit) {
    Text(stringResource(R.string.vlm_avatar_ethnicity_title), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(AvatarEthnicity.entries, key = { it.name }) { ethnicity ->
            FilterChip(selected = selected == ethnicity, enabled = enabled, onClick = { onSelect(ethnicity) },
                modifier = Modifier.testTag("${tagPrefix}_${ethnicity.name}"),
                label = { Text(stringResource(when (ethnicity) {
                    AvatarEthnicity.WHITE -> R.string.vlm_avatar_ethnicity_white
                    AvatarEthnicity.ASIAN -> R.string.vlm_avatar_ethnicity_asian
                    AvatarEthnicity.BLACK -> R.string.vlm_avatar_ethnicity_black
                })) })
        }
    }
    Text(stringResource(R.string.vlm_avatar_ethnicity_draft), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun resolvedArmLabel(guide: CompositionGuidance): String =
    if (guide.legacyHandNearFace) poseLabel(PoseId.HAT) else armLabel(guide.directive.arms)

/** 在生成前由用户固定本次构图参考的角色，模型不会根据图片替换该选择。 */
@Composable
private fun AvatarSelector(state: VlmUiState, onEvent: (VlmIntent) -> Unit, busy: Boolean) {
    Text(stringResource(R.string.vlm_avatar_select_title), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(AvatarId.entries) { avatar ->
            FilterChip(
                selected = state.selectedAvatar == avatar,
                onClick = { onEvent(VlmIntent.AvatarSelected(avatar)) },
                enabled = !busy,
                label = { Text(avatarLabel(avatar)) }
            )
        }
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
@Composable
private fun poseLabel(pose: PoseId): String = stringResource(when (pose) {
    PoseId.RELAXED -> R.string.vlm_pose_relaxed
    PoseId.SIDE -> R.string.vlm_pose_side
    PoseId.LOOK_BACK -> R.string.vlm_pose_look_back
    PoseId.HANDS_FRONT -> R.string.vlm_pose_hands_front
    PoseId.HAND_HIP -> R.string.vlm_pose_hand_hip
    PoseId.HANDS_HIPS -> R.string.vlm_pose_hands_hips
    PoseId.WAVE -> R.string.vlm_pose_wave
    PoseId.POINT -> R.string.vlm_pose_point
    PoseId.HAT -> R.string.vlm_pose_hat
    PoseId.LOOK_UP -> R.string.vlm_pose_look_up
    PoseId.LOOK_DOWN -> R.string.vlm_pose_look_down
    PoseId.ARMS_OPEN -> R.string.vlm_pose_arms_open
})

/** 将面部表情编号映射为用户名称。
 * @param expression 公共表情编号。
 * @return 简体中文表情名称。
 */
@Composable
private fun expressionLabel(expression: ExpressionId): String = stringResource(when (expression) {
    ExpressionId.NEUTRAL -> R.string.vlm_expression_neutral
    ExpressionId.SMILE -> R.string.vlm_expression_smile
    ExpressionId.HAPPY -> R.string.vlm_expression_happy
    ExpressionId.SURPRISED -> R.string.vlm_expression_surprised
    ExpressionId.THOUGHTFUL -> R.string.vlm_expression_thoughtful
    ExpressionId.CONFIDENT -> R.string.vlm_expression_confident
})

@Composable
private fun bodyLabel(body: BodyPose): String = stringResource(when (body) {
    BodyPose.FRONT -> R.string.vlm_body_front
    BodyPose.TURN_LEFT -> R.string.vlm_body_turn_left
    BodyPose.TURN_RIGHT -> R.string.vlm_body_turn_right
    BodyPose.LEAN_FORWARD -> R.string.vlm_body_lean_forward
})

@Composable
private fun armLabel(arms: ArmPose): String = stringResource(when (arms) {
    ArmPose.RELAXED -> R.string.vlm_arms_relaxed
    ArmPose.HAND_HIP -> R.string.vlm_arms_hand_hip
    ArmPose.HANDS_HIPS -> R.string.vlm_arms_hands_hips
    ArmPose.HANDS_FRONT -> R.string.vlm_arms_hands_front
    ArmPose.WAVE -> R.string.vlm_arms_wave
    ArmPose.POINT -> R.string.vlm_arms_point
    ArmPose.ARMS_OPEN -> R.string.vlm_arms_open
})

@Composable
private fun headLabel(head: HeadPose): String = stringResource(when (head) {
    HeadPose.FRONT -> R.string.vlm_head_front
    HeadPose.TURN_LEFT -> R.string.vlm_head_turn_left
    HeadPose.TURN_RIGHT -> R.string.vlm_head_turn_right
    HeadPose.LOOK_UP -> R.string.vlm_head_look_up
    HeadPose.LOOK_DOWN -> R.string.vlm_head_look_down
    HeadPose.LOOK_BACK -> R.string.vlm_head_look_back
})

@Composable
private fun stanceLabel(stance: StancePose): String = stringResource(when (stance) {
    StancePose.NEUTRAL -> R.string.vlm_stance_neutral
    StancePose.WEIGHT_LEFT -> R.string.vlm_stance_weight_left
    StancePose.WEIGHT_RIGHT -> R.string.vlm_stance_weight_right
    StancePose.STEP_FORWARD -> R.string.vlm_stance_step_forward
})

@Composable
private fun distanceLabel(distance: SubjectDistance): String = stringResource(when (distance) {
    SubjectDistance.FAR -> R.string.vlm_distance_far
    SubjectDistance.MID -> R.string.vlm_distance_mid
    SubjectDistance.NEAR -> R.string.vlm_distance_near
})

/** 将用户选择的内置 Rocketbox 人像映射为本地化标签。 */
@Composable
private fun avatarLabel(avatar: AvatarId): String = stringResource(when (avatar) {
    AvatarId.ADULT_FEMALE -> R.string.vlm_avatar_adult_female
    AvatarId.ADULT_MALE -> R.string.vlm_avatar_adult_male
    AvatarId.CHILD_GIRL -> R.string.vlm_avatar_child_girl
    AvatarId.CHILD_BOY -> R.string.vlm_avatar_child_boy
})

/** 将景别编号转换为简体中文。
 * @param shot 公共协议中的景别。
 * @return 用户可理解的名称。
 */
@Composable
private fun shotLabel(shot: ShotType): String = stringResource(when (shot) {
    ShotType.ENVIRONMENT -> R.string.vlm_shot_environment
    ShotType.FULL -> R.string.vlm_shot_full
    ShotType.HALF -> R.string.vlm_shot_half
    ShotType.CLOSE_UP -> R.string.vlm_shot_close_up
})

/** 显示安全映射后真正应用在预览中的画面方位。 */
@Composable
private fun placementZoneLabel(x: Float): String = stringResource(when {
    x < .4f -> R.string.vlm_zone_left
    x > .6f -> R.string.vlm_zone_right
    else -> R.string.vlm_zone_center
})

/** 显示安全映射后真正应用在预览中的朝向。 */
@Composable
private fun facingLabel(yaw: Float): String = stringResource(when {
    yaw < -8f -> R.string.vlm_facing_left
    yaw > 8f -> R.string.vlm_facing_right
    else -> R.string.vlm_facing_front
})

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
