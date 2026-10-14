package com.framer.sense.feature.camera.vlm.data

import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.framer.sense.feature.camera.vlm.agent.OfflineSceneDescription
import com.framer.sense.feature.camera.vlm.agent.OfflineSceneDescriptionCache
import com.framer.sense.feature.camera.vlm.agent.OfflineViewSelectionProtocol
import android.util.Log
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.BuildConfig
import java.io.File
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JNI 声明：句柄只能由 MnnProvider 的专用串行线程访问。 */
object MnnNative {
    /** 加载已编译的本地库；无参数，ABI 或库缺失时抛出链接错误。 */
    fun initialize() { System.loadLibrary("vlm_mnn") }
    /** 加载多模态模型。@param configPath 已校验模型包中的 config.json 绝对路径。@return 原生句柄。 */
    external fun load(configPath: String): Long
    /** 一次真实视觉预填充后返回模型选择的合法编号。
     * @param handle 活跃模型句柄。
     * @param imagePaths 按编号排列的私有 JPEG 绝对路径。
     * @param prompt UTF-8 编码的导演提示词。
     * @param cancelled Kotlin 协程取消标志，在视觉/语言计算图的算子边界读取。
     * @param progress 兼容现有运行时接口；编号选择不进行自由文本解码。
     * @return UTF-8 编号字节。
     */
    external fun inferImages(handle: Long, imagePaths: Array<String>, prompt: ByteArray, cancelled: AtomicBoolean, progress: MnnTokenProgress): ByteArray
    /** 单图兼容入口。 */
    external fun infer(handle: Long, imagePath: String, prompt: ByteArray, cancelled: AtomicBoolean, progress: MnnTokenProgress): ByteArray
    /** 环境描述使用独立的 192-token 输出上限，复用现有算子边界取消。 */
    external fun describeScene(handle: Long, imagePath: String, prompt: ByteArray, cancelled: AtomicBoolean, progress: MnnTokenProgress): ByteArray
    external fun choosePhoto(handle: Long, imagePath: String, prompt: ByteArray, cancelled: AtomicBoolean): ByteArray
    /** 释放空闲句柄。@param handle 已结束所有推理的模型句柄。 */
    external fun release(handle: Long)
}

/** 原生运行时抽象，用于不加载真实权重的事务测试。 */
interface MnnRuntime {
    fun choosePhoto(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean): ByteArray =
        throw UnsupportedOperationException("LOCAL_PHOTO_CHOICE_UNSUPPORTED")
    /** 初始化本地库；无参数，失败抛出链接错误。 */
    fun initialize()
    /** 加载配置。@param path config.json 路径。@return 原生句柄。 */
    fun load(path: String): Long
    /** 释放空闲句柄。@param handle 已停止推理的句柄。 */
    fun release(handle: Long)
    /** 图像推理。@param handle 活跃句柄。@param image 私有图像路径。@param prompt UTF-8 提示。@param cancel 取消标志。@param onToken 实际输出新 token 的回调。@return 输出字节。 */
    fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray
    fun describeScene(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray =
        infer(handle, image, prompt, cancel, onToken)
    fun inferImages(handle: Long, images: List<String>, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
        if (images.size == 1) return infer(handle, images.single(), prompt, cancel, onToken)
        throw UnsupportedOperationException("LOCAL_MULTIVIEW_UNSUPPORTED")
    }
}

/** 默认真实 JNI 实现，不提供生产假响应。 */
object NativeMnnRuntime : MnnRuntime {
    override fun choosePhoto(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean): ByteArray =
        MnnNative.choosePhoto(handle, image, prompt, cancel)
    /** 初始化 JNI 库；无参数。 */
    override fun initialize() = MnnNative.initialize()
    /** 加载官方配置。@param path 配置路径。@return 原生句柄。 */
    override fun load(path: String): Long = MnnNative.load(path)
    /** 释放原生模型。@param handle 空闲句柄。 */
    override fun release(handle: Long) = MnnNative.release(handle)
    /** 执行多模态推理。@param handle 活跃句柄。@param image 图片路径。@param prompt UTF-8 输入。@param cancel 取消标志。@return UTF-8 输出。 */
    override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray =
        MnnNative.infer(handle, image, prompt, cancel, MnnTokenProgress(onToken))
    override fun describeScene(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray =
        MnnNative.describeScene(handle, image, prompt, cancel, MnnTokenProgress(onToken))
    override fun inferImages(handle: Long, images: List<String>, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray =
        MnnNative.inferImages(handle, images.toTypedArray(), prompt, cancel, MnnTokenProgress(onToken))
}

enum class LocalModelPhase { UNLOADED, LOADING, READY, FAILED }
data class LocalModelLoadState(val phase: LocalModelPhase = LocalModelPhase.UNLOADED,
    val path: String? = null, val error: String? = null)

/** 真正的本地 VLM；模型导入、加载失败不会返回规则结果。 */
class MnnProvider(
    private val store: LocalModelStore,
    private val supportedAbis: () -> Array<String> = { Build.SUPPORTED_ABIS },
    private val runtime: MnnRuntime = NativeMnnRuntime
) : VisionModelProvider {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "vlm-mnn").apply { isDaemon = true } }
    private val sceneDescriptions = OfflineSceneDescriptionCache()
    private var handle = 0L
    private var loadedPath: String? = null
    private val nativeInferenceRunning = AtomicBoolean(false)
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupRequested = AtomicBoolean(false)
    private val _loadState = MutableStateFlow(LocalModelLoadState())
    val loadState: StateFlow<LocalModelLoadState> = _loadState.asStateFlow()
    val isInferenceRunning: Boolean get() = nativeInferenceRunning.get()

