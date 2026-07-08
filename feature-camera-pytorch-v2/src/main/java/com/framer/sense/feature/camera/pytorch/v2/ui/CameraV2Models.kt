package com.framer.sense.feature.camera.pytorch.v2.ui

import androidx.annotation.StringRes
import com.framer.sense.feature.camera.pytorch.v2.R
import kotlin.math.abs

data class V2Point(
    val x: Float,
    val y: Float
)

data class V2Rect(
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

    fun clamped(): V2Rect =
        V2Rect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f)
        )

    fun intersectionArea(other: V2Rect): Float {
        val intersectionWidth = minOf(right, other.right) - maxOf(left, other.left)
        val intersectionHeight = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return if (intersectionWidth <= 0f || intersectionHeight <= 0f) 0f else intersectionWidth * intersectionHeight
    }

    fun centerDistanceTo(other: V2Rect): Float =
        abs(centerX - other.centerX) + abs(centerY - other.centerY)
}

data class SceneObject(
    val bounds: V2Rect,
    val label: String,
    val confidence: Float,
    val classId: Int
)

data class ScenePerson(
    val bounds: V2Rect,
    val confidence: Float
)

enum class SceneGroup {
    UNKNOWN,
    INDOOR,
    OUTDOOR,
    NATURE,
    URBAN
}

data class SemanticScene(
    val label: String,
    val group: SceneGroup,
    val confidence: Float
) {
    companion object {
        val Unknown = SemanticScene(
            label = "unknown",
            group = SceneGroup.UNKNOWN,
            confidence = 0f
        )
    }
}

data class PoseKeypoint(
    val name: PoseKeypointName,
    val point: V2Point,
    val confidence: Float
)

enum class PoseKeypointName {
    NOSE,
    LEFT_EYE,
    RIGHT_EYE,
    LEFT_EAR,
    RIGHT_EAR,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE
}

data class PoseEstimate(
    val keypoints: List<PoseKeypoint>,
    val confidence: Float
) {
    fun point(name: PoseKeypointName): V2Point? =
        keypoints.firstOrNull { it.name == name && it.confidence >= MIN_KEYPOINT_CONFIDENCE }?.point

    companion object {
        private const val MIN_KEYPOINT_CONFIDENCE = 0.2f
        val Empty = PoseEstimate(emptyList(), 0f)
    }
}

data class CameraV2Analysis(
    val people: List<ScenePerson>,
    val objects: List<SceneObject>,
    val pose: PoseEstimate,
    val semanticScene: SemanticScene,
    val luminance: Double,
    val modelAvailability: ModelAvailability
)

data class ModelAvailability(
    val objectDetectorReady: Boolean,
    val poseDetectorReady: Boolean
) {
    val allRequiredReady: Boolean =
        objectDetectorReady && poseDetectorReady

    companion object {
        val Missing = ModelAvailability(
            objectDetectorReady = false,
            poseDetectorReady = false
        )
    }
}

enum class CameraV2Quality {
    GOOD,
    NEEDS_MOVE,
    POOR
}

enum class CameraV2Movement {
    NONE,
    LEFT,
    RIGHT,
    UP,
    DOWN,
    BACKWARD,
    FORWARD
}

enum class CameraV2Hint(@StringRes val messageRes: Int) {
    STARTING(R.string.camera_v2_hint_starting),
    PERMISSION_REQUIRED(R.string.camera_v2_permission_message),
    MODEL_ASSETS_MISSING(R.string.camera_v2_hint_model_assets_missing),
    ENTER_GUIDE(R.string.camera_v2_hint_enter_guide),
    GOOD(R.string.camera_v2_hint_good),
    LOW_LIGHT(R.string.camera_v2_hint_low_light),
    SCENE_BUSY(R.string.camera_v2_hint_scene_busy),
    MOVE_LEFT(R.string.camera_v2_hint_move_left),
    MOVE_RIGHT(R.string.camera_v2_hint_move_right),
    MOVE_UP(R.string.camera_v2_hint_move_up),
    MOVE_DOWN(R.string.camera_v2_hint_move_down),
    STEP_BACK(R.string.camera_v2_hint_step_back),
    STEP_FORWARD(R.string.camera_v2_hint_step_forward),
    CAMERA_ERROR(R.string.camera_v2_camera_error_message),
    STORAGE_PERMISSION_NEEDED(R.string.camera_v2_storage_permission_needed),
    CAPTURE_FAILED(R.string.camera_v2_capture_failed)
}

data class BodyProfile(
    val heightCm: Int,
    val weightKg: Int
) {
    val safeHeightCm: Int = heightCm.coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
    val safeWeightKg: Int = weightKg.coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)
    val bmi: Float = safeWeightKg / ((safeHeightCm / 100f) * (safeHeightCm / 100f))
    val widthScale: Float = (0.86f + (bmi - 21f) * 0.018f).coerceIn(0.72f, 1.24f)
    val heightScale: Float = (safeHeightCm / 170f).coerceIn(0.88f, 1.12f)

    companion object {
        const val DEFAULT_HEIGHT_CM = 170
        const val DEFAULT_WEIGHT_KG = 60
        private const val MIN_HEIGHT_CM = 120
        private const val MAX_HEIGHT_CM = 220
        private const val MIN_WEIGHT_KG = 30
        private const val MAX_WEIGHT_KG = 180
    }
}

enum class PoseTemplate {
    RELAXED_STAND,
    SIDE_STANCE,
    WALKING
}

data class VirtualHumanLine(
    val start: V2Point,
    val end: V2Point,
    val depth: Float
)

data class VirtualHumanFigure(
    val bounds: V2Rect,
    val template: PoseTemplate,
    val lines: List<VirtualHumanLine>,
    val headCenter: V2Point,
    val headRadius: Float
)

data class CameraV2Guide(
    val targetBounds: V2Rect,
    val quality: CameraV2Quality,
    val movement: CameraV2Movement,
    val hint: CameraV2Hint,
    val semanticScene: SemanticScene,
    val virtualHuman: VirtualHumanFigure,
    val modelAvailability: ModelAvailability
) {
    companion object {
        fun initial(profile: BodyProfile = BodyProfile(BodyProfile.DEFAULT_HEIGHT_CM, BodyProfile.DEFAULT_WEIGHT_KG)): CameraV2Guide {
            val bounds = V2Rect(0.32f, 0.15f, 0.68f, 0.91f)
            return CameraV2Guide(
                targetBounds = bounds,
                quality = CameraV2Quality.NEEDS_MOVE,
                movement = CameraV2Movement.NONE,
                hint = CameraV2Hint.STARTING,
                semanticScene = SemanticScene.Unknown,
                virtualHuman = VirtualHumanProjector().project(
                    targetBounds = bounds,
                    profile = profile,
                    template = PoseTemplate.RELAXED_STAND
                ),
                modelAvailability = ModelAvailability.Missing
            )
        }
    }
}

sealed interface PhotoV2CaptureState {
    data object Idle : PhotoV2CaptureState
    data object Saving : PhotoV2CaptureState
    data object Saved : PhotoV2CaptureState
    data class Error(val hint: CameraV2Hint) : PhotoV2CaptureState
}
