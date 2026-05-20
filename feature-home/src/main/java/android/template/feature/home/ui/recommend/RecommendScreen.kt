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

package android.template.feature.home.ui.recommend

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.template.core.ui.MyApplicationTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
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
 * 生成假数据 - 模拟小红书风格内容
 */
@Composable
fun rememberFakeRecommendItems(): List<RecommendItem> = remember {
    listOf(
        RecommendItem(1, "春日限定樱花拍摄攻略", "https://picsum.photos/id/10/400/600", "摄影小达人", 2847),
        RecommendItem(2, "周末探店 | 这家咖啡厅太好拍了", "https://picsum.photos/id/292/400/500", "城市漫游者", 1523),
        RecommendItem(3, "居家好物分享 提升幸福感", "https://picsum.photos/id/20/400/400", "生活美学家", 961),
        RecommendItem(4, "健身打卡第30天 体态变化记录", "https://picsum.photos/id/237/400/550", "运动狂人", 3201),
        RecommendItem(5, "自制低卡减脂餐 一周不重样", "https://picsum.photos/id/225/400/450", "健康饮食家", 1876),
        RecommendItem(6, "旅行Vlog | 云南大理的浪漫", "https://picsum.photos/id/1015/400/650", "旅行的意义", 4520),
        RecommendItem(7, "穿搭分享 春季通勤穿搭灵感", "https://picsum.photos/id/338/400/520", "时尚博主", 2134),
        RecommendItem(8, "读书笔记《原子习惯》精华摘录", "https://picsum.photos/id/24/400/420", "阅读爱好者", 892),
        RecommendItem(9, "手绘教程 零基础也能学会的水彩", "https://picsum.photos/id/106/400/480", "艺术创作者", 1654),
        RecommendItem(10, "宠物日常 我家猫咪的迷惑行为", "https://picsum.photos/id/40/400/550", "铲屎官日记", 5621),
        RecommendItem(11, "数码测评 新款耳机使用体验", "https://picsum.photos/id/180/400/460", "科技评测师", 1087),
        RecommendItem(12, "装修记录 小户型改造全过程", "https://picsum.photos/id/163/400/600", "家装设计师", 3412),
        RecommendItem(13, "美食制作 自制提拉米苏详细教程", "https://picsum.photos/id/312/400/500", "甜品控", 2567),
        RecommendItem(14, "护肤分享 油皮亲妈水乳测评", "https://picsum.photos/id/250/400/440", "护肤达人", 1933),
        RecommendItem(15, "户外徒步 周末登山路线推荐", "https://picsum.photos/id/1036/400/580", "户外探险家", 2789),
        RecommendItem(16, "学习干货 效率工具合集推荐", "https://img1.baidu.com/it/u=123060855,2001333173&fm=253&fmt=auto&app=138&f=JPEG?w=500&h=653", "效率提升官", 1456),
    )
}

/**
 * 推荐页面 - 两列瀑布流布局（类似小红书首页）
 *
 * 使用 LazyVerticalGrid + 固定列数实现类瀑布流效果
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecommendScreen(
    modifier: Modifier = Modifier
) {
    val items = rememberFakeRecommendItems()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize()
    ) {
        items(
            items = items,
            key = { it.id },
            contentType = { "recommend_card" }
        ) { item ->
            RecommendCard(item = item)
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
        RecommendScreen()
    }
}
