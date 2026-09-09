package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseRecommendationEngineTest {

    private val engine = PoseRecommendationEngine()

    @Test
    fun recommend_forUrbanScene_onlyReturnsCompatibleFullBodyCandidates() {
        val recommendation = engine.recommend(SemanticScene("street", SceneGroup.URBAN, 0.9f))

        assertEquals(TargetPoseCategory.FULL_BODY, recommendation.targetPose.category)
        assertTrue(recommendation.candidates.all { pose ->
            pose.category == TargetPoseCategory.FULL_BODY && SceneGroup.URBAN in pose.compatibleSceneGroups
        })
    }

    @Test
    fun recommend_forIndoorScene_returnsHalfBodyCandidates() {
        val recommendation = engine.recommend(SemanticScene("room", SceneGroup.INDOOR, 0.9f))

        assertEquals(TargetPoseCategory.HALF_BODY, recommendation.targetPose.category)
        assertTrue(recommendation.candidates.size > 1)
    }

    @Test
    fun recommend_forUnknownScene_returnsCloseUpCandidates() {
        val recommendation = engine.recommend(SemanticScene.Unknown)

        assertEquals(TargetPoseCategory.CLOSE_UP, recommendation.targetPose.category)
        assertTrue(recommendation.candidates.all { SceneGroup.UNKNOWN in it.compatibleSceneGroups })
    }
}
