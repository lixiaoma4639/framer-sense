package com.framer.sense.feature.camera.pytorch.v2.ui

import kotlin.math.abs
import kotlin.math.max

class VirtualHumanProjector {

    fun project(
        targetBounds: V2Rect,
        profile: BodyProfile,
        template: PoseTemplate,
        pose: PoseEstimate = PoseEstimate.Empty,
        contourPathPoints: List<V2Point> = emptyList(),
        matchTargetBounds: Boolean = false
    ): VirtualHumanFigure {
        val bounds = if (matchTargetBounds) {
            targetBounds.clamped().ensureMinimumSize()
        } else {
            val figureHeight = (targetBounds.height * profile.heightScale).coerceIn(
                targetBounds.height * 0.86f,
                minOf(0.82f, targetBounds.height * 1.08f)
            )
            val centerY = targetBounds.bottom - figureHeight / 2f
            val top = (centerY - figureHeight / 2f).coerceAtLeast(0.06f)
            val bottom = (top + figureHeight).coerceAtMost(0.96f)
            val width = (targetBounds.width * profile.widthScale).coerceIn(0.20f, 0.48f)
            val left = (targetBounds.centerX - width / 2f).coerceIn(0.04f, 0.96f - width)
            V2Rect(left, top, left + width, bottom)
        }

        val posePoints = buildPosePoints(template, profile, pose, bounds)
        val projected = posePoints.points.mapValues { (_, point) ->
            projectPoint(bounds, point)
        }
        val lines = SKELETON.mapNotNull { bone ->
            val start = projected[bone.first]
            val end = projected[bone.second]
            val start3d = posePoints.points[bone.first]
            val end3d = posePoints.points[bone.second]
            if (start == null || end == null || start3d == null || end3d == null) {
                null
            } else {
                VirtualHumanLine(
                    start = start,
                    end = end,
                    depth = (start3d.z + end3d.z) / 2f
                )
            }
        }

        return VirtualHumanFigure(
            bounds = bounds,
            template = template,
            lines = lines,
            headCenter = projected[Joint.HEAD] ?: V2Point(bounds.centerX, bounds.top + bounds.height * 0.10f),
            headRadius = if (posePoints.drawHead) max(bounds.width * 0.12f, 0.025f) else 0f,
            contourPathPoints = contourPathPoints,
            drawHead = posePoints.drawHead && !posePoints.poseDriven && !matchTargetBounds && contourPathPoints.isEmpty(),
            poseDriven = posePoints.poseDriven
        )
    }

    private fun V2Rect.ensureMinimumSize(): V2Rect {
        val adjustedWidth = width.coerceAtLeast(MIN_MATCHED_WIDTH)
        val adjustedHeight = height.coerceAtLeast(MIN_MATCHED_HEIGHT)
        val left = (centerX - adjustedWidth / 2f).coerceIn(0f, 1f - adjustedWidth)
        val top = (centerY - adjustedHeight / 2f).coerceIn(0f, 1f - adjustedHeight)
        return V2Rect(left, top, left + adjustedWidth, top + adjustedHeight)
    }

    private fun buildPosePoints(
        template: PoseTemplate,
        profile: BodyProfile,
        pose: PoseEstimate,
        bounds: V2Rect
    ): PosePointSet {
        val posePoints = buildPoseAwarePoints(pose, bounds)
        return if (posePoints == null) {
            PosePointSet(
                points = buildTemplatePosePoints(template, profile),
                drawHead = true,
                poseDriven = false
            )
        } else {
            posePoints
        }
    }

