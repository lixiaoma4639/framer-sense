package com.framer.sense.feature.camera.vlm.ui

import android.util.Log
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import com.framer.sense.feature.camera.vlm.agent.SceneDescriptionFormatting
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.agent.CompositionIntentMapper
import com.framer.sense.feature.camera.vlm.market.ModelMarketDialog
import androidx.compose.runtime.saveable.rememberSaveable
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
 * @param onExit App 提供的退出导航回调；保存照片期间不可退出。
 */
@Composable
fun VlmCameraScreen(
    onCaptureActionChanged: (VlmCaptureAction?) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VlmCameraViewModel = hiltViewModel(),
    onExit: (() -> Unit)? = null
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
    LaunchedEffect(state.stage, state.cameraReady, state.saving, state.modelBusy, state.settingsVisible, state.photoSettingsVisible, state.photoSample, permission) {
        latestAction(VlmCaptureAction(permission && state.cameraReady && state.stage == VlmStage.LIVE && !state.saving && !state.modelBusy && !state.settingsVisible && !state.photoSettingsVisible && state.photoSample == null) { latestCapture() })
    }
    DisposableEffect(viewModel, controller) {
        onDispose {
            controller.close()
            var host: android.content.Context? = context
            while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
            viewModel.onLeave(resetCameraMode = (host as? android.app.Activity)?.isChangingConfigurations != true)
            latestAction(null)
        }
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
                    val frozen = controller.freeze(viewModel.state.value.zoom)
                    onEvent(VlmIntent.FrameReady(effect.token, frozen.bitmap, frozen.zoom, frozen.arConfig, frozen.orientation))
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { onEvent(VlmIntent.PlatformFailed(effect.token, e.message ?: "无法冻结画面")) }
                is VlmEffect.Capture -> try {
                    controller.capturePhoto(viewModel.state.value.zoom)
                    onEvent(VlmIntent.CaptureFinished(effect.token))
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { onEvent(VlmIntent.CaptureFinished(effect.token, e.message ?: "保存失败")) }
            }
        }
    }
    val latestExit by rememberUpdatedState(onExit)
    val exitCamera: (() -> Unit)? = onExit?.let { {
        // 即便点击发生在 Compose 尚未重绘之前，也不能中断已经开始的照片保存。
        if (!viewModel.state.value.saving) latestExit?.invoke()
    } }
    CameraBackHandler(state, onEvent, exitCamera)
    val safeInsets = WindowInsets.displayCutout
        .union(WindowInsets.mandatorySystemGestures).asPaddingValues()
    CameraIconRotationScope(onOrientation = {
        controller.orientation(it)
        onEvent(VlmIntent.PhotoOrientationChanged(it))
    }) {
        BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).testTag("vlm_camera_screen").padding(safeInsets)) {
            val controlsMaxHeight = (maxHeight - 48.dp).coerceAtLeast(0.dp)
            CameraPane(state, controller, permission, { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onEvent, Modifier.fillMaxSize().padding(top = 48.dp, bottom = 84.dp))
            CameraBottomBar(state, onEvent, { latestCapture() }, permission,
                Modifier.align(Alignment.BottomCenter).heightIn(max = controlsMaxHeight))
            ArSupportControl(controller, state, permission, onEvent)
            CameraTopBar(state, onEvent, Modifier.align(Alignment.TopCenter), onExit = exitCamera)
            CompositionInstructionOverlay(state, onEvent, Modifier.fillMaxSize())
        }
        if (state.photoSettingsVisible) PhotoSettingsDialog(state, onEvent, { imageLauncher.launch("image/*") })
        if (state.compositionDetailsVisible && state.stage == VlmStage.SCAN_REVIEW) CompositionDetailsDialog(state, onEvent)
        if (state.settingsVisible) ModelSettingsDialog(state, onEvent, { modelLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }, { directoryLauncher.launch(null) })
        state.photoSample?.let { PhotoSampleDialog(it, onEvent) }
    }
}

