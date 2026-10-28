package com.framer.sense.feature.camera.vlm.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import com.framer.sense.feature.camera.vlm.BuildConfig
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ar.ArSource
import com.framer.sense.feature.camera.vlm.ar.ArTrackingState
import coil3.compose.AsyncImage
import java.io.File

internal val CameraHeaderHeight = 48.dp
internal val CameraHighlightColor = Color(0xFFFFD66A)
private val CameraAiActionForeground = Color(0xFF202126)
private val CameraCompositionCaptionBackground = Color.Black.copy(alpha = .55f)
private val CameraCompositionCaptionForeground = Color.White

@Composable
internal fun CameraTopBar(state: VlmUiState, onEvent: (VlmIntent) -> Unit, modifier: Modifier = Modifier, onExit: (() -> Unit)? = null) {
    var aspectMenu by remember { mutableStateOf(false) }
    val historyDescription = stringResource(R.string.vlm_history_open)
    val ratioLabels = CaptureAspectRatio.entries.map { aspectLabel(it) }
    BoxWithConstraints(modifier.fillMaxWidth().height(CameraHeaderHeight).background(Color.Black.copy(alpha = .6f))
        .testTag("vlm_camera_top_bar")) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onEvent(VlmIntent.HistoryDrawerVisible(true)) },
                modifier = Modifier.size(48.dp).testTag("vlm_history_open").semantics { contentDescription = historyDescription }) {
                Canvas(Modifier.size(24.dp).cameraIconRotation()) {
                    drawLine(Color.White, Offset(2.dp.toPx(), 8.dp.toPx()), Offset(22.dp.toPx(), 8.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, Offset(2.dp.toPx(), 16.dp.toPx()), Offset(15.dp.toPx(), 16.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            Box {
                TextButton(onClick = { aspectMenu = true }, enabled = state.framingUnlocked && state.historyAspectRatio == null,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp).testTag("vlm_aspect_ratio")) {
                    Text(aspectLabel(state.captureAspectRatio, state.historyLandscape), color = Color.White,
                        modifier = Modifier.testTag("vlm_aspect_ratio_label").cameraIconRotation())
                }
                if (aspectMenu) CameraAdaptiveDialog(onDismissRequest = { aspectMenu = false }) {
                    Surface(shape = MaterialTheme.shapes.large) {
                        Column {
                            CaptureAspectRatio.entries.forEachIndexed { index, ratio ->
                                DropdownMenuItem(text = { Text(ratioLabels[index]) }, enabled = state.framingUnlocked,
                                    modifier = Modifier.testTag("vlm_ratio_${ratio.name}"), onClick = {
                                        aspectMenu = false; onEvent(VlmIntent.AspectRatioSelected(ratio))
                                    })
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            if (onExit != null) IconButton(onClick = {
                if (state.instructionEditorVisible) onEvent(VlmIntent.InstructionEditorVisible(false)) else onExit()
            }, enabled = !state.saving, modifier = Modifier.size(48.dp).testTag("vlm_camera_exit")) {
                Icon(Icons.Filled.Close, stringResource(R.string.vlm_exit_camera), tint = Color.White,
                    modifier = Modifier.size(24.dp).testTag("vlm_camera_exit_icon"))
            }
        }
        // 48dp 点击区内居中的 24dp 图标，与输入面板的 12dp 外边距对齐。
        Button(onClick = { aspectMenu = false; onEvent(VlmIntent.InstructionEditorVisible(!state.instructionEditorVisible)) },
            enabled = state.canEditInstruction, contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CameraHighlightColor, contentColor = CameraAiActionForeground),
            modifier = Modifier.align(Alignment.Center).height(36.dp)
                .widthIn(max = (maxWidth - 192.dp).coerceAtLeast(48.dp)).testTag("vlm_ai_instruction_toggle")
                .semantics { selected = state.instructionEditorVisible }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null,
                modifier = Modifier.size(18.dp).testTag("vlm_ai_instruction_icon").cameraIconRotation())
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.vlm_ai_instruction_entry), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 设置直接覆盖抽屉，保留历史展开状态与原有图标、门控。 */
@Composable
internal fun CameraSettingsButtons(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onEvent(VlmIntent.SettingsVisible(true)) },
            enabled = !state.historyLoading && !state.historySaving && state.stage !in listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING),
            modifier = Modifier.size(48.dp).testTag("vlm_model_settings")) {
            Box(Modifier.testTag("vlm_model_settings_icon").cameraIconRotation()) {
                Icon(Icons.Default.Psychology, stringResource(R.string.vlm_model_settings_title), tint = Color.White)
                if (state.cameraMode == CameraMode.AR) {
                    Icon(Icons.Default.ViewInAr, stringResource(R.string.vlm_ar_mode_label),
                        tint = if (state.ar.tracking == ArTrackingState.PLACED) Color(0xFFB3E5AC) else Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp)
                            .size(12.dp).testTag("vlm_ar_indicator"))
                }
            }
        }
        IconButton(onClick = { onEvent(VlmIntent.PhotoSettingsVisible(true)) }, enabled = !state.operationBusy,
            modifier = Modifier.size(48.dp).testTag("vlm_photo_settings")) {
            Icon(Icons.Default.Settings, stringResource(R.string.vlm_photo_settings_title), tint = Color.White,
                modifier = Modifier.size(24.dp).testTag("vlm_photo_settings_icon").cameraIconRotation())
        }
    }
}

@Composable
private fun aspectLabel(ratio: CaptureAspectRatio, landscapeOverride: Boolean? = null): String {
    val landscape = landscapeOverride ?: (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
    return stringResource(if (ratio == CaptureAspectRatio.THREE_FOUR) {
        if (landscape) R.string.vlm_ratio_4_3 else R.string.vlm_ratio_3_4
    } else if (landscape) R.string.vlm_ratio_16_9 else R.string.vlm_ratio_9_16)
}

/** 所有操作叠加在固定视口上；三卡片、进度或弹窗不挤压相机尺寸。 */
@Composable
internal fun CameraBottomBar(state: VlmUiState, onEvent: (VlmIntent) -> Unit, capture: () -> Unit,
    permission: Boolean, modifier: Modifier = Modifier) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
        .padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Controls(state, onEvent, {}, Modifier.weight(1f, fill = false).fillMaxWidth())
        if (state.stage == VlmStage.LIVE || state.stage == VlmStage.SCANNING) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                state.camera.zoomOptions.forEach { ratio ->
                    val selected = kotlin.math.abs(state.actualZoom - ratio) < .04f
                    TextButton(onClick = { onEvent(VlmIntent.ZoomChanged(ratio)) }, enabled = state.canZoom,
                        modifier = Modifier.testTag("vlm_zoom_$ratio").semantics { this.selected = selected }) {
                        Text(stringResource(R.string.vlm_zoom_value, ratio),
                            color = if (selected) CameraHighlightColor else Color.White,
                            fontSize = if (selected) 16.sp else 14.sp,
                            modifier = Modifier.testTag("vlm_zoom_label_$ratio").cameraIconRotation())
                    }
                }
            }
        }
        Box(Modifier.widthIn(max = CameraWorkflowMaxWidth).fillMaxWidth()
            .height(if (landscape) 52.dp else 68.dp)) {
            CameraGalleryEntry(state.canOpenGallery, { onEvent(VlmIntent.OpenGallery) },
                Modifier.align(Alignment.CenterStart))
            Box(Modifier.align(Alignment.Center)) {
                CameraShutter(state.canCapture && permission, capture, if (landscape) 52 else 68)
            }
        }
    }
}

