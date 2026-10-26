package com.framer.sense.feature.camera.vlm

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.framer.sense.feature.camera.vlm.data.SettingsStore
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 使用隔离偏好与真实 Keystore 验证新增密钥不会覆盖已验证的配置。 */
class SeedSettingsStoreTest {
    private fun context() = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "seed-settings-${UUID.randomUUID()}"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
    }

    @Test fun threeSecretsAreIndependentAndSeedUpdatesPreserveQwenAndOfflinePreferences() {
        val context = context(); val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
        try {
            val store = SettingsStore(context)
            val photos = PhotoPreferences()
            store.savePhotoPreferences(photos)
            val original = ModelSettings(mode = ModelMode.CLOUD, qwenApiKey = "sk-qwen-secret", gatewayToken = "gateway-secret")
            store.save(original)
            val seed = original.copy(preferred = ProviderId.SEED, seedApiKey = "seed-secret")
            store.save(seed)
            assertEquals(seed.normalizeReferenceSource(), SettingsStore(context).read())
            val encoded = prefs.getString("settings", "")!!
            listOf(seed.seedApiKey, seed.qwenApiKey, seed.gatewayToken).forEach { assertFalse(encoded.contains(it)) }
            assertNotEquals(seed.seedApiKey, prefs.getString("seed-api-key", null))
            assertNotEquals(seed.qwenApiKey, prefs.getString("qwen-api-key", null))
            assertNotEquals(seed.gatewayToken, prefs.getString("token", null))
            val offline = seed.copy(mode = ModelMode.OFFLINE, seedApiKey = "")
            store.save(offline)
            assertEquals(offline, store.read())
            assertEquals(photos, store.readPhotoPreferences())
            store.save(seed)
            prefs.edit().putString("seed-api-key", "corrupt-ciphertext").commit()
            val restored = store.read()
            assertEquals("", restored.seedApiKey)
            assertEquals(original.qwenApiKey, restored.qwenApiKey)
            assertEquals(original.gatewayToken, restored.gatewayToken)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun legacySeedGatewayKeepsAccessWhileQwenRemainsOfficial() {
        val context = context(); val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
        try {
            val store = SettingsStore(context)
            assertEquals(SeedAccess.OFFICIAL, store.read().seedAccess)
            val settings = ModelSettings(mode = ModelMode.CLOUD, qwenApiKey = "sk-qwen-secret", gatewayToken = "legacy-token")
            store.save(settings)
            val json = VlmJson.parseToJsonElement(VlmJson.encodeToString(settings.copy(gatewayToken = ""))).jsonObject
            prefs.edit().putString("settings", JsonObject(json.filterKeys { it != "seedAccess" }).toString()).commit()
            assertEquals(SeedAccess.GATEWAY, store.read().seedAccess)
            assertEquals(QwenAccess.OFFICIAL, store.read().qwenAccess)
            assertEquals(settings.qwenApiKey, store.read().qwenApiKey)
            prefs.edit().remove("token").commit()
            assertEquals(SeedAccess.OFFICIAL, store.read().seedAccess)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun fixedModesNormalizeLegacyReferenceSourcesOnReadAndSave() {
        val context = context(); val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
        try {
            val store = SettingsStore(context)
            listOf(
                ModelSettings(mode = ModelMode.CLOUD) to ReferenceSource.GENERATED_IMAGE,
                ModelSettings(mode = ModelMode.OFFLINE, referenceSource = ReferenceSource.GENERATED_IMAGE) to ReferenceSource.LEGACY_GLB
            ).forEach { (legacy, source) ->
                prefs.edit().putString("settings", VlmJson.encodeToString(legacy)).commit()
                val expected = legacy.copy(referenceSource = source)
                assertEquals(expected, store.read())
                store.save(legacy)
                val persisted = VlmJson.decodeFromString<ModelSettings>(prefs.getString("settings", null)!!)
                assertEquals(expected, persisted)
                assertEquals(expected, SettingsStore(context).read())
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun autoModeMigratesLegacyReferenceChoicesAndKeepsImageConfiguration() {
        val context = context(); val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
        try {
            val store = SettingsStore(context)
            ReferenceSource.entries.forEach { source ->
                val legacy = ModelSettings(mode = ModelMode.AUTO, referenceSource = source, seedreamModel = SeedreamModel.PRO)
                val expected = legacy.copy(referenceSource = ReferenceSource.GENERATED_IMAGE)
                prefs.edit().putString("settings", VlmJson.encodeToString(legacy)).commit()
                assertEquals(expected, store.read())
                store.save(legacy)
                assertEquals(expected, SettingsStore(context).read())
            }
        } finally { prefs.edit().clear().commit() }
    }
}
