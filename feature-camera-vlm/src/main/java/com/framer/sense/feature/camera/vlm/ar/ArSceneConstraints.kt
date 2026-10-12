package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.*

/** 保守的测量约束：不推断门框尺寸、不用固定屏幕占比代替深度。 */
object ArSceneConstraints {
    const val MIN_CONFIDENCE = 160
    const val MIN_SUPPORT = 3
    fun floorProblem(camera: ArScaleSnapshot, plane: ArGroundPlane, manual: Boolean = false): String? {
        if (plane.boundary.size < 3 || plane.boundary.any { !it.finite() }) return "GROUND_POLYGON_INVALID"
        if (plane.stableObservations < 3) return "GROUND_NOT_STABLE"
        val above=camera.cameraPosition.y-plane.boundary.first().y
        if (above !in (if (manual) .4f..2.8f else .8f..2.4f)) return "CAMERA_GROUND_HEIGHT_LIMIT"
        val area=abs(plane.boundary.indices.sumOf { i ->
            val a=plane.boundary[i]; val b=plane.boundary[(i+1)%plane.boundary.size]
            (a.x*b.z-b.x*a.z).toDouble()
        })*.5
        return if (area >= .25) null else "GROUND_TOO_SMALL"
    }
    fun reliableFloor(camera: ArScaleSnapshot, plane: ArGroundPlane, manual: Boolean = false) = floorProblem(camera,plane,manual) == null
    fun points(camera: ArScaleSnapshot) = camera.depth.filter {
        it.confidence >= MIN_CONFIDENCE && it.world.finite() && it.image.x in 0f..1f && it.image.y in 0f..1f
    }
    fun opticalDepth(camera: ArScaleSnapshot, point: SpatialPoint): Float =
        -SpatialMatrices.transform(camera.view,listOf(point.x,point.y,point.z,1f))[2]
    data class MeasuredPoint(val sample: ArDepthPoint, val opticalDepth: Float)
    data class Evidence(val points: List<MeasuredPoint>, val backgroundDepth: Float?)
    fun evidence(plan: CompositionPlan, camera: ArScaleSnapshot): Evidence {
        if (!camera.valid()) return Evidence(emptyList(),null)
        val measured=points(camera).map { MeasuredPoint(it,opticalDepth(camera,it.world)) }
        val ranges=measured.filter { value ->
            val d=value.sample
            if (plan.backgroundKeepRegions.isEmpty()) d.image.y in .1f.. .7f
            else plan.backgroundKeepRegions.any { r -> d.image.x in r.left..r.right && d.image.y in r.top..r.bottom }
        }.map { it.opticalDepth }.filter { it in ArScaleSolver.MIN_DISTANCE..ArScaleSolver.MAX_DISTANCE }.sorted()
        return Evidence(measured,if (ranges.size >= MIN_SUPPORT) ranges[ranges.size/2] else null)
    }
    fun backgroundDepth(plan: CompositionPlan, camera: ArScaleSnapshot) = evidence(plan,camera).backgroundDepth

    fun allows(camera: ArScaleSnapshot, plane: ArGroundPlane, position: SpatialPoint,
               foot: Point2, head: Point2, manual: Boolean, evidence: Evidence): Boolean {
        if (!reliableFloor(camera,plane,manual)) return false
        val z=opticalDepth(camera,position)
        // 已稳定跟踪的平面具有米制空间坐标，不依赖深度图才能确定实体身高。
        // 近处人物配远景是合法摄影关系，背景深度只参与择优，不能强制同深度。
        val halfWidth=abs(foot.y-head.y)*.135f
        val foreground=evidence.points.count { value ->
            val d=value.sample
            d.image.x in foot.x-halfWidth..foot.x+halfWidth &&
                d.image.y in head.y+(foot.y-head.y)*.12f..foot.y-(foot.y-head.y)*.1f &&
                d.world.y > plane.boundary.first().y+.15f && value.opticalDepth < z-.3f
        }
        return foreground < MIN_SUPPORT
    }
}
