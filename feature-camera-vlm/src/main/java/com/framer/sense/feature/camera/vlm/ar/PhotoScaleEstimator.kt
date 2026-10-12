package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.*

/** 环境尺度的尽力估算。无地面也可执行；全部估算结果保留来源，不能标为实测站位。 */
object PhotoScaleEstimator {
    fun estimate(plan: CompositionPlan, metadata: AvatarScaleMetadata? = null): CompositionPlan {
        if (plan.hasMeasuredScale()) return plan
        if(plan.photography != null && !plan.photography.layoutFallback) {
            val layout=plan.photography
            val head=layout.head?.let { PlanCoordinates.toPlan(it,plan.crop) }
            val foot=layout.foot?.let { PlanCoordinates.toPlan(it,plan.crop) }
            if(head != null && foot != null && foot.y-head.y in .08f..4f) {
                val placement=metadata?.let { PhotographyPlacementSolver.estimate(plan,it) }
                val base=plan.copy(scaleEstimate=PhotoScaleEstimate(ScaleEstimateMethod.VLM_LAYOUT,
                    placement?.let { ArSceneConstraints.opticalDepth(it.camera,it.position) },layout.references.size),spatialPlacement=null)
                    .withActualAvatar(plan.avatar.copy(foot=foot,height=foot.y-head.y,projectionCalibrated=true))
                return if(placement != null) ArFraming.apply(base,placement) else base
            }
        }
        val desired = plan.arDesiredAvatar ?: plan.avatar
        val camera = plan.arCamera?.takeIf { it.valid() }
        val height = plan.subjectHeightMeters.takeIf { it.isFinite() && it in .6f..2.2f }
            ?: AvatarHeightPolicy.defaultCm(plan.avatar.avatarId) / 100f
        val x = desired.foot.x.takeIf { it.isFinite() }?.coerceIn(.15f,.85f) ?: .5f
        val region = plan.arPersonRegions.filter(::validRegion).minByOrNull { abs((it.left+it.right)/2-x) }
        data class Evidence(val point: Point2, val z: Float)
        val depth = camera?.depth.orEmpty().mapNotNull { sample ->
            if (sample.confidence < 160 || !sample.world.finite()) null else camera?.let { c ->
                val z = ArSceneConstraints.opticalDepth(c,sample.world)
                if (z in .4f..30f && sample.image.x.isFinite() && sample.image.y.isFinite()) Evidence(sample.image,z) else null
            }
        }
        val features = camera?.features.orEmpty().mapNotNull { sample ->
            if (!sample.world.finite() || !sample.confidence.isFinite() || sample.confidence < .5f) null else camera?.let { c ->
                val z = ArSceneConstraints.opticalDepth(c,sample.world)
                SpatialMatrices.project(c,sample.world,CropRect())?.takeIf { it.x in 0f..1f && it.y in 0f..1f && z in .4f..30f }
                    ?.let { Evidence(it,z) }
            }
        }
        fun nearPerson(points: List<Evidence>): List<Evidence> {
            val local = points.filter { p -> abs(p.point.x-x) <= .22f &&
                (region == null || p.point.y in region.top..region.bottom) }
            return if (local.size >= 3) local else points
        }
        val measured = nearPerson(depth).takeIf { it.size >= 3 } ?: nearPerson(features).takeIf { it.size >= 3 }
        val method = when {
            depth.size >= 3 -> ScaleEstimateMethod.AR_DEPTH
            features.size >= 3 -> ScaleEstimateMethod.AR_FEATURES
            region != null -> ScaleEstimateMethod.MODEL_REGION
            desired.height.isFinite() && desired.height > .08f -> ScaleEstimateMethod.MODEL_LAYOUT
            else -> ScaleEstimateMethod.COMPOSITION_PRIOR
        }
        // 深度描述环境表面，并不证明人物就站在该表面；取邻域中值并略向相机前移，仍标为估算。
        val distancePrior = when (plan.shot) {
            ShotType.ENVIRONMENT -> 6f; ShotType.FULL -> 3.5f
            ShotType.HALF -> 2.2f; ShotType.CLOSE_UP -> 1.4f
        } * when (desired.subjectDistance) { SubjectDistance.FAR -> 1.4f; SubjectDistance.NEAR -> .8f; else -> 1f }
        val focal = camera?.projection?.get(5)?.let(::abs)?.takeIf { it.isFinite() && it > .01f } ?: (1.732f * (plan.zoom.takeIf { it.isFinite() && it > 0f } ?: 1f).coerceIn(.1f,100f))
        val regionSpan = region?.let { (it.bottom-it.top) / visibleFraction(plan.shot) }
        val z = (measured?.map { it.z }?.sorted()?.let { it[it.size/2]*.85f }
            ?: regionSpan?.let { height*focal/(2*it) } ?: distancePrior).coerceIn(.6f,15f)
        val record = PhotoScaleEstimate(method,z,measured?.size ?: 0)
        if (plan.photography == null && camera != null && metadata?.valid() == true &&
            metadata.assetPath == plan.resolvedSpec?.action?.avatarAssetPath &&
            metadata.assetVersion == plan.resolvedSpec?.action?.avatarAssetVersion &&
            plan.viewBinding?.arSessionId == camera.sessionId && plan.viewBinding?.configVersion == camera.configVersion) {
            val foot = Point2(x,region?.bottom?.coerceIn(.5f,.96f) ?: .93f)
            // 允许在没有平面时建立估算空间位置；若景别/背景不可同时满足则转为明确标记的屏幕参考。
            for (distance in listOf(z,z*1.5f,z*2f).map { it.coerceIn(.6f,15f) }.distinct()) {
                val position = unproject(camera,foot,distance) ?: continue
                val head = SpatialMatrices.project(camera,position.copy(y=position.y+height),CropRect()) ?: continue
                val span = foot.y-head.y
                val crop = ArFraming.crop(plan.shot,foot,span,plan.backgroundKeepRegions,x) ?: continue
                if (span/(crop.bottom-crop.top) !in .08f..4f) continue
                val yaw = Math.toDegrees(atan2((camera.cameraPosition.x-position.x).toDouble(),
                    (camera.cameraPosition.z-position.z).toDouble())).toFloat() + desired.yaw
                val placement = ResolvedSpatialPlacement(camera,"estimate:${method.name}",position,yaw,height,metadata,
                    PlanCoordinates.toPlan(foot,crop),span/(crop.bottom-crop.top),
                    sqrt((position.x-camera.cameraPosition.x).pow(2)+(position.y-camera.cameraPosition.y).pow(2)+(position.z-camera.cameraPosition.z).pow(2)),
                    framingCrop=crop,estimated=true)
                val estimated = ArFraming.apply(plan,placement).copy(scaleEstimate=record.copy(assumedOpticalDepthMeters=distance),arDesiredAvatar=desired)
                return estimated.withActualAvatar(estimated.avatar)
            }
        }
        // 屏幕估算同样综合人物区域、环境距离、景别及背景保护区；不再将失败处理成零高度。
        val fullSpan = regionSpan ?: desired.height.takeIf { it.isFinite() && it > .08f } ?: height*focal/(2*z)
        val cut = 1f-visibleFraction(plan.shot)
        val span = if (cut == 0f) fullSpan.coerceIn(.12f,.86f)
            else fullSpan.coerceIn(.35f/(1-cut), .92f/(1-cut))
        val y = if (cut == 0f) (region?.bottom ?: .93f).coerceIn(span+.02f,.98f) else 1f+cut*span
        fun overlap(px: Float): Float = plan.backgroundKeepRegions.filter(::validRegion).sumOf { r ->
            (max(0f,min(r.right,px+span*.135f)-max(r.left,px-span*.135f)) *
                max(0f,min(r.bottom,min(y,1f))-max(r.top,y-span))).toDouble()
        }.toFloat()
        val placedX = listOf(x,.25f,.5f,.75f).minByOrNull { 2f*abs(it-x)+3f*overlap(it) } ?: x
        val layout = plan.avatar.copy(foot=Point2(placedX,y),height=span,projectionCalibrated=true)
        val estimated = plan.copy(spatialPlacement=null,avatar=layout,subjectHeightMeters=height,arDesiredAvatar=desired,
            crop=if (camera != null && plan.photography == null) CropRect() else plan.crop,
            zoom=if (camera != null && plan.photography == null) 1f else plan.zoom, needsRetake=if (camera != null && plan.photography == null) false else plan.needsRetake,
            scaleEstimate=if(plan.photography != null) record.copy(method=ScaleEstimateMethod.LAYOUT_FALLBACK) else record)
        return estimated.withActualAvatar(layout)
    }

