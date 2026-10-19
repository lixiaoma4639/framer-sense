package com.framer.sense.feature.camera.vlm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalCameraIconAngle = compositionLocalOf { 0f }
internal val CameraIconDegrees = SemanticsPropertyKey<Float>("CameraIconDegrees")

/** 页面保持进入时的方向，设备方向只驱动控件内容的四档转向；退出时恢复宿主原有方向策略。 */
@Composable
internal fun CameraIconRotationScope(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageRotation = remember { view.display?.rotation ?: Surface.ROTATION_0 }
    val activity = remember(context) { context.cameraHostActivity() }
    var angle by remember { mutableFloatStateOf(0f) }

    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        // LOCKED 保持当前显示方向，不指定 portrait/landscape，也不跟随传感器切换页面。
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose { if (previous != null) activity?.requestedOrientation = previous }
    }
    DisposableEffect(context, lifecycle, pageRotation) {
        val tracker = CameraIconAngle(pageRotation)
        val listener = object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) { angle = tracker.update(orientation) }
        }
        fun updateListening() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && listener.canDetectOrientation()) listener.enable()
            else listener.disable()
        }
        val observer = LifecycleEventObserver { _, _ -> updateListening() }
        lifecycle.addObserver(observer)
        updateListening()
        onDispose { lifecycle.removeObserver(observer); listener.disable() }
    }
    CompositionLocalProvider(LocalCameraIconAngle provides angle, content = content)
}

/** 仅旋转图案或倍率／比例标签的绘制层，父按钮的位置、大小和命中范围保持不变。 */
@Composable
internal fun Modifier.cameraIconRotation(): Modifier {
    val target = LocalCameraIconAngle.current
    val angle = remember { Animatable(target) }
    LaunchedEffect(target) {
        val visible = angle.value
        angle.animateTo(visible + shortestIconRotation(visible, target),
            tween(durationMillis = 300, easing = FastOutSlowInEasing))
    }
    return graphicsLayer { rotationZ = angle.value }
        .semantics { this[CameraIconDegrees] = angle.value }
}

private tailrec fun Context.cameraHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.cameraHostActivity()
    else -> null
}