    /** 后台读取已保存模式；指定云端跳过，其余模式预加载既有安装，进程内仅一次。
     * 不下载、不受 Tab 生命周期取消。@param readMode 已保存模式的读取操作。
     */
    fun preloadOnAppStart(readMode: () -> ModelMode): Job? {
        if (!startupRequested.compareAndSet(false, true)) return null
        return processScope.launch {
            try {
                val mode = readMode()
                if (mode == ModelMode.CLOUD) {
                    Log.i(LOG_TAG, "首页预加载跳过 mode=$mode reason=指定云端")
                    return@launch
                }
                if (store.activeDirectory() == null) return@launch
                Log.i(LOG_TAG, "首页预加载开始 source=离线 mode=$mode")
                load()
                Log.i(LOG_TAG, "首页预加载完成 source=离线 mode=$mode")
            }
            catch (error: Exception) { Log.w(LOG_TAG, "首页预加载失败，保留模型供手动重试", error) }
        }
    }

    /** 排在旧 JNI 操作之后执行；取消等待不会卸载句柄或启动新的原生计算。 */
    suspend fun awaitIdle(): Unit = submit { _ -> Unit }

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
        _loadState.value = LocalModelLoadState(LocalModelPhase.LOADING, dir.path)
        try {
        if (handle != 0L) runtime.release(handle)
        sceneDescriptions.clear()
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
            _loadState.value = LocalModelLoadState(LocalModelPhase.READY, dir.path)
            // 旧安装清理失败不反转已成功提交的加载事务。
            runCatching { old?.deleteRecursively() }
            store.message(R.string.vlm_model_loaded)
        } catch (error: Throwable) { runtime.release(candidate); throw error }
        } catch (error: Throwable) {
            _loadState.value = LocalModelLoadState(if (error is CancellationException) LocalModelPhase.UNLOADED else LocalModelPhase.FAILED,
                dir.path, if (error is CancellationException) null else store.message(R.string.vlm_model_load_failed))
            throw error
        }
    }

    /** 检查原生 ABI；无参数，禁止在不支持的设备上进入 JNI。 */
    private fun checkAbi() {
        if ("arm64-v8a" !in supportedAbis()) throw ModelFailure("UNSUPPORTED_ABI", store.message(R.string.vlm_model_abi), true)
    }

    /** 安全卸载模型；无参数，排在正在完成的原生推理之后执行。 */
    suspend fun unload(): Unit = submit { _ ->
        val previousPath = loadedPath ?: store.activeDirectory()?.path
        if (handle != 0L) runtime.release(handle)
        sceneDescriptions.clear()
        handle = 0L
        loadedPath = null
        _loadState.value = LocalModelLoadState(LocalModelPhase.UNLOADED, previousPath)
    }

    /** 对输入图片执行离线推理。
     * @param call 当前图像、提示词与请求标识。
     * @return 模型真实输出和耗时。
     */
    override suspend fun step(call: ModelCall): StepReply {
        // 原生计算只能在安全边界退出；超时后立即重试不能排在仍未退出的 JNI 后无限等待。
        if (nativeInferenceRunning.get()) throw ModelFailure("LOCAL_BUSY", store.message(R.string.vlm_model_inference_busy), true)
        return try {
            withTokenInactivityWatchdog { onToken -> infer(call, onToken) }
        } catch (failure: ModelFailure) {
            if (failure.code == "LOCAL_TIMEOUT") {
                Log.w(LOG_TAG, "离线推理停滞 request=${call.requestId.take(8)} idleLimitMs=39000")
                throw ModelFailure("LOCAL_TIMEOUT", store.message(R.string.vlm_model_inference_timeout), true)
            }
            throw failure
        }
    }

    private suspend fun infer(call: ModelCall, onToken: () -> Unit): StepReply = submit { cancel ->
        val image = File(call.input.scene.modelImagePath)
        Log.i(LOG_TAG, "离线推理开始 request=${call.requestId.take(8)} image=${call.input.scene.id.take(8)} imageExists=${image.isFile} imageBytes=${image.length()} promptBytes=${call.prompt.toByteArray(Charsets.UTF_8).size}")
        var loaded = false
        try {
            ensureLoaded()
            loaded = true
            // load 不能中断；若等待期间已取消，加载返回后不得继续启动推理。
            if (cancel.get()) throw CancellationException()
            val start = System.nanoTime()
            nativeInferenceRunning.set(true)
            var sceneObservation: SceneObservation? = null
            val output = try {
                val photoChoice = com.framer.sense.feature.camera.vlm.agent.PhotoReferenceDecisionPolicy.enabled(call.input)
                val raw = (if (photoChoice)
                    runtime.choosePhoto(handle, image.path, call.prompt.toByteArray(Charsets.UTF_8), cancel)
                else runtime.infer(handle, image.path, call.prompt.toByteArray(Charsets.UTF_8), cancel, onToken)).toString(Charsets.UTF_8)
                if (cancel.get()) throw CancellationException()
                if (photoChoice && raw.trim().toIntOrNull()?.let { it in 1..6 } == true) {
                    val key = OfflineSceneDescriptionCache.Key(call.input.scene.id, image.path, image.length(),
                        image.lastModified(), "${loadedPath}:$handle")
                    sceneObservation = sceneDescriptions.get(key)
                    if (sceneObservation != null) {
                        Log.i(LOG_TAG, "环境描述缓存命中 request=${call.requestId.take(8)} image=${call.input.scene.id.take(8)}")
                    } else {
                        val descriptionStart = System.nanoTime()
                        Log.i(LOG_TAG, "选中单图环境描述开始 request=${call.requestId.take(8)} image=${call.input.scene.id.take(8)} choice=${raw.trim()}")
                        try {
                            // 编号决策没有自由文本 token；此处首 token 前仍不启用停滞超时。
                            val descriptionRaw = runtime.describeScene(handle, image.path,
                                OfflineSceneDescription.PROMPT.toByteArray(Charsets.UTF_8), cancel, onToken).toString(Charsets.UTF_8)
                            if (cancel.get()) throw CancellationException()
                            sceneObservation = OfflineSceneDescription.parse(descriptionRaw, call.input.scene.id)
                            sceneObservation?.let { sceneDescriptions.put(key, it) }
                            Log.i(LOG_TAG, "选中单图环境描述结束 request=${call.requestId.take(8)} image=${call.input.scene.id.take(8)} valid=${sceneObservation != null} elapsedMs=${(System.nanoTime()-descriptionStart)/1_000_000} outputChars=${descriptionRaw.length}")
                            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "环境描述原始输出 request=${call.requestId.take(8)} raw=${VlmJson.encodeToString(descriptionRaw)}")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (cancel.get()) throw CancellationException()
                            // 描述是附加观察；失败不丢弃已完成的合法摄影组合，也不伪造说明。
                            Log.w(LOG_TAG, "环境描述生成失败，保留摄影组合 request=${call.requestId.take(8)}", failure)
                        }
                    }
                }
                raw
            } finally {
                nativeInferenceRunning.set(false)
            }
            if (cancel.get()) throw CancellationException()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            Log.i(LOG_TAG, "离线推理完成 request=${call.requestId.take(8)} model=${store.modelName()} elapsedMs=$elapsedMs outputBytes=${output.toByteArray().size}")
            if (BuildConfig.DEBUG) {
                // JSON 字符串转义保留换行、控制符和首尾空白；小段输出避免 Logcat 截断。
                val parts = VlmJson.encodeToString(output).chunked(800)
                parts.forEachIndexed { index, part ->
                    Log.d(LOG_TAG, "模型原始输出 request=${call.requestId.take(8)} part=${index + 1}/${parts.size} raw=$part")
                }
            }
            StepReply(store.modelName(), output, elapsedMs = elapsedMs, sceneObservation = sceneObservation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "离线推理失败 request=${call.requestId.take(8)} activeModel=${store.activeDirectory()?.name ?: "none"} error=${error::class.java.simpleName}", error)
            throw when {
                error is ModelFailure -> error
                error.message == "LOCAL_OUTPUT_LIMIT" -> ModelFailure("LOCAL_OUTPUT_LIMIT", store.message(R.string.vlm_local_output_limit), false)
                error is TimeoutException -> ModelFailure("LOCAL_TIMEOUT", store.message(R.string.vlm_model_inference_timeout), true)
                loaded -> ModelFailure("LOCAL_INFERENCE_FAILED", store.message(R.string.vlm_model_inference_failed), true)
                else -> ModelFailure("LOCAL_FAILED", if (error is ModelStorageException) store.errorMessage(error) else store.message(R.string.vlm_model_load_failed), true)
            }
        }
    }

    /** 单次多图选景；复制缩小输入隔离取消后的扫描缓存清理，不上传图片。 */
    suspend fun compareViews(images: List<String>, prompt: String): StepReply {
        val request = newVlmId().take(8)
        Log.i(LOG_TAG, "离线多图任务开始 request=$request images=${images.size} promptBytes=${prompt.toByteArray(Charsets.UTF_8).size} nativeBusy=${nativeInferenceRunning.get()}")
        require(images.size in 1..OfflineViewSelectionProtocol.MAX_FRAMES)
        if (nativeInferenceRunning.get()) throw ModelFailure("LOCAL_BUSY", store.message(R.string.vlm_model_inference_busy), true)
        return try {
            withTokenInactivityWatchdog { onToken -> submit { cancel ->
                try {
                    ensureLoaded()
                    Log.i(LOG_TAG, "离线多图模型就绪 request=$request model=${store.modelName()} cancelled=${cancel.get()}")
                    if (cancel.get()) throw CancellationException()
                    val dir = File(store.root.parentFile, "vlm-view-inputs/${newVlmId()}").apply { mkdirs() }
                    try {
                        val inputs = images.mapIndexed { index, path ->
                            if (cancel.get()) throw CancellationException()
                            val source = File(path)
                            check(source.isFile && source.length() in 1..ScanLimits.MAX_REQUEST_BYTES) { "LOCAL_VIEW_INPUT" }
                            val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path)) { "LOCAL_VIEW_INPUT" }
                            try {
                                val ratio = minOf(1f, OfflineViewSelectionProtocol.INPUT_LONG_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
                                val small = bitmap.scale(maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()))
                                val copy = File(dir, "$index.jpg")
                                try { copy.outputStream().use { check(small.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
                                finally { if (small !== bitmap) small.recycle() }
                                Log.i(LOG_TAG, "离线选景输入准备 request=$request ordinal=${index + 1} sourceBytes=${source.length()} sourceSize=${bitmap.width}x${bitmap.height} inputSize=${maxOf(1, (bitmap.width * ratio).toInt())}x${maxOf(1, (bitmap.height * ratio).toInt())} jpegBytes=${copy.length()}")
                                copy.path
                            } finally { bitmap.recycle() }
                        }
                        if (cancel.get()) throw CancellationException()
                        nativeInferenceRunning.set(true)
                        val start = System.nanoTime()
                        Log.i(LOG_TAG, "离线多图进入JNI request=$request images=${inputs.size}")
                        val output = try { runtime.inferImages(handle, inputs, prompt.toByteArray(Charsets.UTF_8), cancel, onToken) }
                            finally { nativeInferenceRunning.set(false) }
                        if (cancel.get()) throw CancellationException()
                        val elapsed = (System.nanoTime() - start) / 1_000_000
                        val raw = output.toString(Charsets.UTF_8)
                        Log.i(LOG_TAG, "离线多图推理返回 request=$request images=${inputs.size} elapsedMs=$elapsed outputBytes=${output.size} outputChars=${raw.length} awaitingProtocolValidation=true")
                        if (BuildConfig.DEBUG) {
                            val parts = VlmJson.encodeToString(raw).chunked(800)
                            parts.forEachIndexed { index, part ->
                                Log.d(LOG_TAG, "离线选景模型原始输出 request=$request part=${index + 1}/${parts.size} raw=$part")
                            }
                        }
                        StepReply(store.modelName(), raw, elapsedMs = elapsed)
                    } finally { dir.deleteRecursively() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { throw viewFailure(failure) }
            } }
        } catch (cancel: CancellationException) {
            Log.i(LOG_TAG, "离线多图取消等待 request=$request nativeBusy=${nativeInferenceRunning.get()}")
            throw cancel
        }
        catch (failure: Throwable) {
            Log.e(LOG_TAG, "离线多图任务失败 request=$request code=${(failure as? ModelFailure)?.code} error=${failure::class.java.simpleName}", failure)
            throw viewFailure(failure)
        }
    }

    /** 在专用线程和停滞监控边界保留正确的多图失败原因。 */
    private fun viewFailure(failure: Throwable): ModelFailure {
        if (failure is ModelFailure && failure.code != "LOCAL_TIMEOUT") return failure
        val resource = when {
            failure is TimeoutException || (failure as? ModelFailure)?.code == "LOCAL_TIMEOUT" -> R.string.vlm_model_inference_timeout
            failure is ModelStorageException -> R.string.vlm_model_load_failed
            failure.message == "LOCAL_MULTIVIEW_UNSUPPORTED" -> R.string.vlm_scan_local_unsupported
            failure.message == "LOCAL_VIEW_CHOICE_UNSUPPORTED" -> R.string.vlm_scan_local_choice_unsupported
            failure.message == "LOCAL_VIEW_INPUT" -> R.string.vlm_scan_local_input_failed
            else -> R.string.vlm_scan_local_select_failed
        }
        val code = failure.message?.takeIf { it in setOf("LOCAL_VIEW_CHOICE_INVALID", "LOCAL_VIEW_CHOICE_UNSUPPORTED") } ?: "LOCAL_VIEW_FAILED"
        return ModelFailure(code, store.message(resource), false)
    }

    /** 确保当前模型已加载；无参数，只能在专用线程调用。 */
    private fun ensureLoaded() {
        val dir = store.activeDirectory() ?: throw ModelFailure("MODEL_MISSING", store.message(R.string.vlm_model_absent), true)
        if (handle != 0L && loadedPath == dir.path) {
            _loadState.value = LocalModelLoadState(LocalModelPhase.READY, dir.path)
            Log.d(LOG_TAG, "复用已加载离线模型 dir=${dir.name} handle=$handle")
            return
        }
        _loadState.value = LocalModelLoadState(LocalModelPhase.LOADING, dir.path)
        try {
        checkAbi()
        if (handle != 0L) runtime.release(handle)
        sceneDescriptions.clear()
        handle = 0L
        loadedPath = null
        val config = File(dir, "config.json")
        Log.i(LOG_TAG, "加载离线模型 dir=${dir.name} configExists=${config.isFile} configBytes=${config.length()}")
        runtime.initialize()
        handle = runtime.load(config.path)
        if (handle == 0L) throw ModelStorageException(R.string.vlm_model_load_failed)
        loadedPath = dir.path
        _loadState.value = LocalModelLoadState(LocalModelPhase.READY, dir.path)
        Log.i(LOG_TAG, "离线模型加载成功 dir=${dir.name} handle=$handle")
        } catch (error: Throwable) {
            _loadState.value = LocalModelLoadState(LocalModelPhase.FAILED, dir.path,
                (error as? ModelFailure)?.message ?: store.message(R.string.vlm_model_load_failed))
            throw error
        }
    }

    /** 将原生操作排入串行线程，并桥接协程取消。
     * @param block 接收取消标记的原生任务；不得在其他线程访问句柄。
     * @return 任务结果；取消通知原生计算图退出，待其清理后交接，不并发销毁句柄。
     */
    private suspend fun <T> submit(block: (AtomicBoolean) -> T): T = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        val task = nextNativeTask.incrementAndGet()
        val cancelAt = AtomicLong(0)
        continuation.invokeOnCancellation {
            cancelAt.compareAndSet(0, System.nanoTime())
            cancelled.set(true)
            Log.i(LOG_TAG, "原生任务取消请求 task=$task nativeBusy=${nativeInferenceRunning.get()}")
        }
        executor.execute {
            if (!continuation.isActive) return@execute
            try {
                val result = block(cancelled)
                if (continuation.isActive) continuation.resume(result)
            } catch (cancelled: CancellationException) {
                if (continuation.isActive) continuation.cancel(cancelled)
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "原生模型任务异常 error=${error::class.java.simpleName}", error)
                if (continuation.isActive) continuation.resumeWithException(
                    if (error is ModelFailure) error else ModelFailure("LOCAL_FAILED", if (error is ModelStorageException) store.errorMessage(error) else store.message(R.string.vlm_model_load_failed), true)
                )
            } finally {
                val requestedAt = cancelAt.get()
                if (requestedAt != 0L) Log.i(LOG_TAG, "原生任务取消交接完成 task=$task cancelToExitMs=${(System.nanoTime() - requestedAt) / 1_000_000} nativeBusy=${nativeInferenceRunning.get()}")
            }
        }
    }

    private companion object {
        const val LOG_TAG = "VlmOffline"
        val nextNativeTask = AtomicLong(0)
    }
}
