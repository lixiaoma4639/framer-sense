package com.framer.sense.feature.camera.vlm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.ui.VlmLoadingOverlay
import com.framer.sense.feature.camera.vlm.ui.VlmStage
import org.junit.Rule
import org.junit.Test

/** 验证导演加载遮罩展示当前真实步骤，而不是旧的通用固定文案。 */
class VlmCameraScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun loadingOverlayShowsCurrentDirectorStepInsteadOfGenericCopy() {
        composeRule.setContent {
            MaterialTheme {
                VlmLoadingOverlay(
                    VlmStage.GENERATING,
                    DirectorProgress.ValidatingResponse(2),
                    onCancel = {}
                )
            }
        }

        composeRule.onNodeWithTag("vlm_director_progress").assertTextContains("正在解析并校验模型结果（第 2 轮）")
        composeRule.onNodeWithText("导演正在分析与校验").assertDoesNotExist()
    }
}
