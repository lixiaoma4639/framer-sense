package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.framer.sense.feature.camera.vlm.R

/** 独立利用快门原有 6dp 底边距和底部留白，不参与操作栏的测量。 */
@Composable
internal fun CameraWorkflowFooter(state: VlmUiState, bottomSpace: Dp, modifier: Modifier = Modifier) {
    val available = bottomSpace.coerceAtLeast(0.dp)
    val height = (available + 5.dp).coerceAtMost(20.dp)
    Box(modifier.fillMaxWidth().offset(y = available).height(available + 6.dp)
        .padding(horizontal = 12.dp).padding(bottom = 1.dp), contentAlignment = Alignment.BottomCenter) {
        CameraWorkflowTimeline(state, height = height)
    }
}

/** 只有节点图案随握持方向转正；窄小底部留白按实际高度紧凑显示。 */
@Composable
internal fun CameraWorkflowTimeline(state: VlmUiState, modifier: Modifier = Modifier, height: Dp = 20.dp) {
    val progress = state.workflowProgress()
    val completeColor = Color(0xFF69D484)
    val nodeSize = height.coerceAtMost(20.dp)
    Row(modifier.widthIn(max = 360.dp).fillMaxWidth().height(height)
        .testTag("vlm_workflow_timeline"), verticalAlignment = Alignment.CenterVertically) {
        CameraWorkflowStep.entries.forEachIndexed { index, step ->
            val status = progress[index]
            val (icon, labelId) = when (step) {
                CameraWorkflowStep.SCAN -> Icons.Default.DocumentScanner to R.string.vlm_workflow_scan
                CameraWorkflowStep.GENERATE -> Icons.Default.AutoAwesome to R.string.vlm_workflow_generate
                CameraWorkflowStep.SELECT -> Icons.Default.ViewCarousel to R.string.vlm_workflow_select
                CameraWorkflowStep.ALIGN -> Icons.Default.CenterFocusStrong to R.string.vlm_workflow_align
                CameraWorkflowStep.CAPTURE -> Icons.Default.PhotoCamera to R.string.vlm_workflow_capture
            }
            val label = stringResource(labelId)
            val description = stringResource(when (status) {
                CameraStepStatus.PENDING -> R.string.vlm_workflow_pending
                CameraStepStatus.RUNNING -> R.string.vlm_workflow_running
                CameraStepStatus.COMPLETE -> R.string.vlm_workflow_complete
            })
            Box(Modifier.size(nodeSize).testTag("vlm_workflow_${step.name}")
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = description
                }, contentAlignment = Alignment.Center) {
                if (status == CameraStepStatus.RUNNING) {
                    CircularProgressIndicator(Modifier.size(nodeSize.coerceAtMost(14.dp)).testTag("vlm_workflow_loading_${step.name}"),
                        color = Color.White, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
                } else {
                    Icon(icon, contentDescription = null,
                        tint = if (status == CameraStepStatus.COMPLETE) completeColor else Color.White,
                        modifier = Modifier.size(nodeSize.coerceAtMost(16.dp)).testTag("vlm_workflow_icon_${step.name}").cameraIconRotation())
                }
            }
            if (index < CameraWorkflowStep.entries.lastIndex) {
                Spacer(Modifier.weight(1f).height(1.dp).background(
                    if (status == CameraStepStatus.COMPLETE) completeColor else Color.White.copy(alpha = .4f)))
            }
        }
    }
}
