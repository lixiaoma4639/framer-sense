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

package com.framer.sense.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationViewModelTest {

    @Test
    fun uiState_defaultsToHome() {
        val viewModel = MainNavigationViewModel()

        assertEquals(BottomNavTab.HOME, viewModel.uiState.value.selectedTab)
        assertTrue(viewModel.uiState.value.showBottomBar)
    }

    @Test
    fun onTabSelected_updatesSelectedTab() {
        val viewModel = MainNavigationViewModel()

        viewModel.onTabSelected(BottomNavTab.MY_MODEL)

        assertEquals(BottomNavTab.MY_MODEL, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun onTabSelected_keepsBottomBarState() {
        val viewModel = MainNavigationViewModel()

        viewModel.onTabSelected(BottomNavTab.CAMERA)

        assertEquals(BottomNavTab.CAMERA, viewModel.uiState.value.selectedTab)
        assertTrue(viewModel.uiState.value.showBottomBar)
    }
}
