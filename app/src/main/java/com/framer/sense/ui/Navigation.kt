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

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.framer.sense.feature.camera.pytorch.v2.ui.CameraScreen
import com.framer.sense.feature.camera.pytorch.v2.ui.CameraV2CaptureAction
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
    cameraContent: @Composable ((CameraV2CaptureAction?) -> Unit) -> Unit = { onCaptureActionChanged ->
        CameraScreen(onCaptureActionChanged = onCaptureActionChanged)
    },
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
    modifier: Modifier = Modifier,
    isLandscape: Boolean =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
) {
    val tabStateHolder = rememberSaveableStateHolder()
    val myModelBackStack = rememberNavBackStack(Main)
    var cameraCaptureAction by remember { mutableStateOf<CameraV2CaptureAction?>(null) }
    val showCameraSideNavigation =
        uiState.showBottomBar && uiState.selectedTab == BottomNavTab.CAMERA && isLandscape

    fun navigateToMyModelDestination(destination: MyModelNavKey) {
        onTabSelected(BottomNavTab.MY_MODEL)
        myModelBackStack.add(destination)
    }

    fun navigateBackFromMyModelDestination() {
        if (myModelBackStack.size > 1) {
            myModelBackStack.removeLastOrNull()
        }
    }

    fun onNavigationItemClick(tab: BottomNavTab) {
        if (
            tab == BottomNavTab.CAMERA &&
                uiState.selectedTab == BottomNavTab.CAMERA &&
                cameraCaptureAction != null
        ) {
            cameraCaptureAction?.onClick?.invoke()
        } else {
            cameraCaptureAction = null
            onTabSelected(tab)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (uiState.showBottomBar && !showCameraSideNavigation) {
                    BottomNavigationBar(
                        selectedTab = uiState.selectedTab,
                        cameraCaptureAction = cameraCaptureAction,
                        onTabClick = ::onNavigationItemClick
                    )
                }
            }
        ) { innerPadding ->
            MainNavigationDestination(
                selectedTab = uiState.selectedTab,
                tabStateHolder = tabStateHolder,
                homeContent = homeContent,
                cameraContent = cameraContent,
                onCaptureActionChanged = { cameraCaptureAction = it },
                myModelContent = myModelContent,
                onSettingsClick = { navigateToMyModelDestination(Settings) },
                onMessagesClick = { navigateToMyModelDestination(Messages) },
                onScanClick = { navigateToMyModelDestination(Scan) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .then(
                        if (showCameraSideNavigation) {
                            Modifier.padding(end = CAMERA_LANDSCAPE_RAIL_WIDTH)
                        } else {
                            Modifier
                        }
                    )
            )
        }

        if (showCameraSideNavigation) {
            CameraLandscapeNavigationRail(
                selectedTab = uiState.selectedTab,
                cameraCaptureAction = cameraCaptureAction,
                onTabClick = ::onNavigationItemClick,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(CAMERA_LANDSCAPE_RAIL_WIDTH)
            )
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
private fun BottomNavigationBar(
    selectedTab: BottomNavTab,
    cameraCaptureAction: CameraV2CaptureAction?,
    onTabClick: (BottomNavTab) -> Unit
) {
    NavigationBar(modifier = Modifier.testTag("bottom_navigation")) {
        BottomNavTab.entries.forEach { tab ->
            val isCaptureAction = tab.isCaptureAction(selectedTab)
            NavigationBarItem(
                modifier = Modifier.testTag("bottom_tab_${tab.name}"),
                icon = {
                    if (isCaptureAction) {
                        CaptureActionContent(tab.icon)
                    } else {
                        Icon(tab.icon, contentDescription = tab.label)
                    }
                },
                label = if (isCaptureAction) null else ({ Text(tab.label) }),
                selected = selectedTab == tab,
                enabled = tab.isEnabled(selectedTab, cameraCaptureAction),
                colors = if (isCaptureAction) {
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color.Transparent,
                        disabledIconColor = Color.White.copy(alpha = 0.60f),
                        disabledTextColor = Color.White.copy(alpha = 0.60f)
                    )
                } else {
                    NavigationBarItemDefaults.colors()
                },
                onClick = { onTabClick(tab) }
            )
        }
    }
}

@Composable
private fun CameraLandscapeNavigationRail(
    selectedTab: BottomNavTab,
    cameraCaptureAction: CameraV2CaptureAction?,
    onTabClick: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier.testTag("camera_landscape_navigation")
    ) {
        BottomNavTab.entries.forEach { tab ->
            Spacer(modifier = Modifier.weight(1f))
            val isCaptureAction = tab.isCaptureAction(selectedTab)
            NavigationRailItem(
                modifier = Modifier.testTag("bottom_tab_${tab.name}"),
                icon = {
                    if (isCaptureAction) {
                        CaptureActionContent(tab.icon)
                    } else {
                        Icon(tab.icon, contentDescription = tab.label)
                    }
                },
                label = if (isCaptureAction) null else ({ Text(tab.label) }),
                selected = selectedTab == tab,
                enabled = tab.isEnabled(selectedTab, cameraCaptureAction),
                colors = if (isCaptureAction) {
                    NavigationRailItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = Color.Transparent,
                        disabledIconColor = Color.White.copy(alpha = 0.60f),
                        disabledTextColor = Color.White.copy(alpha = 0.60f)
                    )
                } else {
                    NavigationRailItemDefaults.colors()
                },
                onClick = { onTabClick(tab) }
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

private val CAMERA_LANDSCAPE_RAIL_WIDTH = 80.dp

private fun BottomNavTab.displayLabel(selectedTab: BottomNavTab): String =
    if (isCaptureAction(selectedTab)) "拍摄" else label

private fun BottomNavTab.isCaptureAction(selectedTab: BottomNavTab): Boolean =
    this == BottomNavTab.CAMERA && selectedTab == BottomNavTab.CAMERA

private fun BottomNavTab.isEnabled(
    selectedTab: BottomNavTab,
    cameraCaptureAction: CameraV2CaptureAction?
): Boolean =
    this != BottomNavTab.CAMERA || selectedTab != BottomNavTab.CAMERA ||
        cameraCaptureAction?.enabled == true

@Composable
private fun CaptureActionContent(icon: ImageVector) {
    Column(
        modifier = Modifier
            .background(CAPTURE_ACTION_BLUE, RoundedCornerShape(10.dp))
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "拍摄",
            tint = Color.White
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = "拍摄", color = Color.White)
    }
}

private val CAPTURE_ACTION_BLUE = Color(0xFF075967)

@Composable
private fun MainNavigationDestination(
    selectedTab: BottomNavTab,
    tabStateHolder: SaveableStateHolder,
    homeContent: @Composable () -> Unit,
    cameraContent: @Composable ((CameraV2CaptureAction?) -> Unit) -> Unit,
    onCaptureActionChanged: (CameraV2CaptureAction?) -> Unit,
    myModelContent: @Composable (
        onSettingsClick: () -> Unit,
        onMessagesClick: () -> Unit,
        onScanClick: () -> Unit
    ) -> Unit,
    onSettingsClick: () -> Unit,
    onMessagesClick: () -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                BottomNavTab.HOME -> homeContent()
                BottomNavTab.CAMERA -> cameraContent(onCaptureActionChanged)
                BottomNavTab.MY_MODEL -> myModelContent(
                    onSettingsClick,
                    onMessagesClick,
                    onScanClick
                )
            }
        }
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
        cameraContent = { _ -> Text("AI 构图引导") },
        myModelContent = { _, _, _ -> Text("用户名称") },
        settingsContent = { Text("设置") },
        messageListContent = { Text("消息") },
        scanContent = { Text("扫一扫") }
    )
}
