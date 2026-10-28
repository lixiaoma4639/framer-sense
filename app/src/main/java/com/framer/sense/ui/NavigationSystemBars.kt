package com.framer.sense.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 系统栏只由 App 导航管理；返回、前台恢复和弹窗关闭均按当前目的地重新设置。 */
@Composable
@Suppress("DEPRECATION")
internal fun NavigationSystemBars(immersive: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, immersive) {
        val window = view.context.hostActivity()?.window
        if (window == null) return@DisposableEffect onDispose { }
        val bars = WindowCompat.getInsetsController(window, view)
        val oldStatusLight = bars.isAppearanceLightStatusBars
        val oldNavigationLight = bars.isAppearanceLightNavigationBars
        val oldBehavior = bars.systemBarsBehavior
        val oldNavigationColor = window.navigationBarColor
        val oldNavigationDivider = if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor else null
        val oldStatusColor = window.statusBarColor
        val oldContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else null
        val oldCutoutMode = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else null
        fun apply() {
            bars.isAppearanceLightStatusBars = !immersive
            bars.isAppearanceLightNavigationBars = !immersive
            window.statusBarColor = Color.TRANSPARENT
            // 相机和抽屉均延伸到系统横条下方，导航区只叠加系统控件。
            window.navigationBarColor = if (immersive) Color.TRANSPARENT else Color.WHITE
            if (Build.VERSION.SDK_INT >= 28 && immersive) window.navigationBarDividerColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            if (Build.VERSION.SDK_INT >= 28 && oldCutoutMode != null) {
                val wanted = if (immersive) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else oldCutoutMode
                if (window.attributes.layoutInDisplayCutoutMode != wanted) {
                    window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = wanted }
                }
            }
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) apply() }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) apply() }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        apply()
        onDispose {
            lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            bars.show(WindowInsetsCompat.Type.systemBars())
            bars.systemBarsBehavior = oldBehavior
            bars.isAppearanceLightStatusBars = oldStatusLight
            bars.isAppearanceLightNavigationBars = oldNavigationLight
            window.navigationBarColor = oldNavigationColor
            if (Build.VERSION.SDK_INT >= 28 && oldNavigationDivider != null) window.navigationBarDividerColor = oldNavigationDivider
            window.statusBarColor = oldStatusColor
            if (Build.VERSION.SDK_INT >= 29 && oldContrast != null) window.isNavigationBarContrastEnforced = oldContrast
            if (Build.VERSION.SDK_INT >= 28 && oldCutoutMode != null) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = oldCutoutMode }
            }
        }
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}
