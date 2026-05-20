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

import android.template.core.ui.MyApplicationTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 可视化演示测试：覆盖当前底部导航和“我的 -> 设置”流程。
 */
class VisualDemoTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun init() {
        composeTestRule.setContent {
            MyApplicationTheme {
                MainNavigation()
            }
        }
    }

    @Test
    fun visualDemo_fullNavigationJourney() {
        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()
        pause()

        composeTestRule.onNodeWithTag("bottom_tab_CAMERA").performClick()
        composeTestRule.onNodeWithText("当前模块：Camera").assertIsDisplayed()
        pause()

        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()
        composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()
        pause()

        composeTestRule.onNodeWithContentDescription("设置").performClick()
        composeTestRule.onNodeWithText("隐私条款").assertIsDisplayed()
        pause()

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("作品").assertIsDisplayed()
        pause()

        composeTestRule.onNodeWithTag("bottom_tab_HOME").performClick()
        composeTestRule.onNodeWithText("推荐").assertIsDisplayed()
    }

    @Test
    fun visualDemo_repeatedBottomTabClicksStayResponsive() {
        repeat(2) {
            composeTestRule.onNodeWithTag("bottom_tab_CAMERA").performClick()
            composeTestRule.onNodeWithText("当前模块：Camera").assertIsDisplayed()

            composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()
            composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()

            composeTestRule.onNodeWithTag("bottom_tab_HOME").performClick()
            composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()
        }
    }

    private fun pause() {
        Thread.sleep(DELAY_MS)
    }

    companion object {
        private const val DELAY_MS = 500L
    }
}
