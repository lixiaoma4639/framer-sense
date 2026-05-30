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

package com.framer.sense.feature.home.ui.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendViewModelTest {

    @Test
    fun uiState_defaultsToSuccessWithRecommendItems() {
        val viewModel = RecommendViewModel()
        val state = viewModel.uiState.value

        assertTrue(state is RecommendUiState.Success)
        state as RecommendUiState.Success
        assertEquals("春日限定樱花拍摄攻略", state.items.first().title)
    }
}
