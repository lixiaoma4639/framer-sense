package com.framer.sense.feature.camera.vlm.ar

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.*

/** 列主序纯数学，可在 JVM 验证；不读取 ARCore/Android 对象。 */
object SpatialMatrices {
    fun identity(): List<Float> = List(16) { if (it % 5 == 0) 1f else 0f }
    fun multiply(a: List<Float>, b: List<Float>): List<Float> = List(16) { i ->
        val row = i % 4; val col = i / 4
        (0..3).sumOf { k -> (a[k * 4 + row] * b[col * 4 + k]).toDouble() }.toFloat()
    }
    fun transform(m: List<Float>, v: List<Float>): List<Float> = List(4) { row ->
        (0..3).sumOf { k -> (m[k * 4 + row] * v[k]).toDouble() }.toFloat()
    }
    fun inverse(m: List<Float>): List<Float>? {
        if (m.size != 16 || m.any { !it.isFinite() }) return null
        val rows = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) m[c * 4 + r].toDouble() else if (c - 4 == r) 1.0 else 0.0 } }
        for (c in 0..3) {
            val pivot = (c..3).maxByOrNull { abs(rows[it][c]) } ?: return null
            if (abs(rows[pivot][c]) < 1e-10) return null
            val swap = rows[c]; rows[c] = rows[pivot]; rows[pivot] = swap
            val divisor = rows[c][c]; for (j in 0..7) rows[c][j] /= divisor
            for (r in 0..3) if (r != c) { val f = rows[r][c]; for (j in 0..7) rows[r][j] -= f * rows[c][j] }
        }
        return List(16) { rows[it % 4][it / 4 + 4].toFloat() }.takeIf { it.all(Float::isFinite) }
    }
    /** VIEW 左上/右上/左下映射到冻结图左上坐标，包含旋转、镜像及裁切。 */
    fun imageProjection(projection: List<Float>, corners: List<Point2>): List<Float> {
        require(corners.size == 3)
        val o = corners[0]; val ax = corners[1].x-o.x; val bx = corners[2].x-o.x
        val ay = corners[1].y-o.y; val by = corners[2].y-o.y
        val m = identity().toMutableList()
        m[0] = ax; m[4] = -bx; m[12] = ax+bx+2f*o.x-1f
        m[1] = -ay; m[5] = by; m[13] = 1f-ay-by-2f*o.y
        return multiply(m,projection)
    }
    fun cropProjection(projection: List<Float>, crop: CropRect): List<Float> {
        val x = crop.right - crop.left; val y = crop.bottom - crop.top
        require(x > 0f && y > 0f)
        val adjust = identity().toMutableList()
        adjust[0] = 1f / x; adjust[5] = 1f / y
        adjust[12] = -(crop.left + crop.right - 1f) / x
        adjust[13] = -(1f - crop.top - crop.bottom) / y
        return multiply(adjust, projection)
    }
    fun model(p: SpatialPoint, yaw: Float): List<Float> {
        val a = Math.toRadians(yaw.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return listOf(c,0f,-s,0f, 0f,1f,0f,0f, s,0f,c,0f, p.x,p.y,p.z,1f)
    }
    fun project(camera: ArScaleSnapshot, p: SpatialPoint, crop: CropRect): Point2? {
        return project(multiply(cropProjection(camera.projection, crop), camera.view),p)
    }
    fun project(matrix: List<Float>, p: SpatialPoint): Point2? {
        val clip = transform(matrix, listOf(p.x,p.y,p.z,1f))
        if (clip.any { !it.isFinite() } || clip[3] <= .0001f || clip[2] < -clip[3] || clip[2] > clip[3]) return null
        return Point2((clip[0]/clip[3]+1f)/2f, (1f-clip[1]/clip[3])/2f)
    }
}

data class ArSolveDiagnostics(val attempted: Int, val eligiblePlanes: Int, val depthPoints: Int,
    val rejected: Map<String, Int>, val solved: Boolean)

object ArScaleSolver {
    const val GRID_METERS = .25f
    const val MIN_DISTANCE = .4f
    const val MAX_DISTANCE = 15f
    private const val MAX_CANDIDATES = 16000
    fun compatible(plan: CompositionPlan, camera: ArScaleSnapshot, ground: ArGroundSnapshot): Boolean =
        camera.valid() && camera.sessionId == ground.sessionId && camera.configVersion == ground.configVersion &&
            plan.viewBinding?.arSessionId == camera.sessionId && plan.viewBinding?.configVersion == camera.configVersion

