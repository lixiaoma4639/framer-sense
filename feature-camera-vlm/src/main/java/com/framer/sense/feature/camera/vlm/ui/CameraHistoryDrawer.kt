package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.framer.sense.core.data.CameraHistoryEntry
import com.framer.sense.feature.camera.vlm.R
import kotlinx.coroutines.flow.drop
import java.io.File

@Composable
internal fun CameraHistoryDrawer(state: VlmUiState, onEvent: (VlmIntent) -> Unit, content: @Composable () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val latestEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(state.historyDrawerVisible) {
        if (state.historyDrawerVisible) { focus.clearFocus(); keyboard?.hide(); drawer.open() } else drawer.close()
    }
    LaunchedEffect(drawer) {
        snapshotFlow { drawer.currentValue }.drop(1).collect { latestEvent(VlmIntent.HistoryDrawerVisible(it == DrawerValue.Open)) }
    }
    ModalNavigationDrawer(drawerState = drawer,
        gesturesEnabled = state.historyDrawerVisible || drawer.currentValue == DrawerValue.Open,
        scrimColor = Color.Black.copy(alpha = .55f), drawerContent = {
            // Sheet 背景铺满窗口高度，系统栏安全区仅作用于内部内容。
            ModalDrawerSheet(Modifier.fillMaxWidth(.75f).fillMaxHeight().testTag("vlm_history_drawer"),
                drawerContainerColor = Color(0xFF171717), drawerContentColor = Color.White) {
                // Sheet 已处理顶部系统安全区，与相机顶部栏一样紧接安全区放置 48dp 行。
                Row(Modifier.fillMaxWidth().height(CameraHeaderHeight).padding(start = 16.dp)
                    .testTag("vlm_history_header"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vlm_history_title), style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("vlm_history_title"))
                    CameraSettingsButtons(state, onEvent)
                }
                if (state.historySaving || state.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White)
                if (state.historySaveFailed) TextButton(onClick = { onEvent(VlmIntent.RetryHistorySave) },
                    enabled = !state.historySwitchBlocked, modifier = Modifier.testTag("vlm_history_save_retry")) {
                    Text(stringResource(R.string.vlm_history_retry_save), color = Color.White)
                }
                val pinned = state.historyEntries.filter { it.pinnedAt != null }
                val recent = state.historyEntries.filter { it.pinnedAt == null }
                LazyColumn(Modifier.fillMaxSize().testTag("vlm_history_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (state.historyEntries.isEmpty()) item {
                        Text(stringResource(R.string.vlm_history_empty), color = Color.White.copy(alpha = .6f),
                            modifier = Modifier.padding(16.dp).testTag("vlm_history_empty"))
                    }
                    if (pinned.isNotEmpty()) {
                        item(key = "section_pinned") { HistorySection(R.string.vlm_history_pinned) }
                        items(pinned, key = { "entry_${it.id}" }) { HistoryRow(it, state, onEvent) }
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "section_recent") { HistorySection(R.string.vlm_history_recent) }
                        items(recent, key = { "entry_${it.id}" }) { HistoryRow(it, state, onEvent) }
                    }
                }
            }
        }, content = content)
    state.historyDeleteId?.let {
        AlertDialog(onDismissRequest = { onEvent(VlmIntent.DeleteHistoryRequested(null)) },
            title = { Text(stringResource(R.string.vlm_history_delete_title)) },
            text = { Text(stringResource(R.string.vlm_history_delete_message)) },
            confirmButton = { TextButton(onClick = { onEvent(VlmIntent.ConfirmDeleteHistory) }, enabled = !state.historySwitchBlocked,
                modifier = Modifier.testTag("vlm_history_delete_confirm")) { Text(stringResource(R.string.vlm_history_delete)) } },
            dismissButton = { TextButton(onClick = { onEvent(VlmIntent.DeleteHistoryRequested(null)) }) {
                Text(stringResource(R.string.vlm_history_cancel)) } })
    }
}

@Composable
private fun HistorySection(label: Int) {
    Text(stringResource(label), color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
private fun HistoryRow(entry: CameraHistoryEntry, state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val blocked = stringResource(R.string.vlm_history_busy)
    val selected = state.generated.historyId == entry.id
    Box(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = .08f) else Color.Transparent)
            .combinedClickable(onClick = {
                if (state.historySwitchBlocked) onEvent(VlmIntent.UserMessage(blocked)) else onEvent(VlmIntent.RestoreHistory(entry.id))
            }, onLongClick = { menu = true })
            .semantics { this.selected = selected }.padding(10.dp).testTag("vlm_history_item_${entry.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(File(entry.thumbnail), stringResource(R.string.vlm_selected_composition),
                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .08f)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.modelName, color = Color.White.copy(alpha = .55f), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(if (entry.pinnedAt == null) R.string.vlm_history_pin else R.string.vlm_history_unpin)) },
                onClick = { menu = false; onEvent(VlmIntent.PinHistory(entry.id, entry.pinnedAt == null)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.vlm_history_delete)) }, enabled = !state.historySwitchBlocked,
                onClick = { menu = false; onEvent(VlmIntent.DeleteHistoryRequested(entry.id)) })
        }
    }
}
