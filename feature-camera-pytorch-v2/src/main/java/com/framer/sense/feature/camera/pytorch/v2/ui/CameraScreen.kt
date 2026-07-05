package com.framer.sense.feature.camera.pytorch.v2.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.framer.sense.core.ui.MyApplicationTheme
import com.framer.sense.feature.camera.pytorch.v2.R

@Composable
fun CameraScreen(
    heightCm: Int = BodyProfile.DEFAULT_HEIGHT_CM,
    weightKg: Int = BodyProfile.DEFAULT_WEIGHT_KG,
    modifier: Modifier = Modifier,
    viewModel: CameraV2ViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onIntent(CameraV2Intent.CameraPermissionResult(granted))
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onIntent(CameraV2Intent.LegacyStoragePermissionResult(granted))
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    LaunchedEffect(heightCm, weightKg) {
        viewModel.onIntent(
            CameraV2Intent.Entered(
                hasCameraPermission = hasCameraPermission(),
                heightCm = heightCm,
                weightKg = weightKg
            )
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CameraV2Effect.RequestCameraPermission -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                CameraV2Effect.RequestLegacyStoragePermission -> {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    CameraV2ScreenContent(
        state = state,
        onRequestPermission = {
            viewModel.onIntent(CameraV2Intent.PermissionButtonClicked(hasCameraPermission()))
        },
        onCaptureClick = {
            viewModel.onIntent(CameraV2Intent.CapturePressed(needsLegacyStoragePermission()))
        },
        onIntent = viewModel::onIntent,
        modifier = modifier
    )
}

@Composable
internal fun CameraV2ScreenContent(
    state: CameraV2State,
    onRequestPermission: () -> Unit,
    onCaptureClick: () -> Unit,
    onIntent: (CameraV2Intent) -> Unit,
    modifier: Modifier = Modifier,
    showCameraPreview: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (val screenState = state.screenState) {
            CameraV2ScreenState.Booting -> CameraV2Message(
                title = stringResource(R.string.camera_v2_title),
                message = stringResource(R.string.camera_v2_booting_message),
                showProgress = true
            )

            CameraV2ScreenState.PermissionRequired -> CameraV2Message(
                title = stringResource(R.string.camera_v2_title),
                message = stringResource(R.string.camera_v2_permission_message),
                actionText = stringResource(R.string.camera_v2_permission_action),
                onAction = onRequestPermission
            )

            CameraV2ScreenState.Streaming -> {
                if (showCameraPreview) {
                    CameraV2Preview(
                        bodyProfile = state.bodyProfile,
                        captureToken = state.captureToken,
                        onGuide = { onIntent(CameraV2Intent.GuideProduced(it)) },
                        onPhotoSaved = { onIntent(CameraV2Intent.PhotoSaved) },
                        onPhotoError = { onIntent(CameraV2Intent.PhotoSaveFailed(it)) },
                        onCameraError = { onIntent(CameraV2Intent.CameraIssue(it)) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                CameraV2Overlay(
                    guide = state.guide,
                    modifier = Modifier.fillMaxSize()
                )
                CameraV2CaptureControls(
                    captureState = state.captureState,
                    bodyProfile = state.bodyProfile,
                    onCaptureClick = onCaptureClick,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            is CameraV2ScreenState.Failure -> CameraV2Message(
                title = stringResource(R.string.camera_v2_camera_error_title),
                message = screenState.detail ?: stringResource(screenState.hint.messageRes),
                actionText = stringResource(R.string.camera_v2_permission_action),
                onAction = onRequestPermission
            )
        }
    }
}

@Composable
private fun CameraV2CaptureControls(
    captureState: PhotoV2CaptureState,
    bodyProfile: BodyProfile,
    onCaptureClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.30f))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(
                R.string.camera_v2_body_profile,
                bodyProfile.safeHeightCm,
                bodyProfile.safeWeightKg
            ),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.82f)
        )
        when (captureState) {
            PhotoV2CaptureState.Idle -> Unit
            PhotoV2CaptureState.Saving -> Text(
                text = stringResource(R.string.camera_v2_capture_saving),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            PhotoV2CaptureState.Saved -> Text(
                text = stringResource(R.string.camera_v2_capture_saved),
                color = colorResource(R.color.camera_v2_good),
                style = MaterialTheme.typography.bodyMedium
            )
            is PhotoV2CaptureState.Error -> Text(
                text = stringResource(captureState.hint.messageRes),
                color = colorResource(R.color.camera_v2_poor),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick = onCaptureClick,
            enabled = captureState !is PhotoV2CaptureState.Saving,
            shape = CircleShape
        ) {
            Text(
                text = if (captureState is PhotoV2CaptureState.Saving) {
                    stringResource(R.string.camera_v2_capture_saving_short)
                } else {
                    stringResource(R.string.camera_v2_capture_action)
                },
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun CameraV2Message(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        color = Color.Black,
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (showProgress) {
                CircularProgressIndicator(color = Color.White)
            }
            Text(
                text = title,
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
}

@Preview(showBackground = true)
@Composable
private fun CameraV2ScreenPreview() {
    MyApplicationTheme {
        CameraV2ScreenContent(
            state = CameraV2State(
                screenState = CameraV2ScreenState.Streaming,
                guide = CameraV2Guide.initial()
            ),
            onRequestPermission = {},
            onCaptureClick = {},
            onIntent = {},
            showCameraPreview = false
        )
    }
}
