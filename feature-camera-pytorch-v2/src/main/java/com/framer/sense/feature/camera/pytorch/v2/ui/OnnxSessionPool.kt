package com.framer.sense.feature.camera.pytorch.v2.ui

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.io.File

class OnnxSessionPool(
    context: Context
) : Closeable {

    private val appContext = context.applicationContext
    val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    val objectSession: OrtSession? = createSessionIfPresent(OBJECT_MODEL)
    val poseSession: OrtSession? = createSessionIfPresent(POSE_MODEL)

    val availability: ModelAvailability =
        ModelAvailability(
            objectDetectorReady = objectSession != null,
            poseDetectorReady = poseSession != null
        )

    private fun createSessionIfPresent(assetPath: String): OrtSession? {
        if (!assetExists(assetPath)) return null
        val modelFile = copyAssetToCache(assetPath)
        return environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    private fun assetExists(assetPath: String): Boolean =
        runCatching {
            appContext.assets.open(assetPath).use { true }
        }.getOrDefault(false)

    private fun copyAssetToCache(assetPath: String): File {
        val target = File(appContext.cacheDir, assetPath.substringAfterLast('/'))
        appContext.assets.open(assetPath).use { input ->
            if (target.exists() && target.length() == input.available().toLong()) {
                return target
            }
        }
        appContext.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    override fun close() {
        objectSession?.close()
        poseSession?.close()
    }

    companion object {
        const val OBJECT_MODEL = "models/yolov8n.onnx"
        const val POSE_MODEL = "models/yolov8n-pose.onnx"
    }
}
