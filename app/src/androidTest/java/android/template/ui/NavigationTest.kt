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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 底部导航集成测试
 *
 * 验证：
 * - 默认显示首页模块
 * - 底部导航栏有3个Tab：首页/拍照/我的
 * - 点击导航按钮可以切换模块
 * - 切换后显示对应模块内容
 */
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

    // ========== 首页 Tab 测试 ==========

    @Test
    fun defaultScreen_showsHome() {
        // 默认选中首页 Tab，应显示首页内容
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
        composeTestRule.onNodeWithText("当前模块：Home").assertIsDisplayed()
    }

    @Test
    fun homeTab_isSelectedByDefault() {
        // 默认情况下首页 Tab 应处于选中状态
        composeTestRule.onNodeWithContentDescription("首页")
            .assertIsSelected()
    }

    // ========== 拍照 Tab 测试 ==========

    @Test
    fun clickCameraTab_showsCameraScreen() {
        // 点击拍照 Tab
        composeTestRule.onNodeWithContentDescription("拍照").performClick()

        // 应显示拍照模块内容
        composeTestRule.onNodeWithText("拍照").assertIsDisplayed()
        composeTestRule.onNodeWithText("当前模块：Camera").assertIsDisplayed()
    }

    @Test
    fun clickCameraTab_cameraTabBecomesSelected() {
        // 点击拍照 Tab 后，该 Tab 应被选中，首页不再被选中
        composeTestRule.onNodeWithContentDescription("拍照").performClick()

        composeTestRule.onNodeWithContentDescription("拍照")
            .assertIsSelected()
        composeTestRule.onNodeWithContentDescription("首页")
            .assertIsNotSelected()
    }

    @Test
    fun clickCameraTab_homeContentNoLongerVisible() {
        // 点击拍照后，首页内容不应再显示
        composeTestRule.onNodeWithContentDescription("拍照").performClick()

        composeTestRule.onNodeWithText("当前模块：Home").assertDoesNotExist()
    }

    // ========== 我的 Tab 测试 ==========

    @Test
    fun clickMyModelTab_showsMyModelScreen() {
        // 点击我的 Tab
        composeTestRule.onNodeWithContentDescription("我的").performClick()

        // 应显示我的模块内容
        composeTestRule.onNodeWithText("我的").assertIsDisplayed()
        composeTestRule.onNodeWithText("当前模块：MyModel").assertIsDisplayed()
    }

    @Test
    fun clickMyModelTab_myModelTabBecomesSelected() {
        composeTestRule.onNodeWithContentDescription("我的").performClick()

        composeTestRule.onNodeWithContentDescription("我的")
            .assertIsSelected()
        composeTestRule.onNodeWithContentDescription("首页")
            .assertIsNotSelected()
    }

    // ========== Tab 切换流程测试 ==========

    @Test
    fun switchBetweenAllTabs_displaysCorrectContentEachTime() {
        // 默认 -> 首页
        composeTestRule.onNodeWithText("当前模块：Home").assertIsDisplayed()

        // 首页 -> 拍照
        composeTestRule.onNodeWithContentDescription("拍照").performClick()
        composeTestRule.onNodeWithText("当前模块：Camera").assertIsDisplayed()

        // 拍照 -> 我的
        composeTestRule.onNodeWithContentDescription("我的").performClick()
        composeTestRule.onNodeWithText("当前模块：MyModel").assertIsDisplayed()

        // 我的 -> 回到首页
        composeTestRule.onNodeWithContentDescription("首页").performClick()
        composeTestRule.onNodeWithText("当前模块：Home").assertIsDisplayed()
    }

    @Test
    fun allThreeTabs_areAlwaysVisibleInNavigationBar() {
        // 底部导航栏始终显示3个Tab
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
        composeTestRule.onNodeWithText("拍照").assertIsDisplayed()
        composeTestRule.onNodeWithText("我的").assertIsDisplayed()
    }
}
