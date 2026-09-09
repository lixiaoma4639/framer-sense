package com.framer.sense.feature.camera.pytorch.v2.ui

import kotlin.math.max
import kotlin.math.min

/**
 * 将项目自建的 133 点目标姿势转换成实体化的 2.5D 虚拟人。
 *
 * 这不是把真实用户照片绘制到相机中：脸、衣服与光影均为程序生成，只有姿态、景别和透视
 * 来自目标 Pose。因此可以在没有网络、没有第三方人物图片和没有实时 RTMPose 的情况下稳定展示。
 */
internal object WholeBodyAvatarBuilder {

    fun build(targetPose: TargetPose, bounds: V2Rect, profile: BodyProfile): VirtualHumanAvatar {
        val source = targetPose.projectedWholeBodyPose(bounds, profile).keypoints.associateBy { it.index }
        fun point(index: Int): V2Point = source.getValue(index).point
        fun depth(vararg indexes: Int): Float = indexes.map { targetPose.point(it).z }.average().toFloat()

        val leftShoulder = point(5)
        val rightShoulder = point(6)
        val leftHip = point(11)
        val rightHip = point(12)
        val shoulderWidth = max(0.045f, rightShoulder.x - leftShoulder.x)
        val hipWidth = max(0.040f, rightHip.x - leftHip.x)
        val facePoints = (23..90).map(::point)
        val faceBox = facePoints.bounds().expanded(
            horizontal = shoulderWidth * 0.10f,
            vertical = bounds.height * 0.015f
        )
        val faceCenter = V2Point(faceBox.centerX, faceBox.centerY)

        return VirtualHumanAvatar(
            shapes = listOf(
                // 后方肢体先绘制；overlay 会再按 depth 做正确遮挡排序。
                limb(VirtualHumanAvatarPart.LEFT_LEG, listOf(leftHip, point(13), point(15)), shoulderWidth * 0.30f, depth(11, 13, 15)),
                limb(VirtualHumanAvatarPart.RIGHT_LEG, listOf(rightHip, point(14), point(16)), shoulderWidth * 0.30f, depth(12, 14, 16)),
                polygon(
                    VirtualHumanAvatarPart.TORSO,
                    listOf(
                        V2Point(leftShoulder.x - shoulderWidth * 0.08f, leftShoulder.y),
                        V2Point(rightShoulder.x + shoulderWidth * 0.08f, rightShoulder.y),
                        V2Point(rightHip.x + hipWidth * 0.16f, rightHip.y),
                        V2Point(leftHip.x - hipWidth * 0.16f, leftHip.y)
                    ),
                    depth(5, 6, 11, 12)
                ),
                limb(VirtualHumanAvatarPart.LEFT_ARM, listOf(leftShoulder, point(7), point(9)), shoulderWidth * 0.25f, depth(5, 7, 9)),
                limb(VirtualHumanAvatarPart.RIGHT_ARM, listOf(rightShoulder, point(8), point(10)), shoulderWidth * 0.25f, depth(6, 8, 10)),
                polygon(VirtualHumanAvatarPart.FACE, oval(faceCenter, faceBox.width / 2f, faceBox.height / 2f), depth(0, 1, 2)),
                polygon(
                    VirtualHumanAvatarPart.HAIR,
                    oval(
                        center = V2Point(faceCenter.x, faceBox.top + faceBox.height * 0.34f),
                        radiusX = faceBox.width * 0.54f,
                        radiusY = faceBox.height * 0.40f
                    ),
                    // 头发位于脸部前上方，避免被脸部填充完全覆盖。
                    depth(0, 1, 2) + 0.08f
                ),
                polygon(VirtualHumanAvatarPart.LEFT_HAND, palm(point(9), (91..111).map(::point)), depth(9, 91, 95, 99)),
                polygon(VirtualHumanAvatarPart.RIGHT_HAND, palm(point(10), (112..132).map(::point)), depth(10, 112, 116, 120)),
                polygon(VirtualHumanAvatarPart.LEFT_SHOE, shoe(point(15), point(17), point(18), point(19)), depth(15, 17, 18, 19)),
                polygon(VirtualHumanAvatarPart.RIGHT_SHOE, shoe(point(16), point(20), point(21), point(22)), depth(16, 20, 21, 22))
            )
        )
    }

    private fun limb(
        part: VirtualHumanAvatarPart,
        points: List<V2Point>,
        widthRatio: Float,
        depth: Float
    ) = VirtualHumanAvatarShape(part, points, depth, widthRatio, closed = false)

    private fun polygon(part: VirtualHumanAvatarPart, points: List<V2Point>, depth: Float) =
        VirtualHumanAvatarShape(part, points, depth)

    private fun List<V2Point>.bounds(): V2Rect = V2Rect(
        left = minOf { it.x },
        top = minOf { it.y },
        right = maxOf { it.x },
        bottom = maxOf { it.y }
    )

    private fun V2Rect.expanded(horizontal: Float, vertical: Float): V2Rect = V2Rect(
        left = (left - horizontal).coerceIn(0f, 1f),
        top = (top - vertical).coerceIn(0f, 1f),
        right = (right + horizontal).coerceIn(0f, 1f),
        bottom = (bottom + vertical).coerceIn(0f, 1f)
    )

    private fun oval(center: V2Point, radiusX: Float, radiusY: Float): List<V2Point> =
        listOf(
            -.92f to -.25f, -.66f to -.73f, 0f to -1f, .66f to -.73f,
            .92f to -.25f, .88f to .36f, .50f to .86f, 0f to 1f,
            -.50f to .86f, -.88f to .36f
        ).map { (x, y) ->
            V2Point(
                x = (center.x + radiusX * x).coerceIn(0f, 1f),
                y = (center.y + radiusY * y).coerceIn(0f, 1f)
            )
        }

    private fun palm(wrist: V2Point, allPoints: List<V2Point>): List<V2Point> {
        val box = allPoints.bounds().expanded(0.006f, 0.004f)
        // 手掌由完整 21 点的整体范围决定，手指详情仍通过 133 点轮廓叠加展示。
        val center = V2Point((box.centerX + wrist.x) / 2f, (box.centerY + wrist.y) / 2f)
        return oval(center, max(0.012f, box.width * 0.42f), max(0.017f, box.height * 0.28f))
    }

    private fun shoe(ankle: V2Point, outer: V2Point, inner: V2Point, heel: V2Point): List<V2Point> =
        listOf(
            V2Point(ankle.x, ankle.y - 0.006f),
            outer,
            V2Point((outer.x + heel.x) / 2f, max(outer.y, heel.y) + 0.008f),
            heel,
            inner
        )
}
