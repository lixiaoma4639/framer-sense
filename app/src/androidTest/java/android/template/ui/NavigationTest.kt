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
package android.template.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class NavigationTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun mainActivity_showsMainScreen() {
        // 验证主界面显示 Save 按钮
        composeTestRule.onNodeWithText("Save").assertExists().assertIsDisplayed()
    }

    @Test
    fun mainActivity_saveButtonIsEnabled() {
        // 验证 Save 按钮可点击
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun mainActivity_showsDefaultTextFieldValue() {
        // 验证 TextField 默认值为 "Compose"
        composeTestRule.onNodeWithText("Compose").assertExists()
    }

    @Test
    fun mainActivity_showsFakeDataItems() {
        // FakeDataModule 提供 ["One", "Two", "Three"]，验证初始数据加载
        composeTestRule.onNodeWithText("Saved item: One", substring = true).assertExists()
        composeTestRule.onNodeWithText("Saved item: Two", substring = true).assertExists()
        composeTestRule.onNodeWithText("Saved item: Three", substring = true).assertExists()
    }

    @Test
    fun mainActivity_saveNewItem_displaysInList() {
        // 输入新内容并点击 Save
        composeTestRule.onNodeWithText("Compose").performTextInput("NewTest")
        composeTestRule.onNodeWithText("Save").performClick()

        // 验证新保存的项出现在列表中
        composeTestRule.onNodeWithText("Saved item: NewTest", substring = true)
            .assertExists()
    }

    @Test
    fun mainActivity_clickSave_multipleTimesAddsMultipleItems() {
        // 连续点击 Save 两次，验证多条记录显示
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        // 列表中应包含默认 "Compose" 保存的项（至少出现两次）
        val savedItems = composeTestRule.onAllNodesWithText("Saved item:", substring = true)
        assert(savedItems.fetchSemanticsNodes().size >= 5) {
            "Expected at least 5 saved items (3 fake + 2 new), but found ${savedItems.fetchSemanticsNodes().size}"
        }
    }
}
