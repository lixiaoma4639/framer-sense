package com.framer.sense.feature.camera.vlm

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.agent.CompositionRepository
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** 验证相机事件门控，不调用摄像头、网络、GPU 或原生模型。 */
class VlmSessionTest {
    /** 为状态机创建测试依赖。
     * @param saved 进程恢复状态。
     * @return 无外部模型调用的状态机。
     */
    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()): VlmCameraViewModel {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            private val name = "vlm-session-${UUID.randomUUID()}"
            /** 返回测试模型目录；无参数。 */
            override fun getNoBackupFilesDir(): File = File(cacheDir, name).apply { mkdirs() }
            /** 隔离测试设置。@param name 原设置名；@param mode 私有存储模式。 */
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${this.name}-$name", mode)
        }
        val store = LocalModelStore(context)
        val provider = object : VisionModelProvider {
            /** 禁止测试访问真实模型。@param call 当前输入，任何调用都会失败。 */
            override suspend fun step(call: ModelCall): StepReply = error("本测试不应推理")
        }
        val renderer = object : AvatarRenderer {
            /** 禁止访问 GPU。plan 为方案，width/height 为尺寸，warmth/brightness 为光照；均不执行。 */
            override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview = error("本测试不应渲染")
        }
        return VlmCameraViewModel(CompositionRepository(provider), SnapshotStore(context), PreviewStore(renderer), SettingsStore(context), store, MnnProvider(store), ModelDownloadRepository(context, store), saved)
    }

    /** 冻结期间连续开始、拍摄以及取消后的迟到帧均不能触发额外动作；无参数。 */
    @Test fun freezingDoesNotQueueCaptureAndLateFrameIsDiscarded() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.StartComposition)
                vm.onIntent(VlmIntent.StartComposition)
                vm.onIntent(VlmIntent.Capture)
                yield()
                assertEquals(1, events.size)
                val token = (events.single() as VlmEffect.Freeze).token
                vm.onIntent(VlmIntent.Cancel)
                val late = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                vm.onIntent(VlmIntent.FrameReady(token, late, 1f))
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                yield()
                assertTrue(late.isRecycled)
                assertNull(vm.state.value.scene)
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertEquals(1, events.size)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    /** 重复拍摄点击只产生一个保存请求，旧完成回调不能结束新请求；无参数。 */
    @Test fun repeatedCaptureIsGatedByCurrentToken() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities()))
                vm.onIntent(VlmIntent.Capture)
                vm.onIntent(VlmIntent.Capture)
                yield()
                assertEquals(1, events.size)
                val first = (events.single() as VlmEffect.Capture).token
                vm.onIntent(VlmIntent.CaptureFinished(first))
                vm.onIntent(VlmIntent.Capture)
                yield()
                vm.onIntent(VlmIntent.CaptureFinished(first))
                assertTrue(vm.state.value.saving)
                assertEquals(2, events.size)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    /** 进程恢复只显示重拍提示，不恢复不存在的图片或旧推理；无参数。 */
    @Test fun restoredProcessReturnsToLiveCamera() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel(SavedStateHandle(mapOf("vlm_started" to true)))
            assertEquals(VlmStage.LIVE, vm.state.value.stage)
            assertNull(vm.state.value.scene)
            assertTrue(vm.state.value.notice.orEmpty().contains("重新构图"))
            vm.onLeave()
        }
    }
}