    private fun buildTemplatePosePoints(template: PoseTemplate, profile: BodyProfile): Map<Joint, Point3> {
        val shoulderHalf = 0.24f * profile.widthScale
        val hipHalf = 0.17f * profile.widthScale
        val sideOffset = if (template == PoseTemplate.SIDE_STANCE) 0.06f else 0f
        val walking = template == PoseTemplate.WALKING

        return mapOf(
            Joint.HEAD to Point3(0f + sideOffset, 0.10f, 0.03f),
            Joint.NECK to Point3(0f + sideOffset * 0.6f, 0.22f, 0.02f),
            Joint.LEFT_SHOULDER to Point3(-shoulderHalf + sideOffset, 0.27f, -0.04f),
            Joint.RIGHT_SHOULDER to Point3(shoulderHalf + sideOffset, 0.27f, 0.04f),
            Joint.LEFT_ELBOW to Point3(-shoulderHalf * 1.18f, if (walking) 0.43f else 0.46f, 0.02f),
            Joint.RIGHT_ELBOW to Point3(shoulderHalf * 1.15f, if (walking) 0.40f else 0.43f, -0.02f),
            Joint.LEFT_HAND to Point3(-shoulderHalf * 1.05f, if (walking) 0.56f else 0.60f, 0.08f),
            Joint.RIGHT_HAND to Point3(shoulderHalf * 0.95f, if (walking) 0.53f else 0.57f, -0.08f),
            Joint.LEFT_HIP to Point3(-hipHalf, 0.57f, -0.02f),
            Joint.RIGHT_HIP to Point3(hipHalf, 0.57f, 0.02f),
            Joint.LEFT_KNEE to Point3(if (walking) -hipHalf * 1.55f else -hipHalf * 0.92f, 0.76f, if (walking) 0.07f else 0f),
            Joint.RIGHT_KNEE to Point3(if (walking) hipHalf * 1.42f else hipHalf * 0.94f, 0.77f, if (walking) -0.06f else 0f),
            Joint.LEFT_FOOT to Point3(if (walking) -hipHalf * 1.95f else -hipHalf * 1.05f, 0.98f, if (walking) 0.10f else 0.03f),
            Joint.RIGHT_FOOT to Point3(if (walking) hipHalf * 1.80f else hipHalf * 1.05f, 0.98f, if (walking) -0.08f else -0.03f)
        )
    }

