package com.framer.sense.feature.camera.vlm

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraPhotoViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rotatingPanelPreservesPhotoPixelsWithoutApplyingTheParentAngleTwice() {
        val phone = mutableFloatStateOf(0f)
        compose.setContent {
            CompositionLocalProvider(LocalCameraIconAngle provides phone.floatValue) {
                CameraRotatingViewport(Modifier.size(160.dp, 240.dp).testTag("fixed_panel"), preservePhotoDirection = true) {
                    CameraPhotoViewport(90, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Color.Blue)) {
                            Box(Modifier.fillMaxSize(.3f).background(Color.Red))
                            Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).fillMaxSize(.3f).background(Color.Green))
                        }
                    }
                }
            }
        }
        val panel = compose.onNodeWithTag("fixed_panel")
        val bounds = panel.getUnclippedBoundsInRoot()
        listOf(0f, 90f, 180f, 270f, 0f).forEach { direction ->
            compose.runOnIdle { phone.floatValue = direction }
            val pixels = panel.captureToImage().toPixelMap()
            assertEquals(Color.Red, pixels[(pixels.width * .9f).toInt(), (pixels.height * .1f).toInt()])
            assertEquals(Color.Green, pixels[(pixels.width * .1f).toInt(), (pixels.height * .9f).toInt()])
            assertEquals(bounds, panel.getUnclippedBoundsInRoot())
        }
    }

    @Test fun photoAndAvatarRotateTogetherAndStayAtCaptureDirectionWhenPhoneTurns() {
        val captured = mutableIntStateOf(0)
        val currentPhone = mutableFloatStateOf(0f)
        var measured = IntSize.Zero
        compose.setContent {
            CompositionLocalProvider(LocalCameraIconAngle provides currentPhone.floatValue) {
                CameraPhotoViewport(captured.intValue, Modifier.size(160.dp, 240.dp).testTag("photo_viewport")) {
                    Box(Modifier.fillMaxSize().background(Color.Blue).onSizeChanged { measured = it }) {
                        // 背景左上红块与人物右下绿块必须作为一个整体旋转。
                        Box(Modifier.fillMaxSize(.3f).background(Color.Red))
                        Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).fillMaxSize(.3f).background(Color.Green))
                    }
                }
            }
        }
        compose.waitForIdle()
        val portrait = measured
        listOf(0, 90, 180, 270).forEach { capture ->
            compose.runOnIdle { captured.intValue = capture }
            val photo = compose.onNodeWithTag("photo_viewport")
            val originalBounds = photo.getUnclippedBoundsInRoot()
            val pixels = photo.captureToImage().toPixelMap()
            fun color(x: Float, y: Float) = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
            when (capture) {
                90 -> { assertEquals(Color.Red, color(.9f, .1f)); assertEquals(Color.Green, color(.1f, .9f)) }
                180 -> { assertEquals(Color.Red, color(.9f, .9f)); assertEquals(Color.Green, color(.1f, .1f)) }
                270 -> { assertEquals(Color.Red, color(.1f, .9f)); assertEquals(Color.Green, color(.9f, .1f)) }
                else -> { assertEquals(Color.Red, color(.1f, .1f)); assertEquals(Color.Green, color(.9f, .9f)) }
            }
            listOf(90f, 180f, 270f, 0f).forEach { phone ->
                compose.runOnIdle { currentPhone.floatValue = phone }
                photo.assert(SemanticsMatcher.expectValue(CameraPhotoDegrees, capture))
                assertEquals(originalBounds, photo.getUnclippedBoundsInRoot())
                assertEquals(if (capture % 180 == 0) portrait else IntSize(portrait.height, portrait.width), measured)
            }
        }
    }

    @Test fun physicalDragMapsBackToPhotoXAxisInAllCaptureDirections() {
        val captured = mutableIntStateOf(0)
        var moved = Offset.Zero
        compose.setContent {
            CameraPhotoViewport(captured.intValue, Modifier.size(160.dp, 240.dp).testTag("drag_viewport")) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures { change, amount -> change.consume(); moved += amount }
                })
            }
        }
        listOf(0, 90, 180, 270).forEach { capture ->
            compose.runOnIdle { captured.intValue = capture; moved = Offset.Zero }
            compose.onNodeWithTag("drag_viewport").performTouchInput {
                val direction = when (capture) {
                    90 -> Offset(0f, height * .3f)
                    180 -> Offset(-width * .3f, 0f)
                    270 -> Offset(0f, -height * .3f)
                    else -> Offset(width * .3f, 0f)
                }
                swipe(center - direction / 2f, center + direction / 2f, durationMillis = 300)
            }
            compose.runOnIdle {
                assertTrue("$capture 度的物理拖动应映射到照片 x 正轴", moved.x > 0)
                assertEquals(0f, moved.y, .1f)
            }
        }
    }
}
