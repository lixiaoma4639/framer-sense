package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPoseLibraryTest {

    @Test
    fun baseLibrary_containsExactlyTwentyFourOwnedNaturalPoses() {
        assertEquals(24, TargetPoseLibrary.basePoses.size)
        assertEquals(48, TargetPoseLibrary.allPoses.size)
        assertTrue(TargetPoseLibrary.basePoses.map { it.id }.distinct().size == 24)
    }

    @Test
    fun everyBasePose_containsCompleteWholeBodyGroups() {
        TargetPoseLibrary.basePoses.forEach { pose ->
            assertEquals((0..132).toSet(), pose.points.keys)
            assertEquals(17, pose.points.keys.count { it in WholeBodyPoseEstimate.BODY_RANGE })
            assertEquals(6, pose.points.keys.count { it in WholeBodyPoseEstimate.FOOT_RANGE })
            assertEquals(68, pose.points.keys.count { it in WholeBodyPoseEstimate.FACE_RANGE })
            assertEquals(21, pose.points.keys.count { it in WholeBodyPoseEstimate.LEFT_HAND_RANGE })
            assertEquals(21, pose.points.keys.count { it in WholeBodyPoseEstimate.RIGHT_HAND_RANGE })
        }
    }

    @Test
    fun mirroredPose_swapsSidesAndReflectsCoordinates() {
        val source = TargetPoseLibrary.find("side_weight") ?: error("missing pose")
        val mirror = TargetPoseLibrary.find("side_weight_mirror") ?: error("missing mirror pose")

        assertEquals(source.id, mirror.mirroredFromId)
        assertEquals(
            -source.point(TargetPoseJoint.LEFT_HAND.index).x,
            mirror.point(TargetPoseJoint.RIGHT_HAND.index).x,
            0.0001f
        )
        assertEquals(-source.point(91).x, mirror.point(112).x, 0.0001f)
        assertEquals(-source.point(17).x, mirror.point(20).x, 0.0001f)
        assertEquals(TargetPoseHeadDirection.RIGHT, mirror.headDirection)
    }
}
