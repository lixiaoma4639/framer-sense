package com.framer.sense.feature.camera.pytorch.v2.ui

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class CameraV2FrameAnalyzer(
    context: Context,
    private val bodyProfile: BodyProfile,
    private val callbackExecutor: Executor,
    private val onGuide: (CameraV2Guide) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer, Closeable {

    private val closed = AtomicBoolean(false)
    private val onnxAnalyzer = lazy {
        CameraV2OnnxAnalyzer(OnnxSessionPool(context.applicationContext))
    }
    private val compositionEngine = CameraV2CompositionEngine()
    private var lastAnalysisTimestamp = 0L

    override fun analyze(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if (closed.get() || now - lastAnalysisTimestamp < ANALYSIS_INTERVAL_MS) {
            image.close()
            return
        }
        lastAnalysisTimestamp = now

        try {
            val analysis = onnxAnalyzer.value.analyze(image)
            val guide = compositionEngine.buildGuide(analysis, bodyProfile)
            callbackExecutor.execute {
                if (!closed.get()) onGuide(guide)
            }
        } catch (throwable: Throwable) {
            callbackExecutor.execute {
                if (!closed.get()) onError(throwable)
            }
        } finally {
            image.close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true) && onnxAnalyzer.isInitialized()) {
            onnxAnalyzer.value.close()
        }
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 520L
    }
}
