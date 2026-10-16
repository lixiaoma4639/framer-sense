package com.framer.sense.feature.camera.vlm.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framer.sense.feature.camera.vlm.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarketUiState(
    val models: List<MarketModel> = emptyList(),
    val allModels: List<MarketModel> = emptyList(),
    val filter: MarketFilter = MarketFilter(),
    val source: MarketSource = MarketSource.MODELSCOPE,
    val installed: List<InstalledModel> = emptyList(),
    val downloads: Map<String, ModelDownloadState> = emptyMap(),
    val activeModel: String? = null,
    val loading: Boolean = false,
    val error: Int? = null,
    val runtime: LocalModelLoadState = LocalModelLoadState()
) {
    /** 下载记录不能证明原生句柄可用；按实际加载目录显示状态，兼容同一模型多个版本。 */
    fun modelPhase(id: String): DownloadPhase {
        val task = downloads[id]?.phase ?: DownloadPhase.IDLE
        if (task !in setOf(DownloadPhase.IDLE, DownloadPhase.DOWNLOADED, DownloadPhase.LOADING,
                DownloadPhase.READY, DownloadPhase.LOAD_FAILED)) return task
        val installations = installed.filter { it.id == id }
        if (runtime.path != null && installations.any { it.directory == runtime.path }) {
            when (runtime.phase) {
                LocalModelPhase.LOADING -> return DownloadPhase.LOADING
                LocalModelPhase.READY -> return DownloadPhase.READY
                LocalModelPhase.FAILED -> return DownloadPhase.LOAD_FAILED
                LocalModelPhase.UNLOADED -> Unit
            }
        }
        return if (installations.isNotEmpty() || task != DownloadPhase.IDLE) DownloadPhase.DOWNLOADED else DownloadPhase.IDLE
    }
}

/** 市场独立 MVVM；相机模型应用、删除由相机 MVI 管理，不绕过摄影忙碌门控。 */
@HiltViewModel
class ModelMarketViewModel @Inject constructor(
    private val repository: ModelMarketRepository,
    private val downloads: MarketDownloadManager,
    private val store: LocalModelStore,
    private val local: MnnProvider
) : ViewModel() {
    private val operationError = MutableStateFlow<Int?>(null)
    private val catalog = combine(repository.state, operationError) { value, error -> value.copy(error = error ?: value.error) }
    private val filter = MutableStateFlow(MarketFilter())
    private val source = MutableStateFlow(repository.source())
    val state: StateFlow<MarketUiState> = combine(catalog, filter, source, downloads.states, store.changes) {
            catalog, filter, source, tasks, _ ->
        val installed = store.installed.value
        val allModels = MarketCatalog.withInstalled(catalog.models, installed)
        MarketUiState(MarketCatalog.filter(allModels, filter, installed.map { it.id }.toSet(), tasks.mapValues { it.value.phase }),
            allModels, filter, source, installed, tasks, store.modelId(), catalog.loading, catalog.error)
    }.combine(local.loadState) { state, runtime -> state.copy(runtime = runtime) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketUiState(source = source.value))
    init { refresh() }
    fun refresh() { operationError.value = null; viewModelScope.launch { repository.refresh() } }
    fun setFilter(value: MarketFilter) { filter.value = if (value.installedOnly) MarketFilter(installedOnly = true) else value }
    fun setSource(value: MarketSource) { repository.saveSource(value); source.value = value }
    fun pause(id: String) = manage { downloads.pause(id) }
    fun cancel(id: String) = manage { downloads.cancel(id) }
    private fun manage(operation: () -> Unit) { viewModelScope.launch(Dispatchers.IO) {
        try { operation(); operationError.value = null }
        catch (_: Exception) { operationError.value = com.framer.sense.feature.camera.vlm.R.string.vlm_model_operation_failed }
    } }
}
