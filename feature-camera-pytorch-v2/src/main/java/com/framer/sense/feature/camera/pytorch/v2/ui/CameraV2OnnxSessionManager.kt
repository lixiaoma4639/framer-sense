package com.framer.sense.feature.camera.pytorch.v2.ui

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 描述 ONNX 模型会话池当前的加载状态。
enum class OnnxSessionLoadState {
    // 还没有开始加载模型 session。
    NOT_STARTED,
    // 模型 session 正在后台线程加载。
    LOADING,
    // 所有需要的 session 已经加载完成，可以用于推理。
    READY,
    // session 加载失败，页面需要展示失败或降级状态。
    FAILED
}

/**
 * 在应用进程内共享 ONNX Runtime session。
 *
 * 预热和相机分析会复用同一个 Future：首次预热不会阻塞主线程，而首次分析只会等待
 * 已启动的加载任务，不会再次创建四个模型 session。
 */
// 单例管理器：在整个应用进程内复用一份 ONNX session pool。
object CameraV2OnnxSessionManager {

    // 专门用于加载 ONNX session 的单线程执行器，避免多个模型并发初始化抢占资源。
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        // 为执行器创建自定义线程，便于调试时识别它来自相机 v2 ONNX 预加载。
        Thread(runnable, "CameraV2OnnxPreload").apply {
            // 降低线程优先级，减少模型预加载对 UI 和相机预览的影响。
            priority = Thread.MIN_PRIORITY
        }
    }
    // 锁对象，用来保护 loader 创建和加载状态观察任务启动。
    private val loaderLock = Any()
    // 内部可变状态流，初始状态是未开始加载。
    private val _loadState = MutableStateFlow(OnnxSessionLoadState.NOT_STARTED)

    // 对外暴露只读 StateFlow，让 UI/ViewModel 可以观察加载状态但不能直接修改。
    val loadState: StateFlow<OnnxSessionLoadState> = _loadState.asStateFlow()

    // volatile 保证不同线程能看到最新的 sessionLoader 引用。
    @Volatile
    // 缓存唯一的加载器；它内部会保证真实加载任务只提交一次。
    private var sessionLoader: SingleFlightValueLoader<OnnxSessionPool>? = null

    // 标记 loadState 的后台观察任务是否已经启动，避免重复启动多个观察任务。
    private var loadStateObserverStarted = false

    // 提前启动 ONNX session 加载，但不阻塞调用线程。
    fun preload(context: Context) {
        // 获取或创建当前应用进程唯一的 loader，并启动加载。
        startLoading(loaderFor(context))
    }

    // 获取可用于推理的 OnnxSessionPool；如果还没加载完成，会阻塞当前调用线程等待结果。
    fun awaitSessionPool(context: Context): OnnxSessionPool {
        // 获取或创建 loader；这里和 preload 复用同一个 loader。
        val loader = loaderFor(context)
        // 确保后台加载已经开始，并确保 loadState 会被更新。
        startLoading(loader)
        // 等待加载结果；成功返回 session pool，失败则抛出加载异常。
        return loader.await()
    }

    // 启动加载任务，并启动一次 loadState 观察任务。
    private fun startLoading(loader: SingleFlightValueLoader<OnnxSessionPool>) {
        // 触发 loader 提交后台任务；如果任务已提交，这里不会重复创建。
        loader.preload()
        // 加锁保护 loadStateObserverStarted，避免并发调用时重复启动状态观察。
        synchronized(loaderLock) {
            // 如果观察任务已经启动过，就直接返回。
            if (loadStateObserverStarted) return
            // 标记观察任务已启动。
            loadStateObserverStarted = true
            // 通知外部当前开始加载。
            _loadState.value = OnnxSessionLoadState.LOADING
            // 在同一个后台执行器上等待 loader 完成，并更新最终状态。
            executor.execute {
                // 捕获 await 的成功或失败，避免后台线程异常直接漏出。
                runCatching { loader.await() }
                    // 加载成功时把状态改成 READY。
                    .onSuccess { _loadState.value = OnnxSessionLoadState.READY }
                    // 加载失败时把状态改成 FAILED。
                    .onFailure { _loadState.value = OnnxSessionLoadState.FAILED }
            }
        }
    }

    // 获取当前进程唯一的 SingleFlightValueLoader。
    private fun loaderFor(context: Context): SingleFlightValueLoader<OnnxSessionPool> {
        // 如果已经创建过 loader，直接复用。
        sessionLoader?.let { return it }
        // 使用 applicationContext，避免长时间持有 Activity context 导致泄漏。
        val appContext = context.applicationContext
        // 加锁创建 loader，防止多个线程同时创建多个加载器。
        return synchronized(loaderLock) {
            // 锁内再次检查，避免进入锁前已有其他线程创建完成。
            sessionLoader ?: SingleFlightValueLoader(executor) {
                // 真正的加载逻辑：创建 OnnxSessionPool，内部会初始化各个 ONNX Runtime session。
                OnnxSessionPool(appContext)
            // 创建后缓存到 sessionLoader，供后续 preload/await 复用。
            }.also { sessionLoader = it }
        }
    }
}

/** 保证并发调用只执行一次加载，并缓存成功或失败结果。 */
// 通用 single-flight 加载器：多个调用方同时请求同一值时，只提交一个后台任务。
internal class SingleFlightValueLoader<T>(
    // 执行实际加载任务的线程池。
    private val executor: ExecutorService,
    // 真正生成目标值的加载函数。
    private val valueLoader: () -> T
) {

    // 保护 future 创建的锁。
    private val lock = Any()

    // volatile 保证 future 创建后能被其他线程立即看到。
    @Volatile
    // 缓存后台任务；成功和失败都会被同一个 Future 记录下来。
    private var future: Future<T>? = null

    // 预加载入口，只负责确保任务已经提交，不等待结果。
    fun preload() {
        // 调用 task 会在必要时提交任务。
        task()
    }

    // 等待加载完成并返回结果。
    fun await(): T = task().get()

    // 获取唯一的 Future；如果不存在则创建并提交后台任务。
    private fun task(): Future<T> {
        // 无锁快速路径：已有任务时直接返回。
        future?.let { return it }
        // 加锁确保并发场景下只有一个线程能提交任务。
        return synchronized(lock) {
            // 锁内二次检查；如果其他线程已经创建 Future，就直接复用。
            future ?: executor.submit(Callable { valueLoader() }).also { future = it }
        }
    }
}
