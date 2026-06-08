package com.framer.sense.feature.camera.pytorch.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onIntent(CameraIntent.CameraPermissionResult(granted))
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onIntent(CameraIntent.LegacyStoragePermissionResult(granted))
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    //启动一个 Compose 副作用。Unit 作为 key，表示该副作用在当前 Composable 首次进入组合时执行一次。
    LaunchedEffect(Unit) {
        viewModel.onIntent(CameraIntent.PageStarted(hasCameraPermission()))
    }

    //启动另一个副作用，用于收集 ViewModel 发出的单次事件。key 是 viewModel，ViewModel 变化时会重新启动收集。
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CameraEffect.RequestCameraPermission -> {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                CameraEffect.RequestLegacyStoragePermission -> {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    CameraScreenContent(
        uiState = uiState,
        onRequestPermission = {
            viewModel.onIntent(CameraIntent.RequestPermissionClicked(hasCameraPermission()))
        },
        onCaptureClick = {
            viewModel.onIntent(CameraIntent.CaptureClicked(needsLegacyStoragePermission()))
        },
        onIntent = viewModel::onIntent,
        modifier = modifier
    )
}

/**
 * internal 同一个模块（Module）内」被访问
 */
@Composable
internal fun CameraScreenContent(
    uiState: CameraUiState,
    onRequestPermission: () -> Unit,
    onCaptureClick: () -> Unit,
    onIntent: (CameraIntent) -> Unit,
    modifier: Modifier = Modifier,
    showCameraPreview: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (val screenState = uiState.screenState) {
            CameraScreenState.Loading -> CameraMessage(
                title = "AI 构图引导",
                message = "正在启动 ONNX 相机模型",
                showProgress = true
            )

            CameraScreenState.PermissionDenied -> CameraMessage(
                title = "AI 构图引导",
                message = "需要相机权限，请允许访问相机，以便实时识别画面并绘制构图虚线",
                actionText = "重新授权",
                onAction = onRequestPermission
            )

            CameraScreenState.Ready -> {
                if (showCameraPreview) {
                    CameraPreview(
                        captureRequestId = uiState.captureRequestId,
                        onGuideState = { guideState ->
                            onIntent(CameraIntent.GuideStateChanged(guideState))
                        },
                        onPhotoSaved = {
                            onIntent(CameraIntent.PhotoSaved)
                        },
                        onPhotoCaptureError = { throwable ->
                            onIntent(CameraIntent.PhotoCaptureFailed(throwable.message))
                        },
                        onCameraError = { throwable ->
                            onIntent(CameraIntent.CameraFailed(throwable.message))
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                CameraGuideOverlay(
                    guideState = uiState.guideState,
                    modifier = Modifier.fillMaxSize()
                )
                CameraCaptureControls(
                    captureStatus = uiState.captureStatus,
                    onCaptureClick = onCaptureClick,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            is CameraScreenState.Error -> CameraMessage(
                title = "相机启动失败",
                message = screenState.message,
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
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .background(Color.Black.copy(alpha = 0.20f)),
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
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp)
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showProgress) {
            CircularProgressIndicator(color = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (actionText != null && onAction != null) {
            Button(onClick = onAction) {
                Text(actionText)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CameraScreenPreview() {
    MyApplicationTheme {
        CameraScreenContent(
            uiState = CameraUiState(screenState = CameraScreenState.Ready),
            onRequestPermission = {},
            onCaptureClick = {},
            onIntent = {},
            showCameraPreview = false
        )
    }
}
