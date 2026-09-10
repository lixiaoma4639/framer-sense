package com.framer.sense.feature.camera.vlm.data

import android.os.Build
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JNI 声明：句柄只能由 MnnProvider 的专用串行线程访问。 */
object MnnNative {
    /** 加载已编译的本地库；无参数，ABI 或库缺失时抛出链接错误。 */
    fun initialize() { System.loadLibrary("vlm_mnn") }
    /** 加载多模态模型。@param configPath 已校验模型包中的 config.json 绝对路径。@return 原生句柄。 */
    external fun load(configPath: String): Long
    /** 执行真实图像推理。
     * @param handle 活跃模型句柄。
     * @param imagePath 私有 JPEG 绝对路径。
     * @param prompt UTF-8 编码的导演提示词。
     * @param cancelled Kotlin 协程取消标志，在生成边界读取。
     * @return UTF-8 输出字节。
     */
    external fun infer(handle: Long, imagePath: String, prompt: ByteArray, cancelled: AtomicBoolean): ByteArray
    /** 释放空闲句柄。@param handle 已结束所有推理的模型句柄。 */
    external fun release(handle: Long)
}

/** 真正的本地 VLM；模型导入、加载失败不会返回规则结果。 */
class MnnProvider(
    private val store: LocalModelStore,
    private val supportedAbis: () -> Array<String> = { Build.SUPPORTED_ABIS }
) : VisionModelProvider {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "vlm-mnn").apply { isDaemon = true } }
    private var handle = 0L
    private var loadedPath: String? = null

    /** 加载当前已导入模型；无参数，串行完成，避免与推理同时释放资源。 */
    suspend fun load(): String = submit { _ ->
        ensureLoaded()
        store.description()
    }

    /** 安全卸载模型；无参数，排在正在完成的原生推理之后执行。 */
    suspend fun unload(): Unit = submit { _ ->
        if (handle != 0L) MnnNative.release(handle)
        handle = 0L
        loadedPath = null
    }

    /** 对输入图片执行离线推理。
     * @param call 当前图像、提示词与请求标识。
     * @return 模型真实输出和耗时。
     */
    override suspend fun step(call: ModelCall): StepReply = submit { cancel ->
        ensureLoaded()
        val start = System.nanoTime()
        val output = MnnNative.infer(handle, call.input.scene.modelImagePath, call.prompt.toByteArray(Charsets.UTF_8), cancel).toString(Charsets.UTF_8)
        StepReply(store.description(), output, elapsedMs = (System.nanoTime() - start) / 1_000_000)
    }

    /** 确保当前模型已加载；无参数，只能在专用线程调用。 */
    private fun ensureLoaded() {
        if ("arm64-v8a" !in supportedAbis()) throw ModelFailure("UNSUPPORTED_ABI", "离线推理需要 ARM64 设备", true)
        val dir = store.activeDirectory() ?: throw ModelFailure("MODEL_MISSING", "请先导入离线模型包", true)
        if (handle != 0L && loadedPath == dir.path) return
        if (handle != 0L) MnnNative.release(handle)
        handle = 0L
        MnnNative.initialize()
        handle = MnnNative.load(java.io.File(dir, "config.json").path)
        check(handle != 0L) { "模型加载失败" }
        loadedPath = dir.path
    }

    /** 将原生操作排入串行线程，并桥接协程取消。
     * @param block 接收取消标记的原生任务；不得在其他线程访问句柄。
     * @return 任务结果；取消只丢弃回传，不并发销毁句柄。
     */
    private suspend fun <T> submit(block: (AtomicBoolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        continuation.invokeOnCancellation { cancelled.set(true) }
        executor.execute {
            if (!continuation.isActive) return@execute
            try {
                val result = block(cancelled)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(
                    if (error is ModelFailure) error else ModelFailure("LOCAL_FAILED", "离线模型加载或推理失败：${error.message.orEmpty().take(180)}", true)
                )
            }
        }
    }
}
