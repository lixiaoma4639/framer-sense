package com.framer.sense.feature.camera.vlm

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.agent.CompositionRepository
import com.framer.sense.feature.camera.vlm.agent.DirectorProgress
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.data.*
import com.framer.sense.feature.camera.vlm.model.*
import com.framer.sense.feature.camera.vlm.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
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
    private fun viewModel(
        saved: SavedStateHandle = SavedStateHandle(),
        provider: VisionModelProvider = object : VisionModelProvider {
            /** 禁止测试访问真实模型。@param call 当前输入，任何调用都会失败。 */
            override suspend fun step(call: ModelCall): StepReply = error("本测试不应推理")
        },
        renderer: AvatarRenderer = object : AvatarRenderer {
            /** 禁止访问 GPU。plan 为方案，width/height 为尺寸，warmth/brightness 为光照；均不执行。 */
            override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview = error("本测试不应渲染")
        }
    ): VlmCameraViewModel {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            private val name = "vlm-session-${UUID.randomUUID()}"
            /** 返回测试模型目录；无参数。 */
            override fun getNoBackupFilesDir(): File = File(cacheDir, name).apply { mkdirs() }
            /** 隔离测试设置。@param name 原设置名；@param mode 私有存储模式。 */
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${this.name}-$name", mode)
        }
        val store = LocalModelStore(context)
        return VlmCameraViewModel(CompositionRepository(provider), SnapshotStore(context), PreviewStore(renderer), SettingsStore(context), store, MnnProvider(store), ModelDownloadRepository(context, store), saved)
    }

    /** 返回可通过基础和投影校验的三份方案。 */
    private fun plans(): List<CompositionPlan> = listOf(
        CompositionPlan("p1", "环境", ShotType.ENVIRONMENT, CropRect(), 1f, AvatarPlacement(height = .3f), "保留建筑", "环境为主"),
        CompositionPlan("p2", "全身", ShotType.FULL, CropRect(), 1f, AvatarPlacement(height = .65f), "自然站立", "全身完整"),
        CompositionPlan("p3", "半身", ShotType.HALF, CropRect(), 1f, AvatarPlacement(foot = Point2(.5f, 1.4f), height = 1.25f), "看向镜头", "突出表情")
    )

    /** 创建当前冻结画面对应的最终模型答复。 */
    private fun reply(imageId: String): StepReply = StepReply(
        "fake",
        VlmJson.encodeToString(DirectorDecision(ActionKind.FINAL, plans(), imageId = imageId)),
        DirectorDecision(ActionKind.FINAL, plans(), imageId = imageId)
    )

    /** 通过冻结事件进入自动生成流程，并返回当前冻结请求令牌。 */
    private suspend fun startGenerating(vm: VlmCameraViewModel, events: MutableList<VlmEffect>): String {
        vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = ModelMode.OFFLINE)))
        withTimeout(5000) { vm.state.first { it.settings.mode == ModelMode.OFFLINE } }
        vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
        vm.onIntent(VlmIntent.StartComposition)
        yield()
        return (events.single() as VlmEffect.Freeze).token
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

    /** 生成期间更新当前步骤，完成后不保留步骤状态。 */
    @Test fun directorProgressUpdatesAndClearsAfterSuccessfulGeneration() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val provider = object : VisionModelProvider {
                /** 等待断言加载文案后再提交三个合法方案。 */
                override suspend fun step(call: ModelCall): StepReply {
                    entered.complete(Unit)
                    released.await()
                    return reply(call.input.scene.id)
                }
            }
            val renderer = object : AvatarRenderer {
                /** 返回透明测试人偶，避免访问 GPU。 */
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview =
                    AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect())
            }
            val vm = viewModel(provider = provider, renderer = renderer)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                val token = startGenerating(vm, events)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                entered.await()
                assertTrue(vm.state.value.directorProgress is DirectorProgress.AnalyzingScene)
                released.complete(Unit)
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY } }
                assertNull(vm.state.value.directorProgress)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    /** 推理失败和取消都应清空当前步骤；取消后的迟到进度不能写回会话。 */
    @Test fun directorProgressClearsAfterFailureAndIgnoresLateProgressAfterCancellation() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val provider = object : VisionModelProvider {
                /** 忽略取消直到测试放行，用于模拟平台迟到回调。 */
                override suspend fun step(call: ModelCall): StepReply {
                    entered.complete(Unit)
                    withContext(NonCancellable) { released.await() }
                    return StepReply("fake", "not-json")
                }
            }
            val vm = viewModel(provider = provider)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                val token = startGenerating(vm, events)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                entered.await()
                assertTrue(vm.state.value.directorProgress is DirectorProgress.AnalyzingScene)
                vm.onIntent(VlmIntent.Cancel)
                assertEquals(VlmStage.FROZEN, vm.state.value.stage)
                assertNull(vm.state.value.directorProgress)
                released.complete(Unit)
                repeat(4) { yield() }
                assertEquals(VlmStage.FROZEN, vm.state.value.stage)
                assertNull(vm.state.value.directorProgress)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    /** 模型调用失败后，错误状态不应继续显示已过期的导演步骤。 */
    @Test fun directorProgressClearsAfterGenerationFailure() = runBlocking {
        withContext(Dispatchers.Main) {
            val provider = object : VisionModelProvider {
                /** 返回不可自动切换的失败。 */
                override suspend fun step(call: ModelCall): StepReply = throw ModelFailure("REFUSED", "拒绝", false)
            }
            val vm = viewModel(provider = provider)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                val token = startGenerating(vm, events)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.FROZEN && it.error != null } }
                assertNull(vm.state.value.directorProgress)
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
