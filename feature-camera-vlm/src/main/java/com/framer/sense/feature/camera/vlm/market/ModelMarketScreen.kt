package com.framer.sense.feature.camera.vlm.market

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.ModelMode
import com.framer.sense.feature.camera.vlm.ui.downloadPhaseLabel
import com.framer.sense.feature.camera.vlm.ui.CameraAdaptiveDialog
import com.framer.sense.feature.camera.vlm.ui.CameraAdaptiveAlertDialog
import com.framer.sense.feature.camera.vlm.ui.CameraAdaptiveBottomSheet

@Composable
internal fun ModelMarketDialog(onBack: () -> Unit, onApply: (String) -> Unit, onDelete: (String) -> Unit,
    modelBusy: Boolean, savedMode: ModelMode, cameraError: String? = null, viewModel: ModelMarketViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val failure = stringResource(R.string.vlm_download_service_failed)
    var serviceError by remember { mutableStateOf<String?>(null) }
    var requested by remember { mutableStateOf<Triple<String, Boolean, MarketSource>?>(null) }
    val start: (Triple<String, Boolean, MarketSource>) -> Unit = { (id, metered, source) ->
        try {
            val repository = state.allModels.first { it.id == id }.repository(source) ?: error("MODEL_SOURCE_UNAVAILABLE")
            ModelDownloadService.startMarket(context, id, source, metered, repository); serviceError = null
        }
        catch (_: Exception) { serviceError = failure }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requested?.let(start); requested = null
    }
    CameraAdaptiveDialog(onDismissRequest = onBack, fullScreen = true) {
        Surface(Modifier.fillMaxSize()) {
            ModelMarketContent(state, modelBusy, savedMode, onBack, viewModel::setFilter, viewModel::setSource,
                viewModel::refresh, { id, metered ->
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requested = Triple(id, metered, state.source); notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else start(Triple(id, metered, state.source))
                }, viewModel::pause, viewModel::cancel, onApply, { id ->
                    onDelete(id)
                }, serviceError ?: cameraError, { ModelDownloadNetwork.requiresConfirmation(context) })
        }
    }
}

