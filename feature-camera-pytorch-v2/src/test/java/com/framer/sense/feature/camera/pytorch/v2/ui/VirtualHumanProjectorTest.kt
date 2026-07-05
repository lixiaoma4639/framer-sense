package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualHumanProjectorTest {

    private val projector = VirtualHumanProjector()

    @Test
    fun project_forDifferentBodyProfiles_keepsHumanInsidePreview() {
        val target = V2Rect(0.32f, 0.14f, 0.68f, 0.92f)
        val slim = projector.project(target, BodyProfile(185, 58), PoseTemplate.RELAXED_STAND)
        val heavy = projector.project(target, BodyProfile(160, 110), PoseTemplate.RELAXED_STAND)

        assertTrue(slim.bounds.top >= 0f)
        assertTrue(slim.bounds.bottom <= 1f)
        assertTrue(heavy.bounds.left >= 0f)
        assertTrue(heavy.bounds.right <= 1f)
        assertTrue(heavy.bounds.width > slim.bounds.width)
    }

    @Test
    fun project_walkingPose_containsProjectedSkeletonLines() {
        val figure = projector.project(
            targetBounds = V2Rect(0.32f, 0.14f, 0.68f, 0.92f),
            profile = BodyProfile(170, 60),
            template = PoseTemplate.WALKING
        )

        assertTrue(figure.lines.size >= 10)
        assertTrue(figure.lines.all { line ->
            line.start.x in 0f..1f &&
                line.start.y in 0f..1f &&
                line.end.x in 0f..1f &&
                line.end.y in 0f..1f
        })
    }
}
