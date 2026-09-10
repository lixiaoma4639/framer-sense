package com.framer.sense.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

/** 在真实 Hilt Activity 中验证默认 VLM 入口，避免无 Hilt 的模板宿主无法创建 ViewModel。 */
@HiltAndroidTest
class VlmNavigationEntryTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    /** 主导航默认拍照入口应展示 VLM 页面，不需要执行模型推理；无参数。 */
    @Test fun cameraTabUsesVlmScreen() {
        compose.onNodeWithTag("bottom_tab_CAMERA").performClick()
        compose.onNodeWithText("AI 构图导演").assertIsDisplayed()
        compose.onNodeWithText("模型设置").assertIsDisplayed()
    }
}
