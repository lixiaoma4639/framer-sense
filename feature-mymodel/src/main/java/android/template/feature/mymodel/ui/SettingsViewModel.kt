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

package android.template.feature.mymodel.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val items: List<SettingsItem> = defaultSettingsItems()
)

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun onItemClick(item: SettingsItem) = Unit
}

internal fun defaultSettingsItems(): List<SettingsItem> = listOf(
    SettingsItem(title = "隐私条款"),
    SettingsItem(title = "个人信息收集清单"),
    SettingsItem(title = "第三方信息共享清单"),
    SettingsItem(title = "开源软件声明"),
    SettingsItem(title = "关于"),
    SettingsItem(title = "用户协议"),
    SettingsItem(title = "应用权限")
)
