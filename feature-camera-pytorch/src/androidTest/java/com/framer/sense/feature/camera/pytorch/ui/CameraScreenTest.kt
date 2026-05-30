package com.framer.sense.feature.camera.pytorch.ui

import com.framer.sense.core.ui.MyApplicationTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class CameraScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun permissionDenied_showsPermissionMessage() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraScreenContent(
                    uiState = CameraUiState.PermissionDenied,
                    captureStatus = PhotoCaptureStatus.Idle,
                    captureRequestId = 0,
                    onRequestPermission = {},
                    onGuideState = {},
                    onCaptureClick = {},
                    onPhotoSaved = {},
                    onPhotoCaptureError = {},
                    onCameraError = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("AI 构图引导").assertIsDisplayed()
        composeTestRule.onNodeWithText("重新授权").assertIsDisplayed()
    }

    @Test
    fun ready_showsOnnxGuideHint() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraScreenContent(
                    uiState = CameraUiState.Ready(
                        CameraGuideState.Initial.copy(
                            message = "ONNX 实时检测中：请让人物走进虚线区域",
                            poseLines = listOf(
                                NormalizedLine(
                                    NormalizedPoint(0.5f, 0.2f),
                                    NormalizedPoint(0.5f, 0.8f)
                                )
                            )
                        )
                    ),
                    captureStatus = PhotoCaptureStatus.Idle,
                    captureRequestId = 0,
                    onRequestPermission = {},
                    onGuideState = {},
                    onCaptureClick = {},
                    onPhotoSaved = {},
                    onPhotoCaptureError = {},
                    onCameraError = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("AI 构图引导").assertIsDisplayed()
        composeTestRule.onNodeWithText("ONNX 端侧模型").assertIsDisplayed()
        composeTestRule.onNodeWithText("ONNX 实时检测中：请让人物走进虚线区域").assertIsDisplayed()
    }

    @Test
    fun error_showsRetryAction() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CameraScreenContent(
                    uiState = CameraUiState.Error("无法启动 ONNX 相机引导"),
                    captureStatus = PhotoCaptureStatus.Idle,
                    captureRequestId = 0,
                    onRequestPermission = {},
                    onGuideState = {},
                    onCaptureClick = {},
                    onPhotoSaved = {},
                    onPhotoCaptureError = {},
                    onCameraError = {},
                    showCameraPreview = false
                )
            }
        }

        composeTestRule.onNodeWithText("相机启动失败").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").assertIsDisplayed()
    }
}
