package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.*

/** 先确定真实站位，再裁切相机和背景；绝不按景别重新缩放身体。 */
object ArFraming {
    const val VERSION = 4
    private const val MIN_CROP_SPAN = .20f
    private const val HEAD_MARGIN = .06f

    fun crop(shot: ShotType, foot: Point2, height: Float, keep: List<CropRect>, horizontal: Float = .5f): CropRect? {
        val top = foot.y-height
        if (!height.isFinite() || height <= 0f || foot.x !in .02f.. .98f || top < 0f || foot.y < .1f) return null
        if (shot in listOf(ShotType.FULL,ShotType.ENVIRONMENT) && foot.y > 1f) return null
        if (shot == ShotType.ENVIRONMENT) return CropRect()
        val cut = when (shot) { ShotType.HALF -> .40f; ShotType.CLOSE_UP -> .68f; else -> 0f }
        val visible = height*(1f-cut)
        var span = (visible*(1f+2f*HEAD_MARGIN)).coerceIn(MIN_CROP_SPAN,1f)
        // 全身可适度扩展裁切以保留背景；半身/特写不能假称已执行未满足的景别。
        repeat(17) {
            val y = (top-visible*HEAD_MARGIN).coerceIn(0f,1f-span)
            val x = (foot.x-span*horizontal.coerceIn(.1f,.9f)).coerceIn(0f,1f-span)
            val crop = CropRect(x,y,x+span,y+span)
            val mappedFoot = PlanCoordinates.toPlan(foot,crop)
            val mappedHeight = height/span
            val topInFrame = mappedFoot.y-mappedHeight
            val cutRatio = (mappedFoot.y-1f)/mappedHeight
            val fits = mappedHeight in .08f..4f && mappedFoot.y in .1f..4f && topInFrame >= -.02f &&
                when (shot) {
                    ShotType.FULL -> mappedFoot.y <= 1f && topInFrame >= 0f
                    ShotType.HALF -> cutRatio in .20f.. .58f
                    ShotType.CLOSE_UP -> cutRatio in .58f.. .84f
                    else -> true
                }
            if (fits && keepsBackground(crop,keep)) return crop
            if (span == 1f) return null
            span = (span+.05f).coerceAtMost(1f)
        }
        return null
    }

    fun keepsBackground(crop: CropRect, regions: List<CropRect>): Boolean = regions.all { r ->
        val area = (r.right-r.left)*(r.bottom-r.top)
        val visible = max(0f,min(r.right,crop.right)-max(r.left,crop.left)) *
            max(0f,min(r.bottom,crop.bottom)-max(r.top,crop.top))
        area <= 0f || visible/area >= .5f
    }

    /** JPEG 全尺寸→预览画幅→方案裁切，背景和人像必须使用相同变换。 */
    fun combine(outer: CropRect, inner: CropRect): CropRect {
        val a=PlanCoordinates.toOriginal(Point2(inner.left,inner.top),outer)
        val b=PlanCoordinates.toOriginal(Point2(inner.right,inner.bottom),outer)
        return CropRect(a.x,a.y,b.x,b.y)
    }
    fun apply(plan: CompositionPlan, placement: ResolvedSpatialPlacement): CompositionPlan {
        val framed=plan.copy(crop=placement.framingCrop,zoom=1f,needsRetake=false)
        return framed.withSpatial(placement).withActualAvatar(framed.avatar.copy(
            foot=placement.projectedFoot,height=placement.projectedHeight,projectionCalibrated=true))
    }
}
