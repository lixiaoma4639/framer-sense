package com.framer.sense.feature.camera.vlm.ui

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.*
import java.io.File

@Composable
internal fun seedreamModelLabel(model: SeedreamModel): String = stringResource(
    if (model == SeedreamModel.FLASH) R.string.vlm_seedream_flash else R.string.vlm_seedream_pro)

@Composable
internal fun GeneratedReferenceCards(state: VlmUiState, onEvent: (VlmIntent) -> Unit, scroll: LazyListState) {
    val generated = state.generated
    val enabled = !state.operationBusy && !state.instructionEditorVisible
    val ordered = generated.ordered
    val imageHeight = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 48.dp else 84.dp
    val pending = state.rankedBackgroundFrames.filter { frame -> generated.candidates.none { it.scene.id == frame.scene.id } }
        .take((ScanLimits.MAX_COMPOSITION_BACKGROUNDS - generated.candidates.map { it.scene.id }.distinct().size).coerceAtLeast(0))
    LaunchedEffect(generated.selectedId) {
        val index = ordered.indexOfFirst { it.id == generated.selectedId }
        if (index >= 0) scroll.animateScrollToItem(index)
    }
    LazyRow(Modifier.fillMaxWidth().testTag("vlm_composition_choices"), state = scroll,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ordered, key = { it.id }) { candidate ->
            val isSelected = candidate.id == generated.selectedId
            val photoDegrees = candidate.scene.orientation.previewDegrees
            val ratio = candidate.scene.width.toFloat() / candidate.scene.height
            val displayedRatio = if (photoDegrees % 180 == 0) ratio else 1f / ratio
            val rank = state.viewCandidates.indexOfFirst { it.frameId == candidate.viewBinding?.frameId }.takeIf { it >= 0 }?.plus(1) ?: 1
            val index = ordered.filter { it.scene.id == candidate.scene.id }.indexOf(candidate) + 1
            Card(shape = RectangleShape,
                colors = CardDefaults.cardColors(containerColor = Color(0xFF202126), contentColor = Color.White),
                modifier = Modifier.border(if (isSelected) 2.dp else 0.dp, Color.White, RectangleShape)
                    .clickable(enabled = enabled && candidate.ready) { onEvent(VlmIntent.SelectGenerated(candidate.id)) }
                    .semantics { selected = isSelected }.testTag("vlm_generated_card_${candidate.id}")) {
                Box(Modifier.padding(2.dp).size(imageHeight * displayedRatio, imageHeight), contentAlignment = Alignment.Center) {
                    CameraRotatingViewport(Modifier.fillMaxSize(), preservePhotoDirection = true) {
                        candidate.assets?.let { assets ->
                            CameraPhotoViewport(photoDegrees, Modifier.fillMaxSize()) {
                                AsyncImage(File(assets.compositePath), stringResource(R.string.vlm_selected_composition),
                                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                        }
                        if (candidate.status in listOf(GeneratedCandidateStatus.QUEUED, GeneratedCandidateStatus.RUNNING))
                            CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), color = Color.White)
                        if (candidate.status in listOf(GeneratedCandidateStatus.FAILED, GeneratedCandidateStatus.CANCELLED))
                            Box(Modifier.align(Alignment.Center)) {
                                CameraAction(R.string.vlm_generated_retry, "vlm_generated_retry_${candidate.id}", enabled) {
                                    onEvent(VlmIntent.RetryGenerated(candidate.id))
                                }
                            }
                        CompositionCardCaption(stringResource(R.string.vlm_background_plan_number, rank, index), "vlm_generated_label_${candidate.id}")
                    }
                }
            }
        }
        items(pending, key = { "pending:${it.id}" }) { frame ->
            val rank = state.rankedBackgroundFrames.indexOf(frame) + 1
            PendingBackgroundCard(CompositionChoice.PendingBackground(frame, rank), enabled,
                generated.selectedId == null && state.selectedFrameId == frame.id, imageHeight) {
                onEvent(VlmIntent.PreviewScanView(frame.id))
            }
        }
    }
}

@Composable
internal fun GeneratedReferenceDetails(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    val selected = state.generated.selected ?: return
    Text(seedreamModelLabel(selected.model), color = Color.White, style = MaterialTheme.typography.labelSmall)
    selected.assets?.let { assets ->
        if (assets.sourceLayerCount > 0) Text(stringResource(R.string.vlm_generated_all_layers, assets.sourceLayerCount),
            color = Color.White, style = MaterialTheme.typography.bodySmall)
        if (!assets.hasPortrait) Text(stringResource(R.string.vlm_generated_portrait_unavailable),
            color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
    if (selected.instruction.isNotBlank()) Text(selected.instruction, color = Color.White, style = MaterialTheme.typography.bodySmall)
    CameraActionRow {
        CameraAction(R.string.vlm_generated_enlarge, "vlm_generated_enlarge", !state.operationBusy) {
            onEvent(VlmIntent.GeneratedZoomVisible(true))
        }
        CameraAction(R.string.vlm_generated_replace, "vlm_generated_replace", !state.operationBusy) {
            onEvent(VlmIntent.ReplaceGenerated)
        }
    }
}

@Composable
internal fun GeneratedReferenceDialogs(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    val selected = state.generated.selected
    if (state.generated.zoomVisible && selected?.assets != null) GeneratedReferenceZoom(selected) {
        onEvent(VlmIntent.GeneratedZoomVisible(false))
    }
}

@Composable
internal fun GeneratedReferenceImage(candidate: GeneratedCandidate, modifier: Modifier = Modifier, overlay: Boolean = false) {
    val assets = candidate.assets ?: return
    if (overlay && !assets.hasPortrait) return
    CameraPhotoViewport(candidate.scene.orientation.previewDegrees, modifier) {
        AsyncImage(File(if (overlay) assets.portraitPath else assets.compositePath),
            stringResource(if (overlay) R.string.vlm_reference_avatar else R.string.vlm_selected_composition),
            Modifier.fillMaxSize().testTag(if (overlay) "vlm_generated_overlay" else "vlm_generated_preview"),
            contentScale = ContentScale.Fit, alpha = if (overlay) .90f else 1f)
    }
}

@Composable
private fun GeneratedReferenceZoom(candidate: GeneratedCandidate, close: () -> Unit) {
    var scale by remember(candidate.id) { mutableFloatStateOf(1f) }
    var offset by remember(candidate.id) { mutableStateOf(Offset.Zero) }
    CameraAdaptiveAlertDialog(onDismissRequest = close,
        title = { Text(stringResource(R.string.vlm_generated_enlarge)) },
        text = {
            Box(Modifier.fillMaxWidth().height(420.dp).clipToBounds().background(Color.Black)
                .pointerInput(candidate.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }) {
                AsyncImage(File(requireNotNull(candidate.assets).compositePath), stringResource(R.string.vlm_selected_composition),
                    Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.vlm_model_settings_close)) } })
}
