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

import org.junit.Assert.assertEquals
import org.junit.Test

class MyModelMainViewModelTest {

    @Test
    fun uiState_defaultsToProfileAndWorksTab() {
        val viewModel = MyModelMainViewModel()

        assertEquals("用户名称", viewModel.uiState.value.profile.userName)
        assertEquals("这是一段个人简介，记录生活中的美好瞬间", viewModel.uiState.value.profile.bio)
        assertEquals(MyModelTab.WORKS, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun onTabSelected_updatesSelectedTab() {
        val viewModel = MyModelMainViewModel()

        viewModel.onTabSelected(MyModelTab.FAVORITES)

        assertEquals(MyModelTab.FAVORITES, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun onPageChanged_updatesSelectedTab() {
        val viewModel = MyModelMainViewModel()

        viewModel.onPageChanged(MyModelTab.COMMENTS.ordinal)

        assertEquals(MyModelTab.COMMENTS, viewModel.uiState.value.selectedTab)
    }
}
