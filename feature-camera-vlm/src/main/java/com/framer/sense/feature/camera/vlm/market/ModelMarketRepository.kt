package com.framer.sense.feature.camera.vlm.market

import android.content.Context
import android.util.AtomicFile
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.data.HubDownloadClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class MarketCatalogState(val models: List<MarketModel> = emptyList(), val loading: Boolean = false, val error: Int? = null)

/** 官方目录资产与缓存优先显示，刷新失败保留可浏览目录。 */
class ModelMarketRepository(private val context: Context, private val hub: HubDownloadClient = HubDownloadClient()) {
    private val prefs = context.getSharedPreferences("vlm-market", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.noBackupFilesDir, "vlm-market-catalog.json"))
    private val mutex = Mutex()
    private val _state = MutableStateFlow(MarketCatalogState())
    val state = _state.asStateFlow()
    fun source(): MarketSource = runCatching { MarketSource.valueOf(prefs.getString("source", null)!!) }
        .getOrElse { MarketSource.defaultFor(context.resources.configuration.locales[0].language) }
    fun saveSource(source: MarketSource) { prefs.edit().putString("source", source.name).apply() }

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bundled = context.assets.open("model-market/catalog.json").bufferedReader().use { it.readText() }
            var best = MarketCatalog.decode(bundled)
            if (cache.baseFile.exists() && cache.baseFile.length() <= 8 * 1024 * 1024) runCatching {
                MarketCatalog.decode(cache.openRead().bufferedReader().use { it.readText() })
            }.getOrNull()?.takeIf { it.first >= best.first }?.let { best = it }
            _state.value = MarketCatalogState(best.second, loading = true)
            try {
                val text = hub.metadata(MarketCatalog.REMOTE_URL)
                val remote = MarketCatalog.decode(text)
                if (remote.first >= best.first) {
                    val stream = cache.startWrite()
                    try { stream.write(text.toByteArray()); cache.finishWrite(stream) }
                    catch (error: Exception) { cache.failWrite(stream); throw error }
                    best = remote
                }
                _state.value = MarketCatalogState(best.second)
            } catch (cancel: CancellationException) { _state.value = MarketCatalogState(best.second); throw cancel }
            catch (_: Exception) { _state.value = MarketCatalogState(best.second, error = R.string.vlm_market_refresh_failed) }
        }
    }
}
