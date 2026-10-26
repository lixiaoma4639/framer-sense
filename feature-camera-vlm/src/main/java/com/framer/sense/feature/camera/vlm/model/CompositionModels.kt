package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.Json
import java.util.UUID

/** 公共序列化配置；拒绝非有限数值，不忽略未知业务字段。 */
val VlmJson = Json { encodeDefaults = true; explicitNulls = true }

@Serializable enum class Region { CN, GLOBAL }
@Serializable enum class ModelMode { OFFLINE, CLOUD, AUTO }
@Serializable enum class QwenAccess { OFFICIAL, GATEWAY }
@Serializable enum class SeedAccess { OFFICIAL, GATEWAY }
@Serializable enum class ProviderId { QWEN, SEED, GPT, GEMINI, LOCAL }
@Serializable enum class ShotType { ENVIRONMENT, FULL, HALF, CLOSE_UP }
@Serializable enum class PoseId {
    RELAXED, SIDE, LOOK_BACK, HANDS_FRONT, HAND_HIP, HANDS_HIPS,
    WAVE, POINT, HAT, LOOK_UP, LOOK_DOWN, ARMS_OPEN
}
@Serializable enum class ExpressionId { NEUTRAL, SMILE, HAPPY, SURPRISED, THOUGHTFUL, CONFIDENT }
/** 人体躯干的受控朝向；仅允许接近绑定姿势的小幅变换。 */
@Serializable enum class BodyPose { FRONT, TURN_LEFT, TURN_RIGHT, LEAN_FORWARD }
/** 手臂动作；每项映射到已验证的 Rocketbox 骼骨旋转。 */
@Serializable enum class ArmPose { RELAXED, HAND_HIP, HANDS_HIPS, HANDS_FRONT, WAVE, POINT, ARMS_OPEN }
/** 头部方向；与身体朝向独立，便于表达回眸、抬头等摄影动作。 */
@Serializable enum class HeadPose { FRONT, TURN_LEFT, TURN_RIGHT, LOOK_UP, LOOK_DOWN, LOOK_BACK }
/** 下肢只提供轻微重心和前后脚，避免生成不稳定或扭曲的动作。 */
@Serializable enum class StancePose { NEUTRAL, WEIGHT_LEFT, WEIGHT_RIGHT, STEP_FORWARD }
/** 模型对人物与相机距离的摄影语义，不是未经校验的真实测距。 */
@Serializable enum class SubjectDistance { FAR, MID, NEAR }
@Serializable enum class AvatarId { ADULT_FEMALE, ADULT_MALE, CHILD_GIRL, CHILD_BOY }
/** 用户选择的卡通角色外观；不从照片推断，不交给模型改写。 */
@Serializable enum class AvatarEthnicity { WHITE, ASIAN, BLACK }
@Serializable enum class ActionKind { CAPABILITIES, VALIDATE, RENDER, FINAL }
@Serializable enum class CompositionZone { LEFT, CENTER, RIGHT }
@Serializable enum class FacingDirection { FRONT, THREE_QUARTER_LEFT, THREE_QUARTER_RIGHT }

@Serializable data class Point2(val x: Float, val y: Float)
@Serializable data class CropRect(
    @Required val left: Float = 0f, @Required val top: Float = 0f,
    @Required val right: Float = 1f, @Required val bottom: Float = 1f
)
@Serializable data class AvatarPlacement(
    @Required val foot: Point2 = Point2(0.5f, 0.92f),
    @Required val height: Float = 0.65f,
    @Required val yaw: Float = 0f,
    /** 由用户选择的指导角色；模型无权据照片推断或改写该值。 */
    @Required val avatarId: AvatarId = AvatarId.ADULT_FEMALE,
    @Required val pose: PoseId = PoseId.RELAXED,
    @Required val expression: ExpressionId = ExpressionId.NEUTRAL,
    @Required val expressionIntensity: Float = 0.6f,
    /** 新协议的细分姿势；为空时按旧 PoseId 渲染，保证历史方案兼容。 */
    val poseDirective: AvatarPoseDirective? = null,
    /** 用于说明与比例策略的摄影距离语义。 */
    val subjectDistance: SubjectDistance = SubjectDistance.MID,
    /** 仅在 App 内避免已校准的投影再次缩小；不进入 v1 云端协议。 */
    @kotlinx.serialization.Transient val projectionCalibrated: Boolean = false,
    /** 仅用于 App 内角色资源选择，保持现有网关协议不变。 */
    @kotlinx.serialization.Transient val ethnicity: AvatarEthnicity? = null
)

