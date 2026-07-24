package com.framer.sense.feature.camera.pytorch.v2.ui

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// CameraX 的帧分析器：接收相机帧，调用 ONNX 分析，再生成 UI 引导结果。
class CameraV2FrameAnalyzer(
    // 用于初始化或等待 ONNX 推理会话池。
    context: Context,
    // 当前用户身体资料，构图引擎会用它生成更贴合用户的拍照引导。
    private val bodyProfile: BodyProfile,
    // 回调执行器；上层传入主线程执行器，用来安全回调 UI/状态机。
    private val callbackExecutor: Executor,
    // 分析成功后输出 CameraV2Guide，驱动相机覆盖层提示更新。
    private val onGuide: (CameraV2Guide) -> Unit,
    // 分析失败后输出异常，让上层进入错误处理流程。
    private val onError: (Throwable) -> Unit
// 实现 ImageAnalysis.Analyzer 后，CameraX 会持续把帧传给 analyze；实现 Closeable 后，页面退出时可主动关闭。
) : ImageAnalysis.Analyzer, Closeable {

    // 标记分析器是否已关闭；AtomicBoolean 用于处理分析线程和 UI 线程之间的并发可见性。
    private val closed = AtomicBoolean(false)
    // 懒加载 ONNX 分析器：只有第一帧真正需要分析时，才等待 session pool 并创建分析器。
    private val onnxAnalyzer = lazy {
        // 从统一的 SessionManager 获取推理会话池，再交给单帧 ONNX 分析器使用。
        CameraV2OnnxAnalyzer(CameraV2OnnxSessionManager.awaitSessionPool(context))
    }
    // 构图引擎负责把模型分析结果转换成页面可直接展示的 CameraV2Guide。
    private val compositionEngine = CameraV2CompositionEngine()
    // 记录上一次实际执行分析的时间，用于降低模型推理频率。
    private var lastAnalysisTimestamp = 0L

    // CameraX 每送来一帧 ImageProxy，都会调用这个方法。
    override fun analyze(image: ImageProxy) {
        // 记录当前时间，后续用于判断是否距离上次分析已超过间隔。
        val now = System.currentTimeMillis()
        // 如果分析器已关闭，或距离上次分析不足指定间隔，就跳过当前帧。
        if (closed.get() || now - lastAnalysisTimestamp < ANALYSIS_INTERVAL_MS) {
            // 即使跳过分析，也必须关闭 ImageProxy，否则 CameraX 后续帧可能被阻塞。
            image.close()
            // 当前帧处理结束，直接返回。
            return
        }
        // 当前帧会进入真实分析流程，因此更新最后分析时间。
        lastAnalysisTimestamp = now

        // 捕获 ONNX 推理、构图计算或回调调度中的异常。
        try {
            // 调用 ONNX 分析器处理当前相机帧，得到模型层分析结果。
            val analysis = onnxAnalyzer.value.analyze(image)
            // 将模型分析结果和身体资料合成 UI 引导数据。
            val guide = compositionEngine.buildGuide(analysis, bodyProfile)
            // 把成功结果切到指定 executor 上回调，上层通常期望在主线程更新状态。
            callbackExecutor.execute {
                // 回调真正执行前再次检查关闭状态，避免页面退出后继续更新 UI。
                if (!closed.get()) onGuide(guide)
            }
        // 任意分析异常都会进入这里。
        } catch (throwable: Throwable) {
            // 错误同样切到指定 executor 上回调，保持和成功回调一致的线程语义。
            callbackExecutor.execute {
                // 如果分析器已经关闭，就忽略这个过期错误。
                if (!closed.get()) onError(throwable)
            }
        // 无论分析成功还是失败，都必须释放当前帧。
        } finally {
            // 关闭 ImageProxy，通知 CameraX 当前帧已处理完，可以继续分发后续帧。
            image.close()
        }
    }

    // 页面退出或预览实例释放时调用，停止后续分析结果继续回调。
    override fun close() {
        // 将关闭状态从 false 改为 true；重复调用 close 也不会产生额外副作用。
        closed.compareAndSet(false, true)
    }

    // 存放本类内部常量。
    private companion object {
        // 两次真实模型分析之间的最小间隔，约等于每秒分析 1.9 次，避免每帧推理导致卡顿和发热。
        const val ANALYSIS_INTERVAL_MS = 520L
    }
}
