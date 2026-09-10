package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.Json
import java.util.UUID

/** 公共序列化配置；拒绝非有限数值，不忽略未知业务字段。 */
val VlmJson = Json { encodeDefaults = true; explicitNulls = true }

@Serializable enum class Region { CN, GLOBAL }
@Serializable enum class ModelMode { OFFLINE, CLOUD, AUTO }
@Serializable enum class ProviderId { QWEN, SEED, GPT, GEMINI, LOCAL }
@Serializable enum class ShotType { ENVIRONMENT, FULL, HALF, CLOSE_UP }
@Serializable enum class PoseId {
    RELAXED, SIDE, LOOK_BACK, HANDS_FRONT, HAND_HIP, HANDS_HIPS,
    WAVE, POINT, HAT, LOOK_UP, LOOK_DOWN, ARMS_OPEN
}
@Serializable enum class ExpressionId { NEUTRAL, SMILE, HAPPY, SURPRISED }
@Serializable enum class ActionKind { CAPABILITIES, VALIDATE, RENDER, FINAL }

@Serializable data class Point2(val x: Float, val y: Float)
@Serializable data class CropRect(
    @Required val left: Float = 0f, @Required val top: Float = 0f,
    @Required val right: Float = 1f, @Required val bottom: Float = 1f
)
@Serializable data class AvatarPlacement(
    @Required val foot: Point2 = Point2(0.5f, 0.92f),
    @Required val height: Float = 0.65f,
    @Required val yaw: Float = 0f,
    @Required val pose: PoseId = PoseId.RELAXED,
    @Required val expression: ExpressionId = ExpressionId.NEUTRAL,
    @Required val expressionIntensity: Float = 0.6f
)
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
    @Required val revision: Int = 0
)
@Serializable data class SceneSnapshot(
    val id: String,
    val width: Int,
    val height: Int,
    val currentZoom: Float,
    val imagePath: String,
    val modelImagePath: String
)
@Serializable data class CameraCapabilities(
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val zoomOptions: List<Float> = listOf(1f)
)
@Serializable data class ModelSettings(
    val region: Region = Region.CN,
    val mode: ModelMode = ModelMode.AUTO,
    val preferred: ProviderId = ProviderId.QWEN,
    val gateway: String = "http://10.0.2.2:8000",
    val gatewayToken: String = ""
)
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
    val elapsedMs: Long = 0
)
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
    val selectedId: String? = null
)
data class ModelCall(val input: DirectorInput, val prompt: String, val requestId: String)
data class CallTrace(val provider: ProviderId, val model: String, val elapsedMs: Long, val detail: String)
data class CompositionResult(
    val plans: List<CompositionPlan>,
    val traces: List<CallTrace>,
    val observations: List<ToolObservation>,
    val rawOutputs: List<String>
)

/** 新建用于隔离异步请求、图片或会话的唯一编号；无参数。 */
fun newVlmId(): String = UUID.randomUUID().toString()
