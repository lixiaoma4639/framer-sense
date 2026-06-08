package com.framer.sense.feature.camera.pytorch.ui

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

    fun intersects(other: NormalizedRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun intersectionArea(other: NormalizedRect): Float {
        if (!intersects(other)) return 0f
        val intersectionWidth = minOf(right, other.right) - maxOf(left, other.left)
        val intersectionHeight = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return intersectionWidth * intersectionHeight
    }

    /**
     * 坐标限制在 0f..1f：
     * 这些检测框是归一化坐标，不是像素坐标。
     * 表示目标位于画面宽度 25% 到 70%、高度 10% 到 80% 的区域。
     */
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

/**
 * 表示一个检测框：
 * 1. bounds：检测框的坐标
 * 2. confidence：置信度
 * 3. classId：类别ID
 * 4. label：类别名称
 */
data class DetectedObjectFrame(
    val bounds: NormalizedRect,
    val confidence: Float,
    val classId: Int,
    val label: String
)

data class CameraAnalysisResult(
    val people: List<DetectedObjectFrame>,
    val objects: List<DetectedObjectFrame>,
    val luminance: Double
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
            message = "正在加载 ONNX 构图模型，请保持手机稳定",
            sceneQuality = CameraSceneQuality.GOOD,
            movementDirection = CameraMovementDirection.NONE
        )
    }
}

sealed interface PhotoCaptureStatus {
    data object Idle : PhotoCaptureStatus
    data object Saving : PhotoCaptureStatus
    data object Saved : PhotoCaptureStatus
    data class Error(val message: String) : PhotoCaptureStatus
}
