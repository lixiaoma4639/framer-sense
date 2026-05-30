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

package com.framer.sense.feature.home.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelTest {

    @Test
    fun uiState_defaultsToRecommendTab() {
        val viewModel = HomeViewModel()

        assertEquals(HomeTab.RECOMMEND, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun onTabSelected_updatesSelectedTab() {
        val viewModel = HomeViewModel()

        viewModel.onTabSelected(HomeTab.ALBUM)

        assertEquals(HomeTab.ALBUM, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun onPageChanged_updatesSelectedTab() {
        val viewModel = HomeViewModel()

        viewModel.onPageChanged(HomeTab.ALBUM.ordinal)

        assertEquals(HomeTab.ALBUM, viewModel.uiState.value.selectedTab)
    }
}
