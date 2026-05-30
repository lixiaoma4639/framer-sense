package com.framer.sense.feature.camera.pytorch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionGuideEngineTest {

    private val engine = CompositionGuideEngine()

    @Test
    fun buildGuide_emptyScene_generatesCenteredPortraitGuide() {
        val state = engine.buildGuide(
            people = emptyList(),
            objects = emptyList(),
            luminance = 128.0
        )

        assertEquals("ONNX 实时检测中：请让人物走进虚线区域", state.message)
        assertEquals(CameraSceneQuality.NEEDS_MOVE, state.sceneQuality)
        assertTrue(state.guideBounds.centerX in 0.45f..0.55f)
        assertTrue(state.poseLines.isNotEmpty())
    }

    @Test
    fun buildGuide_largeObjectOnLeft_movesGuideToRight() {
        val state = engine.buildGuide(
            people = emptyList(),
            objects = listOf(
                detectedObject(NormalizedRect(0.04f, 0.08f, 0.55f, 0.92f))
            ),
            luminance = 128.0
        )

        assertTrue(state.guideBounds.centerX > 0.6f)
    }

    @Test
    fun buildGuide_objectOnLeftSuggestsMovingRight() {
        val state = engine.buildGuide(
            people = listOf(detectedPerson(NormalizedRect(0.38f, 0.26f, 0.62f, 0.78f))),
            objects = listOf(detectedObject(NormalizedRect(0.02f, 0.12f, 0.90f, 0.90f))),
            luminance = 128.0
        )

        assertEquals(CameraMovementDirection.RIGHT, state.movementDirection)
    }

    @Test
    fun buildGuide_objectOnRightSuggestsMovingLeft() {
        val state = engine.buildGuide(
            people = listOf(detectedPerson(NormalizedRect(0.38f, 0.26f, 0.62f, 0.78f))),
            objects = listOf(detectedObject(NormalizedRect(0.30f, 0.12f, 0.98f, 0.90f))),
            luminance = 128.0
        )

        assertEquals(CameraMovementDirection.LEFT, state.movementDirection)
    }

    @Test
    fun buildGuide_objectOnTopSuggestsMovingDown() {
        val state = engine.buildGuide(
            people = listOf(detectedPerson(NormalizedRect(0.38f, 0.26f, 0.62f, 0.78f))),
            objects = listOf(detectedObject(NormalizedRect(0.20f, 0.02f, 0.80f, 0.42f))),
            luminance = 128.0
        )

        assertEquals(CameraMovementDirection.DOWN, state.movementDirection)
    }

    @Test
    fun buildGuide_objectOnBottomSuggestsMovingUp() {
        val state = engine.buildGuide(
            people = listOf(detectedPerson(NormalizedRect(0.38f, 0.26f, 0.62f, 0.78f))),
            objects = listOf(detectedObject(NormalizedRect(0.20f, 0.58f, 0.80f, 0.98f))),
            luminance = 128.0
        )

        assertEquals(CameraMovementDirection.UP, state.movementDirection)
    }

    @Test
    fun buildGuide_personOutsideGuide_outputsDirectionHint() {
        val state = engine.buildGuide(
            people = listOf(detectedPerson(NormalizedRect(0.12f, 0.24f, 0.32f, 0.82f))),
            objects = emptyList(),
            luminance = 128.0
        )

        assertEquals("人物偏左，向左移动手机", state.message)
        assertEquals(CameraSceneQuality.NEEDS_MOVE, state.sceneQuality)
        assertEquals(CameraMovementDirection.LEFT, state.movementDirection)
    }

    @Test
    fun buildGuide_personAligned_reportsCorrectComposition() {
        val state = engine.buildGuide(
            people = listOf(detectedPerson(NormalizedRect(0.38f, 0.26f, 0.62f, 0.78f))),
            objects = emptyList(),
            luminance = 128.0
        )

        assertEquals("构图正确，保持相机位置", state.message)
        assertEquals(CameraSceneQuality.GOOD, state.sceneQuality)
        assertEquals(CameraMovementDirection.NONE, state.movementDirection)
    }

    @Test
    fun buildGuide_lowLight_outputsPoorSceneHint() {
        val state = engine.buildGuide(
            people = emptyList(),
            objects = emptyList(),
            luminance = 24.0
        )

        assertEquals(CameraSceneQuality.POOR, state.sceneQuality)
        assertEquals("画面偏暗，朝光线更好的方向移动手机", state.message)
    }

    private fun detectedPerson(bounds: NormalizedRect): DetectedObjectFrame =
        DetectedObjectFrame(
            bounds = bounds,
            confidence = 0.9f,
            classId = 1,
            label = "person"
        )

    private fun detectedObject(bounds: NormalizedRect): DetectedObjectFrame =
        DetectedObjectFrame(
            bounds = bounds,
            confidence = 0.9f,
            classId = 63,
            label = "chair"
        )
}
