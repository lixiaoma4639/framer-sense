/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.framer.sense.feature.mymodel.ui.message

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessageItem(
    val title: String,
    val summary: String,
    val time: String,
    val unread: Boolean
)

data class MessageListUiState(
    val messages: List<MessageItem> = defaultMessageItems()
)

@HiltViewModel
class MessageListViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MessageListUiState())
    val uiState: StateFlow<MessageListUiState> = _uiState.asStateFlow()
}

internal fun defaultMessageItems(): List<MessageItem> = listOf(
    MessageItem(
        title = "系统通知",
        summary = "欢迎使用 Framer Sense，开始记录你的灵感瞬间。",
        time = "刚刚",
        unread = true
    ),
    MessageItem(
        title = "互动提醒",
        summary = "你的作品收到了新的点赞，快去看看吧。",
        time = "09:42",
        unread = true
    ),
    MessageItem(
        title = "创作助手",
        summary = "今日构图练习已更新，试试新的拍摄角度。",
        time = "昨天",
        unread = false
    )
)
