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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.framer.sense.core.ui.MyApplicationTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<CameraUiState>(CameraUiState.Loading) }
    var captureStatus by remember { mutableStateOf<PhotoCaptureStatus>(PhotoCaptureStatus.Idle) }
    var captureRequestId by remember { mutableIntStateOf(0) }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequested = true
        uiState = if (granted) {
            CameraUiState.Ready()
        } else {
            CameraUiState.PermissionDenied
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            captureStatus = PhotoCaptureStatus.Saving
            captureRequestId += 1
        } else {
            captureStatus = PhotoCaptureStatus.Error("需要存储权限才能保存到系统相册")
        }
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) {
        if (hasCameraPermission()) {
            uiState = CameraUiState.Ready()
        } else if (!permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            uiState = CameraUiState.PermissionDenied
        }
    }

    CameraScreenContent(
        uiState = uiState,
        captureStatus = captureStatus,
        captureRequestId = captureRequestId,
        onRequestPermission = {
            if (hasCameraPermission()) {
                uiState = CameraUiState.Ready()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onGuideState = { guideState ->
            uiState = CameraUiState.Ready(guideState)
        },
        onCaptureClick = {
            if (needsLegacyStoragePermission()) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                captureStatus = PhotoCaptureStatus.Saving
                captureRequestId += 1
            }
        },
        onPhotoSaved = {
            captureStatus = PhotoCaptureStatus.Saved
        },
        onPhotoCaptureError = { throwable ->
            captureStatus = PhotoCaptureStatus.Error(throwable.message ?: "照片保存失败")
        },
        onCameraError = { throwable ->
            uiState = CameraUiState.Error(throwable.message ?: "无法启动相机")
        },
        modifier = modifier
    )
}

@Composable
internal fun CameraScreenContent(
    uiState: CameraUiState,
    captureStatus: PhotoCaptureStatus,
    captureRequestId: Int,
    onRequestPermission: () -> Unit,
    onGuideState: (CameraGuideState) -> Unit,
    onCaptureClick: () -> Unit,
    onPhotoSaved: () -> Unit,
    onPhotoCaptureError: (Throwable) -> Unit,
    onCameraError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    showCameraPreview: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (uiState) {
            CameraUiState.Loading -> CameraMessage(
                title = "AI 构图引导",
                message = "正在启动相机",
                showProgress = true
            )

            CameraUiState.PermissionDenied -> CameraMessage(
                title = "AI 构图引导",
                message = "需要相机权限，请允许访问相机，以便实时识别画面并绘制构图虚线",
                actionText = "重新授权",
                onAction = onRequestPermission
            )

            is CameraUiState.Ready -> {
                if (showCameraPreview) {
                    CameraPreview(
                        captureRequestId = captureRequestId,
                        onGuideState = onGuideState,
                        onPhotoSaved = { onPhotoSaved() },
                        onPhotoCaptureError = onPhotoCaptureError,
                        onCameraError = onCameraError,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                CameraGuideOverlay(
                    guideState = uiState.guideState,
                    modifier = Modifier.fillMaxSize()
                )
                CameraCaptureControls(
                    captureStatus = captureStatus,
                    onCaptureClick = onCaptureClick,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            is CameraUiState.Error -> CameraMessage(
                title = "相机启动失败",
                message = uiState.message,
                actionText = "重试",
                onAction = onRequestPermission
            )
        }
    }
}

@Composable
private fun CameraCaptureControls(
    captureStatus: PhotoCaptureStatus,
    onCaptureClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.20f))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (captureStatus) {
            PhotoCaptureStatus.Idle -> Unit
            PhotoCaptureStatus.Saving -> Text(
                text = "正在保存到相册...",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            PhotoCaptureStatus.Saved -> Text(
                text = "照片已保存到系统相册",
                color = Color(0xFF5EF2A0),
                style = MaterialTheme.typography.bodyMedium
            )
            is PhotoCaptureStatus.Error -> Text(
                text = captureStatus.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick = onCaptureClick,
            enabled = captureStatus !is PhotoCaptureStatus.Saving,
            shape = CircleShape,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Text(
                text = if (captureStatus is PhotoCaptureStatus.Saving) "保存中" else "拍摄",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun CameraMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showProgress) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.82f),
            textAlign = TextAlign.Center
        )
        if (actionText != null && onAction != null) {
            Button(onClick = onAction) {
                Text(text = actionText)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CameraScreenPreview() {
    MyApplicationTheme {
        CameraScreenContent(
            uiState = CameraUiState.Ready(),
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
