package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.data.LocalModelStore
import com.framer.sense.feature.camera.vlm.data.ModelRouting
import com.framer.sense.feature.camera.vlm.data.CameraReferenceRouting
import com.framer.sense.feature.camera.vlm.ui.VlmUiState
import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.encodeToString

class ModelRoutingTest {
    @Test fun defaultIsOfflineAndSavedModesRemainExplicit() {
        assertEquals(ModelMode.OFFLINE, ModelSettings().mode)
        assertEquals(listOf(ProviderId.LOCAL), ModelRouting.candidates(ModelSettings()))
        assertEquals(ModelMode.OFFLINE, VlmJson.decodeFromString<ModelSettings>("{}").mode)
        ModelMode.entries.forEach { mode ->
            val saved = VlmJson.encodeToString(ModelSettings(mode = mode))
            assertEquals(mode, VlmJson.decodeFromString<ModelSettings>(saved).mode)
        }
    }

    /** 固定离线不能包含网络供应商，即使首选项仍保存为 GPT；无参数。 */
    @Test fun offlineNeverUsesCloud() {
        assertEquals(listOf(ProviderId.LOCAL), ModelRouting.candidates(ModelSettings(region = Region.GLOBAL, mode = ModelMode.OFFLINE, preferred = ProviderId.GPT)))
    }

    /** 品牌选择不再受旧区域字段限制，自动模式也不跨品牌调用。 */
    @Test fun pinnedCloudAndSelectedBrandOrder() {
        assertEquals(listOf(ProviderId.GPT), ModelRouting.candidates(ModelSettings(mode = ModelMode.CLOUD, preferred = ProviderId.GPT)))
        assertEquals(listOf(ProviderId.GEMINI, ProviderId.LOCAL), ModelRouting.candidates(ModelSettings(region = Region.CN, mode = ModelMode.AUTO, preferred = ProviderId.GEMINI)))
        assertEquals(listOf(ProviderId.SEED, ProviderId.LOCAL), ModelRouting.candidates(ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.SEED)))
    }

    @Test fun automaticReferencesUseCloudOnlyWhenNetworkAndImageCredentialsAreAvailable() {
        val settings = ModelSettings(mode = ModelMode.AUTO, preferred = ProviderId.SEED, seedApiKey = "fixture-key")
        assertEquals(ReferenceSource.GENERATED_IMAGE, CameraReferenceRouting.source(settings, networkAvailable = true))
        assertEquals(ReferenceSource.LEGACY_GLB, CameraReferenceRouting.source(settings, networkAvailable = false))
        assertEquals(ReferenceSource.LEGACY_GLB, CameraReferenceRouting.source(settings.copy(seedApiKey = "", qwenApiKey = "qwen-only"), true))
        val gateway = settings.copy(seedAccess = SeedAccess.GATEWAY, seedApiKey = "", gatewayToken = "fixture-token")
        assertEquals(ReferenceSource.GENERATED_IMAGE, CameraReferenceRouting.source(gateway, true))
        assertEquals(ReferenceSource.LEGACY_GLB, CameraReferenceRouting.source(gateway.copy(gatewayToken = ""), true))
        assertEquals(ReferenceSource.LEGACY_GLB, CameraReferenceRouting.source(settings.copy(mode = ModelMode.OFFLINE), true))
        assertEquals(ReferenceSource.GENERATED_IMAGE, CameraReferenceRouting.source(settings.copy(mode = ModelMode.CLOUD), false))
    }

    @Test fun unsupportedBrandsNeverBorrowSeedKeyOrFallBackToOffline() {
        listOf(ProviderId.GPT, ProviderId.GEMINI).forEach { brand ->
            val settings = ModelSettings(mode = ModelMode.AUTO, preferred = brand, seedApiKey = "seed-key")
            assertFalse(settings.supportsImageGeneration)
            assertEquals(ReferenceSource.GENERATED_IMAGE, CameraReferenceRouting.source(settings, true))
            assertEquals(ReferenceSource.GENERATED_IMAGE, CameraReferenceRouting.source(settings, false))
        }
    }

    @Test fun sharedSeedAccessOverridesObsoleteImageAccessAndKeepsKeys() {
        val legacy = ModelSettings(preferred = ProviderId.SEED, seedAccess = SeedAccess.GATEWAY,
            imageAccess = SeedAccess.OFFICIAL, seedApiKey = "seed-key", qwenApiKey = "qwen-key")
        val normalized = CloudModelConfig.applyQwenConfiguration(legacy)
        assertEquals(SeedAccess.GATEWAY, normalized.imageAccess)
        assertEquals(legacy.seedApiKey, normalized.seedApiKey)
        assertEquals(legacy.qwenApiKey, normalized.qwenApiKey)
    }

    @Test fun actualReferenceRouteIgnoresTheLegacySavedChoice() {
        val legacy = ModelSettings(mode = ModelMode.AUTO, referenceSource = ReferenceSource.LEGACY_GLB)
        val cloud = VlmUiState(settings = legacy, automaticReferenceSource = ReferenceSource.GENERATED_IMAGE)
        assertTrue(cloud.usesGeneratedReference)
        val offline = cloud.copy(automaticReferenceSource = ReferenceSource.LEGACY_GLB)
        assertFalse(offline.usesGeneratedReference)
        assertFalse(cloud.copy(settings = legacy.copy(mode = ModelMode.OFFLINE)).usesGeneratedReference)
        assertTrue(offline.copy(settings = legacy.copy(mode = ModelMode.CLOUD)).usesGeneratedReference)
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
