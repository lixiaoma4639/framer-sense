package com.framer.sense.feature.camera.vlm.ui

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.framer.sense.feature.camera.vlm.agent.SceneDescriptionFormatting
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.*
import java.io.File

@Composable
internal fun seedreamModelLabel(model: SeedreamModel): String = stringResource(
    if (model == SeedreamModel.FLASH) R.string.vlm_seedream_flash else R.string.vlm_seedream_pro)

@Composable
internal fun qwenImageModelLabel(model: QwenImageModel): String = stringResource(when (model) {
    QwenImageModel.V21_TURBO -> R.string.vlm_qwen_image_21_turbo
    QwenImageModel.V21_PRO -> R.string.vlm_qwen_image_21_pro
    QwenImageModel.V30 -> R.string.vlm_qwen_image_30
    QwenImageModel.V30_PRO -> R.string.vlm_qwen_image_30_pro
})

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

internal fun VlmUiState.generatedReferenceDescriptions(): GeneratedReferenceDescriptions {
    val selected = generated.selected ?: return GeneratedReferenceDescriptions()
    val descriptions = selected.assets?.descriptions ?: GeneratedReferenceDescriptions()
    val frameId = selected.viewBinding?.frameId
    val observation = frameId?.let { id -> viewCandidates.firstOrNull { it.frameId == id } }
        ?.let { listOf(it.subject.trim(), it.reason.trim()).filter(String::isNotBlank).distinct().joinToString("\n") }.orEmpty()
    return descriptions.copy(actualComposition = descriptions.actualComposition.trim(),
        shootingGuidance = descriptions.shootingGuidance.trim(),
        environment = descriptions.environment.trim().ifBlank { selected.sceneDescription.trim().ifBlank { observation } })
}

@Composable
internal fun GeneratedReferenceDetails(state: VlmUiState) {
    val candidate = state.generated.selected ?: return
    CompositionInstructionDescription(candidate.instruction)
    if (candidate.ready && candidate.assets?.hasPortrait == false) {
        CompositionDescriptionCard {
            Text(stringResource(when (candidate.assets.portraitIssue) {
                GeneratedPortraitIssue.NO_TRANSPARENCY -> R.string.vlm_generated_no_transparency
                GeneratedPortraitIssue.ASPECT_MISMATCH -> R.string.vlm_generated_aspect_mismatch
                null -> R.string.vlm_generated_portrait_unavailable
            }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("vlm_generated_unavailable_reason"))
        }
    }
    val descriptions = state.generatedReferenceDescriptions()
    if (listOf(descriptions.actualComposition, descriptions.shootingGuidance, descriptions.environment).any(String::isNotBlank)) {
        CompositionDescriptionCard {
            if (descriptions.actualComposition.isNotBlank()) CompositionDescriptionText(
                listOf(stringResource(R.string.vlm_description_actual) to descriptions.actualComposition),
                modifier = Modifier.testTag("vlm_generated_actual"), style = MaterialTheme.typography.labelSmall)
            if (descriptions.shootingGuidance.isNotBlank()) CompositionDescriptionText(
                listOf(stringResource(R.string.vlm_description_guidance) to descriptions.shootingGuidance),
                modifier = Modifier.testTag("vlm_generated_guidance"))
            if (descriptions.environment.isNotBlank()) CompositionDescriptionText(
                listOf(stringResource(R.string.vlm_description_environment) to SceneDescriptionFormatting.format(descriptions.environment)),
                modifier = Modifier.testTag("vlm_generated_environment"))
        }
    } else if (candidate.canApply) {
        CompositionDescriptionCard {
            Text(stringResource(R.string.vlm_generated_descriptions_empty), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("vlm_generated_descriptions_empty"))
        }
    }
}

@Composable
internal fun GeneratedReferenceImage(candidate: GeneratedCandidate, modifier: Modifier = Modifier, overlay: Boolean = false) {
    val assets = candidate.assets ?: return
    if (overlay && !assets.hasPortrait) return
    CameraPhotoViewport(candidate.scene.orientation.previewDegrees, modifier) {
        if (overlay) {
            AsyncImage(File(assets.portraitPath), stringResource(R.string.vlm_reference_avatar),
                Modifier.fillMaxSize().testTag("vlm_generated_overlay"), contentScale = ContentScale.Fit, alpha = .90f)
        } else {
            ZoomablePhotoPreview(listOf(candidate.id, candidate.revision, candidate.scene.orientation.previewDegrees),
                assets.width.toFloat() / assets.height, Modifier.fillMaxSize().testTag("vlm_generated_preview_zoom")) {
                AsyncImage(File(assets.compositePath), stringResource(R.string.vlm_selected_composition),
                    Modifier.fillMaxSize().testTag("vlm_generated_preview"), contentScale = ContentScale.Fit)
            }
        }
    }
}