    private fun validRegion(r: CropRect) = listOf(r.left,r.top,r.right,r.bottom).all(Float::isFinite) &&
        r.left in 0f..1f && r.top in 0f..1f && r.right in 0f..1f && r.bottom in 0f..1f && r.right > r.left && r.bottom > r.top

    private fun visibleFraction(shot: ShotType) = when (shot) {
        ShotType.HALF -> .6f; ShotType.CLOSE_UP -> .3f; else -> 1f
    }

    private fun unproject(camera: ArScaleSnapshot, point: Point2, z: Float): SpatialPoint? {
        val inverse = SpatialMatrices.inverse(SpatialMatrices.multiply(camera.projection,camera.view)) ?: return null
        fun at(ndcZ: Float): SpatialPoint? {
            val v = SpatialMatrices.transform(inverse,listOf(point.x*2-1,1-point.y*2,ndcZ,1f))
            if (abs(v[3]) < .00001f) return null
            return SpatialPoint(v[0]/v[3],v[1]/v[3],v[2]/v[3]).takeIf { it.finite() }
        }
        val a = at(-1f) ?: return null; val b = at(1f) ?: return null
        val za = ArSceneConstraints.opticalDepth(camera,a); val zb = ArSceneConstraints.opticalDepth(camera,b)
        if (abs(zb-za) < .00001f) return null
        val t = (z-za)/(zb-za)
        return SpatialPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t).takeIf { it.finite() }
    }
}
