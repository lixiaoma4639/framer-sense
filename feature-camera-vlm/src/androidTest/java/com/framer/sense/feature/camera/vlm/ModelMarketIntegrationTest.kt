package com.framer.sense.feature.camera.vlm

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.market.*
import com.framer.sense.feature.camera.vlm.model.*
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 小文件夹具与模拟运行时，绝不下载真实权重或创建 JNI/GPU 会话。 */
class ModelMarketIntegrationTest {
    private fun context(root: File): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getNoBackupFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("market-${root.name}-$name", mode)
    }
    private fun fixture(root: File, name: String) = File(root, "vlm-models/$name").apply {
        mkdirs()
        File(this, "config.json").writeText("{}")
        File(this, "llm_config.json").writeText("""{"is_visual":true,"tie_embeddings":[128,256,64,4,32]}""")
        listOf("llm.mnn", "llm.mnn.weight", "visual.mnn", "tokenizer.txt").forEach { File(this, it).writeBytes(byteArrayOf(1, 2)) }
    }
    private fun saveTask(root: File, dir: File, id: String, legacy: Boolean = false, phase: DownloadPhase = DownloadPhase.DOWNLOADING) {
        val files = dir.listFiles()!!.map { HubFile(it.name, it.length()) }
        val task = DownloadSession("https://huggingface.co", dir.name, HubSnapshot("a".repeat(40), files),
            phase = phase, modelId = id, repository = "taobao-mnn/$id")
        File(root, if (legacy) "vlm-download.json" else "vlm-market-$id.json").writeText(VlmJson.encodeToString(task))
    }
    private fun root() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "market-${UUID.randomUUID()}").apply { mkdirs() }
    private fun noNetwork(calls: AtomicInteger) = HubDownloadClient(OkHttpClient.Builder().addInterceptor {
        calls.incrementAndGet(); throw IOException("No real network in test")
    }.build())

    @Test fun existingInstallationAndDownloadMigrateWithoutMovingOrChangingSource() = runBlocking<Unit> {
        val root = root()
        val context = context(root)
        val active = fixture(root, "download-legacy-active")
        context.getSharedPreferences("vlm-model", Context.MODE_PRIVATE).edit().putString("active", active.name).commit()
        val pending = fixture(root, "download-legacy-task")
        saveTask(root, pending, MarketCatalog.DEFAULT_MODEL, legacy = true)
        val store = LocalModelStore(context)
        val calls = AtomicInteger()
        val hub = noNetwork(calls)
        val legacy = ModelDownloadRepository(context, store, hub, { true })
        val manager = MarketDownloadManager(context, store, legacy, hub, { true })
        try {
            assertEquals(active, store.activeDirectory())
            assertEquals(MarketCatalog.DEFAULT_MODEL, store.modelId())
            assertEquals(DownloadPhase.PAUSED, legacy.state.value.phase)
            assertEquals(0, calls.get())
            manager.enqueue(MarketCatalog.DEFAULT_MODEL, MarketSource.MODELSCOPE, false)
            // 已安装旧版本时，继续原任务仍使用原快照和来源，不因源切换拼接文件。
            withTimeout(5_000) { legacy.state.first { it.phase == DownloadPhase.DOWNLOADED } }
            assertEquals("https://huggingface.co", legacy.state.value.source)
            assertTrue(File(pending, "config.json").exists())
            assertEquals(0, calls.get())
            assertEquals(pending, store.pendingDirectory())
            assertTrue(active.exists())
            val settings = SettingsStore(context).apply { save(ModelSettings(qwenApiKey = "qwen-test", seedApiKey = "seed-test")) }
            ModelMarketRepository(context, hub).saveSource(MarketSource.HUGGING_FACE)
            assertEquals(MarketSource.HUGGING_FACE, ModelMarketRepository(context, hub).source())
            assertEquals("qwen-test", settings.read().qwenApiKey)
            assertEquals("seed-test", settings.read().seedApiKey)
        } finally { manager.pauseAll(); root.deleteRecursively() }
    }

    @Test fun gemmaAndQwen35CandidatesRestoreAndInstallThroughSharedQueue() = runBlocking<Unit> {
        val root = root()
        val context = context(root)
        val candidates = MarketCatalog.gemmaCandidateIds + MarketCatalog.qwen35CandidateIds
        val dirs = candidates.associateWith { id ->
            fixture(root, "download-$id").also { dir ->
                if (id in MarketCatalog.gemmaCandidateIds) {
                    File(dir, "config.json").writeText("""{"tokenizer_file":"tokenizer.mtok"}""")
                    File(dir, "llm_config.json").writeText("""{"model_type":"gemma4","is_visual":true,"is_audio":true,"tie_embeddings":[128,256,64,4,32],"ple_embed_file":"ple_embeddings_int4.bin"}""")
                    File(dir, "tokenizer.txt").renameTo(File(dir, "tokenizer.mtok"))
                    File(dir, "ple_embeddings_int4.bin").writeBytes(byteArrayOf(1, 2))
                } else {
                    File(dir, "config.json").writeText("""{"jinja":{"context":{"enable_thinking":true}}}""")
                    File(dir, "llm_config.json").writeText("""{"model_type":"qwen3_5","is_visual":true,"tie_embeddings":[128,256,64,4,32]}""")
                }
                saveTask(root, dir, id, phase = DownloadPhase.FAILED)
            }
        }
        val store = LocalModelStore(context)
        val calls = AtomicInteger()
        val hub = noNetwork(calls)
        val legacy = ModelDownloadRepository(context, store, hub, { true })
        val manager = MarketDownloadManager(context, store, legacy, hub, { true })
        try {
            dirs.forEach { (id, dir) ->
                assertEquals(DownloadPhase.FAILED, manager.states.value[id]?.phase)
                manager.enqueue(id, MarketSource.MODELSCOPE, false, "MNN/$id")
                withTimeout(5_000) { manager.states.first { it[id]?.phase == DownloadPhase.DOWNLOADED } }
                assertEquals(id, store.modelId(dir))
                assertEquals(dir, store.pendingDirectory())
                assertEquals("https://huggingface.co", manager.states.value[id]?.source)
            }
            assertEquals(candidates.size, store.installed.value.size)
            assertEquals(0, calls.get())
        } finally { manager.pauseAll(); root.deleteRecursively() }
    }

    @Test fun queueCancellationReleasesSlotAndPreservesOtherModelFiles() = runBlocking<Unit> {
        val root = root()
        val context = context(root)
        val first = "Qwen3-VL-4B-Instruct-MNN"
        val second = "Qwen3-VL-8B-Instruct-MNN"
        val firstDir = fixture(root, "download-first")
        val secondDir = fixture(root, "download-second")
        saveTask(root, firstDir, first)
        saveTask(root, secondDir, second)
        val store = LocalModelStore(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checks = AtomicInteger()
        val calls = AtomicInteger()
        val hub = noNetwork(calls)
        val network: (Boolean) -> Boolean = {
            if (checks.getAndIncrement() == 0) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            true
        }
        val legacy = ModelDownloadRepository(context, store, hub, { true })
        val manager = MarketDownloadManager(context, store, legacy, hub, network)
        try {
            manager.enqueue(first, MarketSource.MODELSCOPE, false)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            manager.enqueue(second, MarketSource.MODELSCOPE, false)
            assertEquals(DownloadPhase.QUEUED, manager.states.value[second]!!.phase)
            manager.cancel(first)
            release.countDown()
            withTimeout(5_000) { manager.states.first { it[first]?.phase == DownloadPhase.IDLE && it[second]?.phase == DownloadPhase.DOWNLOADED } }
            assertFalse(firstDir.exists())
            assertTrue(secondDir.exists())
            assertEquals(secondDir, store.pendingDirectory())
            assertEquals("https://huggingface.co", manager.states.value.getValue(second).source)
            assertEquals(0, calls.get())
        } finally { release.countDown(); manager.pauseAll(); root.deleteRecursively() }
    }

    @Test fun newestCandidateWinsWithoutDeletingPreviousInstallations() = runBlocking<Unit> {
        val root = root()
        val store = LocalModelStore(context(root))
        try {
            val first = fixture(root, "first")
            val second = fixture(root, "second")
            store.register(first, MarketCatalog.DEFAULT_MODEL)
            store.register(second, "Qwen3-VL-4B-Instruct-MNN")
            store.stage(first); store.stage(second)
            store.activate(first) // 第一份加载期间，第二份已经下载完成。
            assertEquals(second, store.pendingDirectory())
            assertEquals(first, store.activeDirectory())
            assertEquals(2, store.installed.value.size)
            store.deleteDirectory(second)
            assertEquals(first, store.activeDirectory())
            assertTrue(first.exists()); assertFalse(second.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedCandidateRestoresOldHandleWithoutLoadingTwoModels() = runBlocking<Unit> {
        for (failRecovery in listOf(false, true)) {
            val root = root()
            val store = LocalModelStore(context(root))
            val previous = fixture(root, "previous")
            val candidate = fixture(root, "candidate")
            store.register(previous, MarketCatalog.DEFAULT_MODEL)
            store.register(candidate, "Qwen3-VL-4B-Instruct-MNN")
            store.activate(previous); store.stage(candidate)
            var live = 0
            var previousLoads = 0
            val runtime = object : MnnRuntime {
                override fun initialize() = Unit
                override fun load(path: String): Long {
                    assertEquals(0, live)
                    if (path.startsWith(candidate.path)) throw IOException("candidate failure")
                    if (previousLoads++ > 0 && failRecovery) throw IOException("recovery failure")
                    live++; return 1L
                }
                override fun release(handle: Long) { live-- }
                override fun infer(handle: Long, image: String, prompt: ByteArray, cancel: AtomicBoolean, onToken: () -> Unit): ByteArray = error("No inference")
            }
            val provider = MnnProvider(store, { arrayOf("arm64-v8a") }, runtime)
            try {
                provider.load()
                val error = runCatching { provider.install(candidate) }.exceptionOrNull() as ModelStorageException
                assertEquals(if (failRecovery) R.string.vlm_market_switch_restore_failed else R.string.vlm_market_switch_restored, error.resource)
                assertEquals(previous, store.activeDirectory())
                assertEquals(if (failRecovery) LocalModelPhase.FAILED else LocalModelPhase.READY, provider.loadState.value.phase)
                assertTrue(previous.exists()); assertTrue(candidate.exists())
                assertEquals(if (failRecovery) 0 else 1, live)
            } finally { provider.unload(); root.deleteRecursively() }
        }
    }
}
