package com.framer.sense.feature.camera.vlm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.framer.sense.feature.camera.vlm.ar.*
import com.framer.sense.feature.camera.vlm.camera.VlmCameraController
import com.framer.sense.feature.camera.vlm.ui.*
import org.junit.Rule
import org.junit.Test

/** 仅验证状态文案；真实相机、锚点、GPU 遮挡与拍照由真机清单验收。 */
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
        compose.onNodeWithText("空间跟踪暂停，人像已隐藏；请重新找回背景和地面").assertExists()
        compose.runOnIdle { state.value = state.value.copy(ar=ArUiState(ArSource.SCREEN,ArTrackingState.UNSUPPORTED)) }
        compose.onNodeWithText("屏幕参考模式：未启用空间定位").assertExists()
    }
}