/** 官方列表、筛选和模型卡片交互的 Compose 实现；不引入聊天或语音模块。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModelMarketContent(
    state: MarketUiState, modelBusy: Boolean, savedMode: ModelMode, onBack: () -> Unit,
    onFilter: (MarketFilter) -> Unit, onSource: (MarketSource) -> Unit, onRefresh: () -> Unit,
    onDownload: (String, Boolean) -> Unit, onPause: (String) -> Unit, onCancel: (String) -> Unit,
    onApply: (String) -> Unit, onDelete: (String) -> Unit, serviceError: String? = null,
    requiresMobileConfirmation: () -> Boolean = { false }
) {
    var filterVisible by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MarketModel?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var meteredModel by remember { mutableStateOf<String?>(null) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val requestDownload: (String) -> Unit = { id ->
        if (requiresMobileConfirmation()) meteredModel = id else onDownload(id, false)
    }
    val applyFilter: (MarketFilter) -> Unit = { onFilter(it.copy(installedOnly = false)) }
    Scaffold(Modifier.fillMaxSize().testTag("vlm_model_market"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.vlm_market_title)) }, navigationIcon = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.vlm_market_back)) }
        }, actions = { TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.vlm_market_refresh)) } })
    }) { padding ->
        // 搜索与筛选也参与滚动，横屏或键盘出现时不会挤掉整个模型列表。
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("vlm_market_list"),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "sources") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MarketSource.entries.forEach { source ->
                        FilterChip(state.source == source, { onSource(source) }, label = { Text(sourceLabel(source)) },
                            modifier = Modifier.weight(1f).testTag("vlm_market_source_${source.name}"))
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(state.filter.search, { applyFilter(state.filter.copy(search = it)) }, singleLine = true,
                    label = { Text(stringResource(R.string.vlm_market_search)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("vlm_market_search"))
            }
            item(key = "filters") {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = state.filter.hasOptions, onClick = { filterVisible = true },
                        label = { Text(stringResource(R.string.vlm_market_filter)) }, modifier = Modifier.testTag("vlm_market_filter_open")) }
                    item { FilterChip(state.filter.installedOnly, { onFilter(MarketFilter(installedOnly = !state.filter.installedOnly)) },
                        label = { Text(stringResource(R.string.vlm_market_installed)) }, modifier = Modifier.testTag("vlm_market_installed")) }
                    items(listOf("Vision", "Video", "Think")) { tag ->
                        FilterChip(tag in state.filter.tags, { applyFilter(state.filter.copy(tags =
                            if (tag in state.filter.tags) state.filter.tags - tag else state.filter.tags + tag)) }, label = { Text(tagLabel(tag)) },
                            modifier = Modifier.testTag("vlm_market_tag_$tag"))
                    }
                }
            }
            if (state.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item(key = "error") { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }
            serviceError?.let { item(key = "service_error") { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }
            if (state.models.isEmpty() && !state.loading) {
                item(key = "empty") { Text(stringResource(R.string.vlm_market_empty), modifier = Modifier.padding(16.dp)) }
                item(key = "clear") { TextButton(onClick = { onFilter(MarketFilter()) }) { Text(stringResource(R.string.vlm_market_clear_filter)) } }
            }
            items(state.models, key = { it.id }) { model ->
                val installed = state.installed.any { it.id == model.id }
                val task = state.downloads[model.id]
                val phase = state.modelPhase(model.id)
                ElevatedCard(onClick = { detail = model },
                    colors = if (installed) CardDefaults.elevatedCardColors(
                        containerColor = colorResource(R.color.vlm_market_downloaded_background),
                        contentColor = colorResource(R.color.vlm_market_downloaded_content)) else CardDefaults.elevatedCardColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                    .testTag("vlm_market_model_${model.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (model.local) stringResource(R.string.vlm_market_local_title, model.id.takeLast(8)) else model.id, style = MaterialTheme.typography.titleSmall)
                        if (!model.local) Text(stringResource(R.string.vlm_market_model_meta, model.vendor, model.parameters.toString(), model.bytes / 1048576),
                            style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(compatibilityLabel(model)), style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(model.tags) { tag -> Text(tagText(tag), style = MaterialTheme.typography.labelSmall) } }
                        if (state.activeModel == model.id) Text(stringResource(R.string.vlm_market_current), color = MaterialTheme.colorScheme.primary)
                        if (phase != DownloadPhase.IDLE) {
                            Text(stringResource(downloadPhaseLabel(phase)), modifier = Modifier.testTag("vlm_market_status_${model.id}"))
                        }
                        if (task != null && task.phase != DownloadPhase.IDLE) {
                            if (task.total > 0) {
                                LinearProgressIndicator(progress = { (task.downloaded.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.vlm_download_progress, task.downloaded / 1048576, task.total / 1048576))
                            }
                            if (task.speedBytesPerSecond > 0 && task.running) Text(stringResource(R.string.vlm_market_speed, task.speedBytesPerSecond / 1024))
                            task.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!installed || task?.phase in setOf(DownloadPhase.RESOLVING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING,
                                    DownloadPhase.QUEUED, DownloadPhase.PAUSED, DownloadPhase.FAILED)) item {
                                TextButton(onClick = { requestDownload(model.id) },
                                    modifier = Modifier.testTag("vlm_market_download_${model.id}"),
                                    enabled = model.compatible && model.repository(state.source) != null &&
                                        (task == null || task.phase in setOf(DownloadPhase.IDLE, DownloadPhase.PAUSED, DownloadPhase.FAILED))) {
                                    Text(stringResource(if (task?.phase in setOf(DownloadPhase.PAUSED, DownloadPhase.FAILED)) R.string.vlm_download_resume else R.string.vlm_download_start))
                                }
                            }
                            if (task?.running == true || task?.phase == DownloadPhase.QUEUED) item {
                                TextButton(onClick = { onPause(model.id) }) { Text(stringResource(R.string.vlm_download_pause)) }
                            }
                            if (task != null && task.phase in setOf(DownloadPhase.RESOLVING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING,
                                    DownloadPhase.QUEUED, DownloadPhase.PAUSED, DownloadPhase.FAILED)) item {
                                TextButton(onClick = { onCancel(model.id) }) { Text(stringResource(R.string.vlm_download_cancel)) }
                            }
                            if (installed) {
                                item { TextButton(onClick = { onApply(model.id) }, enabled = model.compatible && !modelBusy && savedMode != ModelMode.CLOUD) {
                                    Text(stringResource(R.string.vlm_market_use)) } }
                                item { TextButton(onClick = { deleteId = model.id }, enabled = !modelBusy && task?.running != true) {
                                    Text(stringResource(R.string.vlm_model_delete)) } }
                            }
                            item { TextButton(onClick = { detail = model }) { Text(stringResource(R.string.vlm_market_details)) } }
                        }
                    }
                }
            }
        }
    }
    if (filterVisible) CameraAdaptiveBottomSheet(
        onDismissRequest = { filterVisible = false },
        modifier = Modifier.testTag("vlm_market_filter_sheet")
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.vlm_market_filter), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.vlm_market_vendor), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(state.filter.vendor == null, { applyFilter(state.filter.copy(vendor = null)) },
                        label = { Text(stringResource(R.string.vlm_market_all)) })
                    MarketCatalog.vendors(state.allModels).forEach { vendor ->
                        FilterChip(state.filter.vendor?.equals(vendor, true) == true,
                            { applyFilter(state.filter.copy(vendor = vendor)) }, label = { Text(vendorLabel(vendor)) },
                            modifier = Modifier.testTag("vlm_market_vendor_${vendor.lowercase(java.util.Locale.ROOT)}"))
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.vlm_market_size), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..3).forEach { size -> FilterChip(state.filter.size == size,
                        { applyFilter(state.filter.copy(size = if (state.filter.size == size) null else size)) },
                        label = { Text(stringResource(sizeLabel(size))) }) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.vlm_market_download_state), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(DownloadPhase.IDLE, DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED,
                        DownloadPhase.DOWNLOADED, DownloadPhase.FAILED).forEach { phase ->
                        FilterChip(state.filter.downloadPhase == phase,
                            { applyFilter(state.filter.copy(downloadPhase = if (state.filter.downloadPhase == phase) null else phase)) },
                            label = { Text(stringResource(downloadPhaseLabel(phase))) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)) {
                TextButton(onClick = { onFilter(MarketFilter()) }) { Text(stringResource(R.string.vlm_market_clear_filter)) }
                Button(onClick = { filterVisible = false }) { Text(stringResource(R.string.vlm_market_done)) }
            }
        }
    }
    detail?.let { model -> CameraAdaptiveAlertDialog(onDismissRequest = { detail = null }, title = { Text(if (model.local) stringResource(R.string.vlm_market_local_title, model.id.takeLast(8)) else model.id) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(compatibilityLabel(model)))
            if (model.description.isNotBlank()) Text(model.description)
            MarketSource.entries.forEach { source -> model.repository(source)?.let { repository ->
                Text(stringResource(R.string.vlm_market_repository, sourceLabel(source), repository))
                TextButton(onClick = { uriHandler.openUri(source.baseUrl + if (source == MarketSource.MODELSCOPE) "/models/$repository" else "/$repository") }) {
                    Text(stringResource(R.string.vlm_market_open_repository, sourceLabel(source)))
                }
            } }
            Text(stringResource(if (model.local) R.string.vlm_market_local_hint else R.string.vlm_market_asset_notice))
        }
    }, confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.vlm_market_done)) } }) }
    deleteId?.let { id -> CameraAdaptiveAlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.vlm_model_delete)) },
        text = { Text(stringResource(R.string.vlm_market_delete_confirm, id)) },
        confirmButton = { TextButton(onClick = { onDelete(id); deleteId = null }) { Text(stringResource(R.string.vlm_model_delete)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.vlm_cancel)) } }) }
    if (meteredModel != null) CameraAdaptiveAlertDialog(onDismissRequest = { meteredModel = null },
        modifier = Modifier.testTag("vlm_market_mobile_confirmation"),
        title = { Text(stringResource(R.string.vlm_market_mobile_title)) },
        text = { Text(stringResource(R.string.vlm_market_mobile_body)) },
        confirmButton = { TextButton(onClick = {
            meteredModel?.let { onDownload(it, true) }
            meteredModel = null
        }) { Text(stringResource(R.string.vlm_market_mobile_confirm)) } },
        dismissButton = { TextButton(onClick = { meteredModel = null }) { Text(stringResource(R.string.vlm_download_not_now)) } })
}

@Composable private fun sourceLabel(source: MarketSource) = stringResource(if (source == MarketSource.MODELSCOPE) R.string.vlm_market_source_cn else R.string.vlm_market_source_global)
@Composable private fun vendorLabel(vendor: String) = when (vendor.lowercase(java.util.Locale.ROOT)) {
    "qwen" -> stringResource(R.string.vlm_market_vendor_qwen)
    "deepseek" -> stringResource(R.string.vlm_market_vendor_deepseek)
    "gemma" -> stringResource(R.string.vlm_market_vendor_gemma)
    "gpt" -> stringResource(R.string.vlm_market_vendor_gpt)
    else -> vendor
}
@Composable private fun tagLabel(tag: String) = tagText(tag)
@Composable private fun tagText(tag: String): String = when (tag) {
    "Vision" -> stringResource(R.string.vlm_market_tag_vision)
    "Video" -> stringResource(R.string.vlm_market_tag_video)
    "Think" -> stringResource(R.string.vlm_market_tag_think)
    "Audio" -> stringResource(R.string.vlm_market_tag_audio)
    else -> tag
}
private fun compatibilityLabel(model: MarketModel) = when {
    model.local -> R.string.vlm_market_imported
    model.verified -> R.string.vlm_market_verified
    model.bytes > ModelFiles.MAX_BYTES -> R.string.vlm_market_size_unsupported
    model.compatible -> R.string.vlm_market_experimental
    else -> R.string.vlm_market_unsupported
}
private fun sizeLabel(size: Int) = when (size) {
    0 -> R.string.vlm_market_size_small
    1 -> R.string.vlm_market_size_medium
    2 -> R.string.vlm_market_size_large
    else -> R.string.vlm_market_size_huge
}
