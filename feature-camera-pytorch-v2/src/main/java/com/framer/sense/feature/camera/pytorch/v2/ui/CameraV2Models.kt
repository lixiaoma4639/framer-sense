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

data class PersonSegmentation(
    val bounds: V2Rect, //人体矩形边框
    val contour: List<V2Point>, //人体mask集合
    val confidence: Float //评分
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

/**
 * 人体姿态关键点枚举
 * 对应人体17个骨骼关键点标准序号定义（通用姿态识别COCO数据集关键点排布）
 */
enum class PoseKeypointName {
    NOSE,               // 鼻子
    LEFT_EYE,           // 左眼
    RIGHT_EYE,          // 右眼
    LEFT_EAR,           // 左耳
    RIGHT_EAR,          // 右耳
    LEFT_SHOULDER,      // 左肩
    RIGHT_SHOULDER,     // 右肩
    LEFT_ELBOW,         // 左肘
    RIGHT_ELBOW,        // 右肘
    LEFT_WRIST,         // 左手腕
    RIGHT_WRIST,        // 右手腕
    LEFT_HIP,           // 左髋
    RIGHT_HIP,          // 右髋
    LEFT_KNEE,          // 左膝盖
    RIGHT_KNEE,         // 右膝盖
    LEFT_ANKLE,         // 左脚踝
    RIGHT_ANKLE         // 右脚踝
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

data class WholeBodyKeypoint(
    val index: Int,
    val point: V2Point,
    val confidence: Float
)

data class WholeBodyPoseEstimate(
    val keypoints: List<WholeBodyKeypoint>,
    val confidence: Float
) {
    val body: List<WholeBodyKeypoint> = keypoints.inRange(BODY_RANGE)
    val foot: List<WholeBodyKeypoint> = keypoints.inRange(FOOT_RANGE)
    val face: List<WholeBodyKeypoint> = keypoints.inRange(FACE_RANGE)
    val leftHand: List<WholeBodyKeypoint> = keypoints.inRange(LEFT_HAND_RANGE)
    val rightHand: List<WholeBodyKeypoint> = keypoints.inRange(RIGHT_HAND_RANGE)

    fun point(index: Int): V2Point? =
        keypoints.firstOrNull { it.index == index && it.confidence >= MIN_KEYPOINT_CONFIDENCE }?.point

    private fun List<WholeBodyKeypoint>.inRange(range: IntRange): List<WholeBodyKeypoint> =
        filter { it.index in range && it.confidence >= MIN_KEYPOINT_CONFIDENCE }

    companion object {
        private const val MIN_KEYPOINT_CONFIDENCE = 0.2f
        val Empty = WholeBodyPoseEstimate(emptyList(), 0f)

        val BODY_RANGE = 0..16
        val FOOT_RANGE = 17..22
        val FACE_RANGE = 23..90
        val LEFT_HAND_RANGE = 91..111
        val RIGHT_HAND_RANGE = 112..132
    }
}

data class CameraV2Analysis(
    val people: List<ScenePerson>,
    val objects: List<SceneObject>,
    val personSegments: List<PersonSegmentation>,
    val pose: PoseEstimate,
    val wholeBodyPose: WholeBodyPoseEstimate = WholeBodyPoseEstimate.Empty,
    val semanticScene: SemanticScene,
    val luminance: Double,
    val modelAvailability: ModelAvailability,
    val frameAspectRatio: Float = CameraV2PreviewTransform.DEFAULT_FRAME_ASPECT_RATIO
)

data class ModelAvailability(
    val objectDetectorReady: Boolean,
    val poseDetectorReady: Boolean,
    val segmentationReady: Boolean = false,
    val wholeBodyPoseReady: Boolean = false
) {
    val allRequiredReady: Boolean =
        objectDetectorReady && poseDetectorReady

    companion object {
        val Missing = ModelAvailability(
            objectDetectorReady = false,
            poseDetectorReady = false,
            segmentationReady = false,
            wholeBodyPoseReady = false
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
    CAMERA_STARTING(R.string.camera_v2_hint_camera_starting),
    ONNX_LOADING(R.string.camera_v2_hint_onnx_loading),
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

enum class VirtualHumanVisualStyle {
    SKELETON,
    HANFU_GUIDE
}

enum class VirtualHumanPathRole {
    OUTLINE,
    HAIR,
    FACE,
    HANDS,
    SLEEVE,
    WAIST_ORNAMENT,
    SKIRT_FOLD
}

data class VirtualHumanStrokePath(
    val role: VirtualHumanPathRole,
    val points: List<V2Point>,
    val closed: Boolean = false,
    val depth: Float = 0f,
    val smooth: Boolean = false
)

data class VirtualHumanFigure(
    val bounds: V2Rect,
    val template: PoseTemplate,
    val lines: List<VirtualHumanLine>,
    val innerContourLines: List<VirtualHumanLine> = emptyList(),
    val headCenter: V2Point,
    val headRadius: Float,
    val contourPathPoints: List<V2Point> = emptyList(),
    val drawHead: Boolean = true,
    val poseDriven: Boolean = false,
    val visualStyle: VirtualHumanVisualStyle = VirtualHumanVisualStyle.SKELETON,
    val decorativePaths: List<VirtualHumanStrokePath> = emptyList()
)

data class CameraV2Guide(
    val targetBounds: V2Rect,
    val quality: CameraV2Quality,
    val movement: CameraV2Movement,
    val hint: CameraV2Hint,
    val semanticScene: SemanticScene,
    val virtualHuman: VirtualHumanFigure,
    val modelAvailability: ModelAvailability,
    val frameAspectRatio: Float = CameraV2PreviewTransform.DEFAULT_FRAME_ASPECT_RATIO
) {
    companion object {
        fun initial(profile: BodyProfile = BodyProfile(BodyProfile.DEFAULT_HEIGHT_CM, BodyProfile.DEFAULT_WEIGHT_KG)): CameraV2Guide {
            val bounds = V2Rect(0.32f, 0.15f, 0.68f, 0.91f)
            return CameraV2Guide(
                targetBounds = bounds,
                quality = CameraV2Quality.NEEDS_MOVE,
                movement = CameraV2Movement.NONE,
                hint = CameraV2Hint.CAMERA_STARTING,
                semanticScene = SemanticScene.Unknown,
                virtualHuman = VirtualHumanProjector().projectDefaultHanfuGuide(
                    targetBounds = bounds,
                    profile = profile
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