/** 单独检查制作初稿，所有动作走 MVI；不伪造一个模型构图结果。 */
@Composable
internal fun PhotoSampleDialog(sample: PhotoSampleState, onEvent: (VlmIntent) -> Unit) {
    CameraAdaptiveDialog(onDismissRequest = { onEvent(VlmIntent.ClosePhotoSample) }) {
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
    val ratio = state.photoPreferences.aspectRatio.ratio(LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    val lastBounds = remember { arrayOfNulls<String>(1) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val fitted = widthCaptureViewport(maxWidth.value, ratio)
            Box(Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).size(fitted.width.dp, fitted.height.dp).clipToBounds().onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                val bounds = "x=${rect.left.toInt()} y=${rect.top.toInt()} size=${rect.width.toInt()}x${rect.height.toInt()}"
                if (lastBounds[0] != bounds) { lastBounds[0] = bounds; Log.i("VlmCameraUi", "预览布局 $bounds ratio=$ratio source=${state.ar.source} topAligned=true") }
            }) {
                // 空间会话贯穿扫描、选景和模型分析；冻结图片只是覆盖层。
                if (permission && state.cameraMode == CameraMode.AR && state.ar.source == com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA)
                    key(state.cameraEpoch) { ArCameraSurface(controller, state, onEvent, Modifier.fillMaxSize()) }
                if (live) {
                    if (permission && state.cameraMode == CameraMode.NORMAL) CameraSurface(controller, state, onEvent, Modifier.fillMaxSize())
                    else if (!permission) Button(onClick = requestPermission, modifier = Modifier.align(Alignment.Center)) { Text(stringResource(R.string.vlm_camera_permission)) }
                    if (state.referencePlan?.viewBinding != null && state.background.status == BackgroundStatus.SEARCHING) {
                        BackgroundReference(state, Modifier.fillMaxSize())
                    }
                    LiveReferenceAvatar(state, Modifier.fillMaxSize())
                    BackgroundAlignmentOverlay(state, onEvent, Modifier.fillMaxSize())
                } else if (scene != null) {
                    val preview = selected?.let { state.previews[it.id] }
                    if (preview != null) {
                        CameraPhotoViewport(scene.orientation.previewDegrees, Modifier.fillMaxSize()) {
                            Image(preview.composite.asImageBitmap(), stringResource(R.string.vlm_selected_composition), Modifier.fillMaxSize().testTag("vlm_selected_preview")
                                .pointerInput(selected.id, state.stage) {
                                    if (state.stage == VlmStage.READY) awaitEachGesture {
                                        val first = awaitFirstDown(requireUnconsumed = false)
                                        var multiple = false
                                        do {
                                            val event = awaitPointerEvent()
                                            if (event.changes.count { it.pressed } > 1) multiple = true
                                            val change = event.changes.firstOrNull { it.id == first.id }
                                            if (!multiple && change?.pressed == true) {
                                                val amount = change.positionChange()
                                                change.consume()
                                                if (amount != androidx.compose.ui.geometry.Offset.Zero) {
                                                    val imageSize = fitCaptureViewport(size.width.toFloat(), size.height.toFloat(),
                                                        preview.composite.width.toFloat() / preview.composite.height)
                                                    onEvent(VlmIntent.Move(amount.x / imageSize.width, amount.y / imageSize.height))
                                                }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }, contentScale = ContentScale.Fit)
                        }
                    } else FrozenSceneImage(scene, Modifier.fillMaxSize())
                }
                if (state.stage in listOf(VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING,
                        VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING)) {
                    VlmLoadingOverlay(state.stage, state.directorProgress, { onEvent(VlmIntent.Cancel) }, Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/** 尚未选景时保留已保存的扫描画面；选景状态由流程状态管理。 */
@Composable
internal fun FrozenSceneImage(scene: SceneSnapshot, modifier: Modifier = Modifier) {
    CameraPhotoViewport(scene.orientation.previewDegrees, modifier.fillMaxSize()) {
        AsyncImage(File(scene.imagePath), stringResource(R.string.vlm_scan_frame),
            Modifier.fillMaxSize().testTag("vlm_frozen_scene"), contentScale = ContentScale.Fit)
    }
}

/** 扫描收尾、选景和构图共用黑色进度面板，跟随设备方向转正。 */
@Composable
internal fun VlmLoadingOverlay(
    stage: VlmStage,
    progress: DirectorProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    CameraRotatingViewport(modifier.fillMaxSize().testTag("vlm_loading_viewport")) {
        Column(
            Modifier.align(Alignment.Center).padding(16.dp).widthIn(max = 360.dp)
                .background(Color.Black.copy(alpha = .72f)).padding(16.dp)
                .verticalScroll(rememberScrollState()).testTag("vlm_loading_panel"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Color.White, trackColor = Color.White.copy(alpha = .2f),
                modifier = Modifier.testTag("vlm_loading_spinner"))
            Text(
                when (stage) {
                    VlmStage.FREEZING -> stringResource(R.string.vlm_camera_freezing)
                    VlmStage.SCAN_PROCESSING -> stringResource(R.string.vlm_scan_finishing)
                    VlmStage.VIEW_SELECTING -> stringResource(R.string.vlm_scan_selecting)
                    else -> directorProgressLabel(progress)
                },
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("vlm_director_progress")
            )
            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                modifier = Modifier.testTag("vlm_loading_cancel")) { Text(stringResource(R.string.vlm_action_cancel)) }
        }
    }
}

/** 将结构化进度映射为用户可见的本地化文字。 */
@Composable
internal fun directorProgressLabel(progress: DirectorProgress?): String = when (progress) {
    DirectorProgress.PreparingRequest, null -> stringResource(R.string.vlm_director_preparing)
    DirectorProgress.WaitingPreviousTask -> stringResource(R.string.vlm_flow_waiting_native)
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
    val activeTarget = if (state.stage == VlmStage.SCANNING) state.scanSession?.id else state.referencePlan?.takeIf { state.stage == VlmStage.LIVE && it.viewBinding != null }?.let { "guide:${it.viewBinding?.frameId}:${it.id}:${it.revision}" }
    // 变焦解除参考时停用采样即可，无须重绑已存在的分析用例。
    val targetMemory = remember { arrayOfNulls<String>(1) }
    if (activeTarget != null) targetMemory[0] = activeTarget
    val target = activeTarget ?: targetMemory[0]
    key(LocalConfiguration.current.orientation, LocalView.current.display?.rotation, target, state.cameraEpoch) {
        val owner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        val latestEvent by rememberUpdatedState(onEvent)
        val latestZoom by rememberUpdatedState(state.zoom)
        val latestCanZoom by rememberUpdatedState(state.canZoom)
        val latestCamera by rememberUpdatedState(state.camera)
        val epoch = state.cameraEpoch
        var bindingActive by remember { mutableStateOf(true) }
        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        fun emit(event: VlmIntent) { if (bindingActive) latestEvent(event) }
        val latestSampling by rememberUpdatedState(state.cameraReady && (state.stage == VlmStage.SCANNING && state.scanSession != null && !state.scanProgress.paused || state.stage == VlmStage.LIVE && state.referencePlan?.viewBinding != null))
        DisposableEffect(controller, owner) {
            onDispose { bindingActive = false; controller.unbind(); latestEvent(VlmIntent.CameraBindingStopped(epoch)) }
        }
        AndroidView(modifier = modifier, factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                previewView = this
                val gestures = CameraTouchHandler(context,
                    zoom = { latestZoom }, enabled = { latestCanZoom }, limits = { latestCamera },
                    onZoom = { emit(VlmIntent.ZoomChanged(it)) },
                    onClick = { x, y -> controller.focus(this, x, y); performClick() })
                setOnTouchListener { _, event -> gestures.onTouch(event); true }
                doOnLayout {
                    scope.launch {
                        try { emit(VlmIntent.CameraReady(controller.bind(this@apply, owner, latestZoom, target, state.scanSession?.startedNs?.takeIf { state.stage == VlmStage.SCANNING },
                            { latestSampling }, { emit(VlmIntent.CameraConfigured(it, epoch)) }, { emit(VlmIntent.AnalysisReceived(it, epoch)) }, { emit(VlmIntent.ZoomReported(it, epoch)) }), epoch)) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) { if (bindingActive) latestEvent(VlmIntent.PlatformFailed(null, context.getString(R.string.vlm_camera_start_failed))) }
                    }
                }
            }
        })
        LaunchedEffect(state.zoom, state.cameraReady, previewView) {
            if (!state.cameraReady) return@LaunchedEffect
            previewView?.let { controller.queueZoom(it, latestZoom) }
        }
    }
}

/** 展示方案卡片、文字修改和拍摄提示。
 * @param state 页面状态。
 * @param onEvent 用户操作发送器。
 * @param importImage 固定图片调试入口。
 * @param modifier 控件区域及滚动设置。
 */
/** 展示当前选中方案的 VLM 依据与实际采用的人像构图。 */
@Composable
internal fun CompositionDescriptions(result: CompositionResult, selectedId: String?, sceneObservation: String? = null) {
    val index = result.plans.indexOfFirst { it.id == selectedId }
    val plan = result.plans.getOrNull(index) ?: return
    Text(stringResource(R.string.vlm_composition_recommendations), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
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
        Text(if (fallback) title else stringResource(R.string.vlm_composition_plan_title, index + 1, title),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
        val observation = plan.sceneDescription?.takeIf { it.isNotBlank() }
            ?: sceneObservation?.takeIf { it.isNotBlank() } ?: plan.reason.takeIf { it.isNotBlank() }
        if (plan.reason.isNotBlank() && plan.reason != observation)
            CompositionDescriptionText(listOf(stringResource(R.string.vlm_description_reason) to plan.reason))
        CompositionDescriptionText(listOf(stringResource(R.string.vlm_description_actual) to actual),
            style = MaterialTheme.typography.labelSmall)
        val guidance = CompositionGuidance.from(plan)
        val directive = guidance.directive
        CompositionDescriptionText(listOf(
            stringResource(R.string.vlm_description_body) to bodyLabel(directive.body),
            stringResource(R.string.vlm_description_arms) to resolvedArmLabel(guidance),
            stringResource(R.string.vlm_description_head) to headLabel(directive.head),
            stringResource(R.string.vlm_description_stance) to stanceLabel(directive.stance),
            stringResource(R.string.vlm_description_expression) to expressionLabel(guidance.expression),
            stringResource(R.string.vlm_description_distance) to distanceLabel(guidance.distance)
        ), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("vlm_pose_description"))
        CompositionDescriptionText(listOf(stringResource(R.string.vlm_description_guidance) to compositionGuidanceText(plan)),
            modifier = Modifier.testTag("vlm_resolved_guidance"))
        Text(stringResource(R.string.vlm_guidance_size, guidance.heightPercent, guidance.horizontalPercent),
            style = MaterialTheme.typography.labelSmall, color = Color(0xFF444444))
        observation?.let {
            CompositionDescriptionText(listOf(stringResource(R.string.vlm_description_environment) to
                SceneDescriptionFormatting.format(it)), modifier = Modifier.testTag("vlm_scene_description"))
        }
    }
}

/** 标题用深黑粗体，内容用较浅黑色；各项统一以分号分隔。 */
@Composable
private fun CompositionDescriptionText(fields: List<Pair<String, String>>,
    modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) {
    val separator = stringResource(R.string.vlm_description_separator)
    Text(buildAnnotatedString {
        fields.forEachIndexed { index, (label, content) ->
            if (index > 0) append(separator)
            withStyle(SpanStyle(color = Color(0xFF111111), fontWeight = FontWeight.Bold)) { append(label) }
            withStyle(SpanStyle(color = Color(0xFF444444), fontWeight = FontWeight.Normal)) { append(content) }
        }
    }, modifier = modifier, style = style)
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
    val parts = if (steps.isNotEmpty()) listOf(camera, placement) + steps +
        stringResource(R.string.vlm_photo_pose_expression, expressionLabel(guide.expression))
    else listOf(camera, placement, pose)
    return parts.joinToString("\n") { it.replace(Regex("""[;；]\s*|\s+[·•]\s+"""), "\n") }
}

@Composable
private fun planFacingLabel(plan: CompositionPlan): String =
    plan.resolvedSpec?.action?.takeIf { it.stepResources.isNotEmpty() }?.labelResource?.let { stringResource(it) }
        ?: facingLabel(plan.avatar.yaw)

@Composable
internal fun LiveReferenceAvatar(state: VlmUiState, modifier: Modifier) {
    if (state.stage != VlmStage.LIVE) return
    val plan = state.referencePlan ?: return
    val preview = state.reference
    val mode = referencePresentation(plan, state.background.status, state.ar)
    val previewCurrent = preview?.appliedPlan == plan
    LaunchedEffect(plan.id, plan.revision, mode, previewCurrent) {
        Log.i("VlmCameraUi", "人像显示切换 plan=${plan.id} revision=${plan.revision} mode=$mode background=${state.background.status} tracking=${state.ar.tracking} previewCurrent=$previewCurrent")
    }
    if (mode != ReferencePresentation.SCREEN || !previewCurrent || preview == null) return
    Box(modifier) {
        CameraPhotoViewport(state.scene?.orientation?.previewDegrees ?: 0, Modifier.fillMaxSize()) {
            Image(preview.avatar.asImageBitmap(), stringResource(R.string.vlm_reference_avatar),
                Modifier.fillMaxSize().testTag("vlm_live_reference_avatar"), contentScale = ContentScale.Fit, alpha = .65f)
        }
    }
}

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
    bitmap?.let { image ->
        CameraPhotoViewport(scene.orientation.previewDegrees, modifier) {
            Image(image.asImageBitmap(), stringResource(R.string.vlm_background_reference), Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit, alpha = .30f)
        }
    }
}

@Composable
internal fun ScanViews(state: VlmUiState, busy: Boolean, onEvent: (VlmIntent) -> Unit) {
    val frames = state.scanSnapshot?.frames.orEmpty()
    val visible = frames
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visible, key = { it.id }) { frame ->
            val bitmap by produceState<Bitmap?>(null, frame.id) {
                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(frame.scene.modelImagePath, BitmapFactory.Options().apply { inSampleSize = 4 }) }
            }
            Column(Modifier.width(112.dp)) {
                bitmap?.let { image ->
                    CameraPhotoViewport(frame.scene.orientation.previewDegrees, Modifier.size(112.dp, 84.dp)) {
                        Image(image.asImageBitmap(), stringResource(R.string.vlm_scan_frame), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
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
        items(state.availableActions[plan.id].orEmpty().sortedBy { !PhotoActionCatalog.isRainAction(it.id) }, key = { it.id }) { action ->
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
internal fun PhotoFacePreview(state: VlmUiState, busy: Boolean, onEvent: (VlmIntent) -> Unit) {
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
internal fun AvatarEthnicitySelector(selected: AvatarEthnicity, enabled: Boolean, tagPrefix: String,
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
internal fun AvatarSelector(state: VlmUiState, onEvent: (VlmIntent) -> Unit, busy: Boolean) {
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
internal fun DebugDetails(result: CompositionResult) {
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
internal fun ModelSettingsDialog(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importModel: () -> Unit, importDirectory: () -> Unit, marketContent: (@Composable (() -> Unit) -> Unit)? = null) {
    var marketVisible by rememberSaveable { mutableStateOf(false) }
    var draft by remember(state.settings) { mutableStateOf(CloudModelConfig.applyQwenConfiguration(state.settings)) }
    val offlineState = rememberSaveableStateHolder()
    if (marketVisible) {
        val closeMarket = { marketVisible = false }
        if (marketContent != null) marketContent(closeMarket)
        else ModelMarketDialog(closeMarket, { onEvent(VlmIntent.UseMarketModel(it)) },
            { onEvent(VlmIntent.DeleteMarketModel(it)) }, state.modelBusy, state.settings.mode, state.error)
    }
    if (!marketVisible) CameraAdaptiveAlertDialog(onDismissRequest = { if (!state.modelBusy) onEvent(VlmIntent.SettingsVisible(false)) }, title = { Text(stringResource(R.string.vlm_model_settings_title)) }, modifier = Modifier.testTag("vlm_model_settings_dialog"),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModelMode.entries.forEach { mode ->
                        val selected = draft.mode == mode
                        OutlinedButton(onClick = { draft = draft.copy(mode = mode) },
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("vlm_model_mode_${mode.name}")
                                .semantics { this.selected = selected },
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)) {
                            Text(stringResource(when (mode) {
                                ModelMode.OFFLINE -> R.string.vlm_model_mode_offline
                                ModelMode.CLOUD -> R.string.vlm_model_mode_cloud
                                ModelMode.AUTO -> R.string.vlm_model_mode_auto
                            }), textAlign = TextAlign.Center)
                        }
                    }
                }
                HorizontalDivider()
                if (draft.mode != ModelMode.OFFLINE) {
                    Column(Modifier.testTag("vlm_cloud_configuration"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Region.entries.forEach { region ->
                                val label = stringResource(if (region == Region.CN) R.string.vlm_model_region_cn else R.string.vlm_model_region_global)
                                TextButton(onClick = { draft = draft.copy(region = region, preferred = if (region == Region.CN) ProviderId.QWEN else ProviderId.GPT) }) {
                                    Text(if (draft.region == region) stringResource(R.string.vlm_model_selected_option, label) else label)
                                }
                            }
                        }
                        val providers = if (draft.region == Region.CN) listOf(ProviderId.QWEN, ProviderId.SEED) else listOf(ProviderId.GPT, ProviderId.GEMINI)
                        Row { providers.forEach { p -> TextButton(onClick = { draft = draft.copy(preferred = p) }) {
                            val label = stringResource(when (p) {
                                ProviderId.QWEN -> R.string.vlm_model_provider_qwen
                                ProviderId.SEED -> R.string.vlm_model_provider_seed
                                ProviderId.GPT -> R.string.vlm_model_provider_gpt
                                ProviderId.GEMINI -> R.string.vlm_model_provider_gemini
                                ProviderId.LOCAL -> R.string.vlm_model_mode_offline
                            })
                            Text(if (draft.preferred == p) stringResource(R.string.vlm_model_selected_option, label) else label)
                        } } }
                        val showQwen = draft.region == Region.CN && (draft.preferred == ProviderId.QWEN || draft.mode == ModelMode.AUTO)
                        if (showQwen) QwenModelPanel(draft, { draft = it }, state.modelBusy, onEvent)
                        val showSeed = draft.region == Region.CN && (draft.preferred == ProviderId.SEED || draft.mode == ModelMode.AUTO)
                        if (showSeed) {
                            if (showQwen) HorizontalDivider()
                            SeedModelPanel(draft, { draft = it }, state.modelBusy, onEvent)
                        }
                        val needsGateway = draft.region == Region.GLOBAL ||
                            (showQwen && draft.qwenAccess == QwenAccess.GATEWAY) || (showSeed && draft.seedAccess == SeedAccess.GATEWAY)
                        if (needsGateway) {
                            if (showQwen || showSeed) HorizontalDivider()
                            Column(Modifier.testTag("vlm_gateway_configuration"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(draft.gateway, { draft = draft.copy(gateway = it) }, label = { Text(stringResource(R.string.vlm_model_gateway)) }, singleLine = true)
                                SecretKeyField(draft.gatewayToken, { draft = draft.copy(gatewayToken = it) },
                                    R.string.vlm_model_gateway_token, "vlm_gateway_token")
                                Text(stringResource(R.string.vlm_model_gateway_hint), style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { onEvent(VlmIntent.CheckGateway) }, enabled = !state.modelBusy) { Text(stringResource(R.string.vlm_model_check_gateway)) }
                            }
                        }
                    }
                }
                if (draft.mode == ModelMode.AUTO) HorizontalDivider(Modifier.testTag("vlm_cloud_offline_divider"))
                if (draft.mode != ModelMode.CLOUD) {
                    offlineState.SaveableStateProvider("offline_model") {
                        Column(Modifier.testTag("vlm_offline_configuration"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OfflineModelPanel(state, onEvent, importDirectory, importModel, { marketVisible = true })
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = { onEvent(VlmIntent.SaveSettings(draft)) }, enabled = !state.modelBusy) { Text(stringResource(R.string.vlm_model_settings_save)) } },
        dismissButton = { TextButton(onClick = { onEvent(VlmIntent.SettingsVisible(false)) }, enabled = !state.modelBusy) { Text(stringResource(R.string.vlm_model_settings_close)) } }
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
