package com.framer.sense.feature.camera.vlm

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.framer.sense.feature.camera.vlm.agent.CompositionRepository
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.model.CompositionPlan
import com.framer.sense.feature.camera.vlm.model.CameraCapabilities
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
        override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean): ByteArray = error("No inference in this test")
    }

    /** 加载失败不修改安装，成功后才提交并清理旧文件；无参数。 */
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
            assertFalse(old.exists())
        } finally { provider.unload(); store.delete(); root.deleteRecursively() }
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
                val vm = VlmCameraViewModel(CompositionRepository(provider), SnapshotStore(context), PreviewStore(renderer), SettingsStore(context), store, provider, downloads, SavedStateHandle())
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
