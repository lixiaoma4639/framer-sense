package com.framer.sense.feature.camera.pytorch.v2.ui

import kotlin.math.max

class VirtualHumanProjector {

    fun project(
        targetBounds: V2Rect,
        profile: BodyProfile,
        template: PoseTemplate
    ): VirtualHumanFigure {
        val figureHeight = (targetBounds.height * profile.heightScale).coerceIn(
            targetBounds.height * 0.86f,
            minOf(0.82f, targetBounds.height * 1.08f)
        )
        val centerY = targetBounds.bottom - figureHeight / 2f
        val top = (centerY - figureHeight / 2f).coerceAtLeast(0.06f)
        val bottom = (top + figureHeight).coerceAtMost(0.96f)
        val width = (targetBounds.width * profile.widthScale).coerceIn(0.20f, 0.48f)
        val left = (targetBounds.centerX - width / 2f).coerceIn(0.04f, 0.96f - width)
        val bounds = V2Rect(left, top, left + width, bottom)

        val points = buildPosePoints(template, profile)
        val projected = points.mapValues { (_, point) ->
            projectPoint(bounds, point)
        }
        val lines = SKELETON.mapNotNull { bone ->
            val start = projected[bone.first]
            val end = projected[bone.second]
            val start3d = points[bone.first]
            val end3d = points[bone.second]
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
            headCenter = projected.getValue(Joint.HEAD),
            headRadius = max(bounds.width * 0.12f, 0.025f)
        )
    }

    private fun buildPosePoints(template: PoseTemplate, profile: BodyProfile): Map<Joint, Point3> {
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

    private fun projectPoint(bounds: V2Rect, point: Point3): V2Point {
        val perspective = 1f / (1f + point.z * 0.45f)
        return V2Point(
            x = (bounds.centerX + point.x * bounds.width * perspective).coerceIn(0f, 1f),
            y = (bounds.top + point.y * bounds.height).coerceIn(0f, 1f)
        )
    }

    private data class Point3(
        val x: Float,
        val y: Float,
        val z: Float
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
