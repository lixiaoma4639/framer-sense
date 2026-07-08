package com.framer.sense.feature.camera.pytorch.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraV2CompositionEngineTest {

    private val engine = CameraV2CompositionEngine()
    private val profile = BodyProfile(170, 60)

    @Test
    fun buildGuide_whenModelAssetsMissing_stillBuildsVirtualHuman() {
        val guide = engine.buildGuide(
            analysis = analysis(modelAvailability = ModelAvailability.Missing),
            profile = profile
        )

        assertEquals(CameraV2Hint.MODEL_ASSETS_MISSING, guide.hint)
        assertTrue(guide.virtualHuman.lines.isNotEmpty())
        assertTrue(guide.targetBounds.centerX in 0.30f..0.70f)
    }

    @Test
    fun buildGuide_whenPersonAligned_reportsGoodComposition() {
        val guide = engine.buildGuide(
            analysis = analysis(
                people = listOf(ScenePerson(V2Rect(0.40f, 0.22f, 0.60f, 0.86f), 0.92f))
            ),
            profile = profile
        )

        assertEquals(CameraV2Quality.GOOD, guide.quality)
        assertEquals(CameraV2Hint.GOOD, guide.hint)
    }

    @Test
    fun buildGuide_whenPersonTooClose_asksUserToStepBack() {
        val guide = engine.buildGuide(
            analysis = analysis(
                people = listOf(ScenePerson(V2Rect(0.22f, 0.04f, 0.78f, 0.98f), 0.9f))
            ),
            profile = profile
        )

        assertEquals(CameraV2Movement.BACKWARD, guide.movement)
        assertEquals(CameraV2Hint.STEP_BACK, guide.hint)
    }

    @Test
    fun buildGuide_whenObjectBlocksLeftSide_movesTargetToRight() {
        val guide = engine.buildGuide(
            analysis = analysis(
                objects = listOf(SceneObject(V2Rect(0.02f, 0.08f, 0.55f, 0.92f), "chair", 0.9f, 56))
            ),
            profile = profile
        )

        assertTrue(guide.targetBounds.centerX > 0.48f)
    }

    @Test
    fun buildGuide_whenLowLight_reportsPoorScene() {
        val guide = engine.buildGuide(
            analysis = analysis(luminance = 18.0),
            profile = profile
        )

        assertEquals(CameraV2Quality.POOR, guide.quality)
        assertEquals(CameraV2Hint.LOW_LIGHT, guide.hint)
    }

    @Test
    fun buildGuide_whenSceneIsUrban_prefersRightThird() {
        val guide = engine.buildGuide(
            analysis = analysis(
                scene = SemanticScene("street", SceneGroup.URBAN, 0.8f)
            ),
            profile = profile
        )

        assertTrue(guide.targetBounds.centerX > 0.48f)
    }

    private fun analysis(
        people: List<ScenePerson> = emptyList(),
        objects: List<SceneObject> = emptyList(),
        scene: SemanticScene = SemanticScene("living_room", SceneGroup.INDOOR, 0.8f),
        luminance: Double = 128.0,
        modelAvailability: ModelAvailability = ModelAvailability(
            objectDetectorReady = true,
            poseDetectorReady = true
        )
    ): CameraV2Analysis =
        CameraV2Analysis(
            people = people,
            objects = objects,
            pose = PoseEstimate.Empty,
            semanticScene = scene,
            luminance = luminance,
            modelAvailability = modelAvailability
        )
}
