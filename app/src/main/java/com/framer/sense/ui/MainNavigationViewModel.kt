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

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MainNavigationUiState(
    val selectedTab: BottomNavTab = BottomNavTab.HOME,
    val myModelRoute: MyModelRoute = MyModelRoute.MAIN
) {
    val showBottomBar: Boolean = myModelRoute == MyModelRoute.MAIN
}

/**
 * 这里的 @Inject 是 依赖注入（DI）框架 的核心注解（最常见是 Hilt / Dagger）。
 * 不用自己写 MainNavigationViewModel()
 * 不用自己管理对象生命周期
 * 框架自动帮你实例化、注入、管理。
 *
 * ViewModel = 页面数据管家
 * 屏幕旋转，数据不丢失（最核心功能）
 * 存放数据、处理业务逻辑
 * 让页面代码更干净、好维护
 * 生命周期比页面长，真正退出才销毁
 */
@HiltViewModel
class MainNavigationViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MainNavigationUiState())
    val uiState: StateFlow<MainNavigationUiState> = _uiState.asStateFlow()

    fun onTabSelected(tab: BottomNavTab) {
        _uiState.update { state ->
            if (state.selectedTab == tab) state else state.copy(selectedTab = tab)
        }
    }

    fun onSettingsClick() {
        _uiState.update { state ->
            state.copy(
                selectedTab = BottomNavTab.MY_MODEL,
                myModelRoute = MyModelRoute.SETTINGS
            )
        }
    }

    fun onSettingsBack() {
        _uiState.update { state ->
            state.copy(myModelRoute = MyModelRoute.MAIN)
        }
    }
}
