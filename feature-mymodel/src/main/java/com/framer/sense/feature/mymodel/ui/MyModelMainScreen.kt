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

package com.framer.sense.feature.mymodel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.framer.sense.core.ui.MyApplicationTheme
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 我的模块 Tab 定义
 */
enum class MyModelTab(val title: String) {
    WORKS("作品"),
    LIKES("点赞"),
    FAVORITES("收藏"),
    COMMENTS("评论")
}

/**
 * 我的模块主页面
 *
 * 布局：
 * - 顶部标题栏：右侧向左依次为 设置/消息/扫一扫
 * - 个人信息区：圆形头像 + 名称 + 简介
 * - 4个Tab：作品/点赞/收藏/评论，支持左右滑动切换
 */
@Composable
fun MyModelMainScreen(
    onSettingsClick: () -> Unit = {},
    viewModel: MyModelMainViewModel = hiltViewModel<MyModelMainViewModel>(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MyModelMainContent(
        uiState = uiState,
        onTabSelected = viewModel::onTabSelected,
        onPageChanged = viewModel::onPageChanged,
        onSettingsClick = onSettingsClick,
        modifier = modifier
    )
}

@Composable
internal fun MyModelMainContent(
    uiState: MyModelMainUiState,
    onTabSelected: (MyModelTab) -> Unit,
    onPageChanged: (Int) -> Unit,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.selectedTab.ordinal,
        pageCount = { MyModelTab.entries.size }
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onPageChanged)
    }

    LaunchedEffect(uiState.selectedTab) {
        val targetPage = uiState.selectedTab.ordinal
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    MyModelMainContent(
        uiState = uiState,
        pagerState = pagerState,
        onTabSelected = onTabSelected,
        onSettingsClick = onSettingsClick,
        modifier = modifier
    )
}

@Composable
private fun MyModelMainContent(
    uiState: MyModelMainUiState,
    pagerState: PagerState,
    onTabSelected: (MyModelTab) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部标题栏 - 右侧向左：设置、消息、扫一扫
        TopActionBar(onSettingsClick = onSettingsClick)

        // 个人信息区
        ProfileSection(profile = uiState.profile)

        // Tab 栏 + HorizontalPager 联动
        ProfileTabRow(
            selectedTab = uiState.selectedTab,
            onTabClick = onTabSelected
        )

        // 内容区域 - 支持左右滑动
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            PlaceholderContent(uiState.emptyTextFor(MyModelTab.entries[page]))
        }
    }
}

/**
 * 顶部操作栏 - 右侧向左依次为：设置、消息、扫一扫
 */
@Composable
private fun TopActionBar(onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 扫一扫
        IconButton(onClick = { /* TODO */ }) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = "扫一扫",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        // 消息
        IconButton(onClick = { /* TODO */ }) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "消息",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        // 设置
        IconButton(onClick = onSettingsClick) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 个人信息区 - 圆形头像 + 名称 + 简介
 */
@Composable
private fun ProfileSection(profile: ProfileUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 圆形头像
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "头像",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 名称
        Text(
            text = profile.userName,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        // 简介
        Text(
            text = profile.bio,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/**
 * 个人主页 Tab 栏 - 使用 PrimaryTabRow 平分宽度，带下划线指示器
 */
@Composable
private fun ProfileTabRow(
    selectedTab: MyModelTab,
    onTabClick: (MyModelTab) -> Unit
) {
    PrimaryTabRow(
        selectedTabIndex = selectedTab.ordinal
    ) {
        MyModelTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabClick(tab) },
                text = {
                    Text(
                        text = tab.title,
                        style = if (selectedTab == tab)
                            MaterialTheme.typography.titleSmall
                        else
                            MaterialTheme.typography.titleSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    )
                }
            )
        }
    }
}

@Composable
private fun PlaceholderContent(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MyModelMainScreenPreview() {
    MyApplicationTheme {
        MyModelMainContent(
            uiState = MyModelMainUiState(),
            onTabSelected = {},
            onPageChanged = {}
        )
    }
}