@Composable
internal fun CameraShutter(enabled: Boolean, onClick: () -> Unit, size: Int = 68) {
    Box(Modifier.size(size.dp).border(3.dp, Color.White.copy(alpha = if (enabled) 1f else .35f), CircleShape)
        .padding(6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else .35f))
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .testTag("vlm_shutter").then(Modifier.semanticsShutter()), contentAlignment = Alignment.Center) { }
}

@Composable
private fun Modifier.semanticsShutter(): Modifier {
    val label = stringResource(R.string.vlm_take_photo)
    return this.then(Modifier.semantics { contentDescription = label })
}

/** 列表和详情分别控制；收起详情仍保留选图及应用入口。 */
@Composable
internal fun Controls(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importImage: () -> Unit, modifier: Modifier) {
    // 放在显示条件之外，收起列表时保留位置，也不重新触发选中项定位动画。
    val compositionScroll = rememberCompositionListState(state)
    val generatedScroll = rememberLazyListState()
    val generatedMode = state.usesGeneratedReference
    val hasChoices = if (generatedMode) state.generated.candidates.isNotEmpty() else state.hasCompositionChoices
    val busy = state.operationBusy || state.instructionEditorVisible
    val manualBackgroundSelection = !hasChoices && state.viewCandidates.isEmpty() && state.scanSnapshot != null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.error?.let { Text(it, color = Color(0xFFFFAAAA), maxLines = 2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vlm_error")) }
        state.notice?.let { Text(it, color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        if (generatedMode && state.generated.running && state.generated.hasReady)
            Text(stringResource(R.string.vlm_generated_pending), color = Color.White,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("vlm_generated_pending"))
        if (state.stage == VlmStage.SCANNING)
            Text(stringResource(R.string.vlm_scan_progress, state.scanProgress.frames, state.scanProgress.elapsedSeconds), color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (generatedMode && state.compositionDetailsVisible && state.stage in listOf(
                VlmStage.READY, VlmStage.LIVE, VlmStage.FROZEN, VlmStage.SCAN_REVIEW) &&
            (hasChoices || state.rankedBackgroundFrames.isNotEmpty())) {
            if (state.generated.selected?.ready == true) {
                val panelModifier = if (state.compositionDetailsExpanded) Modifier.weight(1f, fill = false).fillMaxWidth()
                    else Modifier.fillMaxWidth()
                CompositionDetailsPanel(state, onEvent, panelModifier)
            }
            GeneratedReferenceCards(state, onEvent, generatedScroll)
        } else if (!generatedMode && state.stage in listOf(VlmStage.READY, VlmStage.LIVE, VlmStage.FROZEN, VlmStage.SCAN_REVIEW) &&
            state.compositionDetailsVisible && state.compositionChoices.isNotEmpty()) {
            if (state.result != null) {
                val panelModifier = if (state.compositionDetailsExpanded) Modifier.weight(1f, fill = false).fillMaxWidth()
                    else Modifier.fillMaxWidth()
                CompositionDetailsPanel(state, onEvent, panelModifier)
            }
            CompositionCards(state, onEvent, compositionScroll)
        }
        if (state.stage == VlmStage.LIVE && state.referencePlan != null) {
            if (state.referencePlan.viewBinding == null || state.background.status != BackgroundStatus.SEARCHING) {
                if (state.faceVisible) state.facePreview?.let {
                    Image(it.asImageBitmap(), stringResource(R.string.vlm_photo_face_description), Modifier.size(72.dp).testTag("vlm_face_preview"))
                }
            }
        }
        CameraActionRow {
            if (!state.isFirstCaptureStep || state.stage in listOf(VlmStage.SCANNING, VlmStage.FREEZING))
                CameraRestartAction(state, onEvent)
            // 手动选图只冻结原图预览，仍可改用整个扫描集继续自动选景。
            if (state.stage == VlmStage.SCAN_REVIEW || state.stage == VlmStage.FROZEN && manualBackgroundSelection) {
                CameraAction(R.string.vlm_scan_analyze, "vlm_scan_analyze", state.scanSnapshot?.frames?.isNotEmpty() == true && !busy) {
                    onEvent(VlmIntent.AnalyzeScan)
                }
            }
            when (state.stage) {
                VlmStage.LIVE -> {
                    if (state.isFirstCaptureStep) {
                        CameraAction(R.string.vlm_scan_start, "vlm_scan_start", state.canRestartScan) { onEvent(VlmIntent.StartScan) }
                        CameraAction(R.string.vlm_scan_single, "vlm_start", state.canStartSingleComposition) { onEvent(VlmIntent.StartComposition) }
                    }
                    if (hasChoices) CompositionSelectionToggle(state, onEvent)
                }
                VlmStage.SCANNING -> {
                    CameraAction(R.string.vlm_scan_prefer, "vlm_scan_prefer", !state.scanProgress.paused && state.scanProgress.preferred < ScanLimits.MAX_PREFERRED) { onEvent(VlmIntent.PreferScanView) }
                    CameraAction(R.string.vlm_scan_stop, "vlm_scan_stop", state.scanSession != null) { onEvent(VlmIntent.StopScan) }
                }
                VlmStage.SCAN_REVIEW -> {
                    CompositionSelectionToggle(state, onEvent, manual = true)
                }
                VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING -> Unit
                else -> {
                    CameraGenerationAction(state, onEvent)
                    if ((hasChoices || state.compositionChoices.isNotEmpty()) && !busy) {
                        CompositionSelectionToggle(state, onEvent, manual = manualBackgroundSelection)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraRestartAction(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    CameraAction(R.string.vlm_flow_restart, "vlm_restart_flow", state.canRestartFlow) { onEvent(VlmIntent.RestartFlow) }
}

/** 卡片只切换预览；开始或追加一批构图统一在按钮处确认。 */
@Composable
private fun CameraGenerationAction(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    val selectedChoice = state.compositionChoices.firstOrNull { choice -> when (choice) {
        is CompositionChoice.Plan -> choice.group.scene.id == state.scene?.id
        is CompositionChoice.PendingBackground -> choice.frame.scene.id == state.scene?.id
    } }
    val pendingSelected = if (state.usesGeneratedReference) selectedChoice is CompositionChoice.PendingBackground &&
        !state.generated.candidates.any { it.scene.id == state.scene?.id && it.ready }
        else state.result == null && selectedChoice is CompositionChoice.PendingBackground && selectedChoice.frame.id == state.selectedFrameId
    val canGenerate = state.canGenerate && (!state.usesGeneratedReference || !state.generated.running)
    val hasGenerated = if (state.usesGeneratedReference) state.generated.candidates.any { it.scene.id == state.scene?.id && it.ready }
        else state.result != null
    var confirming by remember(state.scene?.id, state.compositionBatchId, state.selectedId) { mutableStateOf(false) }
    LaunchedEffect(canGenerate) {
        if (!canGenerate) confirming = false
    }
    CameraAction(if (!hasGenerated && state.stage != VlmStage.GENERATING) R.string.vlm_flow_generate else R.string.vlm_flow_regenerate,
        "vlm_generate", canGenerate,
        contentColor = if (pendingSelected) CameraHighlightColor else Color.White) { confirming = true }
    if (confirming && canGenerate) {
        val rank = selectedChoice?.rank ?: 1
        CameraAdaptiveAlertDialog(onDismissRequest = { confirming = false },
            modifier = Modifier.testTag("vlm_background_generate_dialog"),
            title = { Text(stringResource(R.string.vlm_background_generate_title)) },
            text = { Text(stringResource(if (state.usesGeneratedReference) R.string.vlm_generated_generate_message
                else R.string.vlm_background_generate_message, rank)) },
            dismissButton = {
                TextButton(onClick = { confirming = false }, modifier = Modifier.testTag("vlm_background_generate_cancel")) {
                    Text(stringResource(R.string.vlm_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onEvent(VlmIntent.Generate)
                }, modifier = Modifier.testTag("vlm_background_generate_confirm")) {
                    Text(stringResource(R.string.vlm_background_generate_confirm))
                }
            })
    }
}

/** 任意数量的动作整体居中，空间不足时仍可横向滚动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraActionRow(content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(Modifier.widthIn(max = CameraWorkflowMaxWidth).fillMaxWidth().heightIn(min = 48.dp)
            .horizontalScroll(rememberScrollState()).testTag("vlm_camera_actions"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), content = content)
    }
}

@Composable
private fun CompositionSelectionToggle(state: VlmUiState, onEvent: (VlmIntent) -> Unit, manual: Boolean = false) {
    val tag = if (manual) "vlm_scan_manual_open" else "vlm_details"
    TextButton(onClick = { onEvent(VlmIntent.CompositionDetailsVisible(!state.compositionDetailsVisible)) },
        enabled = !state.operationBusy && !state.instructionEditorVisible,
        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp), shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .4f))) {
        Text(stringResource(if (manual) R.string.vlm_scan_manual_open else R.string.vlm_composition_details), maxLines = 1,
            modifier = Modifier.testTag("${tag}_label"))
        Icon(if (state.compositionDetailsVisible) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            stringResource(if (manual) {
                if (state.compositionDetailsVisible) R.string.vlm_scan_manual_collapse else R.string.vlm_scan_manual_expand
            } else if (state.compositionDetailsVisible) R.string.vlm_composition_collapse else R.string.vlm_composition_expand),
            modifier = Modifier.size(18.dp).testTag("${tag}_icon"))
    }
}

@Composable
internal fun CameraAction(label: Int, tag: String, enabled: Boolean, contentColor: Color = Color.White, click: () -> Unit) {
    TextButton(onClick = click, enabled = enabled,
        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).testTag(tag),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp), shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor, disabledContentColor = contentColor.copy(alpha = .4f))) {
        Text(stringResource(label), maxLines = 1, modifier = Modifier.testTag("${tag}_label"))
    }
}

@Composable
private fun rememberCompositionListState(state: VlmUiState): LazyListState {
    val choices = state.compositionChoices
    val selectedIndex = choices.indexOfFirst { choice -> when (choice) {
        is CompositionChoice.Plan -> choice.group.scene.id == state.scene?.id && choice.batch.id == state.compositionBatchId &&
            choice.plan.id == state.selectedId
        is CompositionChoice.PendingBackground -> state.result == null && choice.frame.id == state.selectedFrameId
    } }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
    LaunchedEffect(state.scene?.id, state.compositionBatchId, state.selectedId, choices.size) {
        if (selectedIndex >= 0) scroll.animateScrollToItem(selectedIndex)
    }
    return scroll
}

@Composable
internal fun CompositionCards(state: VlmUiState, onEvent: (VlmIntent) -> Unit,
    scroll: LazyListState = rememberCompositionListState(state)) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val choices = state.compositionChoices
    LazyRow(Modifier.fillMaxWidth().testTag("vlm_composition_choices"), state = scroll,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(choices, key = { it.key }) { choice ->
            val imageHeight = if (landscape) 48.dp else 84.dp
            when (choice) {
                is CompositionChoice.Plan -> CompositionPlanCard(choice, state, onEvent, imageHeight)
                is CompositionChoice.PendingBackground -> PendingBackgroundCard(choice, !state.operationBusy,
                    state.result == null && choice.frame.id == state.selectedFrameId, imageHeight) {
                    onEvent(VlmIntent.PreviewScanView(choice.frame.id))
                }
            }
        }
    }
}

@Composable
private fun CompositionPlanCard(choice: CompositionChoice.Plan, state: VlmUiState,
    onEvent: (VlmIntent) -> Unit, imageHeight: Dp) {
    val group = choice.group
    val plan = choice.plan
    val active = group.scene.id == state.scene?.id && choice.batch.id == state.compositionBatchId
    val multipleBatches = group.allBatches.size > 1
    val cardId = if (active) plan.id else if (multipleBatches) "${group.scene.id}_${choice.batch.id}_${plan.id}"
        else "${group.scene.id}_${plan.id}"
    val preview = choice.batch.previews[plan.id]?.composite
    val ratio = preview?.let { it.width.toFloat() / it.height }
        ?: (group.scene.width.toFloat() / group.scene.height)
    val photoDegrees = group.scene.orientation.previewDegrees
    val displayedRatio = if (photoDegrees % 180 == 0) ratio else 1f / ratio
    Card(onClick = { onEvent(VlmIntent.Select(plan.id, if (active) null else group.scene.id,
        if (!active && multipleBatches) choice.batch.id else null)) }, enabled = !state.operationBusy,
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202126)),
        modifier = Modifier.border(if (active && plan.id == state.selectedId) 2.dp else 0.dp, Color.White, RectangleShape)
            .semantics { selected = active && plan.id == state.selectedId }
            .testTag("vlm_composition_$cardId")) {
        Box(Modifier.padding(2.dp).size(imageHeight * displayedRatio, imageHeight)) {
            CameraRotatingViewport(Modifier.fillMaxSize().testTag("vlm_composition_content_$cardId"),
                preservePhotoDirection = true) {
                preview?.let {
                    CameraPhotoViewport(photoDegrees, Modifier.fillMaxSize()) {
                        Image(it.asImageBitmap(), stringResource(R.string.vlm_selected_composition),
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
                CompositionCardCaption(stringResource(R.string.vlm_background_plan_number, choice.rank, choice.index + 1),
                    "vlm_composition_label_$cardId")
            }
        }
    }
}

@Composable
internal fun PendingBackgroundCard(choice: CompositionChoice.PendingBackground, enabled: Boolean, selected: Boolean,
    imageHeight: Dp, onClick: () -> Unit) {
    val frame = choice.frame
    val photoDegrees = frame.scene.orientation.previewDegrees
    val ratio = frame.scene.width.toFloat() / frame.scene.height
    val displayedRatio = if (photoDegrees % 180 == 0) ratio else 1f / ratio
    Card(onClick = onClick, enabled = enabled, shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202126)),
        modifier = Modifier.border(if (selected) 2.dp else 0.dp, Color.White, RectangleShape)
            .semantics { this.selected = selected }
            .testTag("vlm_background_candidate_${frame.id}")) {
        Box(Modifier.padding(2.dp).size(imageHeight * displayedRatio, imageHeight)) {
            CameraRotatingViewport(Modifier.fillMaxSize(), preservePhotoDirection = true) {
                CameraPhotoViewport(photoDegrees, Modifier.fillMaxSize()) {
                    AsyncImage(File(frame.scene.modelImagePath), stringResource(R.string.vlm_scan_frame),
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                CompositionCardCaption(stringResource(R.string.vlm_background_candidate_status, choice.rank),
                    "vlm_background_label_${frame.id}")
            }
        }
    }
}

@Composable
internal fun BoxScope.CompositionCardCaption(text: String, tag: String, maxLines: Int = 1) {
    Text(text, color = CameraCompositionCaptionForeground, style = MaterialTheme.typography.labelSmall,
        maxLines = maxLines, softWrap = false, textAlign = TextAlign.Start,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CameraCompositionCaptionBackground)
            .padding(2.dp).testTag(tag))
}

@Composable
internal fun PhotoSettingsDialog(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importImage: () -> Unit) {
    CameraDialog(R.string.vlm_photo_settings_title, "vlm_photo_settings_dialog", { onEvent(VlmIntent.PhotoSettingsVisible(false)) }) {
        AvatarSelector(state, onEvent, state.operationBusy)
        AvatarEthnicitySelector(state.selectedEthnicity, !state.operationBusy, "vlm_preferred_ethnicity",
            showDraftHint = !state.usesGeneratedReference) { onEvent(VlmIntent.PreferredEthnicity(it)) }
        if (!state.usesGeneratedReference) {
        SettingsSwitch(R.string.vlm_photo_show_face, state.faceVisible, !state.operationBusy, "vlm_face_setting") { onEvent(VlmIntent.FaceVisible(it)) }
        SettingsSwitch(R.string.vlm_ar_mode_label, state.cameraMode == CameraMode.AR, state.canChangeCameraMode, "vlm_ar_setting") { onEvent(VlmIntent.CameraModeSelected(if (it) CameraMode.AR else CameraMode.NORMAL)) }
        Text(stringResource(if (state.cameraMode == CameraMode.AR) R.string.vlm_ar_zoom_locked else R.string.vlm_normal_camera_hint), style = MaterialTheme.typography.bodySmall)
        if (state.cameraMode == CameraMode.AR) {
            Text(stringResource(when {
                state.ar.source == ArSource.CHECKING -> R.string.vlm_ar_checking
                state.ar.tracking == ArTrackingState.PLACED -> R.string.vlm_ar_placed
                state.ar.tracking == ArTrackingState.NEEDS_GROUND -> R.string.vlm_ar_choose_ground
                state.ar.tracking in listOf(ArTrackingState.LOST, ArTrackingState.PAUSED) -> R.string.vlm_ar_tracking_lost
                else -> R.string.vlm_ar_tracking
            }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (state.ar.depth) R.string.vlm_ar_depth_on else R.string.vlm_ar_depth_off), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onEvent(VlmIntent.PhotoSettingsVisible(false)); onEvent(VlmIntent.OpenPhotoSample) },
            enabled = !state.operationBusy, modifier = Modifier.testTag("vlm_sample_open")) { Text(stringResource(R.string.vlm_photo_sample_open)) }
        }
        if (BuildConfig.DEBUG) TextButton(onClick = { onEvent(VlmIntent.PhotoSettingsVisible(false)); importImage() },
            enabled = !state.operationBusy, modifier = Modifier.testTag("vlm_import_image")) { Text(stringResource(R.string.vlm_debug_image)) }
    }
}

/** 正文占满区域；紧凑操作区悬浮在其上方，滚动末尾留白避免挡住最后的控件。 */
@Composable
internal fun CompositionDetailsPanel(state: VlmUiState, onEvent: (VlmIntent) -> Unit, modifier: Modifier = Modifier) {
    val plan = state.result?.plans?.find { it.id == state.selectedId }
    val hasSelection = plan != null || state.usesGeneratedReference && state.generated.selected?.ready == true
    var actionsHeight by remember { mutableIntStateOf(0) }
    val footerSpace = maxOf(30.dp, with(LocalDensity.current) { actionsHeight.toDp() }) + 8.dp
    Box(modifier.testTag("vlm_composition_details_overlay")) {
        key(state.scene?.id, state.compositionBatchId, state.selectedId) {
            val scroll = rememberScrollState()
            if (state.compositionDetailsExpanded || !state.usesGeneratedReference && state.result == null) {
                Surface(Modifier.fillMaxSize().testTag("vlm_composition_details_panel"),
                    shape = MaterialTheme.shapes.medium,
                    color = CameraCompositionCaptionBackground,
                    contentColor = Color.White) {
                    CameraRotatingViewport(Modifier.fillMaxSize().testTag("vlm_composition_details_content"),
                        preservePhotoDirection = true) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)
                            .testTag("vlm_composition_details_scroll"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (state.usesGeneratedReference) GeneratedReferenceDetails(state)
                            else CompositionDetailsContent(state, onEvent)
                            if (hasSelection) Spacer(Modifier.height(footerSpace))
                        }
                    }
                }
            }
        }
        if (hasSelection) {
            CompositionActionButtons(state, onEvent, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                .zIndex(1f).onSizeChanged { actionsHeight = it.height })
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CompositionActionButtons(state: VlmUiState, onEvent: (VlmIntent) -> Unit, modifier: Modifier) {
    val description = stringResource(if (state.compositionDetailsExpanded) R.string.vlm_composition_info_hide else R.string.vlm_composition_info_show)
    val colors = ButtonDefaults.buttonColors(
        containerColor = CameraCompositionCaptionBackground,
        contentColor = CameraCompositionCaptionForeground)
    val labelStyle = MaterialTheme.typography.labelSmall.let {
        it.copy(fontSize = (it.fontSize.value + 2f).sp, lineHeight = (it.lineHeight.value + 2f).sp)
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(modifier.testTag("vlm_composition_actions"), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Button(onClick = { onEvent(VlmIntent.CompositionDetailsExpanded(!state.compositionDetailsExpanded)) },
                enabled = !state.operationBusy, shape = RectangleShape, colors = colors,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(30.dp).testTag("vlm_composition_info_toggle")
                    .semantics { selected = state.compositionDetailsExpanded; stateDescription = description }) {
                Text(stringResource(R.string.vlm_composition_info), style = labelStyle, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Icon(if (state.compositionDetailsExpanded) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null, modifier = Modifier.size(16.dp).testTag("vlm_composition_info_icon"))
            }
            Button(onClick = { onEvent(VlmIntent.Apply) }, enabled = !state.operationBusy &&
                (!state.usesGeneratedReference || state.generated.selected?.canApply == true),
                shape = RectangleShape, colors = colors, contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(30.dp).testTag("vlm_apply")) {
                Text(stringResource(R.string.vlm_apply_plan), style = labelStyle, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Icon(Icons.Outlined.CheckCircle, contentDescription = null,
                    modifier = Modifier.size(16.dp).testTag("vlm_apply_icon"))
            }
        }
    }
}

@Composable
private fun CompositionDetailsContent(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
        CompositionInstructionDescription(state.instruction)
        val plan = state.result?.plans?.find { it.id == state.selectedId }
        val background = plan?.viewBinding?.let { binding ->
            state.viewCandidates.firstOrNull { it.frameId == binding.frameId }
        }
        val sceneObservation = background?.let { listOf(it.subject, it.reason).filter(String::isNotBlank).joinToString("\n") }
        state.result?.let { CompositionDescriptions(it, state.selectedId, sceneObservation) }
        if (plan != null) {
            PhotoActionControls(state, plan, state.operationBusy, onEvent)
            Text(stringResource(R.string.vlm_avatar_size), style = MaterialTheme.typography.labelLarge)
            var height by remember(state.scene?.id, state.compositionBatchId, plan.id, plan.revision) { mutableFloatStateOf(plan.avatar.height.coerceIn(.08f, 4f)) }
            Slider(height, { height = it }, valueRange = .08f..4f,
                onValueChangeFinished = { onEvent(VlmIntent.Resize(height)) }, enabled = !state.operationBusy,
                modifier = Modifier.testTag("vlm_avatar_size_slider"))
            plan.uncertainties.filterNot { it == com.framer.sense.feature.camera.vlm.agent.CompositionIntentMapper.SCENE_FALLBACK_MARKER }
                .forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(state.instruction, { onEvent(VlmIntent.InstructionChanged(it)) }, enabled = !state.operationBusy,
                label = { Text(stringResource(R.string.vlm_director_revision_request)) },
                modifier = Modifier.fillMaxWidth().testTag("vlm_director_revision_input"), maxLines = 3)
            Button(onClick = { onEvent(VlmIntent.CompositionDetailsVisible(false)); onEvent(VlmIntent.Revise) }, enabled = !state.operationBusy && state.instruction.isNotBlank()) {
                Text(stringResource(R.string.vlm_director_revise))
            }
        }
        if (state.scanSnapshot != null) {
            if (state.comparedFrames > 0) Text(stringResource(R.string.vlm_scan_compared, state.comparedFrames))
            TextButton(onClick = { onEvent(VlmIntent.ManualScanSelectionVisible(!state.manualScanSelectionVisible)) }, enabled = !state.operationBusy) {
                Text(stringResource(R.string.vlm_scan_change_background))
            }
            if (state.manualScanSelectionVisible) ScanViews(state, state.operationBusy, onEvent)
        }
        (state.diagnostics ?: state.result)?.let { DebugDetails(it) }
}

@Composable
private fun SettingsSwitch(label: Int, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f)); Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun CameraDialog(title: Int, tag: String, close: () -> Unit, scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    CameraAdaptiveDialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.testTag(tag)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = close) { Icon(Icons.Default.Close, stringResource(R.string.vlm_close_dialog)) }
                }
                val scroll = rememberScrollState()
                Column(Modifier.weight(1f, fill = false).then(if (scrollContent) Modifier.verticalScroll(scroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}
