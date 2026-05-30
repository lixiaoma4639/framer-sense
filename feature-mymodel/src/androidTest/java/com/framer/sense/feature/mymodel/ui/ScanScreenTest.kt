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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.framer.sense.feature.mymodel.ui.scan.ScanScreenContent
import com.framer.sense.feature.mymodel.ui.scan.ScanUiState
import org.junit.Rule
import org.junit.Test

class ScanScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun scanScreen_displaysTitleBackButtonAndDescription() {
        composeTestRule.setContent {
            ScanScreenContent(uiState = ScanUiState())
        }

        composeTestRule.onNodeWithText("扫一扫").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        composeTestRule.onNodeWithText("这里是扫码功能入口，后续可接入二维码识别、相册扫码或扫码结果处理。")
            .assertIsDisplayed()
    }
}
