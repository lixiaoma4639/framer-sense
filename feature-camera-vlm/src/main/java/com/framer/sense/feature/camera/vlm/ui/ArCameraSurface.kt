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

/** 普通相机不查询或自动接管；用户开启 AR 后等待旧相机真正关闭。 */
@Composable
internal fun ArSupportControl(controller: VlmCameraController, state: VlmUiState, permission: Boolean, onEvent: (VlmIntent) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestEvent by rememberUpdatedState(onEvent)
    val latestState by rememberUpdatedState(state)
    var installingEpoch by remember { mutableStateOf<Int?>(null) }
    var activatingEpoch by remember { mutableStateOf<Int?>(null) }
    val failure = stringResource(R.string.vlm_ar_install_failed)
    suspend fun activate(epoch: Int) {
        if (activatingEpoch == epoch || latestState.cameraMode != CameraMode.AR || latestState.cameraEpoch != epoch) return
        activatingEpoch = epoch
        try {
            controller.releaseForAr()
            if (latestState.cameraMode == CameraMode.AR && latestState.cameraEpoch == epoch)
                latestEvent(VlmIntent.ArUpdated(ArUiState(source = ArSource.SHARED_CAMERA), epoch))
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            latestEvent(VlmIntent.ArUpdated(ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED), epoch))
            latestEvent(VlmIntent.UserMessage(failure))
        } finally { if (activatingEpoch == epoch) activatingEpoch = null }
    }
    LaunchedEffect(permission, state.cameraMode, state.cameraEpoch) {
        if (!permission || state.cameraMode != CameraMode.AR) return@LaunchedEffect
        val epoch = state.cameraEpoch
        try {
            val availability = suspendCancellableCoroutine<ArCoreApk.Availability> { continuation ->
                ArCoreApk.getInstance().checkAvailabilityAsync(context.applicationContext) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            if (!availability.isSupported) {
                latestEvent(VlmIntent.ArUpdated(ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED), epoch))
                return@LaunchedEffect
            }
            val activity = context.activity() ?: error("AR_ACTIVITY_MISSING")
            if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED)
                installingEpoch = epoch
            else activate(epoch)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            latestEvent(VlmIntent.ArUpdated(ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED), epoch))
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val epoch = installingEpoch
            if (event == Lifecycle.Event.ON_RESUME && epoch != null) {
                installingEpoch = null
                if (latestState.cameraMode == CameraMode.AR && latestState.cameraEpoch == epoch) {
                    try {
                        val activity = context.activity() ?: error("AR_ACTIVITY_MISSING")
                        if (ArCoreApk.getInstance().requestInstall(activity, false) == ArCoreApk.InstallStatus.INSTALLED)
                            scope.launch { activate(epoch) }
                        else latestEvent(VlmIntent.ArUpdated(ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED), epoch))
                    } catch (_: Exception) {
                        latestEvent(VlmIntent.ArUpdated(ArUiState(ArSource.SCREEN, ArTrackingState.UNSUPPORTED), epoch))
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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
    val epoch = state.cameraEpoch
    val arOwner = remember(controller) { arrayOfNulls<ArSharedCameraController>(1) }
    val ar = remember(controller) {
        ArSharedCameraController(context, { update ->
            val own = arOwner[0]
            if (controller.ar !== own || own == null) return@ArSharedCameraController
            if (update.source == ArSource.SCREEN) controller.removeAr(own)
            latestEvent(VlmIntent.ArUpdated(update, epoch))
        }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.CameraConfigured(it, epoch)) }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.CameraReady(it, epoch)) }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.AnalysisReceived(it, epoch)) }).also { arOwner[0] = it }
    }
    DisposableEffect(ar) {
        controller.installAr(ar)
        ar.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { controller.removeAr(ar); latestEvent(VlmIntent.CameraBindingStopped(epoch)) }
    }
    val live = state.stage == VlmStage.LIVE
    val plan = state.referencePlan.takeIf { live }
    val target = if (state.stage == VlmStage.SCANNING) state.scanSession?.id else plan?.viewBinding?.let { "guide:${it.frameId}:${plan.id}:${plan.revision}" }
    SideEffect { ar.update(ArSharedCameraController.Request(target, state.scanSession?.startedNs?.takeIf { state.stage == VlmStage.SCANNING },
        state.cameraReady && (state.stage == VlmStage.SCANNING && !state.scanProgress.paused || live && target != null),
        plan, plan != null && (plan.viewBinding == null || state.background.status != BackgroundStatus.SEARCHING),
        state.requiresLivePreview, state.scene?.orientation ?: state.photoOrientation)) }
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
        val gestureGate = CameraGestureGate()
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureGate.begin()
            gestureGate.pointers(event.pointerCount)
            if (event.action == MotionEvent.ACTION_UP && gestureGate.canClick()) { ar.tap(event.x, event.y); performClick() }
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
