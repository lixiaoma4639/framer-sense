package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable

@Serializable data class SpatialPoint(val x: Float, val y: Float, val z: Float) {
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

/** 选中帧对应的相机；projection 已转换到裁切、转正后的冻结图片坐标。 */
@Serializable data class ArScaleSnapshot(
    val sessionId: String, val configVersion: Int, val timestampNs: Long,
    val view: List<Float>, val projection: List<Float>, val cameraPosition: SpatialPoint,
    val viewportWidth: Int, val viewportHeight: Int,
    val depth: List<ArDepthPoint> = emptyList(), val depthStatus: String = "NOT_CAPTURED",
    val features: List<ArFeaturePoint> = emptyList()
) {
    fun valid() = sessionId.isNotBlank() && viewportWidth > 0 && viewportHeight > 0 &&
        view.size == 16 && projection.size == 16 && (view + projection).all(Float::isFinite) && cameraPosition.finite()
}

/** 水平向上的已跟踪平面边界，世界坐标纯数值快照。 */
@Serializable data class ArDepthPoint(val image: Point2, val world: SpatialPoint, val confidence: Int)
@Serializable data class ArFeaturePoint(val world: SpatialPoint, val confidence: Float)
enum class ScaleEstimateMethod { AR_DEPTH, AR_FEATURES, MODEL_REGION, MODEL_LAYOUT, COMPOSITION_PRIOR, VLM_LAYOUT, LAYOUT_FALLBACK }
data class PhotoScaleEstimate(val method: ScaleEstimateMethod, val assumedOpticalDepthMeters: Float?,
    val evidenceCount: Int, val version: Int = 1)
data class ArGroundPlane(val id: String, val boundary: List<SpatialPoint>, val stableObservations: Int = 0)
data class ArGroundSnapshot(val sessionId: String, val configVersion: Int, val planes: List<ArGroundPlane>)
enum class SpatialScaleStatus { UNMEASURED, WAITING_GROUND, SOLVING, CALIBRATED, NEEDS_RELOCALIZATION, ESTIMATED }
enum class ScaleFailure { UNMEASURED, CAMERA_REQUIRED, GROUND_UNCERTAIN, DEPTH_MISSING, ASSET_MISSING, NO_PLACEMENT, SESSION_CHANGED }

/** 姿势和表情不修改参考身高；原资源脚底/头顶在参考姿态世界坐标中测量。 */
@Serializable data class AvatarScaleMetadata(
    val assetPath: String, val assetVersion: Int, val version: Int,
    val foot: SpatialPoint, val crown: SpatialPoint,
    val forward: SpatialPoint = SpatialPoint(0f, 0f, 1f), val metersPerUnit: Float = 1f,
    val source: String = ""
) {
    val referenceHeight: Float get() = crown.y - foot.y
    fun valid() = assetPath.isNotBlank() && assetVersion > 0 && version > 0 && foot.finite() && crown.finite() &&
        referenceHeight > .001f && metersPerUnit.isFinite() && metersPerUnit > 0f &&
        forward == SpatialPoint(0f, 0f, 1f)
}

data class ResolvedSpatialPlacement(
    val camera: ArScaleSnapshot, val groundId: String, val position: SpatialPoint,
    val yaw: Float, val heightMeters: Float, val metadata: AvatarScaleMetadata,
    val projectedFoot: Point2, val projectedHeight: Float, val distanceMeters: Float,
    val manual: Boolean = false,
    val framingCrop: CropRect = CropRect(), val framingVersion: Int = 4,
    val estimated: Boolean = false
) {
    fun matches(plan: CompositionPlan): Boolean = camera.valid() && camera == plan.arCamera &&
        camera.sessionId == plan.viewBinding?.arSessionId && camera.configVersion == plan.viewBinding?.configVersion &&
        heightMeters == plan.subjectHeightMeters && framingCrop == plan.crop && metadata.valid() && position.finite() && yaw.isFinite() &&
        metadata.assetPath == plan.resolvedSpec?.action?.avatarAssetPath &&
        metadata.assetVersion == plan.resolvedSpec?.action?.avatarAssetVersion
}

object AvatarHeightPolicy {
    fun defaultCm(role: AvatarId): Int = when (role) {
        AvatarId.ADULT_FEMALE -> 165
        AvatarId.ADULT_MALE -> 175
        else -> 120
    }
    fun range(role: AvatarId): IntRange = if (role in listOf(AvatarId.ADULT_FEMALE, AvatarId.ADULT_MALE)) 120..220 else 60..180
    fun clamp(role: AvatarId, value: Int) = value.coerceIn(range(role))
}

fun CompositionPlan.withSpatial(value: ResolvedSpatialPlacement?): CompositionPlan {
    val changed = if (value == null) copy(spatialPlacement = null,
        scaleEstimate = null,
        avatar = avatar.copy(height = 0f, projectionCalibrated = false),
        crop = if (arCamera != null && photography == null) CropRect() else crop,
        zoom = if (arCamera != null && photography == null) 1f else zoom,
        needsRetake = if (arCamera != null && photography == null) false else needsRetake) else copy(spatialPlacement = value,
            scaleEstimate = if (value.estimated) scaleEstimate else null, scaleFailure = null)
    return changed.copy(resolvedSpec = resolvedSpec?.rebase(changed))
}

fun CompositionPlan.hasMeasuredScale() = spatialPlacement?.let { !it.estimated && it.framingVersion == 4 && it.matches(this) } == true
fun CompositionPlan.hasRenderableScale(): Boolean = avatar.height.isFinite() && avatar.height in .08f..4f &&
    (hasMeasuredScale() || scaleEstimate != null && (spatialPlacement == null || spatialPlacement.matches(this)))
fun CompositionPlan.withScaleFailure(reason: ScaleFailure): CompositionPlan =
    copy(scaleFailure = reason).withSpatial(null)
