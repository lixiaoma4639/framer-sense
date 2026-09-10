package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.data.LocalModelStore
import com.framer.sense.feature.camera.vlm.data.ModelRouting
import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelRoutingTest {
    /** 固定离线不能包含网络供应商，即使首选项仍保存为 GPT；无参数。 */
    @Test fun offlineNeverUsesCloud() {
        assertEquals(listOf(ProviderId.LOCAL), ModelRouting.candidates(ModelSettings(region = Region.GLOBAL, mode = ModelMode.OFFLINE, preferred = ProviderId.GPT)))
    }

    /** 指定云端不能偷偷降级，本区域之外的旧设置会被规范化；无参数。 */
    @Test fun pinnedCloudAndRegionalOrder() {
        assertEquals(listOf(ProviderId.QWEN), ModelRouting.candidates(ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.GPT)))
        assertEquals(listOf(ProviderId.GEMINI, ProviderId.GPT, ProviderId.LOCAL), ModelRouting.candidates(ModelSettings(region = Region.GLOBAL, preferred = ProviderId.GEMINI)))
    }

    /** ZIP 解包路径必须始终位于私有目录内；无参数。 */
    @Test fun rejectsTraversalAndAbsolutePaths() {
        val root = File(System.getProperty("java.io.tmpdir"), "vlm-package")
        listOf("../escape", "/absolute", "a/../../escape", "a\\b", "C:/x", "a/./b").forEach { path ->
            assertTrue(path, runCatching { LocalModelStore.safeChild(root, path) }.isFailure)
        }
        assertEquals(File(root, "weights/visual.mnn").canonicalFile, LocalModelStore.safeChild(root, "weights/visual.mnn"))
    }
}
