package com.framer.sense.feature.camera.pytorch.v2.ui

internal object WholeBodyInnerContourBuilder {

    fun build(pose: WholeBodyPoseEstimate): List<VirtualHumanLine> {
        if (pose.confidence < POSE_CONFIDENCE_THRESHOLD) return emptyList()
        return buildList {
            addBodyLines(pose)
            addFootLines(pose)
            addFaceLines(pose)
            addHandLines(pose, LEFT_HAND_BASE)
            addHandLines(pose, RIGHT_HAND_BASE)
        }
    }

    private fun MutableList<VirtualHumanLine>.addBodyLines(pose: WholeBodyPoseEstimate) {
        BODY_BONES.forEach { (start, end) ->
            addLine(pose, start, end, BODY_DEPTH)
        }
        addLine(pose, LEFT_SHOULDER, RIGHT_SHOULDER, BODY_FRONT_DEPTH)
        addLine(pose, LEFT_HIP, RIGHT_HIP, BODY_DEPTH)
        addLine(pose, LEFT_SHOULDER, LEFT_HIP, BODY_BACK_DEPTH)
        addLine(pose, RIGHT_SHOULDER, RIGHT_HIP, BODY_FRONT_DEPTH)
    }

    private fun MutableList<VirtualHumanLine>.addFootLines(pose: WholeBodyPoseEstimate) {
        FOOT_LINES.forEach { (start, end) ->
            addLine(pose, start, end, FOOT_DEPTH)
        }
    }

    private fun MutableList<VirtualHumanLine>.addFaceLines(pose: WholeBodyPoseEstimate) {
        FACE_GROUPS.forEach { offsets ->
            addPolyline(pose, offsets.map { FACE_BASE + it }, FACE_DEPTH)
        }
    }

    private fun MutableList<VirtualHumanLine>.addHandLines(
        pose: WholeBodyPoseEstimate,
        baseIndex: Int
    ) {
        HAND_CHAINS.forEach { offsets ->
            addPolyline(pose, offsets.map { baseIndex + it }, HAND_DEPTH)
        }
    }

    private fun MutableList<VirtualHumanLine>.addPolyline(
        pose: WholeBodyPoseEstimate,
        indexes: List<Int>,
        depth: Float
    ) {
        indexes.zipWithNext().forEach { (start, end) ->
            addLine(pose, start, end, depth)
        }
    }

    private fun MutableList<VirtualHumanLine>.addLine(
        pose: WholeBodyPoseEstimate,
        startIndex: Int,
        endIndex: Int,
        depth: Float
    ) {
        val start = pose.validPoint(startIndex) ?: return
        val end = pose.validPoint(endIndex) ?: return
        add(
            VirtualHumanLine(
                start = start.point.clamped(),
                end = end.point.clamped(),
                depth = depth
            )
        )
    }

    private fun WholeBodyPoseEstimate.validPoint(index: Int): WholeBodyKeypoint? =
        keypoints.firstOrNull { it.index == index && it.confidence >= KEYPOINT_CONFIDENCE_THRESHOLD }

    private fun V2Point.clamped(): V2Point =
        V2Point(
            x = x.coerceIn(0f, 1f),
            y = y.coerceIn(0f, 1f)
        )

    private const val POSE_CONFIDENCE_THRESHOLD = 0.18f
    private const val KEYPOINT_CONFIDENCE_THRESHOLD = 0.20f

    private const val NOSE = 0
    private const val LEFT_EYE = 1
    private const val RIGHT_EYE = 2
    private const val LEFT_EAR = 3
    private const val RIGHT_EAR = 4
    private const val LEFT_SHOULDER = 5
    private const val RIGHT_SHOULDER = 6
    private const val LEFT_ELBOW = 7
    private const val RIGHT_ELBOW = 8
    private const val LEFT_WRIST = 9
    private const val RIGHT_WRIST = 10
    private const val LEFT_HIP = 11
    private const val RIGHT_HIP = 12
    private const val LEFT_KNEE = 13
    private const val RIGHT_KNEE = 14
    private const val LEFT_ANKLE = 15
    private const val RIGHT_ANKLE = 16
    private const val FACE_BASE = 23
    private const val LEFT_HAND_BASE = 91
    private const val RIGHT_HAND_BASE = 112

    private const val BODY_DEPTH = 0.02f
    private const val BODY_FRONT_DEPTH = 0.06f
    private const val BODY_BACK_DEPTH = -0.02f
    private const val FACE_DEPTH = 0.08f
    private const val HAND_DEPTH = 0.09f
    private const val FOOT_DEPTH = 0.01f

    private val BODY_BONES = listOf(
        NOSE to LEFT_EYE,
        NOSE to RIGHT_EYE,
        LEFT_EYE to LEFT_EAR,
        RIGHT_EYE to RIGHT_EAR,
        LEFT_SHOULDER to LEFT_ELBOW,
        LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW,
        RIGHT_ELBOW to RIGHT_WRIST,
        LEFT_HIP to LEFT_KNEE,
        LEFT_KNEE to LEFT_ANKLE,
        RIGHT_HIP to RIGHT_KNEE,
        RIGHT_KNEE to RIGHT_ANKLE
    )

    private val FOOT_LINES = listOf(
        LEFT_ANKLE to 17,
        17 to 18,
        LEFT_ANKLE to 19,
        RIGHT_ANKLE to 20,
        20 to 21,
        RIGHT_ANKLE to 22
    )

    private val FACE_GROUPS = listOf(
        (0..16).toList(),
        (17..21).toList(),
        (22..26).toList(),
        (27..30).toList(),
        (31..35).toList(),
        listOf(36, 37, 38, 39, 40, 41, 36),
        listOf(42, 43, 44, 45, 46, 47, 42),
        (48..59).toList() + 48,
        (60..67).toList() + 60
    )

    private val HAND_CHAINS = listOf(
        listOf(0, 1, 2, 3, 4),
        listOf(0, 5, 6, 7, 8),
        listOf(0, 9, 10, 11, 12),
        listOf(0, 13, 14, 15, 16),
        listOf(0, 17, 18, 19, 20),
        listOf(5, 9, 13, 17)
    )
}
