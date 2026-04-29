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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import android.template.feature.camera.ui.CameraScreen
import android.template.feature.home.ui.HomeScreen
import android.template.feature.mymodel.ui.MyModelMainScreen
import android.template.feature.mymodel.ui.SettingsScreen
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person

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
 * 我的模块内部页面路由
 */
enum class MyModelRoute {
    MAIN,
    SETTINGS
}

/**
 * 主导航组件 - 包含底部 3 个导航按钮（首页/拍照/我的）
 *
 * 仅支持点击导航按钮切换模块，不支持左右滑动切换。
 */
@Composable
fun MainNavigation() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var myModelRoute by remember { mutableStateOf(MyModelRoute.MAIN) }

    Scaffold(
        bottomBar = {
            // 设置页面时隐藏底部导航栏
            if (myModelRoute == MyModelRoute.MAIN) {
                NavigationBar {
                    BottomNavTab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = selectedTab == index,
                            onClick = { selectedTab = index }
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
            when (selectedTab) {
                BottomNavTab.HOME.ordinal -> HomeScreen()
                BottomNavTab.CAMERA.ordinal -> CameraScreen()
                BottomNavTab.MY_MODEL.ordinal -> {
                    // 设置页面时拦截物理返回键，返回我的主页而非退出App
                    BackHandler(enabled = myModelRoute == MyModelRoute.SETTINGS) {
                        myModelRoute = MyModelRoute.MAIN
                    }
                    // 我的模块：使用 AnimatedContent 切换主页面和设置页面
                    AnimatedContent(
                        targetState = myModelRoute,
                        transitionSpec = {
                            if (targetState == MyModelRoute.SETTINGS) {
                                // 进入设置页：从右侧滑入
                                slideInHorizontally { fullWidth -> fullWidth } togetherWith
                                    slideOutHorizontally { fullWidth -> -fullWidth }
                            } else {
                                // 返回主页：从左侧滑入
                                slideInHorizontally { fullWidth -> -fullWidth } togetherWith
                                    slideOutHorizontally { fullWidth -> fullWidth }
                            }
                        },
                        label = "mymodel_nav"
                    ) { route ->
                        when (route) {
                            MyModelRoute.MAIN -> MyModelMainScreen(
                                onSettingsClick = { myModelRoute = MyModelRoute.SETTINGS }
                            )
                            MyModelRoute.SETTINGS -> SettingsScreen(
                                onBackClick = { myModelRoute = MyModelRoute.MAIN }
                            )
                        }
                    }
                }
            }
        }
    }
}
