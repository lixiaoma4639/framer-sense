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

package com.framer.sense.feature.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class CameraScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cameraScreen_displaysPermissionDeniedState() {
        composeTestRule.setContent {
            CameraScreenContent(
                uiState = CameraUiState.PermissionDenied,
                captureStatus = PhotoCaptureStatus.Idle,
                captureRequestId = 0,
                onRequestPermission = {},
                onGuideState = {},
                onCaptureClick = {},
                onPhotoSaved = {},
                onPhotoCaptureError = {},
                onCameraError = {}
            )
        }

        composeTestRule.onNodeWithText("AI 构图引导").assertIsDisplayed()
        composeTestRule.onNodeWithText("需要相机权限", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("重新授权").assertIsDisplayed()
    }

    @Test
    fun cameraScreen_displaysReadyGuideState() {
        val guideState = CompositionGuideEngine().buildGuide(
            objects = emptyList(),
            pose = null,
            luminance = 128.0
        )

        composeTestRule.setContent {
            CameraScreenContent(
                uiState = CameraUiState.Ready(guideState),
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

        composeTestRule.onNodeWithText("AI 构图引导").assertIsDisplayed()
        composeTestRule.onNodeWithText("实时检测中：未检测到人物，请移动相机对准人物").assertIsDisplayed()
        composeTestRule.onNodeWithText("拍摄").assertIsDisplayed()
    }

    @Test
    fun cameraScreen_displaysErrorState() {
        composeTestRule.setContent {
            CameraScreenContent(
                uiState = CameraUiState.Error("无法启动相机"),
                captureStatus = PhotoCaptureStatus.Idle,
                captureRequestId = 0,
                onRequestPermission = {},
                onGuideState = {},
                onCaptureClick = {},
                onPhotoSaved = {},
                onPhotoCaptureError = {},
                onCameraError = {}
            )
        }

        composeTestRule.onNodeWithText("相机启动失败").assertIsDisplayed()
        composeTestRule.onNodeWithText("无法启动相机").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun cameraScreen_displaysSavingStatus() {
        composeTestRule.setContent {
            CameraScreenContent(
                uiState = CameraUiState.Ready(),
                captureStatus = PhotoCaptureStatus.Saving,
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

        composeTestRule.onNodeWithText("正在保存到相册...").assertIsDisplayed()
        composeTestRule.onNodeWithText("保存中").assertIsDisplayed()
    }
}
