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

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float = right - left
    val height: Float = bottom - top
    val centerX: Float = (left + right) / 2f
    val centerY: Float = (top + bottom) / 2f
    val area: Float = width * height

    fun contains(point: NormalizedPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun intersects(other: NormalizedRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun intersectionArea(other: NormalizedRect): Float {
        if (!intersects(other)) return 0f
        val intersectionWidth = minOf(right, other.right) - maxOf(left, other.left)
        val intersectionHeight = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return intersectionWidth * intersectionHeight
    }

    fun clamped(): NormalizedRect =
        NormalizedRect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f)
        )
}

data class NormalizedLine(
    val start: NormalizedPoint,
    val end: NormalizedPoint
)

data class DetectedObjectFrame(
    val bounds: NormalizedRect,
    val confidence: Float
)

data class DetectedPoseFrame(
    val bounds: NormalizedRect,
    val landmarks: List<NormalizedPoint>
)

enum class CameraSceneQuality {
    GOOD,
    NEEDS_MOVE,
    POOR
}

enum class CameraMovementDirection {
    NONE,
    LEFT,
    RIGHT,
    UP,
    DOWN
}

data class CameraGuideState(
    val guideBounds: NormalizedRect,
    val poseLines: List<NormalizedLine>,
    val message: String,
    val sceneQuality: CameraSceneQuality,
    val movementDirection: CameraMovementDirection
) {
    companion object {
        val Initial = CameraGuideState(
            guideBounds = NormalizedRect(0.32f, 0.18f, 0.68f, 0.90f),
            poseLines = emptyList(),
            message = "正在识别场景，请保持手机稳定",
            sceneQuality = CameraSceneQuality.GOOD,
            movementDirection = CameraMovementDirection.NONE
        )
    }
}

sealed interface CameraUiState {
    data object Loading : CameraUiState
    data object PermissionDenied : CameraUiState
    data class Ready(val guideState: CameraGuideState = CameraGuideState.Initial) : CameraUiState
    data class Error(val message: String) : CameraUiState
}

sealed interface PhotoCaptureStatus {
    data object Idle : PhotoCaptureStatus
    data object Saving : PhotoCaptureStatus
    data object Saved : PhotoCaptureStatus
    data class Error(val message: String) : PhotoCaptureStatus
}
