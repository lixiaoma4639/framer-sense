package com.framer.sense.feature.camera.pytorch.v2.ui

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OnnxSessionLoadState {
    NOT_STARTED,
    LOADING,
    READY,
    FAILED
}

/**
 * 在应用进程内共享 ONNX Runtime session。
 *
 * 预热和相机分析会复用同一个 Future：首次预热不会阻塞主线程，而首次分析只会等待
 * 已启动的加载任务，不会再次创建四个模型 session。
 */
object CameraV2OnnxSessionManager {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CameraV2OnnxPreload").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val loaderLock = Any()
    private val _loadState = MutableStateFlow(OnnxSessionLoadState.NOT_STARTED)

    val loadState: StateFlow<OnnxSessionLoadState> = _loadState.asStateFlow()

    @Volatile
    private var sessionLoader: SingleFlightValueLoader<OnnxSessionPool>? = null

    private var loadStateObserverStarted = false

    fun preload(context: Context) {
        startLoading(loaderFor(context))
    }

    fun awaitSessionPool(context: Context): OnnxSessionPool {
        val loader = loaderFor(context)
        startLoading(loader)
        return loader.await()
    }

    private fun startLoading(loader: SingleFlightValueLoader<OnnxSessionPool>) {
        loader.preload()
        synchronized(loaderLock) {
            if (loadStateObserverStarted) return
            loadStateObserverStarted = true
            _loadState.value = OnnxSessionLoadState.LOADING
            executor.execute {
                runCatching { loader.await() }
                    .onSuccess { _loadState.value = OnnxSessionLoadState.READY }
                    .onFailure { _loadState.value = OnnxSessionLoadState.FAILED }
            }
        }
    }

    private fun loaderFor(context: Context): SingleFlightValueLoader<OnnxSessionPool> {
        sessionLoader?.let { return it }
        val appContext = context.applicationContext
        return synchronized(loaderLock) {
            sessionLoader ?: SingleFlightValueLoader(executor) {
                OnnxSessionPool(appContext)
            }.also { sessionLoader = it }
        }
    }
}

/** 保证并发调用只执行一次加载，并缓存成功或失败结果。 */
internal class SingleFlightValueLoader<T>(
    private val executor: ExecutorService,
    private val valueLoader: () -> T
) {

    private val lock = Any()

    @Volatile
    private var future: Future<T>? = null

    fun preload() {
        task()
    }

    fun await(): T = task().get()

    private fun task(): Future<T> {
        future?.let { return it }
        return synchronized(lock) {
            future ?: executor.submit(Callable { valueLoader() }).also { future = it }
        }
    }
}
