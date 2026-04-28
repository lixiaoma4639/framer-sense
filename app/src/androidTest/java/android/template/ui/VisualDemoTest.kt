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
package android.template.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 可视化演示测试 — 在设备屏幕上可以观察到每一步操作过程。
 *
 * 运行前请确保：
 * 1. 连接了模拟器或真机
 * 2. 在 Android Studio 中右键运行此测试类
 * 3. 观察设备屏幕上的 UI 变化
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class VisualDemoTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun init() {
        hiltRule.inject()
    }

    /**
     * 完整的用户操作演示流程：
     * 打开应用 → 等待数据加载 → 输入文本 → 保存 → 连续保存多条 → 验证结果
     *
     * 每一步之间都有 1.5 秒的停顿，方便在设备屏幕上观察 UI 变化。
     */
    @Test
    fun visualDemo_fullUserJourney() {
        // ─── 第 1 步：应用启动，验证主界面 ───
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
        pause("应用已启动，可见 Save 按钮")

        // ─── 第 2 步：等待初始数据加载完成 ───
        // TestFakeDataModule 提供 ["One", "Two", "Three"]，需要等待 Flow 发射
        composeTestRule.waitUntil(5000L) {
            composeTestRule.onAllNodesWithText("Saved item: One", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Saved item: One", substring = true).assertExists()
        composeTestRule.onNodeWithText("Saved item: Two", substring = true).assertExists()
        composeTestRule.onNodeWithText("Saved item: Three", substring = true).assertExists()
        pause("初始数据加载完成：One, Two, Three")

        // ─── 第 3 步：输入新文本 "Android" ───
        composeTestRule.onNodeWithText("Compose").performTextReplacement("Android")
        pause("文本框内容已替换为 Android")

        // ─── 第 4 步：点击 Save 保存 ───
        composeTestRule.onNodeWithText("Save").performClick()
        pause("已点击 Save，观察列表中是否出现新项")

        // ─── 第 5 步：等待并验证 "Android" 已出现在列表中 ───
        composeTestRule.waitUntil(5000L) {
            composeTestRule.onAllNodesWithText("Saved item: Android", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Saved item: Android", substring = true).assertExists()
        pause("验证通过：Android 已出现在列表中")

        // ─── 第 6 步：再次输入 "Kotlin" ───
        composeTestRule.onNodeWithText("Android").performTextReplacement("Kotlin")
        pause("文本框内容已替换为 Kotlin")

        // ─── 第 7 步：再次点击 Save ───
        composeTestRule.onNodeWithText("Save").performClick()
        pause("已点击 Save，观察列表中新增 Kotlin 项")

        // ─── 第 8 步：等待并验证 "Kotlin" 已出现在列表中 ───
        composeTestRule.waitUntil(5000L) {
            composeTestRule.onAllNodesWithText("Saved item: Kotlin", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Saved item: Kotlin", substring = true).assertExists()
        pause("验证通过：Kotlin 已出现在列表中")

        // ─── 第 9 步：再输入 "Jetpack" 并保存 ───
        composeTestRule.onNodeWithText("Kotlin").performTextReplacement("Jetpack")
        pause("文本框内容已替换为 Jetpack")

        composeTestRule.onNodeWithText("Save").performClick()
        pause("已点击 Save")

        composeTestRule.waitUntil(5000L) {
            composeTestRule.onAllNodesWithText("Saved item: Jetpack", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Saved item: Jetpack", substring = true).assertExists()
        pause("验证通过：Jetpack 已出现在列表中")

        // ─── 最终验证：所有保存的项都存在 ───
        composeTestRule.onNodeWithText("Saved item: One", substring = true).assertExists()
        composeTestRule.onNodeWithText("Saved item: Android", substring = true).assertExists()
        composeTestRule.onNodeWithText("Saved item: Kotlin", substring = true).assertExists()
        composeTestRule.onNodeWithText("Saved item: Jetpack", substring = true).assertExists()
        pause("全部验证通过！测试完成")
    }

    /**
     * 演示快速连续保存操作
     */
    @Test
    fun visualDemo_rapidSaveMultipleItems() {
        // 先等待初始数据加载
        composeTestRule.waitUntil(5000L) {
            composeTestRule.onAllNodesWithText("Saved item: One", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 连续保存 3 个不同项
        val items = listOf("Alpha", "Beta", "Gamma")

        for (item in items) {
            // 替换文本框内容
            composeTestRule.onNodeWithText("Compose").performTextReplacement(item)
            pause("已输入: $item")

            // 点击保存
            composeTestRule.onNodeWithText("Save").performClick()
            pause("已保存: $item")

            // 等待并验证出现在列表中
            composeTestRule.waitUntil(5000L) {
                composeTestRule.onAllNodesWithText("Saved item: $item", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText("Saved item: $item", substring = true).assertExists()
        }

        pause("所有项已保存完毕，观察完整列表")
    }

    private fun pause(hint: String = "") {
        Thread.sleep(DELAY_MS)
    }

    companion object {
        // 每步之间的停顿时长（毫秒），可根据需要调整
        private const val DELAY_MS = 1500L
    }
}
