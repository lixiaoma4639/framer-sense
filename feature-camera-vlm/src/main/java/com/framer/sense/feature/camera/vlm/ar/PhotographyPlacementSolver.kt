package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.*

/** 身高不变、背景不变，以模型的头脚投影为目标求世界位置；无需地面。 */
object PhotographyPlacementSolver {
    const val MAX_TARGET_ERROR = .05f
    fun targetError(plan: CompositionPlan, camera: ArScaleSnapshot, position: SpatialPoint): Float {
        val layout=plan.photography ?: return Float.POSITIVE_INFINITY
        val targetFoot=layout.foot?.let { PlanCoordinates.toPlan(it,plan.crop) } ?: return Float.POSITIVE_INFINITY
        val targetHead=layout.head?.let { PlanCoordinates.toPlan(it,plan.crop) } ?: return Float.POSITIVE_INFINITY
        val foot=SpatialMatrices.project(camera,position,plan.crop) ?: return Float.POSITIVE_INFINITY
        val head=SpatialMatrices.project(camera,position.copy(y=position.y+plan.subjectHeightMeters),plan.crop) ?: return Float.POSITIVE_INFINITY
        return maxOf(abs(foot.x-targetFoot.x),abs(foot.y-targetFoot.y),abs(head.x-targetHead.x),abs(head.y-targetHead.y))
    }

    fun estimate(plan: CompositionPlan, metadata: AvatarScaleMetadata): ResolvedSpatialPlacement? {
        val camera=plan.arCamera?.takeIf { it.valid() } ?: return null
        val layout=plan.photography ?: return null
        val foot=layout.foot ?: return null; val head=layout.head ?: return null
        if(!metadata.valid() || metadata.assetPath != plan.resolvedSpec?.action?.avatarAssetPath ||
            metadata.assetVersion != plan.resolvedSpec?.action?.avatarAssetVersion ||
            camera.sessionId != plan.viewBinding?.arSessionId || camera.configVersion != plan.viewBinding?.configVersion) return null
        val inverse=SpatialMatrices.inverse(SpatialMatrices.multiply(camera.projection,camera.view)) ?: return null
        fun world(ndcZ: Float): SpatialPoint? {
            val v=SpatialMatrices.transform(inverse,listOf(foot.x*2-1,1-foot.y*2,ndcZ,1f))
            if(abs(v[3]) < .00001f) return null
            return SpatialPoint(v[0]/v[3],v[1]/v[3],v[2]/v[3]).takeIf { it.finite() }
        }
        val a=world(-1f) ?: return null; val b=world(1f) ?: return null
        val za=ArSceneConstraints.opticalDepth(camera,a); val zb=ArSceneConstraints.opticalDepth(camera,b)
        if(abs(zb-za) < .00001f) return null
        fun position(z: Float): SpatialPoint {
            val t=(z-za)/(zb-za)
            return SpatialPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t)
        }
        fun span(z: Float): Float? = SpatialMatrices.project(camera,position(z).let { it.copy(y=it.y+plan.subjectHeightMeters) },CropRect())?.let { foot.y-it.y }
        val target=foot.y-head.y
        var low=.4f; var high=15f
        val first=span(low) ?: return null; val last=span(high) ?: return null
        if(target !in min(first,last)..max(first,last)) return null
        repeat(40) {
            val mid=(low+high)/2f; val value=span(mid) ?: return null
            if((value > target) == (first > last)) low=mid else high=mid
        }
        val p=position((low+high)/2f)
        if(targetError(plan,camera,p) > MAX_TARGET_ERROR) return null
        val actualFoot=SpatialMatrices.project(camera,p,plan.crop) ?: return null
        val actualHead=SpatialMatrices.project(camera,p.copy(y=p.y+plan.subjectHeightMeters),plan.crop) ?: return null
        val c=camera.cameraPosition
        val distance=sqrt((p.x-c.x).pow(2)+(p.y-c.y).pow(2)+(p.z-c.z).pow(2))
        if(distance !in .4f..15f) return null
        val yaw=Math.toDegrees(atan2((c.x-p.x).toDouble(),(c.z-p.z).toDouble())).toFloat()+plan.avatar.yaw
        return ResolvedSpatialPlacement(camera,"estimate:VLM_LAYOUT",p,yaw,plan.subjectHeightMeters,metadata,
            actualFoot,actualFoot.y-actualHead.y,distance,framingCrop=plan.crop,estimated=true)
    }
}