    fun contains(plane: ArGroundPlane, p: SpatialPoint): Boolean {
        val polygon = plane.boundary
        if (polygon.size < 3 || polygon.any { !it.finite() } || !p.finite() || abs(p.y - polygon.first().y) > .05f) return false
        var inside = false; var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if ((a.z > p.z) != (b.z > p.z) && p.x < (b.x-a.x)*(p.z-a.z)/(b.z-a.z)+a.x) inside = !inside
            j = i
        }
        return inside
    }
    fun rayGround(camera: ArScaleSnapshot, point: Point2, plane: ArGroundPlane, crop: CropRect): SpatialPoint? {
        val inverse = SpatialMatrices.inverse(SpatialMatrices.multiply(SpatialMatrices.cropProjection(camera.projection,crop),camera.view)) ?: return null
        fun world(z: Float): SpatialPoint? {
            val v = SpatialMatrices.transform(inverse, listOf(point.x*2f-1f,1f-point.y*2f,z,1f))
            if (abs(v[3]) < .00001f) return null
            return SpatialPoint(v[0]/v[3],v[1]/v[3],v[2]/v[3])
        }
        val a = world(-1f) ?: return null; val b = world(1f) ?: return null
        if (abs(b.y-a.y) < .0001f) return null
        val y = plane.boundary.firstOrNull()?.y ?: return null
        val t = (y-a.y)/(b.y-a.y)
        if (t < 0f || t > 1f) return null
        return SpatialPoint(a.x+(b.x-a.x)*t,y,a.z+(b.z-a.z)*t).takeIf { contains(plane,it) }
    }
    /** 只用原图相机计算实体投影；画面模板的 height/crop 不参与站位计算。 */
    fun at(plan: CompositionPlan, camera: ArScaleSnapshot, plane: ArGroundPlane, position: SpatialPoint,
           metadata: AvatarScaleMetadata, manual: Boolean = false,
           projection: List<Float> = if (camera.valid()) SpatialMatrices.multiply(camera.projection,camera.view) else emptyList(),
           evidence: ArSceneConstraints.Evidence = ArSceneConstraints.evidence(plan,camera),
           onReject: (String) -> Unit = {}): ResolvedSpatialPlacement? {
        fun rejected(reason: String): ResolvedSpatialPlacement? { onReject(reason); return null }
        if (!camera.valid()) return rejected("CAMERA_INVALID")
        if (!metadata.valid()) return rejected("ASSET_SCALE_INVALID")
        if (!contains(plane,position)) return rejected("OUTSIDE_GROUND")
        val action = plan.resolvedSpec?.action ?: return rejected("ACTION_MISSING")
        if (metadata.assetPath != action.avatarAssetPath || metadata.assetVersion != action.avatarAssetVersion) return rejected("ASSET_VERSION_MISMATCH")
        val height = plan.subjectHeightMeters
        if (!height.isFinite() || height !in .6f..2.2f) return rejected("HEIGHT_INVALID")
        val c = camera.cameraPosition
        val distance = sqrt((position.x-c.x).pow(2)+(position.y-c.y).pow(2)+(position.z-c.z).pow(2))
        if (distance !in MIN_DISTANCE..MAX_DISTANCE) return rejected("DISTANCE_LIMIT")
        val foot = SpatialMatrices.project(projection,position) ?: return rejected("BEHIND_CAMERA")
        val crown = SpatialMatrices.project(projection,position.copy(y=position.y+height)) ?: return rejected("HEAD_PROJECTION_INVALID")
        ArSceneConstraints.floorProblem(camera,plane,manual)?.let { return rejected(it) }
        if (!ArSceneConstraints.allows(camera,plane,position,foot,crown,manual,evidence)) return rejected("FOREGROUND_OBSTRUCTION")
        val span = foot.y-crown.y
        val crop = if(plan.photography != null) {
            if(PhotographyPlacementSolver.targetError(plan,camera,position) > PhotographyPlacementSolver.MAX_TARGET_ERROR)
                return rejected("VLM_TARGET_MISMATCH")
            plan.crop
        } else ArFraming.crop(plan.shot,foot,span,plan.backgroundKeepRegions,(plan.arDesiredAvatar ?: plan.avatar).foot.x) ?: return rejected("SHOT_OR_BACKGROUND_FRAMING")
        val mappedFoot = PlanCoordinates.toPlan(foot,crop)
        val yaw = Math.toDegrees(atan2((c.x-position.x).toDouble(),(c.z-position.z).toDouble())).toFloat()+plan.avatar.yaw
        return ResolvedSpatialPlacement(camera,plane.id,position,yaw,height,metadata,mappedFoot,span/(crop.bottom-crop.top),distance,manual,crop,ArFraming.VERSION)
    }
    fun solve(plan: CompositionPlan, camera: ArScaleSnapshot, ground: ArGroundSnapshot,
              metadata: AvatarScaleMetadata, checkCancelled: () -> Unit = {},
              onDiagnostics: (ArSolveDiagnostics) -> Unit = {}): ResolvedSpatialPlacement? {
        var best: ResolvedSpatialPlacement? = null; var bestScore = Float.POSITIVE_INFINITY; var evaluated = 0
        var eligible = 0
        val rejected=mutableMapOf<String,Int>()
        fun reject(reason: String) { rejected[reason]=(rejected[reason] ?: 0)+1 }
        fun finish(): ResolvedSpatialPlacement? {
            onDiagnostics(ArSolveDiagnostics(evaluated,eligible,camera.depth.size,rejected.toMap(),best != null))
            return best
        }
        if (!compatible(plan,camera,ground)) { reject("SESSION_OR_CAMERA_INVALID"); return finish() }
        val targetX = plan.arGroundTarget?.x ?: plan.photography?.foot?.x ?: (plan.arDesiredAvatar ?: plan.avatar).foot.x
        val personRegion=plan.arPersonRegions.firstOrNull { targetX in it.left..it.right }
        val target = plan.arGroundTarget ?: plan.photography?.foot ?: personRegion?.let { Point2(targetX,it.bottom) }
        val evidence=ArSceneConstraints.evidence(plan,camera)
        val backgroundDepth=evidence.backgroundDepth
        val projection = SpatialMatrices.multiply(camera.projection,camera.view)
        // 自动站位优先使用相机下方最低的已知平面，避免为了凑模板把人物放到桌面上。
        val below = ground.planes.filter { ArSceneConstraints.reliableFloor(camera,it) }
        val lowest = below.minOfOrNull { it.boundary.first().y } ?: run {
            ground.planes.forEach { ArSceneConstraints.floorProblem(camera,it)?.let(::reject) }
            reject("NO_RELIABLE_GROUND"); return finish()
        }
        val planes = below.filter { it.boundary.first().y <= lowest+.15f }
        eligible=planes.size
        fun consider(plane: ArGroundPlane, p: SpatialPoint) {
            if (++evaluated > MAX_CANDIDATES) return
            if (evaluated % 128 == 0) checkCancelled()
            val v = at(plan,camera,plane,p,metadata,projection=projection,evidence=evidence,onReject=::reject) ?: return
            val rawFoot = SpatialMatrices.project(projection,p) ?: return
            val rawCrown = SpatialMatrices.project(projection,p.copy(y=p.y+plan.subjectHeightMeters)) ?: return
            val width = (rawFoot.y-rawCrown.y)*.27f
            val overlap = plan.backgroundKeepRegions.sumOf { r ->
                (max(0f,min(r.right,rawFoot.x+width/2)-max(r.left,rawFoot.x-width/2)) *
                    max(0f,min(r.bottom,rawFoot.y)-max(r.top,rawCrown.y))).toDouble()
            }.toFloat()
            // 不比较任何预设人体高度，不为了变小而把人物移到远处。
            val z=ArSceneConstraints.opticalDepth(camera,p)
            val depthCost=backgroundDepth?.let { max(0f,z-it-.3f)/it } ?: 0f
            val distanceCost=(if (plan.shot == ShotType.ENVIRONMENT) -.005f else .005f)*v.distanceMeters
            val score = (if(plan.photography != null) 20f*PhotographyPlacementSolver.targetError(plan,camera,p) else 0f)+4f*abs(rawFoot.x-targetX)+(target?.let { 2f*abs(rawFoot.y-it.y) } ?: 0f)+
                3f*overlap+depthCost+distanceCost
            if (score < bestScore) { best = v; bestScore = score }
        }
        planes.forEach { plane ->
            target?.let { rayGround(camera,it,plane,CropRect()) }?.let { consider(plane,it) }
            evidence.points.map { it.sample }.filter { abs(it.world.y-plane.boundary.first().y) <= .12f }.forEach {
                consider(plane,it.world.copy(y=plane.boundary.first().y))
            }
            val c = camera.cameraPosition
            val lowX = max(plane.boundary.minOf { it.x },c.x-MAX_DISTANCE)
            val highX = min(plane.boundary.maxOf { it.x },c.x+MAX_DISTANCE)
            val lowZ = max(plane.boundary.minOf { it.z },c.z-MAX_DISTANCE)
            val highZ = min(plane.boundary.maxOf { it.z },c.z+MAX_DISTANCE)
            var x = lowX
            while (x <= highX && evaluated < MAX_CANDIDATES) {
                var z = lowZ
                while (z <= highZ && evaluated < MAX_CANDIDATES) { consider(plane,SpatialPoint(x,plane.boundary.first().y,z)); z += GRID_METERS }
                x += GRID_METERS
            }
        }
        return finish()
    }
}
