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

package com.framer.sense.feature.mymodel.ui

import com.framer.sense.feature.mymodel.ui.message.MessageListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListViewModelTest {

    @Test
    fun uiState_containsDefaultMessages() {
        val viewModel = MessageListViewModel()
        val messages = viewModel.uiState.value.messages

        assertTrue(messages.isNotEmpty())
        assertEquals("系统通知", messages.first().title)
        assertTrue(messages.any { it.unread })
    }
}
