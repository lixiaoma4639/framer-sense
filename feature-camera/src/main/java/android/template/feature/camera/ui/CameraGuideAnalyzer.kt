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

import android.annotation.SuppressLint
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class CameraGuideAnalyzer(
    private val callbackExecutor: Executor,
    private val onGuideState: (CameraGuideState) -> Unit,
    private val onAnalysisError: (Throwable) -> Unit = {}
) : ImageAnalysis.Analyzer, Closeable {

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )
    private val guideEngine = CompositionGuideEngine()
    private val isAnalyzing = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private var lastAnalysisTimestamp = 0L
    private var currentImageProxy: ImageProxy? = null
    private var nextAnalysisId = 0L
    private var activeAnalysisId = 0L

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        if (isClosed.get()) {
            imageProxy.close()
            return
        }

        if (now - lastAnalysisTimestamp < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        if (!isAnalyzing.compareAndSet(false, true)) {
            if (now - lastAnalysisTimestamp > ANALYSIS_TIMEOUT_MS && isAnalyzing.compareAndSet(true, false)) {
                currentImageProxy?.close()
                currentImageProxy = null
            } else {
                imageProxy.close()
                return
            }
            if (!isAnalyzing.compareAndSet(false, true)) {
                imageProxy.close()
                return
            }
        }

        lastAnalysisTimestamp = now
        val analysisId = ++nextAnalysisId
        activeAnalysisId = analysisId
        currentImageProxy = imageProxy

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            currentImageProxy = null
            isAnalyzing.set(false)
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val luminance = imageProxy.averageLuminance()
        val outputWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.height else imageProxy.width
        val outputHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageProxy.width else imageProxy.height

        val objectTask = objectDetector.process(inputImage)
        val poseTask = poseDetector.process(inputImage)

        Tasks.whenAllComplete(objectTask, poseTask)
            .addOnCompleteListener(callbackExecutor) {
                try {
                    if (isClosed.get()) return@addOnCompleteListener

                    val objects = if (objectTask.isSuccessful) {
                        objectTask.result.orEmpty().map { detectedObject ->
                            detectedObject.toDetectedObjectFrame(outputWidth, outputHeight)
                        }
                    } else {
                        emptyList()
                    }
                    val pose = if (poseTask.isSuccessful) {
                        poseTask.result?.toDetectedPoseFrame(outputWidth, outputHeight)
                    } else {
                        null
                    }
                    onGuideState(
                        guideEngine.buildGuide(
                            objects = objects,
                            pose = pose,
                            luminance = luminance
                        )
                    )
                } catch (throwable: Throwable) {
                    onAnalysisError(throwable)
                } finally {
                    if (activeAnalysisId == analysisId && currentImageProxy === imageProxy) {
                        currentImageProxy = null
                        isAnalyzing.set(false)
                    }
                    imageProxy.close()
                }
            }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            currentImageProxy?.close()
            currentImageProxy = null
            objectDetector.close()
            poseDetector.close()
        }
    }

    private fun DetectedObject.toDetectedObjectFrame(width: Int, height: Int): DetectedObjectFrame =
        DetectedObjectFrame(
            bounds = boundingBox.toNormalizedRect(width, height),
            confidence = labels.maxOfOrNull { it.confidence } ?: 1f
        )

    private fun Pose.toDetectedPoseFrame(width: Int, height: Int): DetectedPoseFrame? {
        val points = allPoseLandmarks.map { landmark ->
            NormalizedPoint(
                x = (landmark.position.x / width).coerceIn(0f, 1f),
                y = (landmark.position.y / height).coerceIn(0f, 1f)
            )
        }
        if (points.isEmpty()) return null

        val bounds = NormalizedRect(
            left = points.minOf { it.x },
            top = points.minOf { it.y },
            right = points.maxOf { it.x },
            bottom = points.maxOf { it.y }
        )
        return DetectedPoseFrame(bounds = bounds, landmarks = points)
    }

    private fun Rect.toNormalizedRect(width: Int, height: Int): NormalizedRect =
        NormalizedRect(
            left = left / width.toFloat(),
            top = top / height.toFloat(),
            right = right / width.toFloat(),
            bottom = bottom / height.toFloat()
        ).clamped()

    private fun ImageProxy.averageLuminance(): Double {
        val buffer = planes.firstOrNull()?.buffer ?: return 128.0
        val duplicate = buffer.duplicate()
        var sum = 0L
        var count = 0
        val step = maxOf(1, duplicate.remaining() / LUMINANCE_SAMPLE_COUNT)
        while (duplicate.hasRemaining()) {
            sum += duplicate.get().toInt() and 0xFF
            count++
            val nextPosition = (duplicate.position() + step).coerceAtMost(duplicate.limit())
            duplicate.position(nextPosition)
        }
        return if (count == 0) 128.0 else sum.toDouble() / count
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 450L
        const val ANALYSIS_TIMEOUT_MS = 2_000L
        const val LUMINANCE_SAMPLE_COUNT = 800
    }
}
