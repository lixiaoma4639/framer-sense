package com.framer.sense.feature.camera.pytorch.v2.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun CameraV2Preview(
    bodyProfile: BodyProfile,
    captureToken: Int,
    onGuide: (CameraV2Guide) -> Unit,
    onPhotoSaved: (Uri) -> Unit,
    onPhotoError: (Throwable) -> Unit,
    onCameraError: (Throwable) -> Unit,
    targetRotation: Int = Surface.ROTATION_0,
    modifier: Modifier = Modifier
) {
    var lastHandledCaptureToken by rememberSaveable { mutableIntStateOf(0) }
    key(bodyProfile.safeHeightCm, bodyProfile.safeWeightKg) {
        CameraV2PreviewInstance(
            bodyProfile = bodyProfile,
            captureToken = captureToken,
            onGuide = onGuide,
            onPhotoSaved = onPhotoSaved,
            onPhotoError = onPhotoError,
            onCameraError = onCameraError,
            lastHandledCaptureToken = lastHandledCaptureToken,
            onCaptureTokenHandled = { lastHandledCaptureToken = it },
            targetRotation = targetRotation,
            modifier = modifier
        )
    }
}

@Composable
private fun CameraV2PreviewInstance(
    bodyProfile: BodyProfile,
    captureToken: Int,
    onGuide: (CameraV2Guide) -> Unit,
    onPhotoSaved: (Uri) -> Unit,
    onPhotoError: (Throwable) -> Unit,
    onCameraError: (Throwable) -> Unit,
    lastHandledCaptureToken: Int,
    onCaptureTokenHandled: (Int) -> Unit,
    targetRotation: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnGuide by rememberUpdatedState(onGuide)
    val currentOnPhotoSaved by rememberUpdatedState(onPhotoSaved)
    val currentOnPhotoError by rememberUpdatedState(onPhotoError)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember(targetRotation) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzer = remember(bodyProfile.safeHeightCm, bodyProfile.safeWeightKg) {
        CameraV2FrameAnalyzer(
            context = context,
            bodyProfile = bodyProfile,
            callbackExecutor = mainExecutor,
            onGuide = { currentOnGuide(it) },
            onError = { currentOnCameraError(it) }
        )
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )

    LaunchedEffect(context, lifecycleOwner, previewView, analyzer, imageCapture, targetRotation) {
        try {
            val provider = context.awaitCameraProvider()
            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (throwable: Throwable) {
            currentOnCameraError(throwable)
        }
    }

    LaunchedEffect(captureToken) {
        if (captureToken > lastHandledCaptureToken) {
            onCaptureTokenHandled(captureToken)
            imageCapture.saveToGallery(
                context = context,
                onSaved = currentOnPhotoSaved,
                onError = currentOnPhotoError
            )
        }
    }

    DisposableEffect(context, analyzer) {
        onDispose {
            analyzer.close()
            analysisExecutor.shutdown()
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener(
                { providerFuture.get().unbindAll() },
                ContextCompat.getMainExecutor(context)
            )
        }
    }
}

private fun ImageCapture.saveToGallery(
    context: Context,
    onSaved: (Uri) -> Unit,
    onError: (Throwable) -> Unit
) {
    val values = ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "FramerSenseV2_${System.currentTimeMillis()}")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Framer Sense"
            )
        }
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    ).build()

    takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                outputFileResults.savedUri?.let(onSaved)
                    ?: onError(IllegalStateException("Photo saved without returned uri"))
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (throwable: Throwable) {
                    continuation.resumeWithException(throwable)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
