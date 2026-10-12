package com.framer.sense.feature.camera.vlm.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.window.Dialog
import com.framer.sense.feature.camera.vlm.BuildConfig
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ar.ArSource
import com.framer.sense.feature.camera.vlm.ar.ArTrackingState

@Composable
internal fun CameraTopBar(state: VlmUiState, onEvent: (VlmIntent) -> Unit, modifier: Modifier = Modifier, onExit: (() -> Unit)? = null) {
    var aspectMenu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(48.dp).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onExit != null) IconButton(onClick = onExit, enabled = !state.saving,
            modifier = Modifier.testTag("vlm_camera_exit")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.vlm_exit_camera), tint = Color.White)
        }
        Box {
            TextButton(onClick = { aspectMenu = true }, enabled = state.framingUnlocked,
                modifier = Modifier.testTag("vlm_aspect_ratio")) {
                Text(aspectLabel(state.photoPreferences.aspectRatio), color = Color.White)
            }
            DropdownMenu(expanded = aspectMenu, onDismissRequest = { aspectMenu = false }) {
                CaptureAspectRatio.entries.forEach { ratio ->
                    DropdownMenuItem(text = { Text(aspectLabel(ratio)) }, enabled = state.framingUnlocked,
                        modifier = Modifier.testTag("vlm_ratio_${ratio.name}"), onClick = {
                            aspectMenu = false; onEvent(VlmIntent.AspectRatioSelected(ratio))
                        })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.cameraMode == CameraMode.AR) {
            Icon(Icons.Default.ViewInAr, stringResource(R.string.vlm_ar_mode_label),
                tint = if (state.ar.tracking == ArTrackingState.PLACED) Color(0xFFB3E5AC) else Color.White,
                modifier = Modifier.size(24.dp).testTag("vlm_ar_indicator"))
        }
        IconButton(onClick = { onEvent(VlmIntent.SettingsVisible(true)) },
            enabled = state.stage !in listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING),
            modifier = Modifier.testTag("vlm_model_settings")) {
            Icon(Icons.Default.Psychology, stringResource(R.string.vlm_model_settings_title), tint = Color.White)
        }
        IconButton(onClick = { onEvent(VlmIntent.PhotoSettingsVisible(true)) }, enabled = !state.operationBusy,
            modifier = Modifier.testTag("vlm_photo_settings")) {
            Icon(Icons.Default.Settings, stringResource(R.string.vlm_photo_settings_title), tint = Color.White)
        }
    }
}

@Composable
private fun aspectLabel(ratio: CaptureAspectRatio): String {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
                            color = if (selected) Color(0xFFFFD66A) else Color.White,
                            fontSize = if (selected) 16.sp else 14.sp)
                    }
                }
            }
        }
        CameraShutter(state.canCapture && permission, capture, if (landscape) 52 else 68)
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

