package com.framer.sense.feature.camera.pytorch.v2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.pytorch.v2.R

@Composable
fun CameraV2Overlay(
    guide: CameraV2Guide,
    modifier: Modifier = Modifier
) {
    val guideColor = when (guide.quality) {
        CameraV2Quality.GOOD -> colorResource(R.color.camera_v2_good)
        CameraV2Quality.NEEDS_MOVE -> colorResource(R.color.camera_v2_warning)
        CameraV2Quality.POOR -> colorResource(R.color.camera_v2_poor)
    }
    val modelStatus = if (guide.modelAvailability.allRequiredReady) {
        stringResource(R.string.camera_v2_model_status_ready)
    } else {
        stringResource(R.string.camera_v2_model_status_missing)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f)
            val target = guide.targetBounds
            drawRoundRect(
                color = guideColor.copy(alpha = 0.46f),
                topLeft = Offset(target.left * size.width, target.top * size.height),
                size = Size(target.width * size.width, target.height * size.height),
                cornerRadius = CornerRadius(26f, 26f),
                style = Stroke(width = 4.5f, pathEffect = dash)
            )

            val figure = guide.virtualHuman
            drawCircle(
                color = guideColor.copy(alpha = 0.88f),
                radius = figure.headRadius * size.minDimension,
                center = figure.headCenter.toOffset(size.width, size.height),
                style = Stroke(width = 5f)
            )
            figure.lines.sortedBy { it.depth }.forEach { line ->
                drawLine(
                    color = guideColor.copy(alpha = (0.70f + line.depth * 0.6f).coerceIn(0.45f, 0.95f)),
                    start = line.start.toOffset(size.width, size.height),
                    end = line.end.toOffset(size.width, size.height),
                    strokeWidth = (4.2f - line.depth * 3f).coerceIn(2.4f, 5.2f),
                    pathEffect = dash
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.26f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.60f),
                contentColor = Color.White,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = stringResource(guide.hint.messageRes),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            Text(
                text = stringResource(R.string.camera_v2_title),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.92f)
            )
            Text(
                text = stringResource(
                    R.string.camera_v2_scene_status,
                    guide.semanticScene.label,
                    modelStatus
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.74f),
                textAlign = TextAlign.Center
            )
        }

        guide.movement.directionTextRes()?.let { textRes ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.52f),
                contentColor = guideColor,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = stringResource(textRes),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)
                )
            }
        }
    }
}

private fun V2Point.toOffset(width: Float, height: Float): Offset =
    Offset(x = x * width, y = y * height)

private fun CameraV2Movement.directionTextRes(): Int? =
    when (this) {
        CameraV2Movement.LEFT -> R.string.camera_v2_direction_left
        CameraV2Movement.RIGHT -> R.string.camera_v2_direction_right
        CameraV2Movement.UP -> R.string.camera_v2_direction_up
        CameraV2Movement.DOWN -> R.string.camera_v2_direction_down
        CameraV2Movement.BACKWARD -> R.string.camera_v2_direction_backward
        CameraV2Movement.FORWARD -> R.string.camera_v2_direction_forward
        CameraV2Movement.NONE -> null
    }
