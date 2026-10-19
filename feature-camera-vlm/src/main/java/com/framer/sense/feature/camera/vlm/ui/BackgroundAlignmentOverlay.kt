package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.BackgroundStatus
import kotlin.math.abs

/** 未对齐时提示找回背景，自动／手动确认后仅保留简洁的拍摄提示。 */
@Composable
internal fun BackgroundAlignmentOverlay(
    state: VlmUiState,
    onEvent: (VlmIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.showReferenceGuidanceOverlay) return
    val plan = state.referencePlan ?: return
    val searching = plan.viewBinding != null && state.background.status == BackgroundStatus.SEARCHING
    val heading = state.background.headingError
    val pitch = state.background.pitchError
    val direction = when {
        heading != null && abs(heading) > 5f ->
            if (heading > 0) R.string.vlm_background_right else R.string.vlm_background_left
        pitch != null && abs(pitch) > 5f ->
            if (pitch > 0) R.string.vlm_background_up else R.string.vlm_background_down
        else -> null
    }
    CameraRotatingViewport(modifier.fillMaxSize().testTag("vlm_background_viewport")) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(12.dp).widthIn(max = 480.dp)
                .testTag("vlm_background_panel")
                .background(Color.Black.copy(alpha = .65f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(if (searching) direction ?: R.string.vlm_background_waiting
                    else R.string.vlm_reference_ready),
                color = Color.White, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("vlm_background_status")
            )
            if (searching) {
                //Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth().testTag("vlm_background_confirmation_group"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.vlm_background_searching),
                        color = Color.White, textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).testTag("vlm_background_instructions"))
                    OutlinedButton(onClick = { onEvent(VlmIntent.ConfirmBackground) },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Color.White),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 28.dp)
                            .testTag("vlm_background_confirm")) {
                        Text(stringResource(R.string.vlm_background_confirm))
                    }
                }
            }
        }
    }
}
