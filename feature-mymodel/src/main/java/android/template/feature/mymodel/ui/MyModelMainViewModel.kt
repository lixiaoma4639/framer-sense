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
import kotlinx.coroutines.flow.update

data class ProfileUiState(
    val userName: String = "用户名称",
    val bio: String = "这是一段个人简介，记录生活中的美好瞬间"
)

data class MyModelTabContent(
    val tab: MyModelTab,
    val emptyText: String
)

data class MyModelMainUiState(
    val profile: ProfileUiState = ProfileUiState(),
    val selectedTab: MyModelTab = MyModelTab.WORKS,
    val tabContents: List<MyModelTabContent> = defaultMyModelTabContents()
) {
    fun emptyTextFor(tab: MyModelTab): String =
        tabContents.firstOrNull { it.tab == tab }?.emptyText.orEmpty()
}

@HiltViewModel
class MyModelMainViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MyModelMainUiState())
    val uiState: StateFlow<MyModelMainUiState> = _uiState.asStateFlow()

    fun onTabSelected(tab: MyModelTab) {
        _uiState.update { state ->
            if (state.selectedTab == tab) state else state.copy(selectedTab = tab)
        }
    }

    fun onPageChanged(page: Int) {
        MyModelTab.entries.getOrNull(page)?.let(::onTabSelected)
    }
}

internal fun defaultMyModelTabContents(): List<MyModelTabContent> = listOf(
    MyModelTabContent(MyModelTab.WORKS, "暂无作品"),
    MyModelTabContent(MyModelTab.LIKES, "暂无点赞"),
    MyModelTabContent(MyModelTab.FAVORITES, "暂无收藏"),
    MyModelTabContent(MyModelTab.COMMENTS, "暂无评论")
)
