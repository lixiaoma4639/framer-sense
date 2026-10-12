package com.framer.sense.feature.camera.vlm.model

import kotlinx.serialization.Serializable

data class PhotographyActionCapability(val id: String, val expressions: List<ExpressionId>)

@Serializable enum class ReferenceDepthRelation { SAME, PERSON_NEARER, PERSON_FARTHER, UNKNOWN }
@Serializable data class PhotographyReference(
    val objectName: String, val region: CropRect,
    val heightMinMeters: Float, val heightMaxMeters: Float,
    val depthRelation: ReferenceDepthRelation, val evidence: String
)

/** 所有坐标都属于转正后的原始选中照片，不属于裁切后的卡片。 */
@Serializable data class PhotographyIntent(
    val id: String, val title: String, val shot: ShotType,
    val crop: CropRect, val cropReason: String,
    val head: Point2, val foot: Point2, val personRegion: CropRect,
    val actionId: String, val expression: ExpressionId, val expressionIntensity: Float,
    val yaw: Float, val reason: String, val guidance: String,
    val backgroundKeepRegions: List<CropRect>,
    val references: List<PhotographyReference>, val uncertainties: List<String>
)
@Serializable data class PhotographyDecision(
    val version: Int, val imageId: String, val sceneDescription: String,
    val plans: List<PhotographyIntent>
)

/** App 内保留模型目标；实际渲染和手动修改不能把原始坐标误当成卡片坐标。 */
data class PhotographyLayout(
    val version: Int = 3, val head: Point2?, val foot: Point2?, val personRegion: CropRect?,
    val cropReason: String, val references: List<PhotographyReference>,
    val layoutFallback: Boolean = false, val guidanceCurrent: Boolean = true
)

/** 人工拖动/缩放更新目标；渲染回传仍使用 withActualAvatar，不改写模型目标。 */
fun CompositionPlan.withEditedAvatar(value: AvatarPlacement): CompositionPlan {
    val originalFoot = PlanCoordinates.toOriginal(value.foot, crop)
    val originalHead = PlanCoordinates.toOriginal(Point2(value.foot.x, value.foot.y-value.height), crop)
    val changed = copy(avatar=value, spatialPlacement=null, photography=photography?.copy(
        head=originalHead, foot=originalFoot, personRegion=null, layoutFallback=false,guidanceCurrent=false))
    return changed.copy(resolvedSpec=resolvedSpec?.rebase(changed))
}

/** 找回背景时保持原图；没有扫描来源的单图参考无需等待扫描背景确认。 */
fun CompositionPlan.photographyDisplayCrop(backgroundConfirmed: Boolean): CropRect =
    if(photography != null && (viewBinding == null || backgroundConfirmed)) crop else CropRect()
