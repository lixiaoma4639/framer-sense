package com.framer.sense.feature.camera.vlm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.avatar.PhotoActionResolver
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
        val state = mutableStateOf(VlmUiState(cameraMode=CameraMode.AR,
            ar=ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.NEEDS_GROUND)))
        compose.setContent { MaterialTheme { PhotoSettingsDialog(state.value,{}, {}) } }
        compose.onNodeWithText("请轻点画面中可站立的地面，确认人像落点").assertExists()
        compose.runOnIdle { state.value = state.value.copy(ar=state.value.ar.copy(tracking=ArTrackingState.LOST)) }
        compose.onNodeWithText("空间跟踪暂停，空间人像已隐藏；请重新找回背景和地面").assertExists()
    }

    @Test fun arAlignedReferenceDoesNotUseScreenFallbackWithoutGround() {
        val plan = CompositionPlan("p", "参考", ShotType.FULL, CropRect(), 1f, AvatarPlacement(), "站立", "环境",
            viewBinding = PhotoViewBinding("scan", "frame", 1))
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val state = mutableStateOf(VlmUiState(referencePlan = plan,
            reference = PlanPreview(bitmap, bitmap, CropRect(), plan),
            ar = ArUiState(ArSource.SHARED_CAMERA, ArTrackingState.NEEDS_GROUND)))
        compose.setContent { MaterialTheme { LiveReferenceAvatar(state.value, Modifier.size(128.dp)) } }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_AUTO)) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
        compose.onNodeWithTag("vlm_ar_screen_reference").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance(BackgroundStatus.ALIGNED_MANUAL)) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
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
        compose.runOnIdle { state.value = state.value.copy(reference = PlanPreview(bitmap, bitmap, CropRect(), current), ar=ArUiState(ArSource.SCREEN)) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(background = BackgroundGuidance()) }
        compose.onNodeWithTag("vlm_live_reference_avatar").assertDoesNotExist()
    }
    @Test fun allThreeCardsStartWithoutPeopleAndKeepSelectionAfterCalibration() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val plans=(1..3).map { CompositionPlan("p$it","方案$it",ShotType.FULL,CropRect(),1f,AvatarPlacement(),"站立","环境") }
        val state=mutableStateOf(VlmUiState(stage=VlmStage.READY,cameraMode=CameraMode.AR,selectedId="p2",
            result=CompositionResult(plans,emptyList(),emptyList(),emptyList())))
        compose.setContent { MaterialTheme { CompositionCards(state.value,{}) } }
        plans.forEach { compose.onNodeWithTag("vlm_composition_${it.id}").assertExists() }
        compose.onAllNodesWithText(context.getString(R.string.vlm_scale_unavailable)).assertCountEquals(3)
        val snapshot=ArScaleSnapshot("world",1,1,SpatialMatrices.identity(),SpatialMatrices.identity(),SpatialPoint(0f,1f,0f),480,640)
        val metadata=AvatarScaleMetadata("vlm_avatars/adult_female.glb",1,1,SpatialPoint(0f,0f,0f),SpatialPoint(0f,1.6f,0f))
        val placement=ResolvedSpatialPlacement(snapshot,"floor",SpatialPoint(0f,0f,-3f),0f,1.65f,metadata,Point2(.5f,.9f),.6f,3f)
        compose.runOnIdle { state.value=state.value.copy(result=state.value.result!!.copy(plans=plans.map { PhotoActionResolver().resolve(it.copy(arCamera=snapshot,viewBinding=PhotoViewBinding("scan","frame",1,"world"))).withSpatial(placement).withRevision(1) })) }
        compose.onAllNodesWithText(context.getString(R.string.vlm_scale_calibrated)).assertCountEquals(3)
        compose.runOnIdle { org.junit.Assert.assertEquals("p2",state.value.selectedId) }
    }

}
