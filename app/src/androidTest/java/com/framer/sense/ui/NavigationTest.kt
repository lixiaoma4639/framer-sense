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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framer.sense.core.ui.MyApplicationTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 底部导航集成测试
 *
 * 验证：
 * - 默认显示首页模块
 * - 底部导航栏有3个Tab：首页/拍照/我的
 * - 点击导航按钮可以切换模块
 * - 切换后显示对应模块内容
 */
class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun init() {
        composeTestRule.setContent {
            MyApplicationTheme {
                var uiState by mutableStateOf(MainNavigationUiState())
                MainNavigationContent(
                    uiState = uiState,
                    onTabSelected = { tab ->
                        uiState = uiState.copy(selectedTab = tab)
                    },
                    homeContent = {
                        Column {
                            Text("推荐")
                            Text("春日限定樱花拍摄攻略")
                        }
                    },
                    cameraContent = {
                        Text("AI 构图引导")
                    },
                    myModelContent = { onSettingsClick, onMessagesClick, onScanClick ->
                        Column {
                            Text("我的")
                            Text("用户名称")
                            Text(
                                text = "扫一扫",
                                modifier = Modifier
                                    .semantics { contentDescription = "扫一扫" }
                                    .clickable(onClick = onScanClick)
                            )
                            Text(
                                text = "消息",
                                modifier = Modifier
                                    .semantics { contentDescription = "消息" }
                                    .clickable(onClick = onMessagesClick)
                            )
                            Text(
                                text = "设置",
                                modifier = Modifier
                                    .semantics { contentDescription = "设置" }
                                    .clickable(onClick = onSettingsClick)
                            )
                        }
                    },
                    settingsContent = { onBackClick ->
                        Column {
                            Text("设置")
                            Text("隐私条款")
                            Text(
                                text = "返回",
                                modifier = Modifier
                                    .semantics { contentDescription = "返回" }
                                    .clickable(onClick = onBackClick)
                            )
                        }
                    },
                    messageListContent = { onBackClick ->
                        Column {
                            Text("消息")
                            Text("系统通知")
                            Text(
                                text = "返回",
                                modifier = Modifier
                                    .semantics { contentDescription = "返回" }
                                    .clickable(onClick = onBackClick)
                            )
                        }
                    },
                    scanContent = { onBackClick ->
                        Column {
                            Text("扫一扫")
                            Text("扫码功能入口")
                            Text(
                                text = "返回",
                                modifier = Modifier
                                    .semantics { contentDescription = "返回" }
                                    .clickable(onClick = onBackClick)
                            )
                        }
                    }
                )
            }
        }
    }

    // ========== 首页 Tab 测试 ==========

    @Test
    fun defaultScreen_showsHome() {
        // 默认选中首页 Tab，应显示首页内容
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
        composeTestRule.onNodeWithText("推荐").assertIsDisplayed()
        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()
    }

    @Test
    fun homeTab_isSelectedByDefault() {
        // 默认情况下首页 Tab 应处于选中状态
        composeTestRule.onNodeWithTag("bottom_tab_HOME")
            .assertIsSelected()
    }

    // ========== 拍照 Tab 测试 ==========

    @Test
    fun clickCameraTab_showsCameraScreen() {
        // 点击拍照 Tab
        composeTestRule.onNodeWithTag("bottom_tab_CAMERA").performClick()

        // 应显示拍照模块内容
        composeTestRule.onNodeWithText("AI 构图引导").assertIsDisplayed()
    }

    @Test
    fun defaultCameraTabContent_usesCameraV2Screen() {
        composeTestRule.setContent {
            MyApplicationTheme {
                MainNavigationContent(
                    uiState = MainNavigationUiState(selectedTab = BottomNavTab.CAMERA),
                    onTabSelected = {},
                    homeContent = { Text("推荐") },
                    myModelContent = { _, _, _ -> Text("用户名称") }
                )
            }
        }

        composeTestRule.onNodeWithText("AI 3D 构图引导 V2").assertIsDisplayed()
    }

    @Test
    fun clickCameraTab_cameraTabBecomesSelected() {
        // 点击拍照 Tab 后，该 Tab 应被选中，首页不再被选中
        composeTestRule.onNodeWithTag("bottom_tab_CAMERA").performClick()

        composeTestRule.onNodeWithTag("bottom_tab_CAMERA")
            .assertIsSelected()
        composeTestRule.onNodeWithTag("bottom_tab_HOME")
            .assertIsNotSelected()
    }

    @Test
    fun clickCameraTab_homeContentNoLongerVisible() {
        // 点击拍照后，首页内容不应再显示
        composeTestRule.onNodeWithTag("bottom_tab_CAMERA").performClick()

        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertDoesNotExist()
    }

    // ========== 我的 Tab 测试 ==========

    @Test
    fun clickMyModelTab_showsProfileScreen() {
        // 点击我的 Tab
        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()

        // 应显示我的模块内容
        composeTestRule.onNodeWithText("我的").assertIsDisplayed()
        composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()
    }

    @Test
    fun clickMyModelTab_myModelTabBecomesSelected() {
        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()

        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL")
            .assertIsSelected()
        composeTestRule.onNodeWithTag("bottom_tab_HOME")
            .assertIsNotSelected()
    }

    @Test
    fun clickSettings_showsFullScreenPageAndBackReturnsProfileScreen() {
        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()
        composeTestRule.onNodeWithContentDescription("设置").performClick()

        composeTestRule.onNodeWithText("隐私条款").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
    }

    @Test
    fun clickScan_showsFullScreenPageAndBackReturnsProfileScreen() {
        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()
        composeTestRule.onNodeWithContentDescription("扫一扫").performClick()

        composeTestRule.onNodeWithText("扫码功能入口").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
    }

    @Test
    fun clickMessages_showsFullScreenPageAndBackReturnsProfileScreen() {
        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()
        composeTestRule.onNodeWithContentDescription("消息").performClick()

        composeTestRule.onNodeWithText("系统通知").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("返回").performClick()
        composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
    }

    // ========== Tab 切换流程测试 ==========

    @Test
    fun switchBetweenAllTabs_displaysCorrectContentEachTime() {
        // 默认 -> 首页
        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()

        // 首页 -> 拍照
        composeTestRule.onNodeWithTag("bottom_tab_CAMERA").performClick()
        composeTestRule.onNodeWithText("AI 构图引导").assertIsDisplayed()

        // 拍照 -> 我的
        composeTestRule.onNodeWithTag("bottom_tab_MY_MODEL").performClick()
        composeTestRule.onNodeWithText("用户名称").assertIsDisplayed()

        // 我的 -> 回到首页
        composeTestRule.onNodeWithTag("bottom_tab_HOME").performClick()
        composeTestRule.onNodeWithText("春日限定樱花拍摄攻略").assertIsDisplayed()
    }

    @Test
    fun allThreeTabs_areAlwaysVisibleInNavigationBar() {
        // 底部导航栏始终显示3个Tab
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
        composeTestRule.onNodeWithText("拍照").assertIsDisplayed()
        composeTestRule.onNodeWithText("我的").assertIsDisplayed()
    }
}
