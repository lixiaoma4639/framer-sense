package com.framer.sense.feature.camera.pytorch.ui

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
    private val onnxDetectorDelegate = lazy { OnnxCameraAiDetector(appContext) }
    private var lastAnalysisTimestamp = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (isClosed.get() || now - lastAnalysisTimestamp < ANALYSIS_INTERVAL_MS) {
            //如果分析器已经关闭，或者距离上次分析还不到 520ms，就直接丢弃当前帧。
            imageProxy.close()
            return
        }

        lastAnalysisTimestamp = now
        try {
            //真正调用 ONNX 检测器
            val result = onnxDetectorDelegate.value.detect(imageProxy)
            //把 ONNX 检测结果交给构图规则引擎。
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
            //无论成功还是失败，都关闭当前帧。这个非常关键。
            imageProxy.close()
        }
    }

    override fun close() {
        //只有第一次关闭时才执行内部逻 && 只有 ONNX 检测器真的初始化过，才关闭它。
        if (isClosed.compareAndSet(false, true) && onnxDetectorDelegate.isInitialized()) {
            //关闭 ONNX session。
            onnxDetectorDelegate.value.close()
        }
    }

    private companion object {
        //单位毫秒
        const val ANALYSIS_INTERVAL_MS = 520L
    }
}
