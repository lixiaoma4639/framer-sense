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

package com.framer.sense.feature.home.ui.recommend

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framer.sense.core.ui.MyApplicationTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

/**
 * 推荐数据模型（假数据）
 */
data class RecommendItem(
    val id: Int,
    val title: String,
    val imageUrl: String,
    val author: String,
    val likes: Int
)

/**
 * 推荐页面 - 两列瀑布流布局（类似小红书首页）
 *
 * 使用 LazyVerticalGrid + 固定列数实现类瀑布流效果
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecommendScreen(
    viewModel: RecommendViewModel = hiltViewModel<RecommendViewModel>(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RecommendScreenContent(
        uiState = uiState,
        modifier = modifier
    )
}

@Composable
internal fun RecommendScreenContent(
    uiState: RecommendUiState,
    modifier: Modifier = Modifier
) {
    when (uiState) {
        RecommendUiState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        is RecommendUiState.Error -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = uiState.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
        is RecommendUiState.Success -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier.fillMaxSize()
            ) {
                items(
                    items = uiState.items,
                    key = { it.id },
                    contentType = { "recommend_card" }
                ) { item ->
                    RecommendCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun RecommendCard(
    item: RecommendItem,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 图片区域 - 使用不同 aspectRatio 模拟瀑布流高低错落
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.id % 3 == 0) 0.85f else if (item.id % 2 == 0) 1.05f else 0.95f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 文字信息区域
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formatLikes(item.likes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatLikes(count: Int): String = when {
    count >= 10000 -> "${count / 10000}.${count % 10000 / 1000}w"
    count >= 1000 -> "${count / 1000}.${count % 1000 / 100}k"
    else -> count.toString()
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun RecommendScreenPreview() {
    MyApplicationTheme {
        RecommendScreenContent(
            uiState = RecommendUiState.Success(defaultRecommendItems())
        )
    }
}
