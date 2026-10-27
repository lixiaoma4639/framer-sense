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
    @Test fun localHistoryFailureKeepsSuccessfulImageAndRetryDoesNotCallGeneratorAgain() = runBlocking {
        withContext(Dispatchers.Main) {
            val asset = File.createTempFile("history-local-retry", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            val scene = SceneSnapshot("scene", 60, 80, 1f, asset.path, asset.path)
            val old = GeneratedCandidate("old", "batch", scene, HistoricalImageModel(ProviderId.QWEN, "old-qwen"), "", 0, null,
                status = GeneratedCandidateStatus.READY, assets = GeneratedReferenceAssets(asset.path, asset.path, 60, 80, "old-qwen", 1))
            val stored = GeneratedReferenceState(candidates = listOf(old), selectedId = old.id, historyId = "retry-flow")
            var saves = 0
            var requests = 0
            var deleted: String? = null
            val history = object : CameraCompositionHistory {
                override val entries = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.framer.sense.core.data.CameraHistoryEntry>())
                override suspend fun load(id: String) = RestoredCameraHistory(stored, null, emptyList(), CaptureAspectRatio.THREE_FOUR, false, "")
                override suspend fun save(capture: HistoryCapture, candidate: GeneratedCandidate, title: String, modelName: String) {
                    assertEquals("retry-flow", capture.generated.historyId)
                    if (++saves == 1) throw java.io.IOException("local disk fixture")
                }
                override suspend fun pin(id: String, pinned: Boolean) = Unit
                override suspend fun select(id: String, candidateId: String) = Unit
                override suspend fun styles(id: String, keys: Set<String>) = Unit
                override suspend fun delete(id: String) { deleted = id }
                override suspend fun flush() = Unit
            }
            val vm = viewModel(history = history, generated = GeneratedReferenceGenerator { _, _ -> requests++; old.assets!! })
            try {
                vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.QWEN, qwenApiKey = "fixture-key")))
                withTimeout(5000) { vm.state.first { it.settings.mode == ModelMode.CLOUD } }
                vm.onIntent(VlmIntent.RestoreHistory("retry-flow"))
                withTimeout(5000) { vm.state.first { !it.historyLoading && it.historyAspectRatio != null } }
                vm.onIntent(VlmIntent.Generate)
                val failed = withTimeout(5000) { vm.state.first { it.historySaveFailed && !it.generated.running } }
                assertEquals(2, failed.generated.candidates.count { it.ready })
                assertEquals(1, requests)
                vm.onIntent(VlmIntent.HistoryDrawerVisible(true))
                vm.onIntent(VlmIntent.RetryHistorySave)
                withTimeout(5000) { vm.state.first { saves == 2 && !it.historySaving && !it.historySaveFailed } }
                assertEquals(1, requests)
                vm.onIntent(VlmIntent.DeleteHistoryRequested("retry-flow"))
                vm.onIntent(VlmIntent.ConfirmDeleteHistory)
                val removed = withTimeout(5000) { vm.state.first { deleted != null && !it.historyLoading } }
                assertEquals("retry-flow", deleted)
                assertEquals(VlmStage.LIVE, removed.stage)
                assertTrue(removed.generated.candidates.isEmpty())
                assertNull(removed.scene)
                assertNull(removed.generated.applied)
                assertNull(removed.historyAspectRatio)
            } finally { vm.onLeave(); asset.delete() }
        }
    }

    @Test fun restoredHistoryUsesCurrentModelKeepsOriginalRatioAndAppendsWithoutAutomaticRequests() = runBlocking {
        withContext(Dispatchers.Main) {
            val asset = File.createTempFile("history-reference", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            val scene = SceneSnapshot("history-scene", 60, 80, 1f, asset.path, asset.path,
                PhotoViewBinding("scan", "frame", 1))
            val style = com.framer.sense.feature.camera.vlm.agent.GeneratedPortraitStylePolicy.choice(17)
            val old = GeneratedCandidate("old-result", "old-batch", scene, HistoricalImageModel(ProviderId.SEED, "retired-seed"),
                "last successful instruction", 0, scene.captureBinding, status = GeneratedCandidateStatus.READY,
                assets = GeneratedReferenceAssets(asset.path, asset.path, 60, 80, "retired-seed", 1), styleChoice = style)
            val stored = GeneratedReferenceState(candidates = listOf(old), selectedId = old.id, historyId = "stored-flow", usedStyleKeys = setOf(style.key))
            val loaded = RestoredCameraHistory(stored, null, emptyList(), CaptureAspectRatio.THREE_FOUR, false, old.instruction)
            var reads = 0
            val writes = kotlinx.coroutines.channels.Channel<HistoryCapture>(2)
            val history = object : CameraCompositionHistory {
                override val entries = kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.framer.sense.core.data.CameraHistoryEntry>())
                override suspend fun load(id: String): RestoredCameraHistory { reads++; return loaded }
                override suspend fun save(capture: HistoryCapture, candidate: GeneratedCandidate, title: String, modelName: String) { writes.send(capture) }
                override suspend fun pin(id: String, pinned: Boolean) = Unit
                override suspend fun select(id: String, candidateId: String) = Unit
                override suspend fun styles(id: String, keys: Set<String>) = Unit
                override suspend fun delete(id: String) = Unit
                override suspend fun flush() = Unit
            }
            val entered = kotlinx.coroutines.channels.Channel<GeneratedCandidate>(2)
            val finish = CompletableDeferred<Unit>()
            val vm = viewModel(history = history, generated = GeneratedReferenceGenerator { candidate, _ ->
                entered.send(candidate); finish.await(); old.assets!!
            })
            try {
                vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.QWEN, qwenApiKey = "fixture-key")))
                withTimeout(5000) { vm.state.first { it.settings.mode == ModelMode.CLOUD } }
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.AspectRatioSelected(CaptureAspectRatio.NINE_SIXTEEN))
                vm.onIntent(VlmIntent.AvatarSelected(AvatarId.ADULT_MALE))
                vm.onIntent(VlmIntent.PreferredEthnicity(AvatarEthnicity.ASIAN))
                vm.onIntent(VlmIntent.RestoreHistory("stored-flow"))
                val restored = withTimeout(5000) { vm.state.first { !it.historyLoading && it.historyAspectRatio != null } }
                assertEquals(VlmStage.READY, restored.stage)
                assertEquals("stored-flow", restored.generated.historyId)
                assertEquals(old.instruction, restored.instruction)
                assertEquals(CaptureAspectRatio.NINE_SIXTEEN, restored.photoPreferences.aspectRatio)
                assertEquals(CaptureAspectRatio.THREE_FOUR, restored.captureAspectRatio)
                assertEquals(ProviderId.QWEN, restored.settings.preferred)
                assertEquals(AvatarId.ADULT_MALE, restored.selectedAvatar)
                assertNull(restored.generated.applied)
                assertTrue(entered.tryReceive().isFailure)
                vm.onIntent(VlmIntent.Apply)
                assertEquals(BackgroundStatus.SEARCHING, vm.state.value.background.status)
                vm.onIntent(VlmIntent.ConfirmBackground)
                vm.onIntent(VlmIntent.Generate)
                val next = withTimeout(5000) { entered.receive() }
                assertEquals(ProviderId.QWEN, next.model.provider)
                assertEquals(AvatarId.ADULT_MALE, next.portraitPreferences.avatar)
                assertNotEquals(style.key, next.styleChoice!!.key)
                vm.onIntent(VlmIntent.RestoreHistory("another-flow"))
                assertEquals(1, reads)
                finish.complete(Unit)
                assertEquals("stored-flow", withTimeout(5000) { writes.receive() }.generated.historyId)
                withTimeout(5000) { vm.state.first { !it.generated.running } }
                assertEquals(2, vm.state.value.generated.candidates.size)
                vm.onIntent(VlmIntent.SaveSettings(vm.state.value.settings.copy(mode = ModelMode.OFFLINE)))
                withTimeout(5000) { vm.state.first { it.settings.mode == ModelMode.OFFLINE } }
                assertTrue(vm.state.value.usesGeneratedReference)
                vm.onIntent(VlmIntent.Generate)
                assertTrue(entered.tryReceive().isFailure)
                vm.onIntent(VlmIntent.RestartFlow)
                assertNull(vm.state.value.historyAspectRatio)
                assertEquals(CaptureAspectRatio.NINE_SIXTEEN, vm.state.value.captureAspectRatio)
                assertFalse(vm.state.value.usesGeneratedReference)
            } finally { finish.complete(Unit); vm.onLeave(); asset.delete() }
        }
    }

    @Test fun generatedReferenceSubmitsOneRequestAndBlocksRepeatedSubmission() = exerciseGeneratedReference(ModelMode.CLOUD)

    @Test fun automaticCloudReferenceSurvivesNetworkLossUntilRestart() = exerciseGeneratedReference(ModelMode.AUTO)

    @Test fun qwenReferenceUsesSameSingleCandidateApplyAndAlignmentFlow() = exerciseGeneratedReference(ModelMode.CLOUD, ProviderId.QWEN)

    private fun exerciseGeneratedReference(mode: ModelMode, brand: ProviderId = ProviderId.SEED) = runBlocking {
        withContext(Dispatchers.Main) {
            var online = true
            val entered = kotlinx.coroutines.channels.Channel<GeneratedCandidate>(2)
            val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
            val asset = File.createTempFile("generated-reference", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            val vm = viewModel(networkAvailable = { online }, generated = GeneratedReferenceGenerator { candidate, settings ->
                assertEquals(brand, settings.preferred)
                assertEquals(brand, candidate.model.provider)
                assertEquals(if (brand == ProviderId.QWEN) "qwen-key" else "fixture-key", settings.imageGenerationKey)
                assertEquals(GeneratedPortraitPreferences(AvatarId.CHILD_BOY, AvatarEthnicity.ASIAN), candidate.portraitPreferences)
                val gate = CompletableDeferred<Unit>()
                gates[candidate.id] = gate
                entered.send(candidate)
                gate.await()
                GeneratedReferenceAssets(asset.path, asset.path, 60, 80, "fake-seedream", 2)
            })
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = mode, preferred = brand, seedApiKey = "fixture-key", qwenApiKey = "qwen-key")))
                withTimeout(5_000) { vm.state.first { it.usesGeneratedReference } }
                vm.onIntent(VlmIntent.AvatarSelected(AvatarId.CHILD_BOY))
                vm.onIntent(VlmIntent.PreferredEthnicity(AvatarEthnicity.ASIAN))
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                val token = (effects.single() as VlmEffect.Freeze).token
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                val firstRequest = withTimeout(5_000) { entered.receive() }
                assertEquals(setOf(firstRequest.styleIndex), vm.state.value.generated.usedStyleIndices)
                vm.onIntent(VlmIntent.Generate)
                vm.onIntent(VlmIntent.RetryGenerated(firstRequest.id))
                yield()
                assertEquals(1, vm.state.value.generated.candidates.size)
                assertTrue(entered.tryReceive().isFailure)
                gates.getValue(firstRequest.id).complete(Unit)
                withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertEquals(firstRequest.id, vm.state.value.generated.selectedId)
                assertFalse(vm.state.value.generated.running)
                vm.onIntent(VlmIntent.Apply)
                vm.onIntent(VlmIntent.ConfirmBackground)
                val applied = vm.state.value
                assertEquals(VlmStage.LIVE, applied.stage)
                assertTrue(applied.canCapture)
                assertEquals(BackgroundStatus.ALIGNED_MANUAL, applied.background.status)
                vm.onIntent(VlmIntent.Generate)
                val nextRequest = withTimeout(5_000) { entered.receive() }
                assertNotEquals(firstRequest.styleIndex, nextRequest.styleIndex)
                assertEquals(setOf(firstRequest.styleIndex, nextRequest.styleIndex), vm.state.value.generated.usedStyleIndices)
                vm.onIntent(VlmIntent.Generate)
                vm.onIntent(VlmIntent.RetryGenerated(firstRequest.id))
                yield()
                assertEquals(2, vm.state.value.generated.candidates.size)
                assertTrue(entered.tryReceive().isFailure)
                assertEquals(applied.generated.applied, vm.state.value.generated.applied)
                gates.getValue(nextRequest.id).complete(Unit)
                val complete = withTimeout(5_000) { vm.state.first { !it.generated.running } }
                assertEquals(listOf(firstRequest.id, nextRequest.id), complete.generated.ordered.map { it.id })
                assertEquals(applied.generated.applied, complete.generated.applied)
                assertEquals(applied.background, complete.background)
                assertEquals(applied.workflowKey, complete.workflowKey)
                assertEquals(VlmStage.LIVE, complete.stage)
                if (mode == ModelMode.AUTO) {
                    online = false
                    vm.onIntent(VlmIntent.InstructionEditorVisible(true))
                    vm.onIntent(VlmIntent.SeedreamModelSelected(SeedreamModel.PRO))
                    vm.onIntent(VlmIntent.InstructionEditorVisible(false))
                    withTimeout(5_000) { vm.state.first { it.settings.seedreamModel == SeedreamModel.PRO } }
                    assertTrue(vm.state.value.usesGeneratedReference)
                    assertEquals(applied.generated.applied, vm.state.value.generated.applied)
                    assertEquals(applied.background, vm.state.value.background)
                }
                vm.onIntent(VlmIntent.RestartFlow)
                assertTrue(vm.state.value.generated.candidates.isEmpty())
                assertTrue(vm.state.value.generated.usedStyleIndices.isEmpty())
                assertNull(vm.state.value.activeBackgroundTarget)
                assertEquals(mode == ModelMode.CLOUD, vm.state.value.usesGeneratedReference)
            } finally {
                gates.values.forEach { it.complete(Unit) }
                listener.cancel(); vm.onIntent(VlmIntent.RestartFlow); vm.onLeave(); asset.delete()
            }
        }
    }

    @Test fun automaticServiceFailureFallsBackAfterSingleCandidateFails() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = kotlinx.coroutines.channels.Channel<GeneratedCandidate>(2)
            val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
            val localCalled = CompletableDeferred<Unit>()
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    localCalled.complete(Unit)
                    throw ModelFailure("LOCAL_UNAVAILABLE", "fixture", false)
                }
            }, generated = GeneratedReferenceGenerator { candidate, _ ->
                val gate = CompletableDeferred<Unit>()
                gates[candidate.id] = gate
                entered.send(candidate)
                gate.await()
                throw GeneratedReferenceFailure("NETWORK")
            })
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.SEED, seedApiKey = "fixture-key")))
                withTimeout(5_000) { vm.state.first { it.settings.mode == ModelMode.AUTO } }
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                val token = (effects.single() as VlmEffect.Freeze).token
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                val one = withTimeout(5_000) { entered.receive() }
                assertEquals(1, vm.state.value.generated.candidates.size)
                assertTrue(entered.tryReceive().isFailure)
                assertFalse(localCalled.isCompleted)
                gates.getValue(one.id).complete(Unit)
                withTimeout(5_000) { localCalled.await() }
                assertEquals(ModelMode.AUTO, vm.state.value.settings.mode)
                assertFalse(vm.state.value.usesGeneratedReference)
                assertEquals(1, vm.state.value.generated.candidates.size)
            } finally {
                gates.values.forEach { it.complete(Unit) }
                listener.cancel(); vm.onIntent(VlmIntent.RestartFlow); vm.onLeave()
            }
        }
    }

    @Test fun automaticOfflineRouteSkipsImageGenerationAndRechecksOnRestart() = runBlocking {
        withContext(Dispatchers.Main) {
            var online = false
            val localCalled = CompletableDeferred<Unit>()
            val vm = viewModel(networkAvailable = { online }, provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    localCalled.complete(Unit)
                    throw ModelFailure("LOCAL_UNAVAILABLE", "fixture", false)
                }
            }, generated = GeneratedReferenceGenerator { _, _ -> error("离线不应调用生图") })
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.SEED, seedApiKey = "fixture-key")))
                withTimeout(5_000) { vm.state.first { it.settings.mode == ModelMode.AUTO } }
                assertFalse(vm.state.value.usesGeneratedReference)
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                val token = (effects.single() as VlmEffect.Freeze).token
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                withTimeout(5_000) { localCalled.await() }
                withTimeout(5_000) { vm.state.first { it.stage == VlmStage.FROZEN } }
                assertTrue(vm.state.value.generated.candidates.isEmpty())
                online = true
                vm.onIntent(VlmIntent.RestartFlow)
                assertTrue(vm.state.value.usesGeneratedReference)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun unsupportedBrandsNeverUseSeedreamOrOfflineEvenWhenSeedKeyExists() = runBlocking {
        withContext(Dispatchers.Main) {
            listOf(ModelMode.CLOUD, ModelMode.AUTO).forEach { mode ->
                var generatedCalls = 0
                var offlineCalls = 0
                val vm = viewModel(networkAvailable = { false }, provider = object : VisionModelProvider {
                    override suspend fun step(call: ModelCall): StepReply { offlineCalls++; error("不应转离线") }
                }, generated = GeneratedReferenceGenerator { _, _ -> generatedCalls++; error("不应调用 Seedream") })
                val effects = mutableListOf<VlmEffect>()
                val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
                try {
                    vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = mode, preferred = ProviderId.QWEN, seedApiKey = "fixture-key")))
                    withTimeout(5_000) { vm.state.first { it.settings.mode == mode } }
                    vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                    vm.onIntent(VlmIntent.StartComposition)
                    yield()
                    val token = (effects.single() as VlmEffect.Freeze).token
                    vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                    withTimeout(5_000) { vm.state.first { it.stage == VlmStage.FROZEN && it.error != null } }
                    assertEquals(0, generatedCalls)
                    assertEquals(0, offlineCalls)
                    assertTrue(vm.state.value.usesGeneratedReference)
                    assertTrue(vm.state.value.generated.candidates.isEmpty())
                    vm.onIntent(VlmIntent.InstructionEditorVisible(true))
                    vm.onIntent(VlmIntent.SeedreamModelSelected(SeedreamModel.PRO))
                    assertEquals(SeedreamModel.FLASH, vm.state.value.settings.seedreamModel)
                } finally { listener.cancel(); vm.onLeave() }
            }
        }
    }

    @Test fun switchingBrandCancelsOldGenerationAndDiscardsLateAssets() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = kotlinx.coroutines.channels.Channel<Unit>(2)
            val returned = kotlinx.coroutines.channels.Channel<Unit>(2)
            val gate = CompletableDeferred<Unit>()
            val vm = viewModel(generated = GeneratedReferenceGenerator { _, _ ->
                entered.send(Unit)
                withContext(NonCancellable) { gate.await() }
                returned.trySend(Unit)
                GeneratedReferenceAssets("unused-full", "unused-person", 60, 80, "fixture", 2)
            })
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.SEED, seedApiKey = "fixture-key")))
                withTimeout(5_000) { vm.state.first { it.settings.preferred == ProviderId.SEED } }
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                val token = (effects.single() as VlmEffect.Freeze).token
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                withTimeout(5_000) { entered.receive() }
                vm.onIntent(VlmIntent.SaveSettings(vm.state.value.settings.copy(preferred = ProviderId.QWEN)))
                withTimeout(5_000) { vm.state.first { it.settings.preferred == ProviderId.QWEN } }
                assertTrue(vm.state.value.generated.candidates.isEmpty())
                gate.complete(Unit)
                withTimeout(5_000) { returned.receive() }
                yield()
                assertTrue(vm.state.value.generated.candidates.isEmpty())
                assertNull(vm.state.value.generated.applied)
            } finally { gate.complete(Unit); listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun singleCompositionRequiresFreshBackgroundConfirmationAfterEveryRestart() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall) = reply(call.input)
            }, renderer = backgroundRenderer())
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            var previousBinding: PhotoViewBinding? = null
            try {
                var token = startGenerating(vm, effects)
                repeat(2) { round ->
                    vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888), 1f))
                    val ready = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                    val binding = requireNotNull(ready.scene!!.captureBinding)
                    assertEquals(ready.scene!!.id, binding.frameId)
                    assertNull(binding.arSessionId)
                    assertNotEquals(previousBinding, binding)
                    assertTrue(ready.result!!.plans.all { it.viewBinding == binding })
                    // 后续相机配置更新不能改变照片已绑定的来源，导致应用校验失败。
                    vm.onIntent(VlmIntent.CameraConfigured(10 + round))
                    vm.onIntent(VlmIntent.Apply)
                    val applied = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.LIVE && it.referencePlan != null } }
                    assertEquals(binding, applied.referencePlan!!.viewBinding)
                    assertEquals(BackgroundStatus.SEARCHING, applied.background.status)
                    assertTrue(applied.showBackgroundAlignmentControls)
                    assertEquals(ReferencePresentation.HIDDEN,
                        referencePresentation(applied.referencePlan, applied.background.status, applied.ar))
                    assertEquals(CameraStepStatus.RUNNING, applied.workflowProgress()[3])
                    vm.onIntent(VlmIntent.ConfirmBackground)
                    val confirmed = vm.state.value
                    assertEquals(BackgroundStatus.ALIGNED_MANUAL, confirmed.background.status)
                    assertEquals(ReferencePresentation.SCREEN,
                        referencePresentation(confirmed.referencePlan, confirmed.background.status, confirmed.ar))
                    previousBinding = binding
                    if (round == 0) {
                        vm.onIntent(VlmIntent.RestartFlow)
                        assertNull(vm.state.value.scene)
                        assertNull(vm.state.value.referencePlan)
                        vm.onIntent(VlmIntent.StartComposition)
                        yield()
                        token = effects.filterIsInstance<VlmEffect.Freeze>().last().token
                    }
                }
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun restartFlowClearsAllGeneratedAndAppliedStateWithoutStartingAnotherAction() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall) = reply(call.input)
            }, renderer = backgroundRenderer())
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                val token = startGenerating(vm, effects)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                val first = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                // 后续步骤不能从旧入口直接开始扫描或单张构图。
                val effectCount = effects.size
                vm.onIntent(VlmIntent.StartScan)
                vm.onIntent(VlmIntent.StartComposition)
                assertEquals(first.scene, vm.state.value.scene)
                assertEquals(VlmStage.READY, vm.state.value.stage)
                assertEquals(effectCount, effects.size)
                vm.onIntent(VlmIntent.Generate)
                withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY && it.compositionChoices.size == 6 } }
                vm.onIntent(VlmIntent.Apply)
                val applied = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.LIVE && it.referencePlan != null } }
                vm.onIntent(VlmIntent.StartComposition)
                assertEquals(applied.scene, vm.state.value.scene)
                assertNotNull(vm.state.value.referencePlan)
                vm.onIntent(VlmIntent.RestartFlow)
                yield()
                val initial = vm.state.value
                assertEquals(VlmStage.LIVE, initial.stage)
                assertTrue(initial.cameraReady)
                assertEquals(applied.cameraEpoch, initial.cameraEpoch)
                assertEquals(applied.settings, initial.settings)
                assertEquals(applied.photoPreferences, initial.photoPreferences)
                assertNull(initial.scanSession); assertNull(initial.scanSnapshot); assertNull(initial.scene)
                assertNull(initial.result); assertNull(initial.selectedId); assertNull(initial.selectedFrameId)
                assertNull(initial.reference); assertNull(initial.referencePlan); assertNull(initial.facePreview)
                assertNull(initial.diagnostics); assertNull(initial.directorProgress); assertNull(initial.capturedWorkflow)
                assertTrue(initial.compositionCache.isEmpty()); assertTrue(initial.previews.isEmpty())
                assertTrue(initial.viewCandidates.isEmpty()); assertTrue(initial.availableActions.isEmpty())
                assertTrue(initial.availableExpressions.isEmpty())
                assertEquals(ScanProgress(), initial.scanProgress)
                assertEquals(BackgroundGuidance(), initial.background)
                assertFalse(initial.compositionDetailsVisible); assertFalse(initial.compositionDetailsExpanded)
                assertEquals(List(5) { CameraStepStatus.PENDING }, initial.workflowProgress())
                assertEquals(effectCount, effects.size)
                vm.onIntent(VlmIntent.PlatformFailed(token, "旧冻结错误"))
                assertNull(vm.state.value.error)
                vm.onIntent(VlmIntent.RestartFlow)
                assertNull(vm.state.value.scanSession)
                vm.onIntent(VlmIntent.StartScan)
                withTimeout(5_000) { vm.state.first { it.scanSession != null && it.stage == VlmStage.SCANNING } }
            } finally { listener.cancel(); vm.onIntent(VlmIntent.CancelScan); vm.onLeave() }
        }
    }

    @Test fun restartDuringGenerationDiscardsLateResultsAndWaitsForAnExplicitNewScan() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    entered.complete(Unit)
                    try { withContext(NonCancellable) { release.await() }; return reply(call.input) }
                    finally { finished.complete(Unit) }
                }
            }, renderer = backgroundRenderer())
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                val token = startGenerating(vm, effects)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                withTimeout(5_000) { entered.await() }
                vm.onIntent(VlmIntent.RestartFlow)
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertNull(vm.state.value.scanSession)
                assertNull(vm.state.value.scene)
                assertNull(vm.state.value.directorProgress)
                release.complete(Unit)
                withTimeout(5_000) { finished.await() }
                yield()
                assertNull(vm.state.value.result)
                assertTrue(vm.state.value.compositionCache.isEmpty())
                assertEquals(List(5) { CameraStepStatus.PENDING }, vm.state.value.workflowProgress())
                assertEquals(1, effects.filterIsInstance<VlmEffect.Freeze>().size)
                assertTrue(effects.none { it is VlmEffect.Capture })
                vm.onIntent(VlmIntent.StartScan)
                withTimeout(5_000) { vm.state.first { it.scanSession != null && it.stage == VlmStage.SCANNING } }
            } finally { release.complete(Unit); listener.cancel(); vm.onIntent(VlmIntent.CancelScan); vm.onLeave() }
        }
    }

    @Test fun continuingGenerationAppendsBatchesAndFailureOrCancellationDoesNotConsumeNumbers() = runBlocking {
        withContext(Dispatchers.Main) {
            var calls = 0
            var failNext = false
            var holdNext = false
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++
                    if (failNext) { failNext = false; throw ModelFailure("TEST_FAILURE", "测试失败", false) }
                    if (holdNext) {
                        holdNext = false
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                    val decision = DirectorDecision(ActionKind.FINAL, plans().map {
                        it.copy(title = "${it.title}-$calls", viewBinding = call.input.viewBinding)
                    }, imageId = call.input.scene.id)
                    return StepReply("fake", VlmJson.encodeToString(decision), decision)
                }
            }, renderer = backgroundRenderer())
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                val token = startGenerating(vm, effects)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                val first = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertFalse(first.compositionDetailsExpanded)
                vm.onIntent(VlmIntent.CompositionDetailsExpanded(true))
                assertTrue(vm.state.value.compositionDetailsExpanded)
                vm.onIntent(VlmIntent.Resize(.4f))
                val edited = withTimeout(5_000) { vm.state.first { it.previews["p1"]?.appliedPlan?.revision == 1 } }
                val oldPreview = edited.previews.getValue("p1")
                vm.onIntent(VlmIntent.Generate)
                val second = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertFalse(second.compositionDetailsExpanded)
                assertNotEquals(first.compositionBatchId, second.compositionBatchId)
                assertEquals(6, second.compositionChoices.size)
                assertEquals((0..5).toList(), second.compositionChoices.filterIsInstance<CompositionChoice.Plan>().map { it.index })
                assertSame(oldPreview, second.currentBackgroundComposition()!!.allBatches[0].previews["p1"])
                vm.onIntent(VlmIntent.Select("p1", first.scene!!.id, first.compositionBatchId))
                assertEquals(first.compositionBatchId, vm.state.value.compositionBatchId)
                assertSame(oldPreview, vm.state.value.previews["p1"])
                assertEquals(2, calls)
                // 从较早一轮继续生成也应追加到最新一轮之后。
                vm.onIntent(VlmIntent.Generate)
                val third = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                val batchIds = third.currentBackgroundComposition()!!.allBatches.map { it.id }
                assertEquals(listOf(first.compositionBatchId, second.compositionBatchId, third.compositionBatchId), batchIds)
                assertEquals(9, third.compositionChoices.size)
                failNext = true
                vm.onIntent(VlmIntent.Generate)
                val failed = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY && it.error != null } }
                assertEquals(batchIds, failed.currentBackgroundComposition()!!.allBatches.map { it.id })
                assertEquals(9, failed.compositionChoices.size)
                holdNext = true
                vm.onIntent(VlmIntent.Generate)
                withTimeout(5_000) { entered.await() }
                vm.onIntent(VlmIntent.Cancel)
                release.complete(Unit)
                assertEquals(9, vm.state.value.compositionChoices.size)
                vm.onIntent(VlmIntent.Generate)
                val fourth = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertEquals(12, fourth.compositionChoices.size)
                assertEquals((0..11).toList(), fourth.compositionChoices.filterIsInstance<CompositionChoice.Plan>().map { it.index })
                assertEquals(12, fourth.compositionChoices.map { it.key }.distinct().size)
                assertEquals(3, fourth.result!!.plans.size)
                assertEquals(6, calls)
                vm.onIntent(VlmIntent.Select("p1", first.scene!!.id, first.compositionBatchId))
                assertEquals(edited.result, vm.state.value.result)
                assertSame(oldPreview, vm.state.value.previews["p1"])
                vm.onIntent(VlmIntent.Apply)
                val applied = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.LIVE } }
                assertEquals(oldPreview.appliedPlan, applied.referencePlan)
                assertEquals(first.compositionBatchId, applied.compositionBatchId)
                assertEquals(12, applied.compositionChoices.size)
                assertEquals(6, calls)
            } finally { release.complete(Unit); listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun backgroundsGenerateOnDemandAndRestoreEditsPreviewsAndTheCorrectCaptureSource() = runBlocking {
        withContext(Dispatchers.Main) {
            val inputs = mutableListOf<DirectorInput>()
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    inputs += call.input
                    val decision = DirectorDecision(ActionKind.FINAL,
                        plans().map { it.copy(viewBinding = call.input.viewBinding) }, imageId = call.input.scene.id)
                    return StepReply("fake", VlmJson.encodeToString(decision), decision)
                }
            }, renderer = backgroundRenderer())
            try {
                val frames = cachedScanFrames(vm)
                assertTrue(inputs.isEmpty())
                vm.onIntent(VlmIntent.ScanViewSelected(frames[0].id))
                val first = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                val firstPreview = first.previews.getValue("p1")
                assertEquals(1, inputs.size)
                assertEquals(1, first.compositionCache.size)
                // 未生成背景只预览，不调用模型；切换拍摄方向的原图也不能混入旧人像。
                listOf(1, 2, 1).forEach { index ->
                    vm.onIntent(VlmIntent.PreviewScanView(frames[index].id))
                    val preview = vm.state.value
                    assertEquals(VlmStage.FROZEN, preview.stage)
                    assertEquals(frames[index].scene, preview.displayScene)
                    assertEquals(frames[index].id, preview.selectedFrameId)
                    assertNull(preview.result)
                    assertNull(preview.referencePlan)
                    assertTrue(preview.previews.isEmpty())
                    assertEquals(first.result, preview.compositionCache.getValue(frames[0].scene.id).result)
                    assertEquals(1, inputs.size)
                }
                vm.onIntent(VlmIntent.Select("p2", frames[0].scene.id))
                assertEquals(VlmStage.READY, vm.state.value.stage)
                assertEquals("p2", vm.state.value.selectedId)
                assertEquals(first.scene, vm.state.value.displayScene)
                assertSame(firstPreview, vm.state.value.previews["p1"])
                assertEquals(1, inputs.size)
                vm.onIntent(VlmIntent.PreviewScanView(frames[1].id))
                vm.onIntent(VlmIntent.ScanViewSelected(frames[1].id))
                val second = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertEquals(2, inputs.size)
                assertSame(firstPreview, second.compositionCache.getValue(frames[0].scene.id).previews["p1"])
                // 滑动人物大小后立刻切走，尚未生成的预览在恢复时补齐，调整不能丢。
                vm.onIntent(VlmIntent.Resize(.4f))
                val changed = vm.state.value.result!!.plans.first()
                assertFalse(vm.state.value.previews.containsKey("p1"))
                vm.onIntent(VlmIntent.ScanViewSelected(frames[2].id))
                val third = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertEquals(3, inputs.size)
                assertEquals(9, third.compositionBackgrounds.sumOf { it.result.plans.size })
                assertEquals(3, third.result!!.plans.size)
                vm.onIntent(VlmIntent.Select("p1", frames[1].scene.id))
                val restored = withTimeout(5_000) { vm.state.first {
                    it.scene?.id == frames[1].scene.id && it.previews["p1"]?.appliedPlan?.revision == changed.revision
                } }
                assertEquals(changed.revision, restored.result!!.plans.first().revision)
                assertEquals(.4f, restored.result!!.plans.first().avatar.height, .001f)
                assertEquals(frames[1].scene.orientation, restored.scene!!.orientation)
                assertEquals(3, inputs.size)
                vm.onIntent(VlmIntent.ScanViewSelected(frames[0].id))
                assertSame(firstPreview, vm.state.value.previews["p1"])
                vm.onIntent(VlmIntent.Select("p3", frames[2].scene.id))
                vm.onIntent(VlmIntent.Apply)
                val applied = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.LIVE } }
                assertEquals(frames[2].scene.id, applied.scene!!.id)
                assertEquals(frames[2].scene.orientation, applied.scene!!.orientation)
                assertEquals(frames[2].id, applied.referencePlan!!.viewBinding!!.frameId)
                assertEquals("p3", applied.referencePlan!!.id)
                assertEquals(3, inputs.size)
                frames.zip(inputs).forEach { (frame, input) ->
                    assertEquals(frame.scene, input.scene)
                    assertEquals(frame.id, input.viewBinding!!.frameId)
                }
                vm.onIntent(VlmIntent.RestartFlow)
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertNull(vm.state.value.scanSession)
                assertTrue(vm.state.value.compositionCache.isEmpty())
                assertNull(vm.state.value.result)
            } finally { vm.onIntent(VlmIntent.CancelScan); vm.onLeave() }
        }
    }

    @Test fun failedAndCanceledBackgroundGenerationNeverRemovesEarlierCompositions() = runBlocking {
        withContext(Dispatchers.Main) {
            var calls = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    calls++
                    if (calls == 2) throw ModelFailure("TEST_FAILURE", "测试生成失败", false)
                    if (calls == 3) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                    val decision = DirectorDecision(ActionKind.FINAL,
                        plans().map { it.copy(viewBinding = call.input.viewBinding) }, imageId = call.input.scene.id)
                    return StepReply("fake", VlmJson.encodeToString(decision), decision)
                }
            }, renderer = backgroundRenderer())
            try {
                val frames = cachedScanFrames(vm)
                vm.onIntent(VlmIntent.ScanViewSelected(frames[0].id))
                val first = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                vm.onIntent(VlmIntent.ScanViewSelected(frames[1].id))
                val failed = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.FROZEN && it.error != null } }
                assertTrue(failed.hasCompositionChoices)
                assertEquals(first.result, failed.compositionCache.getValue(frames[0].scene.id).result)
                vm.onIntent(VlmIntent.Select("p2", frames[0].scene.id))
                assertEquals(first.result, vm.state.value.result)
                assertEquals("p2", vm.state.value.selectedId)
                assertEquals(2, calls)
                vm.onIntent(VlmIntent.ScanViewSelected(frames[2].id))
                withTimeout(5_000) { entered.await() }
                vm.onIntent(VlmIntent.Cancel)
                assertEquals(VlmStage.FROZEN, vm.state.value.stage)
                vm.onIntent(VlmIntent.Select("p3", frames[0].scene.id))
                release.complete(Unit)
                yield()
                assertEquals(first.scene, vm.state.value.scene)
                assertEquals(first.result, vm.state.value.result)
                assertEquals("p3", vm.state.value.selectedId)
                assertEquals(1, vm.state.value.compositionBackgrounds.size)
                assertEquals(3, calls)
                vm.onIntent(VlmIntent.CancelScan)
                assertTrue(vm.state.value.compositionCache.isEmpty())
            } finally { release.complete(Unit); vm.onIntent(VlmIntent.CancelScan); vm.onLeave() }
        }
    }

    private fun backgroundRenderer() = object : AvatarRenderer {
        override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float) =
            AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(.4f, .5f, .6f, .92f),
                plan.avatar.copy(projectionCalibrated = true))
    }

    @Test fun manualScanPreviewWaitsForGenerationAndCannotExceedTheBackgroundCacheLimit() = runBlocking {
        withContext(Dispatchers.Main) {
            val inputs = mutableListOf<DirectorInput>()
            val vm = viewModel(provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    inputs += call.input
                    return reply(call.input)
                }
            }, renderer = backgroundRenderer())
            try {
                val frames = cachedScanFrames(vm, listOf(0, 1, 3, 2))
                assertTrue(vm.state.value.viewCandidates.isEmpty())
                assertNull(vm.state.value.scene)
                assertNull(vm.state.value.selectedFrameId)
                assertEquals(4, vm.state.value.compositionChoices.size)
                for (frame in frames.take(3)) {
                    val callsBefore = inputs.size
                    vm.onIntent(VlmIntent.PreviewScanView(frame.id))
                    assertEquals(VlmStage.FROZEN, vm.state.value.stage)
                    assertEquals(frame.scene, vm.state.value.displayScene)
                    assertNull(vm.state.value.result)
                    assertEquals(callsBefore, inputs.size)
                    vm.onIntent(VlmIntent.Generate)
                    val ready = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                    assertEquals(callsBefore + 1, inputs.size)
                    assertEquals(frame.id, ready.result!!.plans.first().viewBinding!!.frameId)
                }
                vm.onIntent(VlmIntent.PreviewScanView(frames[3].id))
                assertEquals(frames[3].scene, vm.state.value.displayScene)
                vm.onIntent(VlmIntent.Generate)
                assertEquals(VlmStage.FROZEN, vm.state.value.stage)
                assertNotNull(vm.state.value.error)
                assertEquals(3, inputs.size)
                assertEquals(3, vm.state.value.compositionCache.size)
                // 缓存满仍可选回已有构图，并为已有背景追加一批。
                vm.onIntent(VlmIntent.Select("p1", frames[0].scene.id))
                vm.onIntent(VlmIntent.Generate)
                val continued = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertEquals(4, inputs.size)
                assertEquals(2, continued.compositionCache.getValue(frames[0].scene.id).allBatches.size)
                assertEquals(3, continued.compositionCache.size)
            } finally { vm.onIntent(VlmIntent.CancelScan); vm.onLeave() }
        }
    }

    /** 保留各拍摄方向的真实扫描文件；后台结束扫描，避免调用真实的选景模型。 */
    private suspend fun cachedScanFrames(vm: VlmCameraViewModel, rotations: List<Int> = listOf(0, 1, 3)): List<ScanFrame> {
        vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
        vm.onIntent(VlmIntent.CameraConfigured(7))
        vm.onIntent(VlmIntent.StartScan)
        val scan = withTimeout(5_000) { vm.state.first { it.scanSession != null }.scanSession!! }
        rotations.forEachIndexed { index, rotation ->
            val direction = PhotoOrientation(rotation, 0)
            vm.onIntent(VlmIntent.PhotoOrientationChanged(direction))
            val buffer = ByteArray(64 * 48 * 3 / 2) { if (it < 64 * 48) 100 else 128.toByte() }
            vm.onIntent(VlmIntent.AnalysisReceived(com.framer.sense.feature.camera.vlm.scan.AnalysisSample(
                scan.id, 1, android.os.SystemClock.elapsedRealtimeNanos(),
                ScanCameraConfig(7, direction.uprightDegrees(90), 1f, CropRect(), false, orientation = direction),
                null, 64, 48, buffer)))
            withTimeout(10_000) { vm.state.first { it.scanProgress.frames == index + 1 } }
        }
        vm.onIntent(VlmIntent.CameraForeground(false))
        vm.onIntent(VlmIntent.StopScan)
        return withTimeout(10_000) { vm.state.first { it.stage == VlmStage.SCAN_REVIEW }.scanSnapshot!!.frames }
    }

    /** 回放真实 NV21 → ORB → 稳定确认 → MVI，不用人工确认冒充自动结果。 */
    @Test fun stableSinglePhotoCameraFramesAutomaticallyReachTheSameStateAsManualConfirmation() = runBlocking {
        withContext(Dispatchers.Main) {
            val provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    val decision = DirectorDecision(ActionKind.FINAL,
                        plans().map { it.copy(viewBinding = call.input.viewBinding) }, imageId = call.input.scene.id)
                    return StepReply("fake", VlmJson.encodeToString(decision), decision)
                }
            }
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float) =
                    AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(.4f, .5f, .6f, .92f),
                        plan.avatar.copy(projectionCalibrated = true))
            }
            val width = 480
            val height = 640
            val texture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(texture).apply { drawColor(android.graphics.Color.GRAY) }
            val random = kotlin.random.Random(237)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            repeat(600) {
                val gray = random.nextInt(30, 225)
                paint.color = android.graphics.Color.rgb(gray, gray, gray)
                canvas.drawCircle(random.nextFloat() * width, random.nextFloat() * height,
                    random.nextFloat() * 9f + 3f, paint)
            }
            val pixels = IntArray(width * height)
            texture.getPixels(pixels, 0, width, 0, 0, width, height)
            val nv21 = ByteArray(width * height * 3 / 2) { 128.toByte() }
            pixels.forEachIndexed { i, pixel -> nv21[i] = (16 + (pixel and 255) * 219 / 255).toByte() }
            val vm = viewModel(provider = provider, renderer = renderer)
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            var sampling: Job? = null
            try {
                val token = startGenerating(vm, effects)
                val config = ScanCameraConfig(7, 0, 1f, CropRect(), false)
                vm.onIntent(VlmIntent.FrameReady(token, texture, 1f))
                withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                vm.onIntent(VlmIntent.Apply)
                val applied = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.LIVE && it.reference != null } }
                val plan = requireNotNull(applied.referencePlan)
                assertNotNull(plan.viewBinding)
                assertNull(plan.viewBinding!!.arSessionId)
                assertEquals(ReferencePresentation.HIDDEN, referencePresentation(plan, applied.background.status, applied.ar))
                vm.onIntent(VlmIntent.CameraConfigured(7))
                vm.onIntent(VlmIntent.CameraForeground(true))
                val target = "guide:${plan.viewBinding!!.frameId}:${plan.id}:${plan.revision}"
                val sampleJob = launch {
                    while (isActive) {
                        val now = android.os.SystemClock.elapsedRealtimeNanos()
                        vm.onIntent(VlmIntent.AnalysisReceived(com.framer.sense.feature.camera.vlm.scan.AnalysisSample(
                            target, now, now, config, null, width, height, nv21)))
                        delay(200)
                    }
                }
                sampling = sampleJob
                val automatic = withTimeout(15_000) { vm.state.first { it.background.status == BackgroundStatus.ALIGNED_AUTO } }
                sampleJob.cancelAndJoin()
                assertTrue(automatic.background.matched)
                assertEquals(VlmStage.LIVE, automatic.stage)
                assertEquals(ReferencePresentation.SCREEN, referencePresentation(plan, automatic.background.status, automatic.ar))
                assertEquals(plan, automatic.reference!!.appliedPlan)
                vm.onIntent(VlmIntent.ConfirmBackground)
                val manual = vm.state.value
                assertEquals(BackgroundStatus.ALIGNED_MANUAL, manual.background.status)
                assertEquals(automatic.reference, manual.reference)
                assertEquals(automatic.referencePlan, manual.referencePlan)
                assertEquals(automatic.canCapture, manual.canCapture)
                assertEquals(referencePresentation(plan, automatic.background.status, automatic.ar),
                    referencePresentation(plan, manual.background.status, manual.ar))
            } finally {
                sampling?.cancelAndJoin()
                listener.cancel()
                vm.onLeave()
                if (!texture.isRecycled) texture.recycle()
            }
        }
    }

    @Test fun applyingRetakePlanShowsAlignmentAndHidesItWhenEditingReopens() = runBlocking {
        withContext(Dispatchers.Main) {
            val provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    val decision = DirectorDecision(ActionKind.FINAL,
                        plans().map { it.copy(needsRetake = true, viewBinding = call.input.viewBinding) }, imageId = call.input.scene.id)
                    return StepReply("fake", VlmJson.encodeToString(decision), decision)
                }
            }
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float) =
                    AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(.4f, .5f, .6f, .92f),
                        plan.avatar.copy(projectionCalibrated = true))
            }
            val vm = viewModel(provider = provider, renderer = renderer)
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                val token = startGenerating(vm, effects)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f,
                    arConfig = ScanCameraConfig(1, 0, 1f, CropRect(), false, arSessionId = "test-source")))
                withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertTrue(vm.state.value.compositionDetailsVisible)
                assertFalse(vm.state.value.compositionDetailsExpanded)
                val selected = vm.state.value.result
                vm.onIntent(VlmIntent.CompositionDetailsExpanded(true))
                assertTrue(vm.state.value.compositionDetailsExpanded)
                vm.onIntent(VlmIntent.CompositionDetailsExpanded(false))
                assertFalse(vm.state.value.compositionDetailsExpanded)
                assertSame(selected, vm.state.value.result)
                vm.onIntent(VlmIntent.Apply)
                assertFalse(vm.state.value.compositionDetailsVisible)
                val applied = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.LIVE } }
                assertTrue(applied.referencePlan!!.needsRetake)
                assertNotNull(applied.reference)
                assertNull(applied.notice)
                assertTrue(applied.showBackgroundAlignmentControls)
                vm.onIntent(VlmIntent.CompositionDetailsVisible(true))
                assertFalse(vm.state.value.showBackgroundAlignmentControls)
                vm.onIntent(VlmIntent.CompositionDetailsVisible(false))
                assertTrue(vm.state.value.showBackgroundAlignmentControls)
                vm.onIntent(VlmIntent.ConfirmBackground)
                assertEquals(BackgroundStatus.ALIGNED_MANUAL, vm.state.value.background.status)
                vm.onIntent(VlmIntent.Capture)
                yield()
                assertEquals(CameraStepStatus.RUNNING, vm.state.value.workflowProgress().last())
                vm.onIntent(VlmIntent.CaptureFinished((effects.last() as VlmEffect.Capture).token))
                assertEquals(CameraStepStatus.COMPLETE, vm.state.value.workflowProgress().last())
                vm.onIntent(VlmIntent.PhotoOrientationChanged(PhotoOrientation(1, 0)))
                assertEquals(CameraStepStatus.RUNNING, vm.state.value.workflowProgress()[3])
                assertEquals(CameraStepStatus.PENDING, vm.state.value.workflowProgress().last())
                assertNull(vm.state.value.capturedWorkflow)
                vm.onIntent(VlmIntent.ConfirmBackground)
                assertEquals(CameraStepStatus.PENDING, vm.state.value.workflowProgress().last())
                vm.onIntent(VlmIntent.Capture)
                yield()
                // 保存尚未完成时又失去对齐，迟到的成功不能点亮后续节点。
                vm.onIntent(VlmIntent.PhotoOrientationChanged(PhotoOrientation(2, 0)))
                vm.onIntent(VlmIntent.CaptureFinished((effects.last() as VlmEffect.Capture).token))
                assertNull(vm.state.value.capturedWorkflow)
                assertEquals(CameraStepStatus.PENDING, vm.state.value.workflowProgress().last())
                vm.onIntent(VlmIntent.ConfirmBackground)
                vm.onIntent(VlmIntent.Capture)
                yield()
                vm.onIntent(VlmIntent.CaptureFinished((effects.last() as VlmEffect.Capture).token))
                assertEquals(CameraStepStatus.COMPLETE, vm.state.value.workflowProgress().last())
                vm.onIntent(VlmIntent.CompositionDetailsVisible(true))
                assertEquals(listOf(CameraStepStatus.COMPLETE, CameraStepStatus.COMPLETE,
                    CameraStepStatus.PENDING, CameraStepStatus.PENDING, CameraStepStatus.PENDING),
                    vm.state.value.workflowProgress())
                assertNull(vm.state.value.capturedWorkflow)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun turningDuringGenerationPreservesCapturedOrientationAndRenderSize() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val captured = PhotoOrientation(1, 0)
            val sizes = mutableListOf<Pair<Int, Int>>()
            val provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    assertEquals(captured, call.input.scene.orientation)
                    assertEquals(80, call.input.scene.width); assertEquals(60, call.input.scene.height)
                    entered.complete(Unit); released.await()
                    return reply(call.input)
                }
            }
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview {
                    sizes += width to height
                    return AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
                        CropRect(.4f, .5f, .6f, .92f), plan.avatar.copy(projectionCalibrated = true))
                }
            }
            val vm = viewModel(provider = provider, renderer = renderer)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                vm.onIntent(VlmIntent.PhotoOrientationChanged(captured))
                val token = startGenerating(vm, events)
                val epoch = vm.state.value.cameraEpoch
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888), 1f,
                    orientation = captured))
                withTimeout(5_000) { entered.await() }
                listOf(2, 3, 0).forEach { vm.onIntent(VlmIntent.PhotoOrientationChanged(PhotoOrientation(it, 0))) }
                assertEquals(VlmStage.GENERATING, vm.state.value.stage)
                assertEquals(captured, vm.state.value.scene!!.orientation)
                assertEquals(epoch, vm.state.value.cameraEpoch)
                released.complete(Unit)
                val ready = withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertTrue(sizes.isNotEmpty()); assertTrue(sizes.all { it == (80 to 60) })
                assertTrue(ready.previews.isNotEmpty())
                assertTrue(ready.previews.values.all { it.avatar.width == 80 && it.avatar.height == 60 })
                assertEquals(captured, ready.scene!!.orientation)
                assertEquals(1, events.filterIsInstance<VlmEffect.Freeze>().size)
            } finally { released.complete(Unit); listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun mixedOrientationScanDoesNotRestartOrDiscardEarlierFrames() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.CameraConfigured(7)); vm.onIntent(VlmIntent.StartScan)
                val started = withTimeout(5_000) { vm.state.first { it.scanSession != null } }
                val scan = started.scanSession!!
                listOf(0, 1, 3, 2).forEachIndexed { index, rotation ->
                    val direction = PhotoOrientation(rotation, 0)
                    vm.onIntent(VlmIntent.PhotoOrientationChanged(direction))
                    val buffer = ByteArray(64 * 48 * 3 / 2) { if (it < 64 * 48) 100 else 128.toByte() }
                    vm.onIntent(VlmIntent.AnalysisReceived(com.framer.sense.feature.camera.vlm.scan.AnalysisSample(
                        scan.id, 1, android.os.SystemClock.elapsedRealtimeNanos(),
                        ScanCameraConfig(7, direction.uprightDegrees(90), 1f, CropRect(), false, orientation = direction),
                        null, 64, 48, buffer)))
                    val current = withTimeout(10_000) { vm.state.first { it.scanProgress.frames == index + 1 } }
                    assertEquals(scan.id, current.scanSession!!.id)
                    assertEquals(started.cameraEpoch, current.cameraEpoch)
                    assertEquals(7, current.cameraConfigVersion)
                    assertEquals(VlmStage.SCANNING, current.stage)
                }
                // 后台中断沿用已有“保留扫描待人工分析”，避免测试访问真实选景模型。
                vm.onIntent(VlmIntent.CameraForeground(false)); vm.onIntent(VlmIntent.StopScan)
                val done = withTimeout(10_000) { vm.state.first { it.stage == VlmStage.SCAN_REVIEW } }
                assertEquals(listOf(0, 1, 3, 2), done.scanSnapshot!!.frames.map { it.scene.orientation.deviceRotation })
            } finally { vm.onIntent(VlmIntent.CancelScan); vm.onLeave() }
        }
    }

    @Test fun recommendationEditsAndHistorySelectionHaveDistinctRecordingRules() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            try {
                fun open() = vm.onIntent(VlmIntent.InstructionEditorVisible(true))
                fun confirm() = vm.onIntent(VlmIntent.SubmitInstruction)
                open()
                vm.onIntent(VlmIntent.InstructionRecommendationSelected("保留风景"))
                confirm()
                assertTrue(vm.state.value.instructionHistory.isEmpty())
                assertTrue(vm.state.value.photoPreferences.instructionFromRecommendation)
                open()
                confirm()
                assertTrue(vm.state.value.instructionHistory.isEmpty())
                open()
                vm.onIntent(VlmIntent.InstructionRecommendationSelected("保留风景"))
                vm.onIntent(VlmIntent.InstructionDraftChanged("保留风景，人物靠右"))
                confirm()
                assertEquals(listOf("保留风景，人物靠右"), vm.state.value.instructionHistory)
                assertFalse(vm.state.value.photoPreferences.instructionFromRecommendation)
                open()
                vm.onIntent(VlmIntent.InstructionDraftChanged("全身人像"))
                confirm()
                assertEquals(listOf("全身人像", "保留风景，人物靠右"), vm.state.value.instructionHistory)
                open()
                vm.onIntent(VlmIntent.InstructionHistorySelected("保留风景，人物靠右"))
                confirm()
                assertEquals(listOf("保留风景，人物靠右", "全身人像"), vm.state.value.instructionHistory)
                open()
                vm.onIntent(VlmIntent.DeleteInstructionHistory("保留风景，人物靠右"))
                assertEquals(listOf("全身人像"), vm.state.value.instructionHistory)
                vm.onIntent(VlmIntent.InstructionRecommendationSelected("居中构图"))
                vm.onIntent(VlmIntent.InstructionDraftChanged("居中构图！"))
                vm.onIntent(VlmIntent.InstructionDraftChanged("居中构图"))
                confirm()
                assertEquals(listOf("居中构图", "全身人像"), vm.state.value.instructionHistory)
            } finally { vm.onLeave() }
        }
    }

    @Test fun instructionDraftOnlyCommitsOnConfirmAndDoesNotRestartCamera() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 8f)))
                val original = vm.state.value.instruction
                val epoch = vm.state.value.cameraEpoch
                vm.onIntent(VlmIntent.InstructionEditorVisible(true))
                vm.onIntent(VlmIntent.InstructionDraftChanged("人物靠右，保留建筑"))
                assertEquals(original, vm.state.value.instruction)
                assertFalse(vm.state.value.canCapture)
                assertFalse(vm.state.value.canRestartScan)
                vm.onIntent(VlmIntent.StartComposition)
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                vm.onIntent(VlmIntent.InstructionEditorVisible(false))
                vm.onIntent(VlmIntent.InstructionEditorVisible(true))
                assertEquals(original, vm.state.value.instructionDraft)
                vm.onIntent(VlmIntent.InstructionDraftChanged("  人物靠右，保留建筑  "))
                vm.onIntent(VlmIntent.SubmitInstruction)
                assertEquals("人物靠右，保留建筑", vm.state.value.instruction)
                assertEquals(vm.state.value.instruction, vm.state.value.photoPreferences.instruction)
                assertFalse(vm.state.value.instructionEditorVisible)
                assertTrue(vm.state.value.canCapture)
                assertTrue(vm.state.value.canRestartScan)
                assertEquals(epoch, vm.state.value.cameraEpoch)
                vm.onIntent(VlmIntent.InstructionEditorVisible(true))
                assertEquals("人物靠右，保留建筑", vm.state.value.instructionDraft)
                vm.onIntent(VlmIntent.InstructionEditorVisible(false))
                vm.onIntent(VlmIntent.StartComposition)
                vm.onIntent(VlmIntent.InstructionEditorVisible(true))
                assertFalse(vm.state.value.instructionEditorVisible)
            } finally { vm.onLeave() }
        }
    }

    @Test fun cancellingSingleCompositionPreservesReadyCameraAndAllowsAnotherFreeze() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(.6f, 4f)))
                assertEquals(1f, vm.state.value.zoom, 0f)
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                assertEquals(VlmStage.FREEZING, vm.state.value.stage)
                vm.onIntent(VlmIntent.ResumeCamera)
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertTrue(vm.state.value.cameraReady)
                assertTrue(vm.state.value.canRestartScan)
                assertTrue(vm.state.value.canCapture)
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                assertEquals(VlmStage.FREEZING, vm.state.value.stage)
                assertEquals(2, events.filterIsInstance<VlmEffect.Freeze>().size)
                vm.onIntent(VlmIntent.Cancel)
                vm.onIntent(VlmIntent.CameraBindingStopped(vm.state.value.cameraEpoch))
                vm.onIntent(VlmIntent.ResumeCamera)
                assertFalse(vm.state.value.cameraReady)
                assertFalse(vm.state.value.canRestartScan)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun framingModeAndLateCallbacksAreIsolatedFromCurrentCamera() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(.6f, 10f)))
                vm.onIntent(VlmIntent.ZoomChanged(8f))
                assertEquals(8f, vm.state.value.zoom, 0f)
                val oldEpoch = vm.state.value.cameraEpoch
                vm.onIntent(VlmIntent.CameraModeSelected(CameraMode.AR))
                assertEquals(1f, vm.state.value.zoom, 0f)
                assertFalse(vm.state.value.canZoom)
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 10f), oldEpoch))
                vm.onIntent(VlmIntent.CameraConfigured(999, oldEpoch))
                assertEquals(0, vm.state.value.cameraConfigVersion)
                assertFalse(vm.state.value.cameraReady)
                vm.onIntent(VlmIntent.CameraModeSelected(CameraMode.NORMAL))
                assertEquals(8f, vm.state.value.zoom, 0f)
                val epoch = vm.state.value.cameraEpoch
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(.6f, 10f), epoch))
                vm.onIntent(VlmIntent.CameraBindingStopped(oldEpoch))
                assertTrue(vm.state.value.cameraReady)
                vm.onIntent(VlmIntent.ArUpdated(com.framer.sense.feature.camera.vlm.ar.ArUiState(
                    com.framer.sense.feature.camera.vlm.ar.ArSource.SHARED_CAMERA), oldEpoch))
                assertEquals(CameraMode.NORMAL, vm.state.value.cameraMode)
                vm.onIntent(VlmIntent.AspectRatioSelected(CaptureAspectRatio.NINE_SIXTEEN))
                assertEquals(CaptureAspectRatio.NINE_SIXTEEN, vm.state.value.photoPreferences.aspectRatio)
                assertFalse(vm.state.value.cameraReady)
            } finally { vm.onLeave() }
        }
    }

    @Test fun sampleDoesNotInvokeModelOrCaptureAndClosedResultsCannotReopenIt() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview {
                    entered.complete(Unit)
                    withContext(NonCancellable) { released.await() }
                    return AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(), plan.avatar, plan.resolvedSpec)
                }
            }
            val vm = viewModel(renderer = renderer)
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities()))
                vm.onIntent(VlmIntent.OpenPhotoSample)
                entered.await()
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertNull(vm.state.value.result)
                vm.onIntent(VlmIntent.Capture)
                vm.onIntent(VlmIntent.StartScan)
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                assertTrue(effects.isEmpty())
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                vm.onIntent(VlmIntent.ClosePhotoSample)
                released.complete(Unit)
                yield()
                assertNull(vm.state.value.photoSample)
                vm.onIntent(VlmIntent.OpenPhotoSample)
                withTimeout(5000) { vm.state.first { it.photoSample?.loading == false } }
                assertEquals(ExpressionId.SMILE, vm.state.value.photoSample!!.expression)
                vm.onIntent(VlmIntent.PhotoSampleAction(PhotoActionCatalog.HAND_HIP_ID))
                withTimeout(5000) { vm.state.first { it.photoSample?.loading == false } }
                assertEquals(PhotoActionCatalog.HAND_HIP_ID, vm.state.value.photoSample!!.actionId)
                vm.onIntent(VlmIntent.PhotoSampleExpression(ExpressionId.HAPPY))
                withTimeout(5000) { vm.state.first { it.photoSample?.loading == false } }
                assertEquals(ExpressionId.HAPPY, vm.state.value.photoSample!!.expression)
                vm.onIntent(VlmIntent.PhotoSampleEthnicity(AvatarEthnicity.ASIAN))
                withTimeout(5000) { vm.state.first { it.photoSample?.loading == false } }
                assertEquals(AvatarEthnicity.ASIAN, vm.state.value.photoSample!!.ethnicity)
                assertEquals(ExpressionId.HAPPY, vm.state.value.photoSample!!.expression)
                assertEquals(PhotoActionCatalog.HAND_HIP_ID, vm.state.value.photoSample!!.actionId)
                assertEquals(AvatarEthnicity.ASIAN, vm.state.value.selectedEthnicity)
                assertTrue(effects.isEmpty())
                vm.onIntent(VlmIntent.CameraForeground(false))
                assertNull(vm.state.value.photoSample)
            } finally {
                released.complete(Unit); listener.cancel(); vm.onLeave()
            }
        }
    }

    @Test fun galleryOpensDirectlyWithoutReadingPhotosAndIsBlockedWhileSaving() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            val effects = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { effects += it } }
            try {
                vm.onIntent(VlmIntent.OpenGallery)
                yield()
                assertEquals(listOf(VlmEffect.OpenGallery), effects)
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities()))
                vm.onIntent(VlmIntent.Capture)
                yield()
                vm.onIntent(VlmIntent.OpenGallery)
                yield()
                assertEquals(1, effects.count { it == VlmEffect.OpenGallery })
                val token = effects.filterIsInstance<VlmEffect.Capture>().single().token
                vm.onIntent(VlmIntent.CaptureFinished(token))
                vm.onIntent(VlmIntent.OpenGallery)
                yield()
                assertEquals(2, effects.count { it == VlmEffect.OpenGallery })
                vm.onIntent(VlmIntent.CameraForeground(false))
                vm.onIntent(VlmIntent.CameraForeground(true))
                yield()
                assertEquals(2, effects.count { it == VlmEffect.OpenGallery })
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

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
        generated: GeneratedReferenceGenerator? = null,
        renderer: AvatarRenderer = object : AvatarRenderer {
            /** 禁止访问 GPU。plan 为方案，width/height 为尺寸，warmth/brightness 为光照；均不执行。 */
            override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview = error("本测试不应渲染")
        },
        networkAvailable: () -> Boolean = { true },
        history: CameraCompositionHistory? = null
    ): VlmCameraViewModel {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            private val name = "vlm-session-${UUID.randomUUID()}"
            /** 返回测试模型目录；无参数。 */
            override fun getNoBackupFilesDir(): File = File(cacheDir, name).apply { mkdirs() }
            /** 隔离测试设置。@param name 原设置名；@param mode 私有存储模式。 */
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${this.name}-$name", mode)
        }
        val store = LocalModelStore(context)
        return VlmCameraViewModel(CompositionRepository(provider), SnapshotStore(context), PreviewStore(renderer), SettingsStore(context), store, MnnProvider(store), ModelDownloadRepository(context, store), saved, com.framer.sense.feature.camera.vlm.scan.PhotoSessionController(context), generatedRepository = generated,
            referenceNetwork = ReferenceNetworkState(networkAvailable), historyRepository = history)
    }

    /** 返回可通过基础和投影校验的三份方案。 */
    private fun plans(): List<CompositionPlan> = listOf(
        CompositionPlan("p1", "环境", ShotType.ENVIRONMENT, CropRect(), 1f, AvatarPlacement(height = .3f), "保留建筑", "环境为主"),
        CompositionPlan("p2", "全身", ShotType.FULL, CropRect(), 1f, AvatarPlacement(height = .65f), "自然站立", "全身完整"),
        CompositionPlan("p3", "半身", ShotType.HALF, CropRect(), 1f, AvatarPlacement(foot = Point2(.5f, 1.4f), height = 1.25f), "看向镜头", "突出表情")
    )

    /** 创建当前冻结画面对应的最终模型答复。 */
    private fun reply(input: DirectorInput): StepReply {
        val decision = DirectorDecision(ActionKind.FINAL,
            plans().map { it.copy(viewBinding = input.viewBinding) }, imageId = input.scene.id)
        return StepReply("fake", VlmJson.encodeToString(decision), decision)
    }

    /** 通过冻结事件进入自动生成流程，并返回当前冻结请求令牌。 */
    private suspend fun startGenerating(vm: VlmCameraViewModel, events: MutableList<VlmEffect>): String {
        vm.onIntent(VlmIntent.SaveSettings(ModelSettings(mode = ModelMode.OFFLINE)))
        withTimeout(5000) { vm.state.first { it.settings.mode == ModelMode.OFFLINE } }
        vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
        vm.onIntent(VlmIntent.StartComposition)
        yield()
        return (events.single() as VlmEffect.Freeze).token
    }

    /** 冻结期间重复开始切换令牌，拍摄不排队，旧帧和取消后的帧均丢弃。 */
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
                assertEquals(2, events.size)
                val first = (events.first() as VlmEffect.Freeze).token
                val token = (events.last() as VlmEffect.Freeze).token
                assertNotEquals(first, token)
                val outdated = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                vm.onIntent(VlmIntent.FrameReady(first, outdated, 1f))
                assertTrue(outdated.isRecycled)
                assertNull(vm.state.value.scene)
                vm.onIntent(VlmIntent.Cancel)
                val late = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                vm.onIntent(VlmIntent.FrameReady(token, late, 1f))
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                yield()
                assertTrue(late.isRecycled)
                assertNull(vm.state.value.scene)
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertEquals(2, events.size)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun regenerationCancelsOldFlowAndOnlyNewestResultCanComplete() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply {
                    if (++calls == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                    return reply(call.input)
                }
            }
            val vm = viewModel(provider = provider)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                val token = startGenerating(vm,events)
                vm.onIntent(VlmIntent.FrameReady(token,Bitmap.createBitmap(60,80,Bitmap.Config.ARGB_8888),1f))
                withTimeout(5_000) { entered.await() }
                vm.onIntent(VlmIntent.Generate)
                assertEquals(VlmStage.GENERATING,vm.state.value.stage)
                assertNull(vm.state.value.result)
                release.complete(Unit)
                withTimeout(5_000) { vm.state.first { it.stage == VlmStage.READY } }
                assertEquals(2,calls)
                assertEquals(3,vm.state.value.result!!.plans.size)
            } finally { release.complete(Unit); listener.cancel(); vm.onLeave() }
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
                    return reply(call.input)
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
                assertTrue(vm.state.value.saving)
                vm.onIntent(VlmIntent.CaptureFinished(first))
                assertNotNull(vm.state.value.capturedWorkflow)
                assertEquals(CameraStepStatus.PENDING, vm.state.value.workflowProgress().last())
                vm.onIntent(VlmIntent.Capture)
                yield()
                vm.onIntent(VlmIntent.CaptureFinished(first))
                assertTrue(vm.state.value.saving)
                assertNull(vm.state.value.capturedWorkflow)
                assertEquals(2, events.size)
                val second = (events.last() as VlmEffect.Capture).token
                vm.onIntent(VlmIntent.CaptureFinished(second, "save failed"))
                assertFalse(vm.state.value.saving)
                assertEquals(CameraStepStatus.PENDING, vm.state.value.workflowProgress().last())
                vm.onIntent(VlmIntent.Capture)
                yield()
                vm.onIntent(VlmIntent.CaptureFinished((events.last() as VlmEffect.Capture).token))
                assertNotNull(vm.state.value.capturedWorkflow)
                vm.onIntent(VlmIntent.StartComposition)
                assertNull(vm.state.value.capturedWorkflow)
                assertEquals(CameraStepStatus.PENDING, vm.state.value.workflowProgress().last())
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    /** 进程恢复只显示重拍提示，不恢复不存在的图片或旧推理；无参数。 */
    @Test fun restoredProcessReturnsToLiveCamera() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel(SavedStateHandle(mapOf("vlm_started" to true)))
            assertEquals(VlmStage.LIVE, vm.state.value.stage)
            assertNull(vm.state.value.scene)
            assertTrue(vm.state.value.notice.orEmpty().contains("重新扫描"))
            vm.onLeave()
        }
    }

    @Test fun resizeDiscardsOldPreviewAndApplyUsesCalibratedPlan() = runBlocking {
        withContext(Dispatchers.Main) {
            val provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall) = reply(call.input)
            }
            val pending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview {
                    if (plan.revision > 0 && !plan.avatar.projectionCalibrated) {
                        pending.complete(Unit)
                        // 模拟 GPU 不响应取消、旧 revision 仍会迟到返回。
                        withContext(NonCancellable) { release.await() }
                    }
                    val applied = if (plan.id == "p1" && !plan.avatar.projectionCalibrated)
                        plan.avatar.copy(height = plan.avatar.height * .8f, projectionCalibrated = true)
                    else plan.avatar.copy(projectionCalibrated = true)
                    return AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(.4f, .5f, .6f, .92f), applied)
                }
            }
            val vm = viewModel(provider = provider, renderer = renderer)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                val token = startGenerating(vm, events)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY } }
                vm.onIntent(VlmIntent.Select("p1"))
                vm.onIntent(VlmIntent.Resize(.4f))
                assertFalse(vm.state.value.previews.containsKey("p1"))
                withTimeout(5000) { pending.await() }
                vm.onIntent(VlmIntent.Resize(.5f))
                release.complete(Unit)
                withTimeout(5000) { vm.state.first { it.previews["p1"]?.appliedPlan?.revision == 2 } }
                val final = vm.state.value.result!!.plans.first()
                assertEquals(.4f, final.avatar.height, .0001f)
                assertEquals(final, vm.state.value.previews.getValue("p1").appliedPlan)
                vm.onIntent(VlmIntent.Apply)
                assertFalse(vm.state.value.compositionDetailsVisible)
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.LIVE } }
                assertEquals(final, vm.state.value.referencePlan)
                assertNotNull(vm.state.value.reference)
                assertNull(vm.state.value.notice)
                assertFalse(vm.state.value.compositionDetailsVisible)
                assertTrue(vm.state.value.showBackgroundAlignmentControls)
            } finally { release.complete(Unit); listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun manualActionExpressionAndFaceUseSameSpecWithoutAnotherModelCall() = runBlocking {
        withContext(Dispatchers.Main) {
            var calls = 0
            var failRender = false
            val provider = object : VisionModelProvider {
                override suspend fun step(call: ModelCall): StepReply { calls++; return reply(call.input) }
            }
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview {
                    check(!failRender) { "模拟 GPU 错误" }
                    return AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(.4f, .5f, .6f, .92f), plan.avatar.copy(projectionCalibrated = true))
                }
                override suspend fun renderFace(plan: CompositionPlan, size: Int) =
                    AvatarPreview(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888), CropRect(), plan.avatar, plan.resolvedSpec)
            }
            val vm = viewModel(provider = provider, renderer = renderer)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                val token = startGenerating(vm, events)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY } }
                val original = vm.state.value.result!!.plans
                vm.onIntent(VlmIntent.ActionSelected(PhotoActionCatalog.basicId(PoseId.WAVE)))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY && it.result!!.plans[0].revision == 1 } }
                vm.onIntent(VlmIntent.ExpressionSelected(ExpressionId.SMILE))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY && it.result!!.plans[0].revision == 2 } }
                vm.onIntent(VlmIntent.ExpressionIntensityChanged(.8f))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY && it.result!!.plans[0].revision == 3 } }
                vm.onIntent(VlmIntent.FaceVisible(true))
                withTimeout(5000) { vm.state.first { it.facePreview != null && !it.faceLoading } }
                val selected = vm.state.value.result!!.plans[0]
                assertEquals(PhotoActionCatalog.basicId(PoseId.WAVE), selected.resolvedSpec!!.action.id)
                assertEquals(ExpressionId.SMILE, selected.resolvedSpec.guidance.expression)
                assertEquals(.8f, selected.resolvedSpec.avatar.expressionIntensity, 0f)
                assertEquals(original.drop(1), vm.state.value.result!!.plans.drop(1))
                assertEquals(1, calls)
                vm.onIntent(VlmIntent.Resize(.4f))
                withTimeout(5000) { vm.state.first { it.previews["p1"]?.appliedPlan?.revision == 4 && it.facePreview != null } }
                val resized = vm.state.value.result!!.plans[0]
                assertEquals(selected.resolvedSpec.action, resized.resolvedSpec!!.action)
                assertTrue(resized.resolvedSpec.matches(resized))
                val previousPreview = vm.state.value.previews.getValue("p1")
                failRender = true
                vm.onIntent(VlmIntent.ActionSelected(PhotoActionCatalog.basicId(PoseId.HAND_HIP)))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY && it.error != null } }
                assertEquals(resized, vm.state.value.result!!.plans[0])
                assertSame(previousPreview, vm.state.value.previews.getValue("p1"))
                assertEquals(1, calls)
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    @Test fun lateFaceCannotReplaceNewlySelectedPlan() = runBlocking {
        withContext(Dispatchers.Main) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val renderer = object : AvatarRenderer {
                override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float) =
                    AvatarPreview(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), CropRect(), plan.avatar.copy(projectionCalibrated = true))
                override suspend fun renderFace(plan: CompositionPlan, size: Int): AvatarPreview {
                    if (plan.id == "p1") { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(if (plan.id == "p1") android.graphics.Color.RED else android.graphics.Color.BLUE)
                    return AvatarPreview(bitmap, CropRect(), plan.avatar, plan.resolvedSpec)
                }
            }
            val vm = viewModel(provider = object : VisionModelProvider { override suspend fun step(call: ModelCall) = reply(call.input) }, renderer = renderer)
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                val token = startGenerating(vm, events)
                vm.onIntent(VlmIntent.FrameReady(token, Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888), 1f))
                withTimeout(5000) { vm.state.first { it.stage == VlmStage.READY } }
                vm.onIntent(VlmIntent.FaceVisible(true))
                withTimeout(5000) { entered.await() }
                vm.onIntent(VlmIntent.Select("p2"))
                assertNull(vm.state.value.facePreview)
                release.complete(Unit)
                val current = withTimeout(5000) { vm.state.first { it.facePreview != null && !it.faceLoading } }
                assertEquals("p2", current.selectedId)
                assertEquals(android.graphics.Color.BLUE, current.facePreview!!.getPixel(0, 0))
            } finally { release.complete(Unit); listener.cancel(); vm.onLeave() }
        }
    }
    /** 扫描中重新开始回到等待状态，显式开始才建立新扫描，旧帧不能进入新窗口。 */
    @Test fun scanControlsGateOtherOperationsAndIgnoreLateFrames() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            val events = mutableListOf<VlmEffect>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { vm.effects.collect { events += it } }
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.CameraConfigured(1))
                vm.onIntent(VlmIntent.StartScan)
                val session = withTimeout(5000) { vm.state.first { it.scanSession != null }.scanSession!! }
                vm.onIntent(VlmIntent.StartScan)
                assertEquals(session.id, vm.state.value.scanSession!!.id)
                vm.onIntent(VlmIntent.RestartFlow)
                yield()
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertNull(vm.state.value.scanSession)
                assertEquals(List(5) { CameraStepStatus.PENDING }, vm.state.value.workflowProgress())
                assertTrue(events.isEmpty())
                vm.onIntent(VlmIntent.StartScan)
                val restarted = withTimeout(5000) { vm.state.first { it.scanSession != null && it.scanSession!!.id != session.id }.scanSession!! }
                vm.onIntent(VlmIntent.StartComposition)
                vm.onIntent(VlmIntent.Capture); vm.onIntent(VlmIntent.ZoomChanged(3f))
                assertEquals(restarted.id, vm.state.value.scanSession!!.id)
                assertEquals(1f, vm.state.value.zoom); assertTrue(events.isEmpty())
                vm.onIntent(VlmIntent.PlatformFailed("retired-freeze-token","迟到的旧冻结错误"))
                assertEquals(VlmStage.SCANNING,vm.state.value.stage)
                assertEquals(restarted.id,vm.state.value.scanSession!!.id)
                assertNull(vm.state.value.error)
                vm.onIntent(VlmIntent.AnalysisReceived(com.framer.sense.feature.camera.vlm.scan.AnalysisSample(
                    session.id, 1, android.os.SystemClock.elapsedRealtimeNanos(), ScanCameraConfig(1,0,1f,CropRect(),false), null,
                    64,48,ByteArray(64 * 48 * 3 / 2))))
                yield(); assertEquals(0, vm.state.value.scanProgress.frames)
                vm.onIntent(VlmIntent.RestartFlow); vm.onIntent(VlmIntent.RestartFlow)
                val config = ScanCameraConfig(1, 0, 1f, CropRect(), false)
                vm.onIntent(VlmIntent.AnalysisReceived(com.framer.sense.feature.camera.vlm.scan.AnalysisSample(
                    session.id, 1, android.os.SystemClock.elapsedRealtimeNanos(), config, null, 64, 48, ByteArray(64 * 48 * 3 / 2))))
                yield(); assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertNull(vm.state.value.scanSnapshot); assertTrue(events.isEmpty())
                assertTrue(vm.state.value.cameraReady)
                assertTrue(vm.state.value.canRestartScan)
                vm.onIntent(VlmIntent.StartScan)
                withTimeout(5000) { vm.state.first { it.scanSession != null } }
                vm.onIntent(VlmIntent.RestartFlow)
                vm.onIntent(VlmIntent.StartComposition)
                yield()
                assertEquals(VlmStage.FREEZING, vm.state.value.stage)
                assertEquals(1, events.filterIsInstance<VlmEffect.Freeze>().size)
                val frozenToken = events.filterIsInstance<VlmEffect.Freeze>().single().token
                vm.onIntent(VlmIntent.RestartFlow)
                val lateBitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888)
                vm.onIntent(VlmIntent.FrameReady(frozenToken, lateBitmap, 1f))
                yield()
                assertTrue(lateBitmap.isRecycled)
                assertEquals(VlmStage.LIVE, vm.state.value.stage)
                assertNull(vm.state.value.scene)
                assertNull(vm.state.value.scanSession)
                assertEquals(List(5) { CameraStepStatus.PENDING }, vm.state.value.workflowProgress())
            } finally { listener.cancel(); vm.onLeave() }
        }
    }

    /** 退后台先停止新增输入，保留有效帧；恢复前台后不自动分析。 */
    @Test fun backgroundInterruptionPreservesFramesUntilExplicitAnalysis() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = viewModel()
            try {
                vm.onIntent(VlmIntent.CameraReady(CameraCapabilities(1f, 3f)))
                vm.onIntent(VlmIntent.CameraConfigured(1)); vm.onIntent(VlmIntent.StartScan)
                val session = withTimeout(5000) { vm.state.first { it.scanSession != null }.scanSession!! }
                val buffer = ByteArray(64 * 48 * 3 / 2) { if (it < 64 * 48) 100 else 128.toByte() }
                val sample = com.framer.sense.feature.camera.vlm.scan.AnalysisSample(session.id, 1,
                    android.os.SystemClock.elapsedRealtimeNanos(), ScanCameraConfig(1, 0, 1f, CropRect(), false), null, 64, 48, buffer)
                vm.onIntent(VlmIntent.AnalysisReceived(sample))
                withTimeout(10000) { vm.state.first { it.scanProgress.frames == 1 } }
                vm.onIntent(VlmIntent.CameraForeground(false)); vm.onIntent(VlmIntent.StopScan)
                val done = withTimeout(10000) { vm.state.first { it.stage == VlmStage.SCAN_REVIEW } }
                assertEquals(1, done.scanSnapshot!!.frames.size)
                vm.onIntent(VlmIntent.CameraForeground(true)); yield()
                assertEquals(VlmStage.SCAN_REVIEW, vm.state.value.stage)
            } finally { vm.onIntent(VlmIntent.CancelScan); vm.onLeave() }
        }
    }

}
