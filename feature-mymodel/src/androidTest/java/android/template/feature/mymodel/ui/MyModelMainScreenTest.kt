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

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MyModelMainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun myModelMainScreen_displaysProfile() {
        composeTestRule.setContent {
            MyModelMainContent(
                uiState = MyModelMainUiState(),
                onTabSelected = {},
                onPageChanged = {}
            )
        }

        composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()
        composeTestRule.onNodeWithText("这是一段个人简介，记录生活中的美好瞬间").assertIsDisplayed()
    }

    @Test
    fun myModelMainScreen_displaysActionsAndTabs() {
        composeTestRule.setContent {
            MyModelMainContent(
                uiState = MyModelMainUiState(),
                onTabSelected = {},
                onPageChanged = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("扫一扫").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("消息").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("设置").assertIsDisplayed()
        composeTestRule.onNodeWithText("作品").assertIsDisplayed()
        composeTestRule.onNodeWithText("点赞").assertIsDisplayed()
        composeTestRule.onNodeWithText("收藏").assertIsDisplayed()
        composeTestRule.onNodeWithText("评论").assertIsDisplayed()
    }
}