/**
 * VLM 可表达、渲染器可安全执行的人像动作。它不是任意关节角度接口，所有字段都会
 * 收敛到 Rocketbox 已知骨骼与有限旋转范围。
 */
@Serializable data class AvatarPoseDirective(
    val body: BodyPose = BodyPose.FRONT,
    val arms: ArmPose = ArmPose.RELAXED,
    val head: HeadPose = HeadPose.FRONT,
    val stance: StancePose = StancePose.NEUTRAL
) {
    companion object {
        /** 将旧姿势编号转换为等价的细分动作，供旧方案与旧网关响应使用。 */
        fun fromLegacy(pose: PoseId): AvatarPoseDirective = when (pose) {
            PoseId.RELAXED -> AvatarPoseDirective()
            PoseId.SIDE -> AvatarPoseDirective(BodyPose.TURN_RIGHT, ArmPose.RELAXED, HeadPose.TURN_LEFT, StancePose.WEIGHT_LEFT)
            PoseId.LOOK_BACK -> AvatarPoseDirective(BodyPose.TURN_RIGHT, ArmPose.RELAXED, HeadPose.LOOK_BACK, StancePose.WEIGHT_RIGHT)
            PoseId.HANDS_FRONT -> AvatarPoseDirective(arms = ArmPose.HANDS_FRONT)
            PoseId.HAND_HIP -> AvatarPoseDirective(arms = ArmPose.HAND_HIP, stance = StancePose.WEIGHT_RIGHT)
            PoseId.HANDS_HIPS -> AvatarPoseDirective(arms = ArmPose.HANDS_HIPS, stance = StancePose.WEIGHT_LEFT)
            PoseId.WAVE -> AvatarPoseDirective(arms = ArmPose.WAVE)
            PoseId.POINT -> AvatarPoseDirective(arms = ArmPose.POINT)
            PoseId.HAT -> AvatarPoseDirective(arms = ArmPose.HAND_HIP, head = HeadPose.TURN_LEFT)
            PoseId.LOOK_UP -> AvatarPoseDirective(head = HeadPose.LOOK_UP)
            PoseId.LOOK_DOWN -> AvatarPoseDirective(body = BodyPose.LEAN_FORWARD, head = HeadPose.LOOK_DOWN)
            PoseId.ARMS_OPEN -> AvatarPoseDirective(arms = ArmPose.ARMS_OPEN)
        }

        /** 为兼容旧卡片和调试日志，将细分动作归纳为最接近的旧姿势名称。 */
        fun legacyPose(directive: AvatarPoseDirective): PoseId = when {
            directive.head == HeadPose.LOOK_BACK -> PoseId.LOOK_BACK
            directive.head == HeadPose.LOOK_UP -> PoseId.LOOK_UP
            directive.head == HeadPose.LOOK_DOWN -> PoseId.LOOK_DOWN
            directive.arms == ArmPose.HAND_HIP -> PoseId.HAND_HIP
            directive.arms == ArmPose.HANDS_HIPS -> PoseId.HANDS_HIPS
            directive.arms == ArmPose.HANDS_FRONT -> PoseId.HANDS_FRONT
            directive.arms == ArmPose.WAVE -> PoseId.WAVE
            directive.arms == ArmPose.POINT -> PoseId.POINT
            directive.arms == ArmPose.ARMS_OPEN -> PoseId.ARMS_OPEN
            directive.body != BodyPose.FRONT -> PoseId.SIDE
            else -> PoseId.RELAXED
        }
    }
}
@Serializable data class CompositionPlan(
    val id: String,
    val title: String,
    val shot: ShotType,
    val crop: CropRect,
    val zoom: Float,
    val avatar: AvatarPlacement,
    val guidance: String,
    val reason: String,
    @Required val needsRetake: Boolean = false,
    @Required val uncertainties: List<String> = emptyList(),
    @Required val revision: Int = 0,
    @kotlinx.serialization.Transient val resolvedSpec: ResolvedPhotoSpec? = null,
    @kotlinx.serialization.Transient val viewBinding: PhotoViewBinding? = null,
    @kotlinx.serialization.Transient val backgroundKeepRegions: List<CropRect> = emptyList(),
    @kotlinx.serialization.Transient val localCombination: Int? = null,
    @kotlinx.serialization.Transient val localAlternative: Boolean = false,
    @kotlinx.serialization.Transient val sceneDescription: String? = null
)
/** 模型仅表达摄影意图；坐标、裁剪和倍率由客户端映射并校验。 */
@Serializable data class CompositionIntent(
    val id: String,
    val title: String,
    val shot: ShotType,
    val zone: CompositionZone,
    val pose: PoseId,
    val facing: FacingDirection,
    val expression: ExpressionId,
    val guidance: String,
    val reason: String,
    @Required val expressionIntensity: Float = .65f,
    @Required val uncertainties: List<String> = emptyList(),
    /** 云端新协议提供细分姿势；旧协议遗漏时由 pose 兼容转换。 */
    val poseDirective: AvatarPoseDirective? = null,
    val subjectDistance: SubjectDistance = SubjectDistance.MID
)
@Serializable data class CompositionIntentDecision(
    val action: ActionKind,
    @Required val intents: List<CompositionIntent> = emptyList(),
    @Required val version: Int = 1,
    val imageId: String
)
@Serializable data class SceneSnapshot(
    val id: String,
    val width: Int,
    val height: Int,
    val currentZoom: Float,
    val imagePath: String,
    val modelImagePath: String,
    /** 相机冻结来源；普通单张与 AR 均据此找回背景，不进入模型网关协议。 */
    @kotlinx.serialization.Transient val captureBinding: PhotoViewBinding? = null,
    /** 仅供 App 把转正照片映回固定预览；不进入模型网关协议。 */
    @kotlinx.serialization.Transient val orientation: PhotoOrientation = PhotoOrientation()
)
@Serializable data class CameraCapabilities(
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val zoomOptions: List<Float> = listOf(1f)
)
@Serializable data class ModelSettings(
    val region: Region = Region.CN,
    val mode: ModelMode = ModelMode.OFFLINE,
    val preferred: ProviderId = ProviderId.QWEN,
    val gateway: String = CloudModelConfig.gatewayBaseUrl,
    val gatewayToken: String = "",
    val qwenAccess: QwenAccess = QwenAccess.OFFICIAL,
    val qwenBaseUrl: String = CloudModelConfig.qwenBaseUrl,
    val qwenModel: String = CloudModelConfig.qwenModel,
    @kotlinx.serialization.Transient val qwenApiKey: String = "",
    val seedAccess: SeedAccess = SeedAccess.OFFICIAL,
    @kotlinx.serialization.Transient val seedApiKey: String = "",
    val referenceSource: ReferenceSource = ReferenceSource.LEGACY_GLB,
    val seedreamModel: SeedreamModel = SeedreamModel.FLASH,
    val qwenImageModel: QwenImageModel = QwenImageModel.V21_TURBO,
    /** 兼容已保存的旧字段；实际生图接入始终复用 seedAccess。 */
    val imageAccess: SeedAccess = SeedAccess.OFFICIAL
) {
    val supportsImageGeneration: Boolean get() = preferred in listOf(ProviderId.SEED, ProviderId.QWEN)
    val imageGenerationModel: ImageGenerationModel get() = if (preferred == ProviderId.QWEN) qwenImageModel else seedreamModel
    val officialImageGeneration: Boolean get() = when (preferred) {
        ProviderId.QWEN -> qwenAccess == QwenAccess.OFFICIAL
        ProviderId.SEED -> seedAccess == SeedAccess.OFFICIAL
        else -> false
    }
    val imageGenerationKey: String get() = when (preferred) {
        ProviderId.QWEN -> qwenApiKey
        ProviderId.SEED -> seedApiKey
        else -> ""
    }

    /** 区域兼容旧协议；生图与视觉分析共用同一品牌的接入方式和密钥。 */
    fun normalizeCloudSelection(): ModelSettings = copy(
        region = if (preferred in listOf(ProviderId.GPT, ProviderId.GEMINI)) Region.GLOBAL else Region.CN,
        imageAccess = seedAccess
    )

    /** 兼容旧持久化字段；自动模式默认尝试云端，实际参考来源由本次任务路由决定。 */
    fun normalizeReferenceSource(): ModelSettings = copy(referenceSource = when (mode) {
        ModelMode.OFFLINE -> ReferenceSource.LEGACY_GLB
        ModelMode.CLOUD -> ReferenceSource.GENERATED_IMAGE
        ModelMode.AUTO -> ReferenceSource.GENERATED_IMAGE
    })
}
@Serializable data class ToolIssue(val code: String, val message: String, val planId: String? = null)

