package com.framer.sense.ui

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 假相机只验证窗口与主题，不创建相机、GPU、AR 或离线推理。 */
class NavigationSystemBarsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cameraHidesSystemBarsAndExitRestoresWhiteNormalPage() {
        val state = mutableStateOf(MainNavigationUiState())
        var host: View? = null
        var background = Color.Unspecified
        var text = Color.Unspecified
        compose.setContent {
            FramerAppTheme {
                val view = LocalView.current
                val colors = MaterialTheme.colorScheme
                SideEffect { host = view; background = colors.background; text = colors.onBackground }
                MainNavigationContent(state.value, { state.value = state.value.copy(selectedTab = it) },
                    homeContent = { Text("首页") },
                    cameraContent = { _, exit -> Column {
                        Text("相机")
                        TextButton(exit, Modifier.testTag("test_exit")) { Text("返回") }
                    } },
                    myModelContent = { _, _, _ -> Text("我的") })
            }
        }
        compose.onNodeWithTag("bottom_tab_CAMERA").performClick()
        waitForBars(visible = false) { host }
        compose.onNodeWithTag("test_exit").performClick()
        waitForBars(visible = true) { host }
        compose.runOnIdle {
            assertEquals(BottomNavTab.HOME, state.value.selectedTab)
            assertTrue(state.value.showBottomBar)
            assertEquals(Color.White, background)
            assertEquals(Color.Black, text)
        }
        compose.onNodeWithTag("bottom_tab_MY_MODEL").performClick()
        compose.onNodeWithTag("bottom_tab_CAMERA").performClick()
        compose.runOnIdle { assertFalse(state.value.showBottomBar) }
        compose.onNodeWithTag("test_exit").performClick()
        compose.runOnIdle { assertEquals(BottomNavTab.MY_MODEL, state.value.selectedTab) }
    }

    @Test fun foregroundResumeRestoresImmersiveSystemBars() {
        var host: View? = null
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        compose.setContent {
            host = LocalView.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                NavigationSystemBars(immersive = true)
                Text("相机窗口")
            }
        }
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        waitForBars(visible = false) { host }
        compose.runOnIdle {
            var context: android.content.Context = requireNotNull(host).context
            while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
            val activity = context as android.app.Activity
            androidx.core.view.WindowCompat.getInsetsController(activity.window, requireNotNull(host))
                .show(WindowInsetsCompat.Type.systemBars())
        }
        compose.runOnIdle {
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        waitForBars(visible = false) { host }
    }

    private fun waitForBars(visible: Boolean, host: () -> View?) {
        compose.waitUntil(5_000) {
            var matches = false
            compose.runOnIdle {
                val insets = host()?.let(ViewCompat::getRootWindowInsets)
                matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.statusBars()) == visible &&
                    insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == visible
            }
            matches
        }
    }
}
