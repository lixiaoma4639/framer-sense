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

    @Test
    fun buildGuide_withPose_usesPoseAwareVirtualHuman() {
        val guideWithoutPose = engine.buildGuide(
            analysis = analysis(
                people = listOf(ScenePerson(V2Rect(0.40f, 0.22f, 0.60f, 0.86f), 0.92f))
            ),
            profile = profile
        )
        val guideWithPose = engine.buildGuide(
            analysis = analysis(
                people = listOf(ScenePerson(V2Rect(0.40f, 0.22f, 0.60f, 0.86f), 0.92f)),
                pose = completePose()
            ),
            profile = profile
        )

        assertTrue(guideWithPose.virtualHuman.lines.isNotEmpty())
        assertTrue(guideWithPose.virtualHuman.lines != guideWithoutPose.virtualHuman.lines)
    }

    @Test
    fun buildGuide_withoutPose_stillBuildsVirtualHuman() {
        val guide = engine.buildGuide(
            analysis = analysis(pose = PoseEstimate.Empty),
            profile = profile
        )

        assertTrue(guide.virtualHuman.lines.isNotEmpty())
    }

    @Test
    fun buildGuide_withSegmentation_usesContourAndCurrentPersonBounds() {
        val segment = PersonSegmentation(
            bounds = V2Rect(0.22f, 0.16f, 0.50f, 0.88f),
            contour = listOf(
                V2Point(0.28f, 0.16f),
                V2Point(0.50f, 0.42f),
                V2Point(0.44f, 0.88f),
                V2Point(0.22f, 0.72f)
            ),
            confidence = 0.9f
        )

        val guide = engine.buildGuide(
            analysis = analysis(personSegments = listOf(segment)),
            profile = profile
        )

        assertEquals(segment.contour, guide.virtualHuman.contourPathPoints)
        assertEquals(segment.bounds.height, guide.virtualHuman.bounds.height, 0.001f)
    }

    @Test
    fun buildGuide_withoutSegmentation_usesPersonBoxForVirtualHumanHeight() {
        val person = ScenePerson(V2Rect(0.24f, 0.18f, 0.52f, 0.86f), 0.88f)

        val guide = engine.buildGuide(
            analysis = analysis(people = listOf(person)),
            profile = profile
        )

        assertTrue(guide.virtualHuman.contourPathPoints.isEmpty())
        assertTrue(kotlin.math.abs(guide.virtualHuman.bounds.height - person.bounds.height) < 0.08f)
    }

    private fun analysis(
        people: List<ScenePerson> = emptyList(),
        objects: List<SceneObject> = emptyList(),
        personSegments: List<PersonSegmentation> = emptyList(),
        pose: PoseEstimate = PoseEstimate.Empty,
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
            personSegments = personSegments,
            pose = pose,
            semanticScene = scene,
            luminance = luminance,
            modelAvailability = modelAvailability
        )

    private fun completePose(): PoseEstimate =
        PoseEstimate(
            keypoints = listOf(
                keypoint(PoseKeypointName.NOSE, 0.50f, 0.18f),
                keypoint(PoseKeypointName.LEFT_SHOULDER, 0.40f, 0.32f),
                keypoint(PoseKeypointName.RIGHT_SHOULDER, 0.59f, 0.30f),
                keypoint(PoseKeypointName.LEFT_ELBOW, 0.34f, 0.46f),
                keypoint(PoseKeypointName.RIGHT_ELBOW, 0.67f, 0.42f),
                keypoint(PoseKeypointName.LEFT_WRIST, 0.31f, 0.60f),
                keypoint(PoseKeypointName.RIGHT_WRIST, 0.70f, 0.54f),
                keypoint(PoseKeypointName.LEFT_HIP, 0.44f, 0.58f),
                keypoint(PoseKeypointName.RIGHT_HIP, 0.57f, 0.57f),
                keypoint(PoseKeypointName.LEFT_KNEE, 0.38f, 0.76f),
                keypoint(PoseKeypointName.RIGHT_KNEE, 0.61f, 0.72f),
                keypoint(PoseKeypointName.LEFT_ANKLE, 0.34f, 0.94f),
                keypoint(PoseKeypointName.RIGHT_ANKLE, 0.65f, 0.89f)
            ),
            confidence = 0.88f
        )

    private fun keypoint(name: PoseKeypointName, x: Float, y: Float): PoseKeypoint =
        PoseKeypoint(name = name, point = V2Point(x, y), confidence = 0.92f)
}