@Serializable data class ToolObservation(
    val tool: String,
    val messages: List<String>,
    val issues: List<ToolIssue> = emptyList(),
    val bounds: Map<String, CropRect> = emptyMap()
)
@Serializable data class DirectorDecision(
    val action: ActionKind,
    @Required val plans: List<CompositionPlan> = emptyList(),
    @Required val version: Int = 1,
    val imageId: String
)
@Serializable data class StepReply(
    val model: String,
    val raw: String,
    val decision: DirectorDecision? = null,
    val elapsedMs: Long = 0,
    @kotlinx.serialization.Transient val photoProtocol: Boolean = false,
    @kotlinx.serialization.Transient val sceneObservation: SceneObservation? = null
)
data class SceneObservation(val imageId: String, val text: String)
@Serializable data class GatewayCapability(
    val provider: ProviderId,
    val model: String,
    val configured: Boolean,
    val vision: Boolean = true,
    val outputMode: String = "prompt_json"
)
@Serializable data class GatewayCapabilities(val providers: List<GatewayCapability>)
@Serializable data class GatewayRequest(
    val provider: ProviderId,
    val requestId: String,
    val imageBase64: String,
    val prompt: String,
    val imageId: String,
    val version: Int = 1
)

data class DirectorInput(
    val scene: SceneSnapshot,
    val camera: CameraCapabilities,
    val instruction: String,
    val existing: List<CompositionPlan> = emptyList(),
    val selectedId: String? = null,
    /** 当前会话中用户明确选择的指导角色。 */
    val avatarId: AvatarId = AvatarId.ADULT_FEMALE,
    val viewBinding: PhotoViewBinding? = null,
    val backgroundRegions: List<CropRect> = emptyList(),
    val sessionBudget: com.framer.sense.feature.camera.vlm.scan.PhotoSessionBudget? = null,
    val photoReferences: Boolean = false,
    val ethnicity: AvatarEthnicity = AvatarEthnicity.WHITE,
    val arMode: Boolean = false
)
data class ModelCall(val input: DirectorInput, val prompt: String, val requestId: String, val legacyPrompt: String? = null)
data class CallTrace(val provider: ProviderId, val model: String, val elapsedMs: Long, val detail: String)
data class CompositionResult(
    val plans: List<CompositionPlan>,
    val traces: List<CallTrace>,
    val observations: List<ToolObservation>,
    val rawOutputs: List<String>
)

/** 新建用于隔离异步请求、图片或会话的唯一编号；无参数。 */
fun newVlmId(): String = UUID.randomUUID().toString()
