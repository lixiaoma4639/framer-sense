package com.framer.sense.feature.camera.pytorch.v2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.framer.sense.core.ui.MyApplicationTheme
import org.junit.Rule
import org.junit.Test

class CameraV2ScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun permissionRequired_showsV2PermissionCopy() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraV2ScreenContent(
                    state = CameraV2State(screenState = CameraV2ScreenState.PermissionRequired),
                    onRequestPermission = {},
                    onIntent = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("AI 3D 构图引导 V2").assertIsDisplayed()
        composeTestRule.onNodeWithText("重新授权").assertIsDisplayed()
    }

    @Test
    fun streaming_showsGuideWithoutInlineCaptureButton() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraV2ScreenContent(
                    state = CameraV2State(
                        screenState = CameraV2ScreenState.Streaming,
                        guide = CameraV2Guide.initial().copy(hint = CameraV2Hint.ENTER_GUIDE)
                    ),
                    onRequestPermission = {},
                    onIntent = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("请让人物走进 3D 虚拟人像区域").assertIsDisplayed()
        composeTestRule.onNodeWithText("拍摄").assertDoesNotExist()
    }

    @Test
    fun streaming_showsOnnxLoadingCopyOnlyWhileSessionsLoad() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraV2ScreenContent(
                    state = CameraV2State(
                        screenState = CameraV2ScreenState.Streaming,
                        onnxLoadState = OnnxSessionLoadState.LOADING
                    ),
                    onRequestPermission = {},
                    onIntent = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("正在加载 ONNX 人物、物体和姿态模型").assertIsDisplayed()
    }

    @Test
    fun streaming_showsCameraStartingCopyAfterSessionsAreReady() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraV2ScreenContent(
                    state = CameraV2State(
                        screenState = CameraV2ScreenState.Streaming,
                        onnxLoadState = OnnxSessionLoadState.READY
                    ),
                    onRequestPermission = {},
                    onIntent = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("正在启动相机分析").assertIsDisplayed()
    }

    @Test
    fun failure_showsRetryAction() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraV2ScreenContent(
                    state = CameraV2State(
                        screenState = CameraV2ScreenState.Failure(CameraV2Hint.CAMERA_ERROR)
                    ),
                    onRequestPermission = {},
                    onIntent = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("相机启动失败").assertIsDisplayed()
        composeTestRule.onNodeWithText("重新授权").assertIsDisplayed()
    }
}
