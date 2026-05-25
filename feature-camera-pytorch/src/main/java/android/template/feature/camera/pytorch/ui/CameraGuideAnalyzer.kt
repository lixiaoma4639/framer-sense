package android.template.feature.camera.pytorch.ui

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class CameraGuideAnalyzer(
    context: Context,
    private val callbackExecutor: Executor,
    private val onGuideState: (CameraGuideState) -> Unit,
    private val onAnalysisError: (Throwable) -> Unit = {}
) : ImageAnalysis.Analyzer, Closeable {

    private val appContext = context.applicationContext
    private val guideEngine = CompositionGuideEngine()
    private val isClosed = AtomicBoolean(false)
    private val detectorDelegate = lazy { OnnxCameraAiDetector(appContext) }
    private var lastAnalysisTimestamp = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (isClosed.get() || now - lastAnalysisTimestamp < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        lastAnalysisTimestamp = now
        try {
            val result = detectorDelegate.value.detect(imageProxy)
            val guideState = guideEngine.buildGuide(
                people = result.people,
                objects = result.objects,
                luminance = result.luminance
            )
            callbackExecutor.execute {
                if (!isClosed.get()) {
                    onGuideState(guideState)
                }
            }
        } catch (throwable: Throwable) {
            callbackExecutor.execute {
                if (!isClosed.get()) {
                    onAnalysisError(throwable)
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true) && detectorDelegate.isInitialized()) {
            detectorDelegate.value.close()
        }
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 520L
    }
}