/** 构图详情与卡片共用展开状态；详情可滚动，底部操作始终可访问。 */
@Composable
internal fun Controls(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importImage: () -> Unit, modifier: Modifier) {
    val busy = state.operationBusy
    val interruptible = state.stage in listOf(VlmStage.SCANNING, VlmStage.SCAN_PROCESSING,
        VlmStage.VIEW_SELECTING, VlmStage.FREEZING, VlmStage.GENERATING, VlmStage.MODIFYING)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.error?.let { Text(it, color = Color(0xFFFFAAAA), maxLines = 2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vlm_error")) }
        state.notice?.let { Text(it, color = Color.White, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        if (state.stage == VlmStage.SCANNING)
            Text(stringResource(R.string.vlm_scan_progress, state.scanProgress.frames, state.scanProgress.elapsedSeconds), color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (state.stage in listOf(VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Text(stringResource(if (state.stage == VlmStage.SCAN_PROCESSING) R.string.vlm_scan_finishing else R.string.vlm_scan_selecting),
                    color = Color.White, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.stage in listOf(VlmStage.READY, VlmStage.LIVE) && state.result != null && state.compositionDetailsVisible) {
            Surface(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("vlm_composition_details_panel"),
                shape = MaterialTheme.shapes.medium) {
                key(state.selectedId) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompositionDetailsContent(state, onEvent)
                    }
                }
            }
            CompositionCards(state, onEvent)
        }
        if (state.stage == VlmStage.LIVE && state.referencePlan != null) {
            if (state.referencePlan.viewBinding != null) {
                Text(stringResource(when (state.background.status) {
                    BackgroundStatus.SEARCHING -> R.string.vlm_background_searching
                    BackgroundStatus.ALIGNED_AUTO -> R.string.vlm_background_auto
                    BackgroundStatus.ALIGNED_MANUAL -> R.string.vlm_background_manual
                }), color = Color.White, maxLines = 1, modifier = Modifier.testTag("vlm_background_status"))
                if (state.background.status == BackgroundStatus.SEARCHING) {
                    val heading = state.background.headingError
                    val pitch = state.background.pitchError
                    if (heading != null && kotlin.math.abs(heading) > 5f) Text(stringResource(if (heading > 0) R.string.vlm_background_right else R.string.vlm_background_left), color = Color.White)
                    else if (pitch != null && kotlin.math.abs(pitch) > 5f) Text(stringResource(if (pitch > 0) R.string.vlm_background_up else R.string.vlm_background_down), color = Color.White)
                    CameraAction(R.string.vlm_background_confirm, "vlm_background_confirm", true) { onEvent(VlmIntent.ConfirmBackground) }
                }
            }
            if (state.referencePlan.viewBinding == null || state.background.status != BackgroundStatus.SEARCHING) {
                Text(compositionGuidanceText(state.referencePlan), color = Color.White, maxLines = 2,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vlm_reference_guidance"))
                if (state.faceVisible) state.facePreview?.let {
                    Image(it.asImageBitmap(), stringResource(R.string.vlm_photo_face_description), Modifier.size(72.dp).testTag("vlm_face_preview"))
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            when (state.stage) {
                VlmStage.LIVE -> {
                    CameraAction(if (state.referencePlan != null) R.string.vlm_scan_restart_active else R.string.vlm_scan_start,
                        "vlm_scan_start", state.cameraReady && !busy) { onEvent(VlmIntent.StartScan) }
                    CameraAction(R.string.vlm_scan_single, "vlm_start", state.cameraReady && !busy) { onEvent(VlmIntent.StartComposition) }
                    if (state.result != null) CompositionSelectionToggle(state, onEvent)
                    if (state.referencePlan != null) CameraAction(R.string.vlm_back_camera, "vlm_resume", !busy) { onEvent(VlmIntent.ResumeCamera) }
                }
                VlmStage.SCANNING -> {
                    CameraAction(R.string.vlm_scan_restart_active, "vlm_scan_restart_active", state.canRestartScan) { onEvent(VlmIntent.StartScan) }
                    CameraAction(R.string.vlm_scan_prefer, "vlm_scan_prefer", !state.scanProgress.paused && state.scanProgress.preferred < ScanLimits.MAX_PREFERRED) { onEvent(VlmIntent.PreferScanView) }
                    CameraAction(R.string.vlm_scan_stop, "vlm_scan_stop", state.scanSession != null) { onEvent(VlmIntent.StopScan) }
                    CameraAction(R.string.vlm_scan_cancel, "vlm_scan_cancel", true) { onEvent(VlmIntent.CancelScan) }
                }
                VlmStage.SCAN_REVIEW -> {
                    CameraAction(R.string.vlm_scan_analyze, "vlm_scan_analyze", state.scanSnapshot?.frames?.isNotEmpty() == true && !busy) { onEvent(VlmIntent.AnalyzeScan) }
                    CameraAction(R.string.vlm_scan_manual_open, "vlm_scan_manual_open", !busy) { onEvent(VlmIntent.CompositionDetailsVisible(true)) }
                }
                VlmStage.SCAN_PROCESSING, VlmStage.VIEW_SELECTING -> Unit
                else -> {
                    if (!interruptible) CameraAction(R.string.vlm_scan_restart_active, "vlm_scan_restart_active", state.canRestartScan) { onEvent(VlmIntent.StartScan) }
                    CameraAction(if (state.result == null && state.stage != VlmStage.GENERATING) R.string.vlm_flow_generate else R.string.vlm_flow_regenerate,
                        "vlm_generate", state.canGenerate) { onEvent(VlmIntent.Generate) }
                    if (state.result != null && !busy) {
                        CompositionSelectionToggle(state, onEvent)
                    }
                    if (!busy) CameraAction(R.string.vlm_back_camera, "vlm_resume", true) { onEvent(VlmIntent.ResumeCamera) }
                }
            }
            if (state.stage == VlmStage.SCAN_REVIEW)
                CameraAction(R.string.vlm_scan_restart_active, "vlm_scan_restart_active", state.canRestartScan) { onEvent(VlmIntent.StartScan) }
            if (interruptible && state.stage != VlmStage.SCANNING) {
                CameraAction(R.string.vlm_scan_restart_active, "vlm_scan_restart_active", state.canRestartScan) { onEvent(VlmIntent.StartScan) }
                CameraAction(R.string.vlm_action_cancel, "vlm_cancel", true) { onEvent(VlmIntent.Cancel) }
            }
        }
    }
}

@Composable
private fun CompositionSelectionToggle(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    TextButton(onClick = { onEvent(VlmIntent.CompositionDetailsVisible(!state.compositionDetailsVisible)) },
        enabled = !state.operationBusy, modifier = Modifier.testTag("vlm_details"),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
        Text(stringResource(R.string.vlm_composition_details), maxLines = 1)
        Icon(if (state.compositionDetailsVisible) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            stringResource(if (state.compositionDetailsVisible) R.string.vlm_composition_collapse else R.string.vlm_composition_expand))
    }
}

@Composable
private fun CameraAction(label: Int, tag: String, enabled: Boolean, click: () -> Unit) {
    TextButton(onClick = click, enabled = enabled, modifier = Modifier.testTag(tag),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .4f))) {
        Text(stringResource(label), maxLines = 1)
    }
}

@Composable
internal fun CompositionCards(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("vlm_composition_choices"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        state.result?.plans.orEmpty().forEachIndexed { index, plan ->
            val preview = state.previews[plan.id]?.composite
            val imageHeight = if (landscape) 48.dp else 84.dp
            val ratio = preview?.let { it.width.toFloat() / it.height }
                ?: state.photoPreferences.aspectRatio.ratio(landscape)
            Card(onClick = { onEvent(VlmIntent.Select(plan.id)) }, enabled = !state.operationBusy,
                shape = RectangleShape,
                colors = CardDefaults.cardColors(containerColor = Color(0xFF202126)),
                modifier = Modifier.border(if (plan.id == state.selectedId) 2.dp else 0.dp, Color.White, RectangleShape)
                    .testTag("vlm_composition_${plan.id}")) {
                Box(Modifier.padding(2.dp).size(imageHeight * ratio, imageHeight)) {
                    preview?.let {
                        Image(it.asImageBitmap(), stringResource(R.string.vlm_selected_composition),
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Text(stringResource(R.string.vlm_composition_plan_number, index + 1), color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color.Black.copy(alpha = .55f)).padding(2.dp),
                        maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun PhotoSettingsDialog(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importImage: () -> Unit) {
    CameraDialog(R.string.vlm_photo_settings_title, "vlm_photo_settings_dialog", { onEvent(VlmIntent.PhotoSettingsVisible(false)) }) {
        AvatarSelector(state, onEvent, state.operationBusy)
        AvatarEthnicitySelector(state.selectedEthnicity, !state.operationBusy, "vlm_preferred_ethnicity") { onEvent(VlmIntent.PreferredEthnicity(it)) }
        OutlinedTextField(state.instruction, { onEvent(VlmIntent.InstructionChanged(it)) }, enabled = !state.operationBusy,
            label = { Text(stringResource(R.string.vlm_photo_requirement)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        SettingsSwitch(R.string.vlm_photo_show_face, state.faceVisible, !state.operationBusy, "vlm_face_setting") { onEvent(VlmIntent.FaceVisible(it)) }
        SettingsSwitch(R.string.vlm_ar_mode_label, state.cameraMode == CameraMode.AR, state.framingUnlocked, "vlm_ar_setting") { onEvent(VlmIntent.CameraModeSelected(if (it) CameraMode.AR else CameraMode.NORMAL)) }
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
        if (BuildConfig.DEBUG) TextButton(onClick = { onEvent(VlmIntent.PhotoSettingsVisible(false)); importImage() },
            enabled = !state.operationBusy, modifier = Modifier.testTag("vlm_import_image")) { Text(stringResource(R.string.vlm_debug_image)) }
    }
}

@Composable
internal fun CompositionDetailsDialog(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    CameraDialog(R.string.vlm_composition_details, "vlm_composition_details_dialog", { onEvent(VlmIntent.CompositionDetailsVisible(false)) }) {
        CompositionDetailsContent(state, onEvent)
    }
}

@Composable
private fun CompositionDetailsContent(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
        val plan = state.result?.plans?.find { it.id == state.selectedId }
        val background = plan?.viewBinding?.let { binding ->
            state.viewCandidates.firstOrNull { it.frameId == binding.frameId }
        }
        val sceneObservation = background?.let { listOf(it.subject, it.reason).filter(String::isNotBlank).joinToString("\n") }
        if (plan != null) PhotoActionControls(state, plan, state.operationBusy, onEvent)
        state.result?.let { CompositionDescriptions(it, state.selectedId, sceneObservation) }
        if (plan != null) {
            Button(onClick = { onEvent(VlmIntent.Apply) }, enabled = !state.operationBusy,
                modifier = Modifier.testTag("vlm_apply")) { Text(stringResource(R.string.vlm_apply_plan)) }
            Text(stringResource(R.string.vlm_avatar_size), style = MaterialTheme.typography.labelLarge)
            var height by remember(plan.id, plan.revision) { mutableFloatStateOf(plan.avatar.height.coerceIn(.08f, 4f)) }
            Slider(height, { height = it }, valueRange = .08f..4f,
                onValueChangeFinished = { onEvent(VlmIntent.Resize(height)) }, enabled = !state.operationBusy)
            plan.uncertainties.filterNot { it == com.framer.sense.feature.camera.vlm.agent.CompositionIntentMapper.SCENE_FALLBACK_MARKER }
                .forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(state.instruction, { onEvent(VlmIntent.InstructionChanged(it)) }, enabled = !state.operationBusy,
                label = { Text(stringResource(R.string.vlm_director_revision_request)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
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
private fun CameraDialog(title: Int, tag: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.testTag(tag)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = close) { Icon(Icons.Default.Close, stringResource(R.string.vlm_close_dialog)) }
                }
                content()
            }
        }
    }
}
