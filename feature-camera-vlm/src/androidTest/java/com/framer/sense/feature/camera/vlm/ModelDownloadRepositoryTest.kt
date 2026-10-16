package com.framer.sense.feature.camera.vlm

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.framer.sense.feature.camera.vlm.scan.PhotoSessionBudget
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.framer.sense.feature.camera.vlm.agent.CompositionRepository
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.model.CompositionPlan
import com.framer.sense.feature.camera.vlm.model.CameraCapabilities
import com.framer.sense.feature.camera.vlm.model.DirectorInput
import com.framer.sense.feature.camera.vlm.model.ModelCall
import com.framer.sense.feature.camera.vlm.model.ModelMode
import com.framer.sense.feature.camera.vlm.model.ModelSettings
import com.framer.sense.feature.camera.vlm.model.SceneSnapshot
import com.framer.sense.feature.camera.vlm.model.ScanLimits
import com.framer.sense.feature.camera.vlm.ui.*
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 测试使用隔离目录和模拟运行时，不访问网络、真实用户安装或 JNI。 */
class ModelDownloadRepositoryTest {
    /** 构造隔离应用上下文。@param root 临时根目录。@return 测试上下文。 */
    private fun context(root: File): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        /** 返回隔离文件目录；无参数。 */
        override fun getNoBackupFilesDir(): File = root
        /** 隔离安装指针。@param name 配置名。@param mode Android 访问模式。 */
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${root.name}-$name", mode)
    }

    /** 创建含全部小文件的官方目录夹具。@param store 文件仓库。@param name 安装目录名。@return 目录。 */
    private fun fixture(store: LocalModelStore, name: String): File = File(store.root, name).apply {
        mkdirs()
        File(this, "config.json").writeText("{}")
        File(this, "llm_config.json").writeText("""{"is_visual":true,"tie_embeddings":[128,256,64,4,32]}""")
        listOf("llm.mnn", "llm.mnn.weight", "visual.mnn", "visual.mnn.weight", "tokenizer.txt").forEach { File(this, it).writeBytes(byteArrayOf(1, 2)) }
    }

    /** 保存进程中断前快照。@param root 私有根目录。@param dir 模型目录。@param phase 原下载状态。 */
    private fun save(root: File, dir: File, phase: DownloadPhase) {
        val files = dir.listFiles()!!.map { HubFile(it.name, it.length()) }
        File(root, "vlm-download.json").writeText(VlmJson.encodeToString(DownloadSession(HubDownloadClient.DEFAULT_SOURCE, dir.name, HubSnapshot("a".repeat(40), files), phase = phase)))
    }

    /** 创建禁止网络的 Hub 客户端。@param calls 记录意外请求数量。@return 模拟客户端。 */
    private fun noNetwork(calls: AtomicInteger): HubDownloadClient = HubDownloadClient(OkHttpClient.Builder().addInterceptor {
        calls.incrementAndGet()
        throw IOException("unexpected test network")
    }.build())

    /** 进程恢复仅暂停，重复继续复用固定快照且不重新联网下载已完成文件；无参数。 */
    @Test fun restoredSessionResumesOnceWithoutRefetchingFiles() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = context(root)
        val store = LocalModelStore(context)
        try {
            val dir = fixture(store, "download-fixture")
            save(root, dir, DownloadPhase.DOWNLOADING)
            val calls = AtomicInteger()
            val repository = ModelDownloadRepository(context, store, noNetwork(calls), { true })
            assertEquals(DownloadPhase.PAUSED, repository.state.value.phase)
            assertEquals(dir.listFiles()!!.sumOf { it.length() }, repository.state.value.downloaded)
            repository.start(HubDownloadClient.DEFAULT_SOURCE, false)
            repository.start("https://different.invalid", false)
            withTimeout(5000) { repository.state.first { it.phase == DownloadPhase.DOWNLOADED } }
            assertEquals(0, calls.get())
            assertEquals(dir, store.pendingDirectory())
            assertNull(store.activeDirectory())
        } finally { store.delete(); root.deleteRecursively() }
    }

    /** 取消删除候选文件并保留旧安装；无参数。 */
    @Test fun cancellingPendingDownloadPreservesActiveInstallation() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = context(root)
        val store = LocalModelStore(context)
        try {
            val old = fixture(store, "old")
            store.activate(old)
            val pending = fixture(store, "pending")
            store.stage(pending)
            save(root, pending, DownloadPhase.PAUSED)
            val repository = ModelDownloadRepository(context, store, noNetwork(AtomicInteger()), { true })
            repository.cancel()
            withTimeout(5000) { repository.state.first { it.phase == DownloadPhase.IDLE } }
            assertFalse(pending.exists())
            assertEquals(old, store.activeDirectory())
            assertTrue(old.exists())
        } finally { store.delete(); root.deleteRecursively() }
    }

    /** 计费网络未授权时停在暂停，不发出任何 HTTP 请求；无参数。 */
    @Test fun meteredNetworkNeedsExplicitOptIn() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = context(root)
        val store = LocalModelStore(context)
        try {
            val calls = AtomicInteger()
            val repository = ModelDownloadRepository(context, store, noNetwork(calls), { allowMetered -> allowMetered })
            repository.start(HubDownloadClient.DEFAULT_SOURCE, false)
            withTimeout(5000) { repository.state.first { it.phase == DownloadPhase.PAUSED } }
            assertEquals(0, calls.get())
            assertTrue(repository.hasSession())
            repository.cancel()
            withTimeout(5000) { repository.state.first { it.phase == DownloadPhase.IDLE } }
        } finally { store.delete(); root.deleteRecursively() }
    }

    /** 模拟加载运行时。@param fail 是否让新模型加载失败。@return 不调用 JNI 的测试运行时。 */
    private fun runtime(fail: AtomicBoolean): MnnRuntime = object : MnnRuntime {
        /** 测试初始化，不执行原生操作；无参数。 */
        override fun initialize() = Unit
        /** 返回测试句柄或模拟失败。@param path 候选配置路径。@return 测试句柄。 */
        override fun load(path: String): Long { if (fail.get()) throw IOException("fixture failure"); return 1L }
        /** 测试释放，无原生资源。@param handle 测试句柄。 */
        override fun release(handle: Long) = Unit
        /** 禁止测试进行推理。@param handle 句柄。@param image 图像。@param prompt 提示。@param cancel 取消标志。@return 不返回假推理结果。 */
        override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray = error("No inference in this test")
    }

    private fun viewImages(root: File, count: Int): List<String> = (1..count).map { index ->
        val bitmap = Bitmap.createBitmap(896, 672, Bitmap.Config.ARGB_8888)
        try {
            File(root, "view-$index.jpg").apply {
                outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            }.path
        } finally { bitmap.recycle() }
    }

    @Test fun homepageWarmupAndRepeatedCameraLoadsShareOneNativeInitialization() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val installed = fixture(store,"active"); store.activate(installed)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun load(path: String): Long {
                calls.incrementAndGet()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return 1L
            }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        try {
            provider.preloadOnAppStart { ModelMode.OFFLINE }; provider.preloadOnAppStart { ModelMode.OFFLINE }
            withTimeout(5_000) { provider.loadState.first { it.phase == LocalModelPhase.LOADING } }
            val camera = async { provider.load() }
            release.countDown(); camera.await()
            provider.load(); provider.awaitIdle()
            assertEquals(LocalModelPhase.READY, provider.loadState.value.phase)
            assertEquals(installed.path, provider.loadState.value.path)
            assertEquals(1, calls.get())
            provider.unload(); provider.preloadOnAppStart { ModelMode.OFFLINE }; provider.awaitIdle()
            assertEquals(LocalModelPhase.UNLOADED, provider.loadState.value.phase)
            assertEquals(1, calls.get())
        } finally { release.countDown(); provider.unload(); root.deleteRecursively() }
    }

    /** 已保存模式决定启动加载，指定云端不能初始化 JNI；自动模式保留原行为。 */
    @Test fun startupWarmupRespectsSavedMode() = runBlocking<Unit> {
        for (mode in ModelMode.entries) {
            val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
            val context = context(root)
            val store = LocalModelStore(context)
            store.activate(fixture(store, "active"))
            val settings = SettingsStore(context).apply { save(ModelSettings(mode = mode)) }
            val initializations = AtomicInteger()
            val loads = AtomicInteger()
            val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
                override fun initialize() { initializations.incrementAndGet() }
                override fun load(path: String): Long { loads.incrementAndGet(); return 1L }
            }
            val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
            try {
                withTimeout(5_000) { provider.preloadOnAppStart { settings.read().mode }!!.join() }
                val expectedLoads = if (mode == ModelMode.CLOUD) 0 else 1
                assertEquals(expectedLoads, initializations.get())
                assertEquals(expectedLoads, loads.get())
                assertEquals(if (mode == ModelMode.CLOUD) LocalModelPhase.UNLOADED else LocalModelPhase.READY, provider.loadState.value.phase)
                assertNull(provider.preloadOnAppStart { settings.read().mode })
                assertNotNull(store.activeDirectory())
            } finally { provider.unload(); store.delete(); root.deleteRecursively() }
        }
    }

    @Test fun cancellationWaitsForOldNativeExitAndPreservesLoadedModel() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root)); store.activate(fixture(store,"active"))
        val images = viewImages(root,1)
        val entered = CompletableDeferred<AtomicBoolean>()
        val release = java.util.concurrent.CountDownLatch(1)
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun inferImages(handle: Long, images: List<String>, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
                entered.complete(cancel)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return "1".toByteArray()
            }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        try {
            val old = launch { provider.compareViews(images,"compare") }
            val cancellation = withTimeout(5_000) { entered.await() }
            old.cancelAndJoin(); assertTrue(cancellation.get())
            val barrier = async { provider.awaitIdle() }
            yield(); assertFalse(barrier.isCompleted)
            release.countDown(); withTimeout(5_000) { barrier.await() }
            assertFalse(provider.isInferenceRunning)
            assertEquals(LocalModelPhase.READY, provider.loadState.value.phase)
            assertEquals("1", provider.compareViews(images,"compare").raw)
        } finally { release.countDown(); provider.unload(); root.deleteRecursively() }
    }

    /** 四张图片独立传入运行时，缩小副本与原图隔离，不读取云端预算。 */
    @Test fun offlineSelectionPassesFourPrivateImagesAndCleansCopies() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val inputs = viewImages(root, 4)
        var copies = emptyList<String>()
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun inferImages(handle: Long, images: List<String>, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
                copies = images
                assertEquals(4, images.size)
                assertEquals(4, images.distinct().size)
                assertTrue(images.none { it in inputs })
                images.forEach { path ->
                    val bitmap = BitmapFactory.decodeFile(path)
                    try { assertEquals(448, bitmap.width); assertEquals(336, bitmap.height) }
                    finally { bitmap.recycle() }
                }
                onToken()
                return "4".toByteArray()
            }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        try {
            store.activate(fixture(store, "active"))
            val reply = withTimeout(5_000) { provider.compareViews(inputs, "compare") }
            assertEquals("4", reply.raw)
            assertTrue(copies.none { File(it).exists() })
            assertTrue(inputs.all { File(it).isFile })
        } finally { provider.unload(); store.delete(); root.deleteRecursively() }
    }

    /** 不响应取消的运行时仍需串行交接，图片副本保留到原生安全退出。 */
    @Test fun cancelledOfflineSelectionKeepsCopiesUntilNativeExit() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val inputs = viewImages(root, 2)
        val entered = CompletableDeferred<Pair<List<String>, AtomicBoolean>>()
        val releaseNative = CountDownLatch(1)
        var copies = emptyList<String>()
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun inferImages(handle: Long, images: List<String>, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
                copies = images
                onToken(); entered.complete(images to cancel)
                check(releaseNative.await(5, TimeUnit.SECONDS))
                assertTrue(images.all { File(it).isFile })
                return "SELECT=1\nSUBJECT=山体\nREASON=适合人像".toByteArray()
            }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        try {
            store.activate(fixture(store, "active"))
            val job = launch { provider.compareViews(inputs, "compare"); fail("迟到结果不能提交") }
            val (_, cancel) = withTimeout(5_000) { entered.await() }
            job.cancelAndJoin()
            inputs.forEach { File(it).delete() }
            assertTrue(cancel.get()); assertTrue(copies.all { File(it).isFile })
            val busy = runCatching { provider.compareViews(inputs, "compare") }.exceptionOrNull() as ModelFailure
            assertEquals("LOCAL_BUSY", busy.code)
        } finally {
            releaseNative.countDown(); provider.unload()
            assertTrue(copies.none { File(it).exists() })
            store.delete(); root.deleteRecursively()
        }
    }

    /** 多图异常与输入缺失提示保留原因。 */
    @Test fun offlineSelectionFailuresPreserveCauseAndDoNotFakeSelection() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val inputs = viewImages(root, 2)
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, runtime(AtomicBoolean(false)))
        try {
            store.activate(fixture(store, "active"))
            val unsupported = runCatching { provider.compareViews(inputs, "compare") }.exceptionOrNull() as ModelFailure
            assertEquals(store.message(R.string.vlm_scan_local_unsupported), unsupported.message)
            val missing = runCatching { provider.compareViews(listOf(File(root, "missing.jpg").path), "compare") }.exceptionOrNull() as ModelFailure
            assertEquals(store.message(R.string.vlm_scan_local_input_failed), missing.message)
            assertTrue(File(root, "vlm-view-inputs").listFiles().orEmpty().isEmpty())
        } finally { provider.unload(); store.delete(); root.deleteRecursively() }
    }

    /** 云端预算已耗尽也不能阻止反复离线选景、构图或修改。 */
    @Test fun exhaustedCloudBudgetDoesNotBlockRepeatedOfflineInference() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val inputs = viewImages(root, 2)
        val selections = AtomicInteger()
        val compositions = AtomicInteger()
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun inferImages(handle: Long, images: List<String>, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
                selections.incrementAndGet(); onToken()
                return "SELECT=1\nSUBJECT=山体\nREASON=适合人像".toByteArray()
            }
            override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
                compositions.incrementAndGet(); onToken()
                return "山体层次清晰".toByteArray()
            }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        try {
            store.activate(fixture(store, "active"))
            val budget = PhotoSessionBudget()
            repeat(ScanLimits.MAX_MODEL_CALLS) { budget.consume(1, selection = it < ScanLimits.MAX_SELECTION_CALLS) }
            val input = DirectorInput(SceneSnapshot("frame", 896, 672, 1f, inputs.first(), inputs.first()), CameraCapabilities(), "", sessionBudget = budget)
            withTimeout(20_000) {
                repeat(ScanLimits.MAX_MODEL_CALLS + 2) {
                    assertTrue(provider.compareViews(inputs, "compare").raw.startsWith("SELECT=1"))
                    assertEquals("山体层次清晰", provider.step(ModelCall(input, "compose", "request-$it")).raw)
                }
            }
            assertEquals(ScanLimits.MAX_MODEL_CALLS + 2, selections.get())
            assertEquals(ScanLimits.MAX_MODEL_CALLS + 2, compositions.get())
            assertEquals(ScanLimits.MAX_MODEL_CALLS, budget.calls)
            assertEquals(ScanLimits.MAX_SELECTION_CALLS, budget.selectionCalls)
            assertEquals(ScanLimits.MAX_MODEL_CALLS.toLong(), budget.uploadedBytes)
        } finally { provider.unload(); store.delete(); root.deleteRecursively() }
    }

    /** 已加载后的推理异常、原生超时与真正的加载失败必须区分。 */
    @Test fun inferenceErrorsAreNotReportedAsLoadFailures() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val failLoad = AtomicBoolean(true)
        var inferenceError: Exception = IllegalStateException("inference failure")
        val native = object : MnnRuntime by runtime(failLoad) {
            override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray = throw inferenceError
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        val call = ModelCall(DirectorInput(SceneSnapshot("frame", 600, 800, 1f, "/unused", "/unused"), CameraCapabilities(), ""), "", "test")
        try {
            store.activate(fixture(store, "active"))
            withTimeout(5_000) {
                val loading = runCatching { provider.step(call) }.exceptionOrNull() as ModelFailure
                assertEquals("LOCAL_FAILED", loading.code)
                assertEquals(store.message(R.string.vlm_model_load_failed), loading.message)
                failLoad.set(false)
                val inference = runCatching { provider.step(call) }.exceptionOrNull() as ModelFailure
                assertEquals("LOCAL_INFERENCE_FAILED", inference.code)
                assertEquals(store.message(R.string.vlm_model_inference_failed), inference.message)
                inferenceError = TimeoutException("native timeout")
                val timeout = runCatching { provider.step(call) }.exceptionOrNull() as ModelFailure
                assertEquals("LOCAL_TIMEOUT", timeout.code)
                assertEquals(store.message(R.string.vlm_model_inference_timeout), timeout.message)
            }
        } finally { provider.unload(); store.delete(); root.deleteRecursively() }
    }

    /** 取消等待后，阻塞 JNI 的迟到结果不能回传，也不能并发释放或排入另一轮推理。 */
    @Test fun cancelledNativeInferenceMustExitBeforeRetryOrRelease() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val entered = CompletableDeferred<AtomicBoolean>()
        val releaseNative = CountDownLatch(1)
        val releases = AtomicInteger()
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
                onToken()
                entered.complete(cancel)
                check(releaseNative.await(5, TimeUnit.SECONDS))
                return "late result".toByteArray()
            }
            override fun release(handle: Long) { releases.incrementAndGet() }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        val call = ModelCall(DirectorInput(SceneSnapshot("frame", 600, 800, 1f, "/unused", "/unused"), CameraCapabilities(), ""), "", "test")
        try {
            store.activate(fixture(store, "active"))
            val job = launch { provider.step(call); fail("取消后不得返回迟到结果") }
            val cancel = withTimeout(5_000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(cancel.get())
            val busy = runCatching { provider.step(call) }.exceptionOrNull() as ModelFailure
            assertEquals("LOCAL_BUSY", busy.code)
            assertEquals(0, releases.get())
        } finally {
            releaseNative.countDown()
            provider.unload()
            assertEquals(1, releases.get())
            store.delete(); root.deleteRecursively()
        }
    }

    /** 首 token 之前也传递取消；运行时退出后重用模型，下一轮拥有独立取消标记。 */
    @Test fun visualPrefillCancellationWithoutTokensAllowsFreshRequestOnSameModel() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val images = viewImages(root, 4)
        val entered = CompletableDeferred<AtomicBoolean>()
        val loads = AtomicInteger()
        val calls = AtomicInteger()
        val releases = AtomicInteger()
        val stopped = AtomicBoolean(false)
        var previousFlag: AtomicBoolean? = null
        var oldCopies = emptyList<String>()
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun load(path: String): Long { loads.incrementAndGet(); return 42L }
            override fun release(handle: Long) { releases.incrementAndGet() }
            override fun inferImages(handle: Long, images: List<String>, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray {
                assertEquals(42L, handle)
                if (calls.incrementAndGet() == 1) {
                    oldCopies = images
                    previousFlag = cancel
                    entered.complete(cancel)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    // 不产生 token，模拟只在视觉算子边界检查取消的运行时。
                    while (!cancel.get() && System.nanoTime() < deadline) Thread.sleep(1)
                    assertTrue("视觉预填充应收到取消", cancel.get())
                    assertTrue(images.all { File(it).isFile })
                    stopped.set(true)
                    return byteArrayOf()
                }
                assertTrue(stopped.get())
                assertNotSame(previousFlag, cancel)
                assertFalse(cancel.get())
                return "2".toByteArray()
            }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        try {
            store.activate(fixture(store, "active"))
            val job = launch { provider.compareViews(images, "compare"); fail("取消后不得提交空输出") }
            val flag = withTimeout(5_000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(flag.get())
            withTimeout(5_000) { provider.awaitIdle() }
            assertTrue(stopped.get())
            assertFalse(provider.isInferenceRunning)
            assertTrue(oldCopies.none { File(it).exists() })
            assertTrue(images.all { File(it).isFile })
            assertEquals("2", withTimeout(5_000) { provider.compareViews(images, "retry") }.raw)
            assertEquals(1, loads.get())
            assertEquals(0, releases.get())
        } finally { provider.unload(); store.delete(); root.deleteRecursively() }
    }

    /** 加载失败不修改安装，成功后提交且保留旧文件；无参数。 */
    @Test fun installationCommitsOnlyAfterSuccessfulNativeLoad() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val store = LocalModelStore(context(root))
        val fail = AtomicBoolean(true)
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, runtime(fail))
        try {
            val old = fixture(store, "old")
            store.activate(old)
            val candidate = fixture(store, "candidate")
            store.stage(candidate)
            assertTrue(runCatching { provider.install(candidate) }.isFailure)
            assertEquals(old, store.activeDirectory())
            assertEquals(candidate, store.pendingDirectory())
            assertTrue(old.exists())
            fail.set(false)
            provider.install(candidate)
            assertEquals(candidate, store.activeDirectory())
            assertNull(store.pendingDirectory())
            assertTrue(old.exists())
            assertTrue(store.installed.value.any { it.directory == old.name })
        } finally { provider.unload(); store.delete(); root.deleteRecursively() }
    }
    /** 指定云端进入拍照页不加载；改为强制离线后仍能加载同一安装。 */
    @Test fun cloudCameraSkipsAutomaticLoadUntilOfflineModeIsSaved() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = context(root)
        val store = LocalModelStore(context)
        val settings = SettingsStore(context).apply { save(ModelSettings(mode = ModelMode.CLOUD)) }
        val loads = AtomicInteger()
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            override fun load(path: String): Long { loads.incrementAndGet(); return 1L }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        val owner = ViewModelStore()
        try {
            val installed = fixture(store, "active")
            store.activate(installed)
            val downloads = ModelDownloadRepository(context, store, noNetwork(AtomicInteger()), { true })
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview = error("No GPU in this test")
            }
            withContext(Dispatchers.Main) {
                val vm = VlmCameraViewModel(CompositionRepository(provider), SnapshotStore(context), PreviewStore(renderer), settings, store, provider, downloads, SavedStateHandle(), com.framer.sense.feature.camera.vlm.scan.PhotoSessionController(context))
                owner.put("camera", vm)
                vm.onIntent(VlmIntent.CameraForeground(true))
                yield()
                provider.awaitIdle()
                assertEquals(0, loads.get())
                assertEquals(LocalModelPhase.UNLOADED, provider.loadState.value.phase)
                vm.onIntent(VlmIntent.SaveSettings(vm.state.value.settings.copy(mode = ModelMode.OFFLINE)))
                withTimeout(5_000) { vm.state.first { it.settings.mode == ModelMode.OFFLINE && it.download.phase == DownloadPhase.READY && !it.modelBusy } }
                assertEquals(1, loads.get())
                assertEquals(installed, store.activeDirectory())
                vm.onLeave()
            }
        } finally {
            withContext(Dispatchers.Main) { owner.clear() }
            provider.unload(); store.delete(); root.deleteRecursively()
        }
    }

    /** 已下载模型只在相机前台空闲时加载，重复前台事件和手动卸载不会触发加载循环；无参数。 */
    @Test fun automaticLoadWaitsForForegroundAndIdleCamera() = runBlocking<Unit> {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = context(root)
        val store = LocalModelStore(context)
        val calls = AtomicInteger()
        val native = object : MnnRuntime by runtime(AtomicBoolean(false)) {
            /** 记录加载次数。@param path 模型配置。@return 测试句柄。 */
            override fun load(path: String): Long { calls.incrementAndGet(); return 1L }
        }
        val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, native)
        val owner = ViewModelStore()
        try {
            val dir = fixture(store, "download-fixture")
            store.stage(dir)
            save(root, dir, DownloadPhase.DOWNLOADED)
            val downloads = ModelDownloadRepository(context, store, noNetwork(AtomicInteger()), { true })
            val renderer = object : AvatarRenderer {
                /** 此测试不执行渲染。@param plan 方案。@param width 宽。@param height 高。@param warmth 色温。@param brightness 亮度。@return 不产生图像。 */
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview = error("No GPU in this test")
            }
            withContext(Dispatchers.Main) {
                val vm = VlmCameraViewModel(CompositionRepository(provider), SnapshotStore(context), PreviewStore(renderer), SettingsStore(context), store, provider, downloads, SavedStateHandle(), com.framer.sense.feature.camera.vlm.scan.PhotoSessionController(context))
                owner.put("camera", vm)
                yield()
                assertEquals(0, calls.get())
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities()))
                vm.onIntent(VlmIntent.StartComposition)
                vm.onIntent(VlmIntent.CameraForeground(true))
                yield()
                assertEquals(0, calls.get())
                vm.onIntent(VlmIntent.Cancel)
                withTimeout(5000) { vm.state.first { it.download.phase == DownloadPhase.READY && !it.modelBusy } }
                repeat(3) { vm.onIntent(VlmIntent.CameraForeground(true)) }
                assertEquals(1, calls.get())
                vm.onIntent(VlmIntent.UnloadModel)
                withTimeout(5000) { vm.state.first { !it.modelBusy && it.download.phase == DownloadPhase.DOWNLOADED } }
                yield()
                assertEquals(1, calls.get())
                vm.onLeave()
            }
        } finally {
            withContext(Dispatchers.Main) { owner.clear() }
            provider.unload(); store.delete(); root.deleteRecursively()
        }
    }

}
