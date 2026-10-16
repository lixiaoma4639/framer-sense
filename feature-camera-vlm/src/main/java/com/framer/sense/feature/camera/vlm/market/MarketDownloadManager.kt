package com.framer.sense.feature.camera.vlm.market

import android.content.Context
import android.util.AtomicFile
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.VlmJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable data class MarketQueueEntry(val id: String, val source: String, val allowMetered: Boolean, val repository: String? = null)

/** 共享原下载事务；每模型独立断点，队列串行，旧下载会话仍占用同一传输槽位。 */
class MarketDownloadManager(
    private val context: Context,
    private val models: LocalModelStore,
    private val legacy: ModelDownloadRepository,
    private val hub: HubDownloadClient = HubDownloadClient(),
    private val networkAllowed: ((Boolean) -> Boolean)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueFile = AtomicFile(File(context.noBackupFilesDir, "vlm-market-queue.json"))
    private val repositories = mutableMapOf(MarketCatalog.DEFAULT_MODEL to legacy)
    private val _states = MutableStateFlow(mapOf(MarketCatalog.DEFAULT_MODEL to legacy.state.value))
    val states = _states.asStateFlow()
    private val _summary = MutableStateFlow(legacy.state.value)
    val summary = _summary.asStateFlow()
    private val queue = mutableListOf<MarketQueueEntry>()
    private val restoredRepositories = mutableMapOf<String, String?>()
    private var active: String? = null
    private var suspended = false

    init {
        val restored = runCatching {
            if (queueFile.baseFile.exists() && queueFile.baseFile.length() < 64 * 1024)
                VlmJson.decodeFromString<List<MarketQueueEntry>>(queueFile.openRead().bufferedReader().use { it.readText() }) else emptyList()
        }.getOrDefault(emptyList()).filter { it.id in MarketCatalog.compatibleIds }.distinctBy { it.id }
        restored.forEach { restoredRepositories[it.id] = it.repository }
        MarketCatalog.compatibleIds.forEach { id ->
            if (id != MarketCatalog.DEFAULT_MODEL) repositories[id] = ModelDownloadRepository(context, models, hub, networkAllowed,
                id, "vlm-market-$id.json")
        }
        repositories.forEach { (id, repository) ->
            val queued = restored.firstOrNull { it.id == id }
            if (queued != null && repository.state.value.phase == DownloadPhase.IDLE)
                _states.value = _states.value + (id to ModelDownloadState(DownloadPhase.PAUSED, queued.source, queued.allowMetered, modelId = id))
            else _states.value = _states.value + (id to repository.state.value)
        }
        repositories.forEach { (id, repository) ->
            scope.launch { repository.state.collect { value -> synchronized(this@MarketDownloadManager) {
                // 尚未启动的排队/恢复记录不能被仓库初始 IDLE 覆盖。
                if (value.phase == DownloadPhase.IDLE && _states.value[id]?.phase !in setOf(DownloadPhase.QUEUED, DownloadPhase.PAUSED))
                    restoredRepositories.remove(id)
                if (value.phase != DownloadPhase.IDLE || _states.value[id]?.phase !in setOf(DownloadPhase.QUEUED, DownloadPhase.PAUSED))
                    _states.value = _states.value + (id to value)
                if (active == id && !value.running) active = null
                advanceSafely()
            } } }
        }
    }

    @Synchronized fun enqueue(id: String, source: MarketSource, allowMetered: Boolean, repository: String? = null) {
        enqueueRequest(id, source.baseUrl, allowMetered, repository)
    }
    @Synchronized fun enqueueLegacy(source: String, allowMetered: Boolean) {
        enqueueRequest(MarketCatalog.DEFAULT_MODEL, source, allowMetered, null)
    }
    private fun enqueueRequest(id: String, source: String, allowMetered: Boolean, repository: String?) {
        require(id in MarketCatalog.compatibleIds)
        val downloader = repositories.getValue(id)
        val old = _states.value[id]!!
        if (old.running || old.phase == DownloadPhase.QUEUED ||
            models.installedModel(id) != null && (!downloader.hasSession() || old.phase in setOf(DownloadPhase.DOWNLOADED, DownloadPhase.READY, DownloadPhase.LOADING))) return
        suspended = false
        // 续传复用原来源；未启动的队列恢复也复用所保存来源。
        val fixedSource = if (downloader.hasSession() || old.phase == DownloadPhase.PAUSED) old.source else source
        queue += MarketQueueEntry(id, fixedSource, allowMetered, (if (old.phase == DownloadPhase.PAUSED) restoredRepositories[id] else null) ?: repository)
        restoredRepositories[id] = queue.last().repository
        _states.value = _states.value + (id to old.copy(phase = DownloadPhase.QUEUED, source = fixedSource, allowMetered = allowMetered))
        if (persistSafely()) advanceSafely()
    }

    @Synchronized fun pause(id: String) {
        queue.removeAll { it.id == id }
        val old = _states.value[id] ?: return
        if (old.running) repositories.getValue(id).pause()
        else _states.value = _states.value + (id to old.copy(phase = DownloadPhase.PAUSED))
        if (persistSafely()) advanceSafely()
    }

    @Synchronized fun cancel(id: String) {
        queue.removeAll { it.id == id }
        restoredRepositories.remove(id)
        val old = _states.value[id] ?: return
        if (old.phase in setOf(DownloadPhase.DOWNLOADED, DownloadPhase.READY, DownloadPhase.LOADING)) return
        _states.value = _states.value + (id to old.copy(phase = DownloadPhase.CANCELLING))
        repositories.getValue(id).cancel()
        if (persistSafely()) advanceSafely()
    }

    @Synchronized fun pauseAll() {
        suspended = true
        queue.forEach { entry -> _states.value[entry.id]?.let { _states.value = _states.value + (entry.id to it.copy(phase = DownloadPhase.PAUSED)) } }
        persistSafely()
        queue.clear()
        repositories.values.forEach { if (it.state.value.running) it.pause() }
        updateSummary()
    }
    fun networkChanged() { repositories.values.forEach { it.networkChanged() } }

    suspend fun forget(id: String) {
        repositories[id]?.forget()
        synchronized(this) {
            queue.removeAll { it.id == id }
            restoredRepositories.remove(id)
            _states.value = _states.value + (id to ModelDownloadState(modelId = id))
            if (persistSafely()) advanceSafely()
        }
    }

    private fun persistSafely(): Boolean = try { persist(); true } catch (error: Exception) { failQueue(error); false }
    private fun advanceSafely() { try { advance() } catch (error: Exception) { failQueue(error) } }
    private fun failQueue(error: Exception) {
        suspended = true
        val failed = active
        active = null
        queue.clear()
        repositories.values.forEach { if (it.state.value.running) it.pause() }
        val message = context.getString(com.framer.sense.feature.camera.vlm.R.string.vlm_model_persist)
        _states.value = _states.value.mapValues { (id, value) ->
            if (id == failed || value.phase == DownloadPhase.QUEUED) value.copy(phase = DownloadPhase.FAILED, error = message) else value
        }
        android.util.Log.w("VlmOffline", "模型下载队列失败 error=${error::class.java.simpleName}")
        updateSummary()
    }

    @Synchronized private fun advance() {
        if (!suspended && active == null && repositories.values.none { it.state.value.running } && queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            active = next.id
            val repository = repositories.getValue(next.id)
            _states.value = _states.value + (next.id to _states.value.getValue(next.id).copy(phase = DownloadPhase.RESOLVING))
            // 先落盘；进程在首次仓库写入前退出，也能恢复为暂停。
            persist(next)
            repository.start(next.source, next.allowMetered, next.repository)
        }
        updateSummary()
    }
    private fun updateSummary() {
        _summary.value = _states.value.values.firstOrNull { it.running }
            ?: _states.value.values.firstOrNull { it.phase == DownloadPhase.QUEUED }
            ?: _states.value.values.firstOrNull { it.phase == DownloadPhase.PAUSED || it.phase == DownloadPhase.FAILED }
            ?: _states.value.values.lastOrNull { it.phase != DownloadPhase.IDLE } ?: legacy.state.value
    }
    private fun persist(activeEntry: MarketQueueEntry? = null) {
        val unfinished = _states.value.filterValues { it.phase in setOf(DownloadPhase.PAUSED, DownloadPhase.QUEUED) }
            .map { (id, value) -> MarketQueueEntry(id, value.source, value.allowMetered, restoredRepositories[id]) }
        val values = (listOfNotNull(activeEntry) + queue + unfinished).distinctBy { it.id }
        val stream = queueFile.startWrite()
        try { stream.write(VlmJson.encodeToString(values).toByteArray()); queueFile.finishWrite(stream) }
        catch (error: Exception) { queueFile.failWrite(stream); throw error }
    }
}
