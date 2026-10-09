package com.framer.sense.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.framer.sense.core.ui.MyApplicationTheme

/** 普通页面固定白底黑字，保留原有动态强调色；相机自行绘制黑色全屏背景。 */
@Composable
internal fun FramerAppTheme(content: @Composable () -> Unit) {
    MyApplicationTheme(darkTheme = false) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
            background = Color.White, surface = Color.White,
            onBackground = Color.Black, onSurface = Color.Black
        ), content = content)
    }
}
