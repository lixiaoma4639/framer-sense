/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.template.feature.camera.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun CameraGuideOverlay(
    guideState: CameraGuideState,
    modifier: Modifier = Modifier
) {
    val guideColor = when (guideState.sceneQuality) {
        CameraSceneQuality.GOOD -> Color(0xFF5EF2A0)
        CameraSceneQuality.NEEDS_MOVE -> Color(0xFFFFD166)
        CameraSceneQuality.POOR -> MaterialTheme.colorScheme.error
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f)
            val stroke = Stroke(width = 5f, pathEffect = dash)
            val bounds = guideState.guideBounds
            val left = bounds.left * size.width
            val top = bounds.top * size.height
            val width = bounds.width * size.width
            val height = bounds.height * size.height
            val headRadius = width * 0.16f
            val headCenter = Offset(
                x = bounds.centerX * size.width,
                y = top + height * 0.12f
            )

            drawRoundRect(
                color = guideColor.copy(alpha = 0.36f),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(36f, 36f),
                style = stroke
            )
            drawCircle(
                color = guideColor,
                radius = headRadius,
                center = headCenter,
                style = stroke
            )
            guideState.poseLines.forEach { line ->
                drawLine(
                    color = guideColor,
                    start = line.start.toOffset(size.width, size.height),
                    end = line.end.toOffset(size.width, size.height),
                    strokeWidth = 5f,
                    pathEffect = dash
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.22f))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.58f),
                contentColor = Color.White,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = guideState.message,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            Text(
                text = "AI 构图引导",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.88f)
            )
        }

        val directionText = guideState.movementDirection.directionText()
        if (directionText != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.46f),
                contentColor = guideColor,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = directionText,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)
                )
            }
        }
    }
}

private fun NormalizedPoint.toOffset(width: Float, height: Float): Offset =
    Offset(x = x * width, y = y * height)

private fun CameraMovementDirection.directionText(): String? =
    when (this) {
        CameraMovementDirection.LEFT -> "← 向左移动相机"
        CameraMovementDirection.RIGHT -> "向右移动相机 →"
        CameraMovementDirection.UP -> "↑ 向上移动相机"
        CameraMovementDirection.DOWN -> "↓ 向下移动相机"
        CameraMovementDirection.NONE -> null
    }
