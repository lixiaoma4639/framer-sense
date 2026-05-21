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

package android.template.feature.camera.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun CameraPreview(
    captureRequestId: Int,
    onGuideState: (CameraGuideState) -> Unit,
    onPhotoSaved: (Uri) -> Unit,
    onPhotoCaptureError: (Throwable) -> Unit,
    onCameraError: (Throwable) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnGuideState by rememberUpdatedState(onGuideState)
    val currentOnPhotoSaved by rememberUpdatedState(onPhotoSaved)
    val currentOnPhotoCaptureError by rememberUpdatedState(onPhotoCaptureError)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzer = remember {
        CameraGuideAnalyzer(
            callbackExecutor = mainExecutor,
            onGuideState = { currentOnGuideState(it) },
            onAnalysisError = { currentOnCameraError(it) }
        )
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )

    LaunchedEffect(context, lifecycleOwner, previewView) {
        try {
            val cameraProvider = context.awaitCameraProvider()
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
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

    LaunchedEffect(captureRequestId) {
        if (captureRequestId > 0) {
            imageCapture.savePhotoToGallery(
                context = context,
                onPhotoSaved = currentOnPhotoSaved,
                onPhotoCaptureError = currentOnPhotoCaptureError
            )
        }
    }

    DisposableEffect(context) {
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

private fun ImageCapture.savePhotoToGallery(
    context: Context,
    onPhotoSaved: (Uri) -> Unit,
    onPhotoCaptureError: (Throwable) -> Unit
) {
    val name = "FramerSense_${System.currentTimeMillis()}"
    val contentValues = ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
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
        contentValues
    ).build()

    takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val savedUri = outputFileResults.savedUri
                if (savedUri != null) {
                    onPhotoSaved(savedUri)
                } else {
                    onPhotoCaptureError(IllegalStateException("照片已保存，但未返回相册 URI"))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onPhotoCaptureError(exception)
            }
        }
    )
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    continuation.resume(providerFuture.get())
                } catch (throwable: Throwable) {
                    continuation.resumeWithException(throwable)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
        continuation.invokeOnCancellation { providerFuture.cancel(true) }
    }
