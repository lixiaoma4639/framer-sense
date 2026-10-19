package com.framer.sense.feature.camera.vlm.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTrackerAddPointsFix
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.framer.sense.feature.camera.vlm.R

// 嵌套弹窗仍使用设备方向；弹窗内部图标的局部角度会归零，避免重复旋转。
private val LocalCameraDialogAngle = compositionLocalOf<Float?> { null }
internal val CameraDialogDegrees = SemanticsPropertyKey<Float>("CameraDialogDegrees")

/**
 * Compose UI 1.10.3 的新速度算法使用 originalEventPosition（窗口坐标），没有转换到
 * 旋转后的内容坐标，导致拖动正确但松手惯性方向错误。弹窗存续时使用按局部位移累计的
 * 兼容算法；最后一个弹窗退出后恢复原值。计数处理嵌套弹窗及其任意销毁顺序。
 * 升级 Compose 后需核对 DefaultVelocityTracker.addPointerInputChangeWithFix 再移除此兼容。
 */
@OptIn(ExperimentalComposeUiApi::class)
private object CameraDialogVelocityCompatibility {
    private var users = 0
    private var previous = true

    fun acquire() {
        if (users++ == 0) {
            previous = VelocityTrackerAddPointsFix
            VelocityTrackerAddPointsFix = false
        }
    }

    fun release() {
        if (--users == 0) VelocityTrackerAddPointsFix = previous
    }
}

/** 仅转动弹窗自己的内容坐标，不修改 Activity 方向和背后的预览。 */
@Composable
internal fun CameraRotatingViewport(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    DisposableEffect(Unit) {
        CameraDialogVelocityCompatibility.acquire()
        onDispose { CameraDialogVelocityCompatibility.release() }
    }
    val target = LocalCameraDialogAngle.current ?: LocalCameraIconAngle.current
    val angle = remember { Animatable(target) }
    LaunchedEffect(target) {
        val current = angle.value
        angle.animateTo(current + shortestIconRotation(current, target), tween(300, easing = FastOutSlowInEasing))
    }
    val original = LocalConfiguration.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val sideways = target.toInt() % 180 != 0
        val logicalWidth = if (sideways) maxHeight else maxWidth
        val logicalHeight = if (sideways) maxWidth else maxHeight
        val configuration = remember(original, logicalWidth, logicalHeight) {
            Configuration(original).apply {
                screenWidthDp = logicalWidth.value.toInt()
                screenHeightDp = logicalHeight.value.toInt()
                orientation = if (logicalWidth > logicalHeight) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
        }
        Layout(modifier = Modifier.fillMaxSize().semantics { this[CameraDialogDegrees] = angle.value }, content = {
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalCameraDialogAngle provides target, LocalCameraIconAngle provides 0f) {
                Box(Modifier.fillMaxSize(), content = content)
            }
        }) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val child = measurables.single().measure(Constraints.fixed(
                if (sideways) height else width, if (sideways) width else height))
            layout(width, height) {
                child.placeWithLayer((width - child.width) / 2, (height - child.height) / 2) {
                    rotationZ = angle.value
                    transformOrigin = TransformOrigin.Center
                }
            }
        }
    }
}

/** 普通对话框和全屏模型库共用原生 Dialog，返回与窗口生命周期保持原机制。 */
@Composable
internal fun CameraAdaptiveDialog(
    onDismissRequest: () -> Unit,
    fullScreen: Boolean = false,
    content: @Composable () -> Unit
) {
    val closeLabel = stringResource(R.string.vlm_close_dialog)
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val insets = WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()
        CameraRotatingViewport(Modifier.fillMaxSize().padding(insets)) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = closeLabel, onClick = onDismissRequest))
            Box(Modifier.align(Alignment.Center).then(if (fullScreen) Modifier.fillMaxSize()
                else Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth())
                // 空白区域参与命中，避免点到弹窗内留白时关闭下面的对话框。
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
                content()
            }
        }
    }
}

/** 与现有 AlertDialog 保持相同槽位，正文有界滚动、操作按钮始终留在可见范围。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CameraAdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    CameraAdaptiveDialog(onDismissRequest) {
        Surface(modifier.fillMaxWidth(), shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (title != null) ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                if (text != null) Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/** 使用同一窗口内的 BottomSheetScaffold，保证筛选面板连同拖动方向一起转正。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraAdaptiveBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val sheet = remember(density) {
        SheetState(skipPartiallyExpanded = true, positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 125.dp.toPx() } }, initialValue = SheetValue.Expanded)
    }
    LaunchedEffect(sheet.currentValue) { if (sheet.currentValue == SheetValue.Hidden) onDismissRequest() }
    CameraAdaptiveDialog(onDismissRequest, fullScreen = true) {
        BottomSheetScaffold(sheetContent = content, modifier = modifier,
            scaffoldState = rememberBottomSheetScaffoldState(sheet), sheetPeekHeight = 0.dp,
            containerColor = Color.Transparent, sheetMaxWidth = 640.dp) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null, onClick = onDismissRequest))
        }
    }
}