    private fun buildPoseAwarePoints(pose: PoseEstimate, bounds: V2Rect): PosePointSet? {
        if (pose.confidence < POSE_CONFIDENCE_THRESHOLD) return null

        val namedPoints = PoseKeypointName.entries.associateWith { pose.point(it) }
        val leftShoulder = namedPoints[PoseKeypointName.LEFT_SHOULDER] ?: return null
        val rightShoulder = namedPoints[PoseKeypointName.RIGHT_SHOULDER] ?: return null
        val leftHip = namedPoints[PoseKeypointName.LEFT_HIP]
        val rightHip = namedPoints[PoseKeypointName.RIGHT_HIP]
        val visibleKeypointCount = namedPoints.values.count { it != null }
        val hasUsefulBodyPoint = listOf(
            PoseKeypointName.NOSE,
            PoseKeypointName.LEFT_EYE,
            PoseKeypointName.RIGHT_EYE,
            PoseKeypointName.LEFT_ELBOW,
            PoseKeypointName.RIGHT_ELBOW,
            PoseKeypointName.LEFT_WRIST,
            PoseKeypointName.RIGHT_WRIST,
            PoseKeypointName.LEFT_HIP,
            PoseKeypointName.RIGHT_HIP
        ).any { namedPoints[it] != null }
        if (visibleKeypointCount < MIN_KEYPOINTS_FOR_POSE || !hasUsefulBodyPoint) return null

        val headAnchor = firstAvailable(
            namedPoints,
            PoseKeypointName.NOSE,
            PoseKeypointName.LEFT_EYE,
            PoseKeypointName.RIGHT_EYE,
            PoseKeypointName.LEFT_EAR,
            PoseKeypointName.RIGHT_EAR
        )
        val mappedPoints = mapOf(
            Joint.HEAD to headAnchor,
            Joint.NECK to midpoint(leftShoulder, rightShoulder),
            Joint.LEFT_SHOULDER to leftShoulder,
            Joint.RIGHT_SHOULDER to rightShoulder,
            Joint.LEFT_ELBOW to namedPoints[PoseKeypointName.LEFT_ELBOW],
            Joint.RIGHT_ELBOW to namedPoints[PoseKeypointName.RIGHT_ELBOW],
            Joint.LEFT_HAND to namedPoints[PoseKeypointName.LEFT_WRIST],
            Joint.RIGHT_HAND to namedPoints[PoseKeypointName.RIGHT_WRIST],
            Joint.LEFT_HIP to leftHip,
            Joint.RIGHT_HIP to rightHip,
            Joint.LEFT_KNEE to namedPoints[PoseKeypointName.LEFT_KNEE],
            Joint.RIGHT_KNEE to namedPoints[PoseKeypointName.RIGHT_KNEE],
            Joint.LEFT_FOOT to namedPoints[PoseKeypointName.LEFT_ANKLE],
            Joint.RIGHT_FOOT to namedPoints[PoseKeypointName.RIGHT_ANKLE]
        )

        val leftDepth = estimateSideDepth(
            shoulder = leftShoulder,
            hip = leftHip,
            foot = namedPoints[PoseKeypointName.LEFT_ANKLE],
            oppositeShoulder = rightShoulder,
            oppositeHip = rightHip,
            oppositeFoot = namedPoints[PoseKeypointName.RIGHT_ANKLE]
        )
        val rightDepth = -leftDepth

        val points = mappedPoints.mapValues { (joint, point) ->
            point?.toPoint3(bounds, joint.depthForSide(leftDepth, rightDepth))
        }.toMutableMap()
        points[Joint.LEFT_ELBOW] = points[Joint.LEFT_ELBOW] ?: interpolate(points[Joint.LEFT_SHOULDER], points[Joint.LEFT_HAND], 0.55f)
        points[Joint.RIGHT_ELBOW] = points[Joint.RIGHT_ELBOW] ?: interpolate(points[Joint.RIGHT_SHOULDER], points[Joint.RIGHT_HAND], 0.55f)
        points[Joint.LEFT_HAND] = points[Joint.LEFT_HAND] ?: extend(points[Joint.LEFT_SHOULDER], points[Joint.LEFT_ELBOW], 0.72f)
        points[Joint.RIGHT_HAND] = points[Joint.RIGHT_HAND] ?: extend(points[Joint.RIGHT_SHOULDER], points[Joint.RIGHT_ELBOW], 0.72f)
        points[Joint.LEFT_KNEE] = points[Joint.LEFT_KNEE] ?: interpolate(points[Joint.LEFT_HIP], points[Joint.LEFT_FOOT], 0.54f)
        points[Joint.RIGHT_KNEE] = points[Joint.RIGHT_KNEE] ?: interpolate(points[Joint.RIGHT_HIP], points[Joint.RIGHT_FOOT], 0.54f)
        points[Joint.LEFT_FOOT] = points[Joint.LEFT_FOOT] ?: extend(points[Joint.LEFT_HIP], points[Joint.LEFT_KNEE], 0.78f)
        points[Joint.RIGHT_FOOT] = points[Joint.RIGHT_FOOT] ?: extend(points[Joint.RIGHT_HIP], points[Joint.RIGHT_KNEE], 0.78f)

        return PosePointSet(
            points = points.filterValues { it != null }.mapValues { it.value ?: error("Unexpected null pose point") },
            drawHead = headAnchor != null,
            poseDriven = true
        )
    }

    private fun projectPoint(bounds: V2Rect, point: Point3): V2Point {
        val perspective = 1f / (1f + point.z * 0.45f)
        return V2Point(
            x = (bounds.centerX + point.x * bounds.width * perspective).coerceIn(0f, 1f),
            y = (bounds.top + point.y * bounds.height).coerceIn(0f, 1f)
        )
    }

    private fun V2Point.toPoint3(bounds: V2Rect, depth: Float): Point3 {
        val centerX = bounds.centerX
        val width = bounds.width.coerceAtLeast(0.001f)
        val height = bounds.height.coerceAtLeast(0.001f)
        val x = ((x - centerX) / width * POSE_X_GAIN).coerceIn(-0.68f, 0.68f)
        val y = ((y - bounds.top) / height * POSE_Y_GAIN).coerceIn(0.02f, 0.99f)
        return Point3(x = x, y = y, z = depth)
    }

    private fun Joint.depthForSide(leftDepth: Float, rightDepth: Float): Float =
        when (this) {
            Joint.LEFT_SHOULDER, Joint.LEFT_ELBOW, Joint.LEFT_HAND, Joint.LEFT_HIP, Joint.LEFT_KNEE, Joint.LEFT_FOOT -> leftDepth
            Joint.RIGHT_SHOULDER, Joint.RIGHT_ELBOW, Joint.RIGHT_HAND, Joint.RIGHT_HIP, Joint.RIGHT_KNEE, Joint.RIGHT_FOOT -> rightDepth
            Joint.HEAD, Joint.NECK -> max(leftDepth, rightDepth) * 0.45f
        }

