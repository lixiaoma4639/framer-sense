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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.template.core.ui.MyApplicationTheme
import android.template.feature.home.ui.album.AlbumScreen
import android.template.feature.home.ui.recommend.RecommendScreen
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 首页 Tab 定义（可扩展）
 */
enum class HomeTab(val title: String) {
    RECOMMEND("推荐"),
    ALBUM("相册"),
}

/**
 * 首页主界面 - 顶部 Tab 切换 + 左右滑动切换
 *
 * 交互方式：
 * - 点击 Tab 切换页面（带动画滚动）
 * - 左右滑动切换页面（Tab 自动跟随选中）
 *
 * 包含两个 Tab：
 * - 相册：展示系统用户相册中的照片网格
 * - 推荐：两列瀑布流推荐内容（类似小红书）
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel<HomeViewModel>(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreenContent(
        uiState = uiState,
        onTabSelected = viewModel::onTabSelected,
        onPageChanged = viewModel::onPageChanged,
        modifier = modifier
    )
}

@Composable
internal fun HomeScreenContent(
    uiState: HomeUiState,
    onTabSelected: (HomeTab) -> Unit,
    onPageChanged: (Int) -> Unit,
    recommendContent: @Composable () -> Unit = { RecommendScreen() },
    albumContent: @Composable () -> Unit = { AlbumScreen() },
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(
        initialPage = uiState.selectedTab.ordinal,
        pageCount = { HomeTab.entries.size }
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

    HomeScreenContent(
        uiState = uiState,
        pagerState = pagerState,
        onTabSelected = onTabSelected,
        recommendContent = recommendContent,
        albumContent = albumContent,
        modifier = modifier
    )
}

@Composable
private fun HomeScreenContent(
    uiState: HomeUiState,
    pagerState: PagerState,
    onTabSelected: (HomeTab) -> Unit,
    recommendContent: @Composable () -> Unit,
    albumContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部 Tab 栏 - 与 Pager 联动
        SecondaryScrollableTabRow(
            selectedTabIndex = uiState.selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            edgePadding = 16.dp
        ) {
            HomeTab.entries.forEach { tab ->
                val selected = uiState.selectedTab == tab
                Tab(
                    selected = selected,
                    onClick = {
                        onTabSelected(tab)
                    },
                    text = {
                        Text(
                            text = tab.title,
                            style = if (selected)
                                MaterialTheme.typography.titleMedium
                            else
                                MaterialTheme.typography.titleMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        )
                    }
                )
            }
        }

        // 内容区域 - HorizontalPager 支持左右滑动
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (HomeTab.entries[page]) {
                HomeTab.ALBUM -> albumContent()
                HomeTab.RECOMMEND -> recommendContent()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    MyApplicationTheme {
        HomeScreenContent(
            uiState = HomeUiState(),
            onTabSelected = {},
            onPageChanged = {},
            recommendContent = { Text("春日限定樱花拍摄攻略") },
            albumContent = { Text("相册内容") }
        )
    }
}
