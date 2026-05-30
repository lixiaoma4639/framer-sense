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

import com.framer.sense.feature.home.ui.recommend.RecommendScreenContent
import com.framer.sense.feature.home.ui.recommend.RecommendItem
import com.framer.sense.feature.home.ui.recommend.RecommendUiState
import androidx.compose.material3.Text
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
    fun homeScreen_displaysRecommendTabByDefault() {
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(),
                onTabSelected = {},
                onPageChanged = {},
                recommendContent = { RecommendScreenContent(RecommendUiState.Success(fakeRecommendItems)) },
                albumContent = { Text("相册内容") }
            )
        }

        composeTestRule.onNodeWithText("推荐").assertIsDisplayed()
        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysAlbumTab() {
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(),
                onTabSelected = {},
                onPageChanged = {},
                recommendContent = { RecommendScreenContent(RecommendUiState.Success(fakeRecommendItems)) },
                albumContent = { Text("相册内容") }
            )
        }

        composeTestRule.onNodeWithText("相册").assertIsDisplayed()
    }

    @Test
    fun homeScreen_clickSelectedRecommendTab_keepsRecommendContent() {
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(),
                onTabSelected = {},
                onPageChanged = {},
                recommendContent = { RecommendScreenContent(RecommendUiState.Success(fakeRecommendItems)) },
                albumContent = { Text("相册内容") }
            )
        }

        composeTestRule.onNodeWithText("推荐").performClick()
        composeTestRule.onNodeWithText("周末探店 | 这家咖啡厅太好拍了").assertIsDisplayed()
    }

    @Test
    fun recommendScreen_showsMultipleCards() {
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(),
                onTabSelected = {},
                onPageChanged = {},
                recommendContent = { RecommendScreenContent(RecommendUiState.Success(fakeRecommendItems)) },
                albumContent = { Text("相册内容") }
            )
        }

        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()
        composeTestRule.onNodeWithText("周末探店 | 这家咖啡厅太好拍了").assertIsDisplayed()
    }
}

private val fakeRecommendItems = listOf(
    RecommendItem(1, "春日限定樱花拍摄攻略", "https://example.com/1.jpg", "摄影小达人", 2847),
    RecommendItem(2, "周末探店 | 这家咖啡厅太好拍了", "https://example.com/2.jpg", "城市漫游者", 1523)
)
