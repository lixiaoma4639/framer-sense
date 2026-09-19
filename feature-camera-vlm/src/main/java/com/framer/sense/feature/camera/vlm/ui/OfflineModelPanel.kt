package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
internal fun OfflineModelPanel(state: VlmUiState, onEvent: (VlmIntent) -> Unit, importDirectory: () -> Unit, importZip: () -> Unit) {
    val download = state.download
    var source by rememberSaveable(download.source) { mutableStateOf(download.source) }
    var metered by rememberSaveable(download.allowMetered) { mutableStateOf(download.allowMetered) }
    var confirmMetered by remember { mutableStateOf(false) }
    val busy = state.modelBusy || download.running
    Text(stringResource(R.string.vlm_download_title), style = MaterialTheme.typography.titleMedium)
    Text(state.modelStatus)
    Text(stringResource(downloadPhaseLabel(download.phase)))
    OutlinedTextField(source, { source = it }, enabled = download.phase == DownloadPhase.IDLE && !state.modelBusy,
        label = { Text(stringResource(R.string.vlm_download_source)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row {
        Checkbox(metered, { checked -> if (checked) confirmMetered = true else metered = false }, enabled = !busy)
        Text(stringResource(R.string.vlm_download_metered), modifier = Modifier.padding(top = 12.dp))
    }
    if (download.total > 0) {
        LinearProgressIndicator(progress = { (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.vlm_download_progress, download.downloaded / 1048576, download.total / 1048576))
    } else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Row {
        val canStart = download.phase in setOf(DownloadPhase.IDLE, DownloadPhase.PAUSED, DownloadPhase.FAILED)
        TextButton(onClick = { onEvent(VlmIntent.StartModelDownload(source, metered)) }, enabled = canStart && !busy) {
            Text(stringResource(if (download.phase == DownloadPhase.IDLE) R.string.vlm_download_start else R.string.vlm_download_resume))
        }
        TextButton(onClick = { onEvent(VlmIntent.PauseModelDownload) }, enabled = download.running && download.phase != DownloadPhase.CANCELLING) {
            Text(stringResource(R.string.vlm_download_pause))
        }
        TextButton(onClick = { onEvent(VlmIntent.CancelModelDownload) }, enabled = !state.modelBusy && download.phase !in setOf(DownloadPhase.IDLE, DownloadPhase.READY, DownloadPhase.CANCELLING)) {
            Text(stringResource(R.string.vlm_download_cancel))
        }
    }
    download.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text(stringResource(R.string.vlm_model_directory_hint), style = MaterialTheme.typography.labelSmall)
    Row {
        TextButton(onClick = importDirectory, enabled = !busy) { Text(stringResource(R.string.vlm_model_import_directory)) }
        TextButton(onClick = importZip, enabled = !busy) { Text(stringResource(R.string.vlm_model_import_zip)) }
    }
    Row {
        TextButton(onClick = { onEvent(VlmIntent.LoadModel) }, enabled = !busy) { Text(stringResource(R.string.vlm_model_load)) }
        TextButton(onClick = { onEvent(VlmIntent.UnloadModel) }, enabled = !busy) { Text(stringResource(R.string.vlm_model_unload)) }
        TextButton(onClick = { onEvent(VlmIntent.DeleteModel) }, enabled = !busy) { Text(stringResource(R.string.vlm_model_delete)) }
    }
    if (confirmMetered) AlertDialog(onDismissRequest = { confirmMetered = false },
        title = { Text(stringResource(R.string.vlm_download_metered_title)) },
        text = { Text(stringResource(R.string.vlm_download_metered_body)) },
        confirmButton = { TextButton(onClick = { metered = true; confirmMetered = false }) { Text(stringResource(R.string.vlm_download_allow)) } },
        dismissButton = { TextButton(onClick = { confirmMetered = false }) { Text(stringResource(R.string.vlm_download_not_now)) } })
}

/** 映射下载状态文案。@param phase 当前下载或加载阶段。@return 对应字符串资源编号。 */
internal fun downloadPhaseLabel(phase: DownloadPhase): Int = when (phase) {
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
