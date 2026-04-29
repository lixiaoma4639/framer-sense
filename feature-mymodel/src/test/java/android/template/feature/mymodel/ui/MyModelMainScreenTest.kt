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

package android.template.feature.mymodel.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MyModelMainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun myModelMainScreen_displaysTitle() {
        composeTestRule.setContent {
            MyModelMainScreen()
        }
        composeTestRule.onNodeWithText("我的").assertIsDisplayed()
    }

    @Test
    fun myModelMainScreen_displaysModuleName() {
        composeTestRule.setContent {
            MyModelMainScreen()
        }
        composeTestRule.onNodeWithText("当前模块：MyModel").assertIsDisplayed()
    }
}
