package com.framer.sense.feature.camera.pytorch.v2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.pytorch.v2.R

@Composable
fun CameraV2Overlay(
    guide: CameraV2Guide,
    hint: CameraV2Hint = guide.hint,
    isLandscape: Boolean = false,
    onSwitchTargetPose: () -> Unit = {},
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
            val previewTransform = CameraV2PreviewTransform(
                frameAspectRatio = guide.frameAspectRatio,
                viewportWidth = size.width,
                viewportHeight = size.height
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), 0f)
            val hanfuDash = PathEffect.dashPathEffect(floatArrayOf(15f, 8f), 0f)
            val innerDash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
            val figure = guide.virtualHuman
            if (figure.visualStyle == VirtualHumanVisualStyle.HANFU_GUIDE) {
                figure.decorativePaths.sortedBy { it.depth }.forEach { path ->
                    val depthFactor = ((path.depth + 0.14f) / 0.28f).coerceIn(0f, 1f)
                    val renderedPath = path.points.toPath(
                        width = size.width,
                        height = size.height,
                        previewTransform = previewTransform,
                        closed = path.closed,
                        smooth = path.smooth
                    )
                    drawPath(
                        path = renderedPath,
                        color = Color.Black.copy(alpha = 0.80f),
                        style = Stroke(
                            width = (5.0f + depthFactor * 3.0f).coerceIn(4.6f, 8.0f),
                            pathEffect = hanfuDash
                        )
                    )
                    drawPath(
                        path = renderedPath,
                        color = guideColor.copy(alpha = (0.80f + depthFactor * 0.18f).coerceIn(0.78f, 0.98f)),
                        style = Stroke(
                            width = (3.0f + depthFactor * 2.6f).coerceIn(2.8f, 5.6f),
                            pathEffect = hanfuDash
                        )
                    )
                }
            }
            // 目标人物使用程序生成的半透明实体虚拟人；它与实时用户姿态完全分离。
            figure.volumetricAvatar?.let { avatar ->
                drawVolumetricAvatar(
                    avatar = avatar,
                    previewTransform = previewTransform,
                    viewportWidth = size.width,
                    viewportHeight = size.height
                )
            }
            if (figure.contourPathPoints.size >= 3) {
                drawPath(
                    path = figure.contourPathPoints.toPath(size.width, size.height, previewTransform),
                    color = Color.Black.copy(alpha = 0.82f),
                    style = Stroke(width = 9.4f, pathEffect = dash)
                )
                drawPath(
                    path = figure.contourPathPoints.toPath(size.width, size.height, previewTransform),
                    color = guideColor.copy(alpha = 0.98f),
                    style = Stroke(width = 6.2f, pathEffect = dash)
                )
            }
            // 固定目标人物：完整 133 点的身体、脚、脸和双手语义轮廓。
            figure.targetContourLines.sortedBy { it.depth }.forEach { line ->
                drawLine(
                    color = Color.Black.copy(alpha = 0.26f),
                    start = line.start.toOffset(size.width, size.height, previewTransform),
                    end = line.end.toOffset(size.width, size.height, previewTransform),
                    strokeWidth = 3.2f,
                    pathEffect = innerDash
                )
                drawLine(
                    color = guideColor.copy(alpha = 0.48f),
                    start = line.start.toOffset(size.width, size.height, previewTransform),
                    end = line.end.toOffset(size.width, size.height, previewTransform),
                    strokeWidth = 1.5f,
                    pathEffect = innerDash
                )
            }
            figure.targetContourPoints.forEach { point ->
                val center = point.toOffset(size.width, size.height, previewTransform)
                drawCircle(Color.Black.copy(alpha = 0.30f), 3.8f, center)
                drawCircle(guideColor.copy(alpha = 0.60f), 2.0f, center)
            }
            // 实时用户关键点保持蓝色，明确区分目标 Pose 与当前动作。
            figure.innerContourLines.sortedBy { it.depth }.forEach { line ->
                drawLine(
                    color = Color.Black.copy(alpha = 0.68f),
                    start = line.start.toOffset(size.width, size.height, previewTransform),
                    end = line.end.toOffset(size.width, size.height, previewTransform),
                    strokeWidth = 4.2f,
                    pathEffect = innerDash
                )
                drawLine(
                    color = Color(0xFF80DEEA).copy(alpha = 0.88f),
                    start = line.start.toOffset(size.width, size.height, previewTransform),
                    end = line.end.toOffset(size.width, size.height, previewTransform),
                    strokeWidth = 2.4f,
                    pathEffect = innerDash
                )
            }
            figure.innerContourPoints.forEach { point ->
                val center = point.toOffset(size.width, size.height, previewTransform)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.68f),
                    radius = 4.2f,
                    center = center
                )
                drawCircle(
                    color = Color(0xFF80DEEA).copy(alpha = 0.92f),
                    radius = 2.5f,
                    center = center
                )
            }
            if (figure.drawHead && figure.headRadius > 0f) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.80f),
                    radius = previewTransform.mapFrameWidth(figure.headRadius),
                    center = figure.headCenter.toOffset(size.width, size.height, previewTransform),
                    style = Stroke(width = 8.2f)
                )
                drawCircle(
                    color = guideColor.copy(alpha = 0.98f),
                    radius = previewTransform.mapFrameWidth(figure.headRadius),
                    center = figure.headCenter.toOffset(size.width, size.height, previewTransform),
                    style = Stroke(width = 5.8f)
                )
            }
            if (figure.visualStyle != VirtualHumanVisualStyle.VOLUMETRIC_AVATAR) figure.lines.sortedBy { it.depth }.forEach { line ->
                val depthFactor = ((line.depth + 0.14f) / 0.28f).coerceIn(0f, 1f)
                drawLine(
                    color = Color.Black.copy(alpha = 0.78f),
                    start = line.start.toOffset(size.width, size.height, previewTransform),
                    end = line.end.toOffset(size.width, size.height, previewTransform),
                    strokeWidth = (5.0f + depthFactor * 3.4f).coerceIn(4.6f, 8.4f),
                    pathEffect = dash
                )
                drawLine(
                    color = guideColor.copy(alpha = (0.78f + depthFactor * 0.20f).coerceIn(0.76f, 1.0f)),
                    start = line.start.toOffset(size.width, size.height, previewTransform),
                    end = line.end.toOffset(size.width, size.height, previewTransform),
                    strokeWidth = (3.2f + depthFactor * 3.0f).coerceIn(3.0f, 6.4f),
                    pathEffect = dash
                )
            }
        }

        Column(
            modifier = (if (isLandscape) {
                Modifier
                    .align(Alignment.TopStart)
                    .widthIn(max = 340.dp)
            } else {
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            })
                .statusBarsPadding()
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
                    text = stringResource(hint.messageRes),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            Text(
                text = stringResource(
                    R.string.camera_v2_scene_status,
                    stringResource(guide.semanticScene.group.labelTextRes()),
                    modelStatus
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.74f),
                textAlign = TextAlign.Center
            )
            guide.targetPose?.let { targetPose ->
                Text(
                    text = stringResource(R.string.camera_v2_target_pose, targetPose.title, targetPose.instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
            guide.alignmentFeedback?.let { feedback ->
                Surface(
                    color = Color.Black.copy(alpha = 0.54f),
                    contentColor = guideColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = feedback.instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        if (guide.poseCandidates.size > 1) {
            Button(
                onClick = onSwitchTargetPose,
                modifier = Modifier
                    .align(if (isLandscape) Alignment.BottomStart else Alignment.CenterEnd)
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.camera_v2_switch_pose))
            }
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

private fun V2Point.toOffset(
    width: Float,
    height: Float,
    previewTransform: CameraV2PreviewTransform
): Offset =
    previewTransform.map(this).let { point -> Offset(x = point.x * width, y = point.y * height) }

/** 在 Canvas 内渲染实体化目标人物：身体有填充、明暗、轮廓和前后层级，但不包含任何真实人像素材。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVolumetricAvatar(
    avatar: VirtualHumanAvatar,
    previewTransform: CameraV2PreviewTransform,
    viewportWidth: Float,
    viewportHeight: Float
) {
    avatar.shapes
        .sortedWith(compareBy<VirtualHumanAvatarShape> { it.depth }.thenBy { it.part.renderOrder })
        .forEach { shape ->
            val mapped = shape.points.map { it.toOffset(viewportWidth, viewportHeight, previewTransform) }
            val colors = shape.part.volumeColors()
            if (shape.closed) {
                val path = Path().apply {
                    moveTo(mapped.first().x, mapped.first().y)
                    mapped.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                // 黑色底影使透明人物在亮、暗场景中都有轮廓；双层渐变形成体积感。
                drawPath(path, Color.Black.copy(alpha = 0.32f))
                drawPath(
                    path = path,
                    brush = Brush.linearGradient(
                        colors = colors,
                        start = mapped.first(),
                        end = mapped.last()
                    ),
                    alpha = 0.82f
                )
                drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(width = 1.4f))
            } else if (mapped.size >= 2) {
                val width = previewTransform.mapFrameWidth(shape.widthRatio)
                drawLine(
                    color = Color.Black.copy(alpha = 0.36f),
                    start = mapped.first(),
                    end = mapped.last(),
                    strokeWidth = width + 4f,
                    cap = StrokeCap.Round
                )
                mapped.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        brush = Brush.linearGradient(colors = colors, start = start, end = end),
                        start = start,
                        end = end,
                        strokeWidth = width,
                        cap = StrokeCap.Round,
                        alpha = 0.84f
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.26f),
                        start = start,
                        end = end,
                        strokeWidth = (width * 0.24f).coerceAtLeast(1.2f),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
}

private val VirtualHumanAvatarPart.renderOrder: Int
    get() = when (this) {
        VirtualHumanAvatarPart.LEFT_LEG, VirtualHumanAvatarPart.RIGHT_LEG -> 0
        VirtualHumanAvatarPart.TORSO -> 1
        VirtualHumanAvatarPart.LEFT_ARM, VirtualHumanAvatarPart.RIGHT_ARM -> 2
        VirtualHumanAvatarPart.FACE -> 3
        VirtualHumanAvatarPart.HAIR -> 4
        VirtualHumanAvatarPart.LEFT_HAND, VirtualHumanAvatarPart.RIGHT_HAND -> 5
        VirtualHumanAvatarPart.LEFT_SHOE, VirtualHumanAvatarPart.RIGHT_SHOE -> 6
    }

private fun VirtualHumanAvatarPart.volumeColors(): List<Color> =
    when (this) {
        VirtualHumanAvatarPart.HAIR -> listOf(Color(0xFF17142B), Color(0xFF6E4B94))
        VirtualHumanAvatarPart.FACE, VirtualHumanAvatarPart.LEFT_HAND, VirtualHumanAvatarPart.RIGHT_HAND ->
            listOf(Color(0xFFFFE3D2), Color(0xFFB97B9C))
        VirtualHumanAvatarPart.LEFT_SHOE, VirtualHumanAvatarPart.RIGHT_SHOE ->
            listOf(Color(0xFF24253C), Color(0xFF7E8FBB))
        VirtualHumanAvatarPart.TORSO -> listOf(Color(0xFFB4F4F2), Color(0xFF6175E8))
        VirtualHumanAvatarPart.LEFT_ARM, VirtualHumanAvatarPart.RIGHT_ARM,
        VirtualHumanAvatarPart.LEFT_LEG, VirtualHumanAvatarPart.RIGHT_LEG ->
            listOf(Color(0xFF9DEEE5), Color(0xFF5F67D5))
    }

private fun List<V2Point>.toPath(
    width: Float,
    height: Float,
    previewTransform: CameraV2PreviewTransform,
    closed: Boolean = true,
    smooth: Boolean = false
): Path =
    Path().apply {
        val mappedPoints = map { point ->
            previewTransform.map(point).let { mapped ->
                Offset(mapped.x * width, mapped.y * height)
            }
        }
        val first = mappedPoints.first()
        moveTo(first.x, first.y)
        if (!smooth || mappedPoints.size < 3) {
            mappedPoints.drop(1).forEach { point -> lineTo(point.x, point.y) }
        } else {
            mappedPoints.drop(1).dropLast(1).forEachIndexed { index, control ->
                val next = mappedPoints[index + 2]
                quadraticBezierTo(
                    control.x,
                    control.y,
                    (control.x + next.x) / 2f,
                    (control.y + next.y) / 2f
                )
            }
            val last = mappedPoints.last()
            lineTo(last.x, last.y)
        }
        if (closed) close()
    }

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

private fun SceneGroup.labelTextRes(): Int =
    when (this) {
        SceneGroup.UNKNOWN -> R.string.camera_v2_scene_unknown
        SceneGroup.INDOOR -> R.string.camera_v2_scene_indoor
        SceneGroup.OUTDOOR -> R.string.camera_v2_scene_outdoor
        SceneGroup.NATURE -> R.string.camera_v2_scene_nature
        SceneGroup.URBAN -> R.string.camera_v2_scene_urban
    }