    private fun estimateSideDepth(
        shoulder: V2Point,
        hip: V2Point?,
        foot: V2Point?,
        oppositeShoulder: V2Point,
        oppositeHip: V2Point?,
        oppositeFoot: V2Point?
    ): Float {
        val ownLower = foot?.y ?: hip?.y ?: shoulder.y
        val oppositeLower = oppositeFoot?.y ?: oppositeHip?.y ?: oppositeShoulder.y
        val lowerBias = (ownLower - oppositeLower).coerceIn(-0.08f, 0.08f)
        val shoulderBias = if (hip != null && oppositeHip != null) {
            (abs(oppositeShoulder.x - shoulder.x) - abs(oppositeHip.x - hip.x)).coerceIn(-0.06f, 0.06f)
        } else {
            0f
        }
        return (lowerBias * 1.2f + shoulderBias * 0.7f).coerceIn(-0.12f, 0.12f)
    }

    private fun midpoint(first: V2Point, second: V2Point): V2Point =
        V2Point((first.x + second.x) / 2f, (first.y + second.y) / 2f)

    private fun firstAvailable(
        points: Map<PoseKeypointName, V2Point?>,
        vararg names: PoseKeypointName
    ): V2Point? =
        names.firstNotNullOfOrNull { points[it] }

    private fun interpolate(start: Point3?, end: Point3?, amount: Float): Point3? {
        if (start == null || end == null) return null
        return Point3(
            x = start.x + (end.x - start.x) * amount,
            y = start.y + (end.y - start.y) * amount,
            z = start.z + (end.z - start.z) * amount
        )
    }

    private fun extend(start: Point3?, end: Point3?, amount: Float): Point3? {
        if (start == null || end == null) return null
        return Point3(
            x = end.x + (end.x - start.x) * amount,
            y = end.y + (end.y - start.y) * amount,
            z = end.z + (end.z - start.z) * amount
        )
    }

    private data class Point3(
        val x: Float,
        val y: Float,
        val z: Float
    )

    private data class PosePointSet(
        val points: Map<Joint, Point3>,
        val drawHead: Boolean,
        val poseDriven: Boolean
    )

    private enum class Joint {
        HEAD,
        NECK,
        LEFT_SHOULDER,
        RIGHT_SHOULDER,
        LEFT_ELBOW,
        RIGHT_ELBOW,
        LEFT_HAND,
        RIGHT_HAND,
        LEFT_HIP,
        RIGHT_HIP,
        LEFT_KNEE,
        RIGHT_KNEE,
        LEFT_FOOT,
        RIGHT_FOOT
    }

    private companion object {
        const val POSE_CONFIDENCE_THRESHOLD = 0.25f
        const val MIN_KEYPOINTS_FOR_POSE = 4
        const val POSE_X_GAIN = 1.18f
        const val POSE_Y_GAIN = 0.90f
        const val MIN_MATCHED_WIDTH = 0.12f
        const val MIN_MATCHED_HEIGHT = 0.24f

        val SKELETON = listOf(
            Joint.HEAD to Joint.NECK,
            Joint.NECK to Joint.LEFT_SHOULDER,
            Joint.NECK to Joint.RIGHT_SHOULDER,
            Joint.LEFT_SHOULDER to Joint.LEFT_ELBOW,
            Joint.LEFT_ELBOW to Joint.LEFT_HAND,
            Joint.RIGHT_SHOULDER to Joint.RIGHT_ELBOW,
            Joint.RIGHT_ELBOW to Joint.RIGHT_HAND,
            Joint.NECK to Joint.LEFT_HIP,
            Joint.NECK to Joint.RIGHT_HIP,
            Joint.LEFT_HIP to Joint.RIGHT_HIP,
            Joint.LEFT_HIP to Joint.LEFT_KNEE,
            Joint.LEFT_KNEE to Joint.LEFT_FOOT,
            Joint.RIGHT_HIP to Joint.RIGHT_KNEE,
            Joint.RIGHT_KNEE to Joint.RIGHT_FOOT
        )
    }
}
