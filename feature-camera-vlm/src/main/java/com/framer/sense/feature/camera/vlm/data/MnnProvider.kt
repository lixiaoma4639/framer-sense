package com.framer.sense.feature.camera.vlm.data

import android.os.Build
import com.framer.sense.feature.camera.vlm.R
import java.io.File
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

/** 原生运行时抽象，用于不加载真实权重的事务测试。 */
interface MnnRuntime {
    /** 初始化本地库；无参数，失败抛出链接错误。 */
    fun initialize()
    /** 加载配置。@param path config.json 路径。@return 原生句柄。 */
    fun load(path: String): Long
    /** 释放空闲句柄。@param handle 已停止推理的句柄。 */
    fun release(handle: Long)
    /** 图像推理。@param handle 活跃句柄。@param image 私有图像路径。@param prompt UTF-8 提示。@param cancel 取消标志。@return 输出字节。 */
    fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean): ByteArray
}

/** 默认真实 JNI 实现，不提供生产假响应。 */
object NativeMnnRuntime : MnnRuntime {
    /** 初始化 JNI 库；无参数。 */
    override fun initialize() = MnnNative.initialize()
    /** 加载官方配置。@param path 配置路径。@return 原生句柄。 */
    override fun load(path: String): Long = MnnNative.load(path)
    /** 释放原生模型。@param handle 空闲句柄。 */
    override fun release(handle: Long) = MnnNative.release(handle)
    /** 执行多模态推理。@param handle 活跃句柄。@param image 图片路径。@param prompt UTF-8 输入。@param cancel 取消标志。@return UTF-8 输出。 */
    override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean): ByteArray = MnnNative.infer(handle, image, prompt, cancel)
}

/** 真正的本地 VLM；模型导入、加载失败不会返回规则结果。 */
class MnnProvider(
    private val store: LocalModelStore,
    private val supportedAbis: () -> Array<String> = { Build.SUPPORTED_ABIS },
    private val runtime: MnnRuntime = NativeMnnRuntime
) : VisionModelProvider {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "vlm-mnn").apply { isDaemon = true } }
    private var handle = 0L
    private var loadedPath: String? = null

    /** 加载当前已导入模型；无参数，串行完成，避免与推理同时释放资源。 */
    suspend fun load(): String = submit { _ ->
        ensureLoaded()
        store.description()
    }

    /** 串行加载候选后提交安装；失败保留旧目录和指针，旧句柄可在下次使用时重新加载。
     * @param dir 已校验且位置固定的私有模型目录。@return 已加载模型的说明。
     */
    suspend fun install(dir: File): String = submit { cancelled ->
        checkAbi()
        if (cancelled.get()) throw kotlinx.coroutines.CancellationException()
        if (handle != 0L) runtime.release(handle)
        handle = 0L
        loadedPath = null
        runtime.initialize()
        val candidate = runtime.load(File(dir, "config.json").path)
        if (candidate == 0L) throw ModelStorageException(R.string.vlm_model_load_failed)
        try {
            if (cancelled.get()) throw kotlinx.coroutines.CancellationException()
            val old = store.activate(dir)
            handle = candidate
            loadedPath = dir.path
            // 旧安装清理失败不反转已成功提交的加载事务。
            runCatching { old?.deleteRecursively() }
            store.message(R.string.vlm_model_loaded)
        } catch (error: Throwable) { runtime.release(candidate); throw error }
    }

    /** 检查原生 ABI；无参数，禁止在不支持的设备上进入 JNI。 */
    private fun checkAbi() {
        if ("arm64-v8a" !in supportedAbis()) throw ModelFailure("UNSUPPORTED_ABI", store.message(R.string.vlm_model_abi), true)
    }

    /** 安全卸载模型；无参数，排在正在完成的原生推理之后执行。 */
    suspend fun unload(): Unit = submit { _ ->
        if (handle != 0L) runtime.release(handle)
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
        val output = runtime.infer(handle, call.input.scene.modelImagePath, call.prompt.toByteArray(Charsets.UTF_8), cancel).toString(Charsets.UTF_8)
        StepReply(store.modelName(), output, elapsedMs = (System.nanoTime() - start) / 1_000_000)
    }

    /** 确保当前模型已加载；无参数，只能在专用线程调用。 */
    private fun ensureLoaded() {
        checkAbi()
        val dir = store.activeDirectory() ?: throw ModelFailure("MODEL_MISSING", store.message(R.string.vlm_model_absent), true)
        if (handle != 0L && loadedPath == dir.path) return
        if (handle != 0L) runtime.release(handle)
        handle = 0L
        runtime.initialize()
        handle = runtime.load(java.io.File(dir, "config.json").path)
        if (handle == 0L) throw ModelStorageException(R.string.vlm_model_load_failed)
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
                    if (error is ModelFailure) error else ModelFailure("LOCAL_FAILED", if (error is ModelStorageException) store.errorMessage(error) else store.message(R.string.vlm_model_load_failed), true)
                )
            }
        }
    }
}
