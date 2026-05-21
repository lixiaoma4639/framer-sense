/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.template.feature.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionGuideEngineTest {

    private val engine = CompositionGuideEngine()

    @Test
    fun buildGuide_emptyScene_generatesCenteredPortraitGuide() {
        val state = engine.buildGuide(
            objects = emptyList(),
            pose = null,
            luminance = 128.0
        )

        assertEquals("实时检测中：未检测到人物，请移动相机对准人物", state.message)
        assertEquals(CameraSceneQuality.NEEDS_MOVE, state.sceneQuality)
        assertTrue(state.guideBounds.centerX in 0.45f..0.55f)
        assertTrue(state.poseLines.isNotEmpty())
    }

    @Test
    fun buildGuide_largeObjectOnLeft_movesGuideToRight() {
        val state = engine.buildGuide(
            objects = listOf(
                DetectedObjectFrame(
                    bounds = NormalizedRect(0.04f, 0.08f, 0.55f, 0.92f),
                    confidence = 0.9f
                )
            ),
            pose = null,
            luminance = 128.0
        )

        assertTrue(state.guideBounds.centerX > 0.6f)
    }

    @Test
    fun buildGuide_poseOutsideGuide_outputsDirectionHint() {
        val state = engine.buildGuide(
            objects = emptyList(),
            pose = DetectedPoseFrame(
                bounds = NormalizedRect(0.12f, 0.24f, 0.32f, 0.82f),
                landmarks = listOf(NormalizedPoint(0.22f, 0.54f))
            ),
            luminance = 128.0
        )

        assertEquals("构图偏左，向左移动相机", state.message)
        assertEquals(CameraSceneQuality.NEEDS_MOVE, state.sceneQuality)
        assertEquals(CameraMovementDirection.LEFT, state.movementDirection)
    }

    @Test
    fun buildGuide_poseRightOfGuide_tellsUserToMoveCameraRight() {
        val state = engine.buildGuide(
            objects = emptyList(),
            pose = DetectedPoseFrame(
                bounds = NormalizedRect(0.68f, 0.24f, 0.88f, 0.82f),
                landmarks = listOf(NormalizedPoint(0.78f, 0.54f))
            ),
            luminance = 128.0
        )

        assertEquals("构图偏右，向右移动相机", state.message)
        assertEquals(CameraMovementDirection.RIGHT, state.movementDirection)
    }

    @Test
    fun buildGuide_poseAboveGuide_tellsUserToMoveCameraUp() {
        val state = engine.buildGuide(
            objects = emptyList(),
            pose = DetectedPoseFrame(
                bounds = NormalizedRect(0.40f, 0.04f, 0.60f, 0.52f),
                landmarks = listOf(NormalizedPoint(0.50f, 0.28f))
            ),
            luminance = 128.0
        )

        assertEquals("构图偏上，向上移动相机", state.message)
        assertEquals(CameraMovementDirection.UP, state.movementDirection)
    }

    @Test
    fun buildGuide_poseBelowGuide_tellsUserToMoveCameraDown() {
        val state = engine.buildGuide(
            objects = emptyList(),
            pose = DetectedPoseFrame(
                bounds = NormalizedRect(0.40f, 0.54f, 0.60f, 0.98f),
                landmarks = listOf(NormalizedPoint(0.50f, 0.76f))
            ),
            luminance = 128.0
        )

        assertEquals("构图偏下，向下移动相机", state.message)
        assertEquals(CameraMovementDirection.DOWN, state.movementDirection)
    }

    @Test
    fun buildGuide_poseAligned_reportsCorrectComposition() {
        val state = engine.buildGuide(
            objects = emptyList(),
            pose = DetectedPoseFrame(
                bounds = NormalizedRect(0.38f, 0.26f, 0.62f, 0.78f),
                landmarks = listOf(NormalizedPoint(0.50f, 0.52f))
            ),
            luminance = 128.0
        )

        assertEquals("实时检测：构图正确，保持相机位置", state.message)
        assertEquals(CameraSceneQuality.GOOD, state.sceneQuality)
        assertEquals(CameraMovementDirection.NONE, state.movementDirection)
    }

    @Test
    fun buildGuide_lowLight_outputsPoorSceneHint() {
        val state = engine.buildGuide(
            objects = emptyList(),
            pose = null,
            luminance = 24.0
        )

        assertEquals(CameraSceneQuality.POOR, state.sceneQuality)
        assertEquals("画面偏暗，朝光线更好的方向移动手机", state.message)
    }
}
