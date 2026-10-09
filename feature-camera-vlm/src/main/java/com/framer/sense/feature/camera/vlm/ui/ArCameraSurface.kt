package com.framer.sense.feature.camera.vlm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.camera.VlmCameraController
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 能力查询在首次绑定相机之前完成；安装完成后先等 CameraX 关闭，再启用 AR。 */
@Composable
internal fun ArSupportControl(controller: VlmCameraController, state: VlmUiState, permission: Boolean, onEvent: (VlmIntent) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestEvent by rememberUpdatedState(onEvent)
    var availability by remember { mutableStateOf<ArCoreApk.Availability?>(null) }
    var installing by remember { mutableStateOf(false) }
    var activating by remember { mutableStateOf(false) }
    val failure = stringResource(R.string.vlm_ar_install_failed)
    suspend fun activate() {
        if (activating) return
        activating = true
        try {
            val started = SystemClock.elapsedRealtime()
            Log.i("VlmCameraUi", "启用 AR：等待 CameraX 关闭")
            controller.releaseForAr()
            Log.i("VlmCameraUi", "相机互斥切换完成 elapsedMs=${SystemClock.elapsedRealtime()-started}")
            latestEvent(VlmIntent.ArUpdated(ArUiState(source = ArSource.SHARED_CAMERA)))
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { latestEvent(VlmIntent.UserMessage(failure)) }
        finally { activating = false }
    }
    LaunchedEffect(permission) {
        if (!permission) return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        Log.i("VlmCameraUi", "AR 能力查询开始 permission=$permission")
        availability = suspendCancellableCoroutine { continuation ->
            ArCoreApk.getInstance().checkAvailabilityAsync(context.applicationContext) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
        Log.i("VlmCameraUi", "AR 能力查询完成 availability=$availability elapsedMs=${SystemClock.elapsedRealtime()-started} source=${state.ar.source}")
        if (state.ar.source == ArSource.CHECKING) {
            if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) activate()
            else latestEvent(VlmIntent.ArUpdated(ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED)))
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && installing) {
                installing = false
                try {
                    val activity = context.activity() ?: error("AR_ACTIVITY_MISSING")
                    if (ArCoreApk.getInstance().requestInstall(activity, false) == ArCoreApk.InstallStatus.INSTALLED) scope.launch { activate() }
                } catch (_: Exception) { latestEvent(VlmIntent.UserMessage(failure)) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Text(stringResource(when (state.ar.source) {
        ArSource.CHECKING -> R.string.vlm_ar_checking
        ArSource.SCREEN -> R.string.vlm_ar_screen_mode
        ArSource.SHARED_CAMERA -> when (state.ar.tracking) {
            ArTrackingState.NEEDS_GROUND -> R.string.vlm_ar_choose_ground
            ArTrackingState.PLACED -> R.string.vlm_ar_placed
            ArTrackingState.LOST, ArTrackingState.PAUSED -> R.string.vlm_ar_tracking_lost
            else -> R.string.vlm_ar_tracking
        }
    }), modifier = Modifier.height(44.dp), maxLines = 2)
    if (state.ar.source == ArSource.SHARED_CAMERA && state.referencePlan != null)
        Text(stringResource(if (state.ar.depth) R.string.vlm_ar_depth_on else R.string.vlm_ar_depth_off))
    if (state.ar.source == ArSource.SCREEN && availability?.isSupported == true) {
        TextButton(enabled = permission && !activating && !installing && state.stage == VlmStage.LIVE && !state.saving && !state.modelBusy && state.scanSnapshot == null,
            onClick = {
                try {
                    val activity = context.activity() ?: error("AR_ACTIVITY_MISSING")
                    if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) installing = true
                    else scope.launch { activate() }
                } catch (_: Exception) { latestEvent(VlmIntent.UserMessage(failure)) }
            }) { Text(stringResource(R.string.vlm_ar_enable)) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Surface 不随扫描阶段卸载；所有图形与相机工作委派到 AR 专用线程。 */
@Composable
internal fun ArCameraSurface(controller: VlmCameraController, state: VlmUiState, onEvent: (VlmIntent) -> Unit, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestEvent by rememberUpdatedState(onEvent)
    val rotation = LocalView.current.display?.rotation ?: 0
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    val arOwner = remember(controller) { arrayOfNulls<ArSharedCameraController>(1) }
    val ar = remember(controller) {
        ArSharedCameraController(context, { update ->
            val own = arOwner[0]
            if (controller.ar !== own || own == null) return@ArSharedCameraController
            if (update.source == ArSource.SCREEN) controller.removeAr(own)
            latestEvent(VlmIntent.ArUpdated(update))
        }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.CameraConfigured(it)) }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.CameraReady(it)) }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.AnalysisReceived(it)) }).also { arOwner[0] = it }
    }
    DisposableEffect(ar) {
        controller.installAr(ar)
        ar.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { controller.removeAr(ar); latestEvent(VlmIntent.CameraStopped) }
    }
    val live = state.stage == VlmStage.LIVE
    val plan = state.referencePlan.takeIf { live }
    val target = if (state.stage == VlmStage.SCANNING) state.scanSession?.id else plan?.viewBinding?.let { "guide:${it.frameId}:${plan.id}:${plan.revision}" }
    SideEffect { ar.update(ArSharedCameraController.Request(target, state.scanSession?.startedNs?.takeIf { state.stage == VlmStage.SCANNING },
        state.cameraReady && (state.stage == VlmStage.SCANNING && !state.scanProgress.paused || live && target != null),
        plan, plan != null && (plan.viewBinding == null || state.background.status != BackgroundStatus.SEARCHING),
        state.requiresLivePreview)) }
    AndroidView(modifier = modifier.clipToBounds(), factory = { ctx -> ArPreviewSurfaceView(ctx).apply {
        view = this
        setZOrderOnTop(false)
        this.holder.setFormat(PixelFormat.RGBA_8888)
        this.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { Log.i("VlmCameraUi", "Surface 创建 valid=${holder.surface.isValid}") }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // 原生挖孔／清屏也限定在预览区域，避免影响页面底色和其他控件。
                clipBounds = Rect(0, 0, width, height)
                Log.i("VlmCameraUi", "Surface 尺寸 size=${width}x$height format=$format rotation=${display?.rotation} clipped=true onTop=false")
                ar.attach(holder.surface, width, height, display?.rotation ?: 0)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) { Log.i("VlmCameraUi", "Surface 销毁"); ar.detachSurface() }
        })
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) { ar.tap(event.x, event.y); performClick() }
            true
        }
    } })
    LaunchedEffect(rotation) { view?.let { ar.resize(it.width, it.height, rotation) } }
}

/** SurfaceView 的 CLEAR 挖孔只影响自身区域，不能清除 Compose 已绘制的整页底色。 */
private class ArPreviewSurfaceView(context: Context) : SurfaceView(context) {
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            super.draw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            super.dispatchDraw(canvas)
        } finally { canvas.restoreToCount(saved) }
    }
}
