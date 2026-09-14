package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.AtomicFile
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.VlmJson
import com.framer.sense.feature.camera.vlm.model.newVlmId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable enum class DownloadPhase { IDLE, RESOLVING, DOWNLOADING, VERIFYING, PAUSED, CANCELLING, DOWNLOADED, LOADING, READY, LOAD_FAILED, FAILED }
@Serializable data class DownloadSession(
    val source: String,
    val directory: String,
    val snapshot: HubSnapshot? = null,
    val allowMetered: Boolean = false,
    val phase: DownloadPhase = DownloadPhase.RESOLVING
)
data class ModelDownloadState(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val source: String = HubDownloadClient.DEFAULT_SOURCE,
    val allowMetered: Boolean = false,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: String? = null
) {
    val running: Boolean get() = phase in setOf(DownloadPhase.RESOLVING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.CANCELLING)
}

/** 进程级下载仓库；服务持有下载任务，页面只订阅状态。
 * @param context 应用上下文。@param models 私有模型仓库。@param hub HTTP 下载实现。
 * @param networkAllowed 可选测试网络策略；生产默认读取系统网络状态。
 */
class ModelDownloadRepository(private val context: Context, private val models: LocalModelStore, private val hub: HubDownloadClient = HubDownloadClient(), private val networkAllowed: ((Boolean) -> Boolean)? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFile = AtomicFile(File(context.noBackupFilesDir, "vlm-download.json"))
    @Volatile private var session: DownloadSession? = restore()
    private val _state = MutableStateFlow(restoredState())
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()
    private var task: Job? = null
    @Volatile private var cancelling = false
    @Volatile private var pauseReason: String? = null

    /** 恢复下载元信息；无参数，进程中断的任务只恢复为暂停，不自动联网。 */
    private fun restore(): DownloadSession? = runCatching {
        if (!stateFile.baseFile.exists()) return@runCatching null
        val saved = stateFile.openRead().use { VlmJson.decodeFromString<DownloadSession>(it.bufferedReader().readText()) }
        val dir = ModelFiles.child(models.root, saved.directory)
        saved.snapshot?.files?.forEach { file ->
            ModelFiles.child(dir, file.path)
            if (file.size < 0 || file.size > ModelFiles.MAX_BYTES) throw ModelStorageException(R.string.vlm_model_metadata)
        }
        saved.copy(phase = when (saved.phase) {
            DownloadPhase.RESOLVING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.CANCELLING -> DownloadPhase.PAUSED
            DownloadPhase.LOADING -> DownloadPhase.DOWNLOADED
            // 进程已重建，旧原生句柄不存在。
            DownloadPhase.READY -> DownloadPhase.DOWNLOADED
            else -> saved.phase
        })
    }.getOrNull()

    /** 从实际文件计算恢复进度；无参数，不依赖上次进程的内存计数。 */
    private fun restoredState(): ModelDownloadState {
        val current = session ?: return ModelDownloadState()
        val dir = ModelFiles.child(models.root, current.directory)
        if (!dir.exists()) return ModelDownloadState(source = current.source, allowMetered = current.allowMetered)
        return ModelDownloadState(current.phase, current.source, current.allowMetered,
            current.snapshot?.files?.sumOf { ModelFiles.child(dir, it.path).length().coerceAtMost(it.size) } ?: 0,
            current.snapshot?.files?.sumOf { it.size } ?: 0)
    }

    /** 是否存在保留的任务。无参数，供界面选择继续而不是更换任务来源。 */
    fun hasSession(): Boolean = session?.let { File(models.root, it.directory).exists() } == true

    /** 启动或继续任务；重复请求不会创建第二个下载。
     * @param source 新任务的下载源；续传时强制复用原来源和提交。
     * @param allowMetered 本次用户是否允许计费网络。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized fun start(source: String, allowMetered: Boolean) {
        if (task != null || _state.value.phase !in setOf(DownloadPhase.IDLE, DownloadPhase.PAUSED, DownloadPhase.FAILED)) return
        cancelling = false
        pauseReason = null
        _state.update { it.copy(phase = DownloadPhase.RESOLVING, allowMetered = allowMetered, error = null) }
        task = scope.launch(start = CoroutineStart.ATOMIC) { runDownload(source, allowMetered) }
    }

    /** 暂停任务并保留断点。@param reason 需要显示的本地化说明，普通用户暂停传 null。 */
    @Synchronized fun pause(reason: String? = null) { if (!cancelling) { if (reason != null) pauseReason = reason; task?.cancel() } }

    /** 取消并清理本次文件；无参数，已激活模型永不删除。 */
    @Synchronized fun cancel() {
        if (_state.value.phase == DownloadPhase.LOADING || cancelling) return
        cancelling = true
        _state.update { it.copy(phase = DownloadPhase.CANCELLING) }
        if (task != null) task?.cancel() else {
            task = scope.launch(start = CoroutineStart.LAZY) { finish(DownloadPhase.IDLE, null) }
            task!!.start()
        }
    }

    /** 执行单个下载会话，所有耗时 I/O 在仓库线程完成。
     * @param source 新会话源。@param allowMetered 是否允许计费网络。
     */
    private suspend fun runDownload(source: String, allowMetered: Boolean) {
        var finalPhase = DownloadPhase.DOWNLOADED
        var failure: String? = null
        try {
            var current = session?.takeIf { File(models.root, it.directory).exists() }?.copy(allowMetered = allowMetered)
                ?: DownloadSession(hub.normalizeSource(source), "download-${newVlmId()}", allowMetered = allowMetered)
            val dir = ModelFiles.child(models.root, current.directory).apply { mkdirs() }
            session = current
            _state.update { it.copy(source = current.source, allowMetered = current.allowMetered) }
            persist(current)
            checkNetwork(allowMetered)
            if (current.snapshot == null) {
                current = current.copy(snapshot = hub.snapshot(current.source))
                session = current
                persist(current)
            }
            val snapshot = current.snapshot!!
            snapshot.files.forEach { ModelFiles.child(dir, it.path) }
            val total = snapshot.files.sumOf { it.size }
            ModelFiles.checkSpace(dir.usableSpace, snapshot.files.sumOf { (it.size - ModelFiles.child(dir, it.path).length()).coerceAtLeast(0) })
            _state.value = ModelDownloadState(DownloadPhase.DOWNLOADING, current.source, allowMetered, total = total)
            persist(current.copy(phase = DownloadPhase.DOWNLOADING))
            var completed = 0L
            var reportedAt = 0L
            for (file in snapshot.files) {
                currentCoroutineContext().ensureActive()
                val base = completed
                hub.download(current.source, snapshot.revision, file, ModelFiles.child(dir, file.path), { checkNetwork(allowMetered) }) { bytes ->
                    val now = System.nanoTime()
                    if (now - reportedAt > 200_000_000L || bytes == file.size) {
                        reportedAt = now
                        _state.update { it.copy(downloaded = base + bytes) }
                    }
                }
                completed += file.size
            }
            _state.update { it.copy(phase = DownloadPhase.VERIFYING, downloaded = total) }
            models.validateDirectory(dir)
            currentCoroutineContext().ensureActive()
            if (models.activeDirectory() != dir) models.stage(dir)
        } catch (cancel: CancellationException) {
            finalPhase = DownloadPhase.PAUSED
            failure = pauseReason
        } catch (error: Exception) {
            val interrupted = !currentCoroutineContext().isActive
            finalPhase = if (interrupted || error is ModelStorageException && error.resource == R.string.vlm_download_network) DownloadPhase.PAUSED else DownloadPhase.FAILED
            failure = if (interrupted) pauseReason else models.errorMessage(error)
        } finally {
            withContext(NonCancellable) { finish(finalPhase, failure) }
        }
    }

    /** 结束任务并持久化，只有清理完成才开放下一次操作。
     * @param phase 结束状态。@param error 本地化错误。
     */
    private fun finish(phase: DownloadPhase, error: String?) {
        synchronized(this) {
            var finalPhase = phase
            var finalError = error
            try {
                if (cancelling) {
                    session?.let { models.discard(ModelFiles.child(models.root, it.directory)) }
                    stateFile.delete()
                    session = null
                    finalPhase = DownloadPhase.IDLE
                } else session?.let { session = it.copy(phase = phase); persist(session!!) }
            } catch (failure: Exception) { finalPhase = DownloadPhase.FAILED; finalError = models.errorMessage(failure) }
            task = null
            cancelling = false
            _state.value = if (finalPhase == DownloadPhase.IDLE) ModelDownloadState(source = _state.value.source)
                else _state.value.copy(phase = finalPhase, error = finalError)
        }
    }

    /** 记录加载结果；无参数下载任务不会在此自动重试。
     * @param phase LOADING、READY 或 LOAD_FAILED。@param error 可展示的加载错误。
     */
    suspend fun loadingState(phase: DownloadPhase, error: String? = null) = withContext(Dispatchers.IO) {
        synchronized(this@ModelDownloadRepository) {
            if (task != null) return@synchronized
            session = session?.copy(phase = phase)
            session?.let { persist(it) }
            _state.update { it.copy(phase = phase, error = error) }
        }
    }

    /** 清除下载记录和未激活暂存文件；无参数，已激活模型保留。 */
    suspend fun forget() = withContext(Dispatchers.IO) {
        synchronized(this@ModelDownloadRepository) {
            if (task != null) return@synchronized
            session?.let { models.discard(ModelFiles.child(models.root, it.directory)) }
            session = null
            stateFile.delete()
            _state.value = ModelDownloadState(source = _state.value.source)
        }
    }

    /** 显示前台服务启动失败；未启动的任务也必须有可重试状态。
     * @param message 已本地化的系统操作错误。
     */
    @Synchronized fun serviceFailed(message: String) {
        if (task != null) pause(message)
        else _state.update { it.copy(phase = DownloadPhase.FAILED, error = message) }
    }

    /** 网络变化时及时取消当前 socket；无参数，避免网络切换后继续使用计费流量。 */
    fun networkChanged() {
        if (!_state.value.running) return
        try { checkNetwork(_state.value.allowMetered) }
        catch (_: ModelStorageException) { pause(models.message(R.string.vlm_download_network)) }
    }

    /** 检查连接和计费网络约束。@param allowMetered 用户是否授权计费网络。 */
    private fun checkNetwork(allowMetered: Boolean) {
        if (networkAllowed != null) {
            if (!networkAllowed.invoke(allowMetered)) throw ModelStorageException(R.string.vlm_download_network)
            return
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        if (capabilities == null || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) || (!allowMetered && connectivity.isActiveNetworkMetered))
            throw ModelStorageException(R.string.vlm_download_network)
    }

    /** 原子保存任务，临时写失败不覆盖上一个断点。@param value 固定版本任务元数据。 */
    private fun persist(value: DownloadSession) {
        val stream = stateFile.startWrite()
        try { stream.write(VlmJson.encodeToString(value).toByteArray()); stateFile.finishWrite(stream) }
        catch (error: Exception) { stateFile.failWrite(stream); throw error }
    }
}
