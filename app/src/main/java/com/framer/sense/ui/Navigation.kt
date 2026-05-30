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

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.framer.sense.feature.camera.pytorch.ui.CameraScreen
import com.framer.sense.feature.home.ui.HomeScreen
import com.framer.sense.feature.mymodel.navigation.Main
import com.framer.sense.feature.mymodel.navigation.Messages
import com.framer.sense.feature.mymodel.navigation.MyModelNavKey
import com.framer.sense.feature.mymodel.navigation.Scan
import com.framer.sense.feature.mymodel.navigation.Settings
import com.framer.sense.feature.mymodel.ui.MyModelMainScreen
import com.framer.sense.feature.mymodel.ui.message.MessageListScreen
import com.framer.sense.feature.mymodel.ui.scan.ScanScreen
import com.framer.sense.feature.mymodel.ui.setting.SettingsScreen

/**
 * 底部导航栏 Tab 定义
 */
enum class BottomNavTab(
    val label: String,
    val icon: ImageVector
) {
    HOME("首页", Icons.Default.Home),
    CAMERA("拍照", Icons.Default.CameraAlt),
    MY_MODEL("我的", Icons.Default.Person)
}

/**
 * 主导航组件 - 包含底部 3 个导航按钮（首页/拍照/我的）
 *
 * 仅支持点击导航按钮切换模块，不支持左右滑动切换。
 */
@Composable
fun MainNavigation(
    viewModel: MainNavigationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MainNavigationContent(
        uiState = uiState,
        onTabSelected = viewModel::onTabSelected
    )
}

@Composable
internal fun MainNavigationContent(
    uiState: MainNavigationUiState,
    onTabSelected: (BottomNavTab) -> Unit,
    homeContent: @Composable () -> Unit = { HomeScreen() },
    cameraContent: @Composable () -> Unit = { CameraScreen() },
    myModelContent: @Composable (
        onSettingsClick: () -> Unit,
        onMessagesClick: () -> Unit,
        onScanClick: () -> Unit
    ) -> Unit = { onClickSettings, onClickMessages, onClickScan ->
        MyModelMainScreen(
            onSettingsClick = onClickSettings,
            onMessagesClick = onClickMessages,
            onScanClick = onClickScan
        )
    },
    settingsContent: @Composable (() -> Unit) -> Unit = { onBackClick ->
        SettingsScreen(onBackClick = onBackClick)
    },
    messageListContent: @Composable (() -> Unit) -> Unit = { onBackClick ->
        MessageListScreen(onBackClick = onBackClick)
    },
    scanContent: @Composable (() -> Unit) -> Unit = { onBackClick ->
        ScanScreen(onBackClick = onBackClick)
    },
    modifier: Modifier = Modifier
) {
    val tabStateHolder = rememberSaveableStateHolder()
    val myModelBackStack = rememberNavBackStack(Main)

    fun navigateToMyModelDestination(destination: MyModelNavKey) {
        onTabSelected(BottomNavTab.MY_MODEL)
        myModelBackStack.add(destination)
    }

    fun navigateBackFromMyModelDestination() {
        if (myModelBackStack.size > 1) {
            myModelBackStack.removeLastOrNull()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 主内容：外层 Scaffold 提供底部导航栏
        Scaffold(
            bottomBar = {
                if (uiState.showBottomBar) {
                    NavigationBar {
                        BottomNavTab.entries.forEach { tab ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("bottom_tab_${tab.name}"),
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                selected = uiState.selectedTab == tab,
                                onClick = {
                                    onTabSelected(tab)
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                tabStateHolder.SaveableStateProvider(uiState.selectedTab.name) {
                    when (uiState.selectedTab) {
                        BottomNavTab.HOME -> homeContent()
                        BottomNavTab.CAMERA -> cameraContent()
                        BottomNavTab.MY_MODEL -> myModelContent(
                            { navigateToMyModelDestination(Settings) },
                            { navigateToMyModelDestination(Messages) },
                            { navigateToMyModelDestination(Scan) }
                        )
                    }
                }
            }
        }

        MyModelNavigationHost(
            backStack = myModelBackStack,
            onBack = ::navigateBackFromMyModelDestination,
            settingsContent = settingsContent,
            messageListContent = messageListContent,
            scanContent = scanContent
        )
    }
}

@Composable
private fun MyModelNavigationHost(
    backStack: List<NavKey>,
    onBack: () -> Unit,
    settingsContent: @Composable (() -> Unit) -> Unit,
    messageListContent: @Composable (() -> Unit) -> Unit,
    scanContent: @Composable (() -> Unit) -> Unit
) {
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = onBack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        transitionSpec = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left) togetherWith
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left)
        },
        popTransitionSpec = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right) togetherWith
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right)
        },
        entryProvider = entryProvider {
            entry<Main> { Box(modifier = Modifier.fillMaxSize()) }
            entry<Settings> { settingsContent(onBack) }
            entry<Messages> { messageListContent(onBack) }
            entry<Scan> { scanContent(onBack) }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun MainNavigationPreview() {
    MainNavigationContent(
        uiState = MainNavigationUiState(),
        onTabSelected = {},
        homeContent = { Text("推荐") },
        cameraContent = { Text("AI 构图引导") },
        myModelContent = { _, _, _ -> Text("用户名称") },
        settingsContent = { Text("设置") },
        messageListContent = { Text("消息") },
        scanContent = { Text("扫一扫") }
    )
}
