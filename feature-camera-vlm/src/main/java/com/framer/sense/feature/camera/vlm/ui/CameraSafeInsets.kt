package com.framer.sense.feature.camera.vlm.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 顶部保留原锁定位置，底部预留常显系统导航栏的高度，与抽屉开关无关。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("DiscouragedApi", "InternalInsetResource")
internal fun rememberCameraSafeInsets(): WindowInsets {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val resources = LocalContext.current.resources
    val view = LocalView.current
    val measuredTop = maxOf(WindowInsets.statusBarsIgnoringVisibility.getTop(density),
        WindowInsets.displayCutout.getTop(density))
    val fixedTop = remember(view, configuration.orientation, configuration.densityDpi) {
        // 首帧 Compose Insets 可能尚未送达，使用设备资源与窗口数据补足安全距离。
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val resourceTop = if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0
        val windowTop = ViewCompat.getRootWindowInsets(view)?.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
        maxOf(measuredTop, resourceTop, windowTop)
    }
    // 不以 screenHeightDp 为 remember 键，避免部分系统在状态栏显隐时修改它。
    return WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .union(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
        .union(WindowInsets(top = fixedTop))
}
