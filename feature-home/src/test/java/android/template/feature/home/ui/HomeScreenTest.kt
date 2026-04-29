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

package android.template.feature.home.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_displaysAlbumTabByDefault() {
        composeTestRule.setContent {
            HomeScreen()
        }
        // 默认选中相册 Tab，应显示相册 Tab 文字
        composeTestRule.onNodeWithText("相册").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysRecommendTab() {
        composeTestRule.setContent {
            HomeScreen()
        }
        // 推荐 Tab 应始终可见
        composeTestRule.onNodeWithText("推荐").assertIsDisplayed()
    }

    @Test
    fun homeScreen_clickRecommendTab_switchesToRecommend() {
        composeTestRule.setContent {
            HomeScreen()
        }
        // 点击推荐 Tab
        composeTestRule.onNodeWithText("推荐").performClick()
        // 推荐内容区域应该显示（假数据中的标题）
        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()
    }

    @Test
    fun homeScreen_clickAlbumTab_staysOnAlbum() {
        composeTestRule.setContent {
            HomeScreen()
        }
        // 点击推荐后再点回相册
        composeTestRule.onNodeWithText("推荐").performClick()
        composeTestRule.onNodeWithText("相册").performClick()
        // 相册 Tab 仍然显示
        composeTestRule.onNodeWithText("相册").assertIsDisplayed()
    }

    @Test
    fun recommendScreen_showsMultipleCards() {
        composeTestRule.setContent {
            HomeScreen()
        }
        // 切换到推荐页
        composeTestRule.onNodeWithText("推荐").performClick()
        // 验证多个推荐卡片标题存在
        composeTestRule.onNodeWithText("周末探店 | 这家咖啡厅太好拍了").assertIsDisplayed()
        composeTestRule.onNodeWithText("旅行Vlog | 云南大理的浪漫").assertIsDisplayed()
    }
}
