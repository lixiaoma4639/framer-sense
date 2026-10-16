package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.framer.sense.core.ui.MyApplicationTheme
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.data.DownloadPhase
import com.framer.sense.feature.camera.vlm.data.ModelDownloadState

/** 展示离线模型下载和备用导入入口。
 * @param state MVI 状态。@param onEvent 用户事件接收器。
 * @param importDirectory 官方文件夹选择动作。@param importZip 旧 ZIP 选择动作。
 */
@Composable
internal fun OfflineModelPanel(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importDirectory: () -> Unit, importZip: () -> Unit, openMarket: () -> Unit = {}) {
    val download = state.download
    val busy = state.modelBusy || download.running
    TextButton(onClick = openMarket, modifier = Modifier.fillMaxWidth().testTag("vlm_open_model_market")) {
        Text(stringResource(R.string.vlm_market_title))
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.vlm_market_active_model_label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onEvent(VlmIntent.LoadModel) }, enabled = !busy,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(stringResource(R.string.vlm_model_load)) }
        TextButton(onClick = { onEvent(VlmIntent.UnloadModel) }, enabled = !busy,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(stringResource(R.string.vlm_model_unload)) }
    }
    state.localModelName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(state.modelStatus)
    if (download.total > 0 && download.running) {
        LinearProgressIndicator(progress = { (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.vlm_download_progress, download.downloaded / 1048576, download.total / 1048576))
    }
    download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    HorizontalDivider(Modifier.fillMaxWidth().testTag("vlm_download_local_divider"))
    Text(stringResource(R.string.vlm_model_directory_hint), style = MaterialTheme.typography.labelSmall)
    Row {
        TextButton(onClick = importDirectory, enabled = !busy) { Text(stringResource(R.string.vlm_model_import_directory)) }
        TextButton(onClick = importZip, enabled = !busy) { Text(stringResource(R.string.vlm_model_import_zip)) }
    }
}

/** 映射下载状态文案。@param phase 当前下载或加载阶段。@return 对应字符串资源编号。 */
internal fun downloadPhaseLabel(phase: DownloadPhase): Int = when (phase) {
    DownloadPhase.QUEUED -> R.string.vlm_market_queued
    DownloadPhase.IDLE -> R.string.vlm_download_idle
    DownloadPhase.RESOLVING -> R.string.vlm_download_resolving
    DownloadPhase.DOWNLOADING -> R.string.vlm_download_running
    DownloadPhase.VERIFYING -> R.string.vlm_download_verifying
    DownloadPhase.PAUSED -> R.string.vlm_download_paused
    DownloadPhase.CANCELLING -> R.string.vlm_download_cancelling
    DownloadPhase.DOWNLOADED -> R.string.vlm_download_finished
    DownloadPhase.LOADING -> R.string.vlm_model_loading
    DownloadPhase.READY -> R.string.vlm_model_loaded
    DownloadPhase.LOAD_FAILED -> R.string.vlm_model_load_failed
    DownloadPhase.FAILED -> R.string.vlm_download_failed
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun OfflineModelPanelPreview() {
    MyApplicationTheme(dynamicColor = false) {
        Surface {
            OfflineModelPanel(
                state = VlmUiState(
                    modelStatus = "正在下载离线模型",
                    download = ModelDownloadState(
                        phase = DownloadPhase.DOWNLOADING,
                        downloaded = 384L * 1048576L,
                        total = 1024L * 1048576L
                    )
                ),
                onEvent = {},
                importDirectory = {},
                importZip = {}
            )
        }
    }
}
