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

/** 验证真实 Keystore 保存和旧设置迁移，不请求网络。 */
class QwenSettingsStoreTest {
    private fun context() = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "qwen-settings-${UUID.randomUUID()}"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
    }

    @Test fun bothSecretsAreEncryptedAndCanBeRestoredWithoutPuttingKeyInSettingsJson() {
        val context = context(); val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
        try {
            val store = SettingsStore(context)
            assertEquals(QwenAccess.OFFICIAL, store.read().qwenAccess)
            val configured = ModelSettings(mode = ModelMode.CLOUD, qwenApiKey = "sk-fake-qwen-key", gatewayToken = "fake-gateway-token")
            store.save(configured)
            assertEquals(configured, SettingsStore(context).read())
            assertFalse(prefs.getString("settings", "")!!.contains(configured.qwenApiKey))
            assertFalse(prefs.getString("settings", "")!!.contains(configured.gatewayToken))
            assertNotEquals(configured.qwenApiKey, prefs.getString("qwen-api-key", null))
            assertNotEquals(configured.gatewayToken, prefs.getString("token", null))
            store.save(configured.copy(qwenApiKey = "", gatewayToken = ""))
            assertEquals("", store.read().qwenApiKey)
            assertEquals("", store.read().gatewayToken)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun usableLegacyGatewayIsPreservedButUnconfiguredUsersDefaultToOfficial() {
        val context = context(); val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
        try {
            val store = SettingsStore(context)
            val settings = ModelSettings(mode = ModelMode.CLOUD, gateway = "https://gateway.example.invalid", gatewayToken = "legacy-token")
            store.save(settings)
            val current = VlmJson.parseToJsonElement(VlmJson.encodeToString(settings.copy(gatewayToken = ""))).jsonObject
            val old = JsonObject(current.filterKeys { !it.startsWith("qwen") })
            prefs.edit().putString("settings", old.toString()).commit()
            assertEquals(QwenAccess.GATEWAY, store.read().qwenAccess)
            assertEquals("legacy-token", store.read().gatewayToken)
            assertEquals(settings.gateway, store.read().gateway)
            prefs.edit().remove("token").commit()
            assertEquals(QwenAccess.OFFICIAL, store.read().qwenAccess)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun currentJsonReplacesPersistedQwenAddressAndModelWithoutLosingSecrets() {
        val context = context(); val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
        try {
            val store = SettingsStore(context)
            val configured = ModelSettings(mode = ModelMode.CLOUD, qwenApiKey = "sk-fake-qwen-key", gatewayToken = "fake-token",
                gateway = "https://gateway.example.invalid", qwenBaseUrl = "https://old.example.invalid/v1", qwenModel = "old-model")
            store.save(configured)
            val expected = CloudModelConfig.applyQwenConfiguration(configured)
            assertEquals(expected, store.read())
            val saved = VlmJson.decodeFromString<ModelSettings>(prefs.getString("settings", null)!!)
            assertEquals(CloudModelConfig.qwenBaseUrl, saved.qwenBaseUrl)
            assertEquals(CloudModelConfig.qwenModel, saved.qwenModel)
            // 模拟升级前持久化的数据，确保读取旧配置也使用当前 JSON，并保留加密密钥。
            prefs.edit().putString("settings", VlmJson.encodeToString(configured.copy(gatewayToken = "", qwenApiKey = ""))).commit()
            assertEquals(expected, SettingsStore(context).read())
        } finally { prefs.edit().clear().commit() }
    }
}
