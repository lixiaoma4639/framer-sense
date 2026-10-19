package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.camera.VlmCameraController
import com.framer.sense.feature.camera.vlm.ui.*
import com.framer.sense.feature.camera.vlm.avatar.PlanPreview
import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Rule
import org.junit.Test

/** 验证文案与人像参考切换；真实相机、锚点、GPU 遮挡与拍照由真机清单验收。 */
class ArReferenceUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun trackingLossAndFallbackAreExplicit() {
        val state = mutableStateOf(VlmUiState(ar = ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.NEEDS_GROUND)))
        compose.setContent {
            val context = LocalContext.current.applicationContext
            val controller = remember { VlmCameraController(context) }
            DisposableEffect(controller) { onDispose { controller.close() } }
            MaterialTheme { ArSupportControl(controller,state.value,false,{}) }
        }
        compose.onNodeWithText("请轻点画面中可站立的地面，确认人像落点").assertExists()
        compose.runOnIdle { state.value = state.value.copy(ar=state.value.ar.copy(tracking=ArTrackingState.LOST)) }
        compose.onNodeWithText("空间跟踪暂停，空间人像已隐藏；请重新找回背景和地面").assertExists()
        compose.runOnIdle { state.value = state.value.copy(ar=ArUiState(ArSource.SCREEN,ArTrackingState.UNSUPPORTED)) }
        compose.onNodeWithText("屏幕参考模式：未启用空间定位").assertExists()
    }

    @Test fun alignedReferenceAppearsWithoutGroundAndIsRemovedAfterSpatialSubmission() {
        val plan = CompositionPlan("p", "参考", ShotType.FULL, CropRect(), 1f, AvatarPlacement(), "站立", "环境",
            viewBinding = PhotoViewBinding("scan", "frame", 1))
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val state = mutableStateOf(VlmUiState(referencePlan = plan,
            reference = PlanPreview(bitmap, bitmap, CropRect(), plan),
            ar = ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.NEEDS_GROUND)))
        compose.setContent { MaterialTheme {
            Box(Modifier.size(280.dp, 360.dp)) {
                LiveReferenceAvatar(state.value, Modifier.fillMaxSize())
                BackgroundAlignmentOverlay(state.value, {})
            }
        } }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO)) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertExists()
        compose.onNodeWithTag("vlm_ar_screen_reference").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_MANUAL)) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertExists()
        compose.runOnIdle { state.value = state.value.copy(ar = state.value.ar.copy(
            tracking = ArTrackingState.PLACED, placedReferenceKey = plan.arReferenceKey())) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
        compose.onNodeWithTag("vlm_ar_screen_reference").assertDoesNotExist()
    }

    @Test fun latePlacementCannotHideNewPlanAndUnalignedBackgroundHidesReference() {
        val old = CompositionPlan("p", "参考", ShotType.FULL, CropRect(), 1f, AvatarPlacement(), "站立", "环境",
            viewBinding = PhotoViewBinding("scan", "frame", 1))
        val current = old.copy(revision = 1)
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val state = mutableStateOf(VlmUiState(referencePlan = current,
            reference = PlanPreview(bitmap, bitmap, CropRect(), old),
            background = BackgroundGuidance(BackgroundStatus.ALIGNED_MANUAL),
            ar = ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.PLACED, placedReferenceKey = old.arReferenceKey())))
        compose.setContent { MaterialTheme { LiveReferenceAvatar(state.value, Modifier.size(128.dp)) } }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(reference = PlanPreview(bitmap, bitmap, CropRect(), current)) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertExists()
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance()) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
    }
}
