package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseAlignmentEngineTest {

    private val engine = PoseAlignmentEngine()

    @Test
    fun evaluate_withNoReliableShoulders_doesNotProduceFeedback() {
        assertNull(engine.evaluate(WholeBodyPoseEstimate.Empty, TargetPoseLibrary.basePoses.first()))
    }

    @Test
    fun evaluate_withArmFarFromTarget_producesOneActionableFeedback() {
        val target = TargetPoseLibrary.find("hand_near_chin") ?: error("missing pose")
        val pose = WholeBodyPoseEstimate(
            keypoints = listOf(
                point(WholeBodyKeypointIndex.NOSE, 0.50f, 0.18f),
                point(WholeBodyKeypointIndex.LEFT_SHOULDER, 0.40f, 0.30f),
                point(WholeBodyKeypointIndex.RIGHT_SHOULDER, 0.60f, 0.30f),
                point(WholeBodyKeypointIndex.LEFT_WRIST, 0.10f, 0.88f),
                point(WholeBodyKeypointIndex.RIGHT_WRIST, 0.90f, 0.88f)
            ),
            confidence = 0.9f
        )

        val feedback = engine.evaluate(pose, target)

        assertTrue(feedback != null)
        assertTrue(feedback!!.instruction.isNotBlank())
        assertTrue(feedback.area == PoseAlignmentArea.ARM || feedback.area == PoseAlignmentArea.HEAD)
    }

    @Test
    fun evaluate_withLowConfidenceHandPoints_doesNotUseHandFeedback() {
        val target = TargetPoseLibrary.basePoses.first()
        val pose = WholeBodyPoseEstimate(
            keypoints = listOf(
                point(WholeBodyKeypointIndex.LEFT_SHOULDER, 0.4f, 0.3f),
                point(WholeBodyKeypointIndex.RIGHT_SHOULDER, 0.6f, 0.3f),
                WholeBodyKeypoint(91, V2Point(0.9f, 0.9f), 0.1f)
            ),
            confidence = 0.9f
        )

        val feedback = engine.evaluate(pose, target)

        assertTrue(feedback?.area != PoseAlignmentArea.HAND)
    }

    private fun point(index: Int, x: Float, y: Float): WholeBodyKeypoint =
        WholeBodyKeypoint(index, V2Point(x, y), 0.95f)
}
