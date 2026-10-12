package com.framer.sense.feature.camera.vlm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    fun current(epoch: Int) = latestState.cameraMode == CameraMode.AR && latestState.cameraEpoch == epoch
    fun fail(epoch: Int, phase: String, reason: ArFailure, error: Throwable? = null) {
        Log.e("VlmAr", "AR 启用失败 epoch=$epoch phase=$phase code=$reason stale=${!current(epoch)}", error)
        if (current(epoch)) latestEvent(VlmIntent.ArUpdated(ArUiState(ArSource.SCREEN,
            ArTrackingState.UNSUPPORTED, failure = reason, failurePhase = phase), epoch))
    }
    suspend fun activate(epoch: Int) {
        if (activatingEpoch == epoch || !current(epoch)) return
        activatingEpoch = epoch
        val started = SystemClock.elapsedRealtime()
        try {
            Log.i("VlmAr", "相机交接开始 epoch=$epoch")
            controller.releaseForAr()
            Log.i("VlmAr", "相机交接完成 epoch=$epoch elapsedMs=${SystemClock.elapsedRealtime() - started} stale=${!current(epoch)}")
            if (current(epoch)) latestEvent(VlmIntent.ArUpdated(ArUiState(source = ArSource.SHARED_CAMERA), epoch))
        } catch (timeout: TimeoutCancellationException) {
            fail(epoch, "CAMERAX_RELEASE", ArFailure.CAMERA_HANDOFF_TIMEOUT, timeout)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { fail(epoch, "CAMERAX_RELEASE", ArFailure.from(error), error) }
        finally { if (activatingEpoch == epoch) activatingEpoch = null }
    }
    fun requestInstallation(epoch: Int, userRequested: Boolean): Boolean {
        val activity = context.activity()
        if (activity == null) {
            fail(epoch, "INSTALL", ArFailure.ACTIVITY_MISSING)
            return false
        }
        val status = ArCoreApk.getInstance().requestInstall(activity, userRequested)
        Log.i("VlmAr", "AR 服务安装结果 epoch=$epoch userRequested=$userRequested status=$status")
        return if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
            // 安装尚未完成时等待下一次恢复前台，不能将 INSTALL_REQUESTED 当作启动失败。
            installingEpoch = epoch
            false
        } else {
            installingEpoch = null
            true
        }
    }
    LaunchedEffect(permission, state.cameraMode, state.cameraEpoch) {
        if (!permission || state.cameraMode != CameraMode.AR) return@LaunchedEffect
        val epoch = state.cameraEpoch
        installingEpoch = null
        var phase = "AVAILABILITY"
        try {
            Log.i("VlmAr", "AR 支持检查开始 epoch=$epoch device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}")
            readArService(context, epoch, "com.google.ar.core")
            readArService(context, epoch, "com.google.android.gms")
            readArService(context, epoch, "com.android.vending")
            var availability: ArCoreApk.Availability? = null
            for (attempt in 1..3) {
                availability = withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine<ArCoreApk.Availability> { continuation ->
                        ArCoreApk.getInstance().checkAvailabilityAsync(context.applicationContext) { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }
                }
                Log.i("VlmAr", "AR 支持检查结果 epoch=$epoch attempt=$attempt availability=$availability")
                if (!current(epoch)) return@LaunchedEffect
                if (availability != null && !availability.isUnknown) break
                if (attempt < 3) delay(400)
            }
            if (availability?.isSupported != true) {
                val reason = if (availability?.isUnsupported == true) ArFailure.DEVICE_UNSUPPORTED
                    else ArFailure.forUnknownAvailability(readArService(context, epoch, "com.google.ar.core"))
                fail(epoch, phase, reason)
                return@LaunchedEffect
            }
            phase = "INSTALL"
            if (requestInstallation(epoch, true)) activate(epoch)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { fail(epoch, phase, ArFailure.from(error), error) }
        catch (error: LinkageError) { fail(epoch, phase, ArFailure.NATIVE_INITIALIZATION, error) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val epoch = installingEpoch
            if (event == Lifecycle.Event.ON_RESUME && epoch != null) {
                installingEpoch = null
                if (current(epoch)) {
                    try {
                        if (requestInstallation(epoch, false)) scope.launch { activate(epoch) }
                    } catch (error: Exception) { fail(epoch, "INSTALL_RESUME", ArFailure.from(error), error) }
                    catch (error: LinkageError) { fail(epoch, "INSTALL_RESUME", ArFailure.NATIVE_INITIALIZATION, error) }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** 所查包均已在 SDK 或模块清单中声明可见；查询异常与包缺失分别记录。 */
@Suppress("DEPRECATION")
private fun readArService(context: Context, epoch: Int, packageName: String): ArServiceState = try {
    val info = context.packageManager.getPackageInfo(packageName, 0)
    val status = if (info.applicationInfo?.enabled == false) ArServiceState.DISABLED else ArServiceState.AVAILABLE
    val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    Log.i("VlmAr", "AR 服务诊断 epoch=$epoch package=$packageName state=$status version=${info.versionName} code=$versionCode")
    status
} catch (_: PackageManager.NameNotFoundException) {
    Log.w("VlmAr", "AR 服务诊断 epoch=$epoch package=$packageName state=NOT_INSTALLED")
    ArServiceState.NOT_INSTALLED
} catch (error: Exception) {
    Log.e("VlmAr", "AR 服务诊断 epoch=$epoch package=$packageName state=QUERY_FAILED", error)
    ArServiceState.QUERY_FAILED
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
        }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.CameraConfigured(it, epoch)) }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.CameraReady(it, epoch)) }, { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.AnalysisReceived(it, epoch)) },
            { if (controller.ar === arOwner[0]) latestEvent(VlmIntent.ArGroundUpdated(it, epoch)) },
            { id, revision, ground, plane, point -> if (controller.ar === arOwner[0]) latestEvent(VlmIntent.ArGroundSelected(id,revision,ground,plane,point,epoch)) }).also { arOwner[0] = it }
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
