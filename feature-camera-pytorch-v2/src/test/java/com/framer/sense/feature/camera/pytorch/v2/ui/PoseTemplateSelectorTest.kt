package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseTemplateSelectorTest {

    private val selector = PoseTemplateSelector()

    @Test
    fun select_whenAnklesAreWide_usesWalkingPose() {
        val pose = WholeBodyPoseEstimate(
            keypoints = listOf(
                keypoint(WholeBodyKeypointIndex.LEFT_SHOULDER, 0.42f, 0.3f),
                keypoint(WholeBodyKeypointIndex.RIGHT_SHOULDER, 0.58f, 0.3f),
                keypoint(WholeBodyKeypointIndex.LEFT_ANKLE, 0.30f, 0.9f),
                keypoint(WholeBodyKeypointIndex.RIGHT_ANKLE, 0.70f, 0.9f)
            ),
            confidence = 0.8f
        )

        assertEquals(
            PoseTemplate.WALKING,
            selector.select(pose, SemanticScene("street", SceneGroup.URBAN, 0.9f))
        )
    }

    @Test
    fun select_whenNoPose_usesSceneDefault() {
        assertEquals(
            PoseTemplate.SIDE_STANCE,
            selector.select(WholeBodyPoseEstimate.Empty, SemanticScene("bedroom", SceneGroup.INDOOR, 0.8f))
        )
    }

    private fun keypoint(index: Int, x: Float, y: Float): WholeBodyKeypoint =
        WholeBodyKeypoint(index, V2Point(x, y), confidence = 0.9f)
}
