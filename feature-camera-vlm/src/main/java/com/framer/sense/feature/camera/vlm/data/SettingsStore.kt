package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 用户模型设置；网关令牌以 Android Keystore 加密后保存。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
    private val alias = "framer-vlm-gateway"

    /** 读取设置；无参数，无法解密时清空令牌，提示用户重新配置。 */
    fun read(): ModelSettings {
        val settings = runCatching { VlmJson.decodeFromString<ModelSettings>(prefs.getString("settings", null) ?: "{}") }.getOrDefault(ModelSettings())
        val token = runCatching {
            val encoded = prefs.getString("token", null) ?: return@runCatching ""
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
        }.getOrDefault("")
        return settings.copy(gatewayToken = token)
    }

    /** 持久化设置，并加密网关令牌。
     * @param settings 区域、模型模式、网关地址与独立网关令牌。
     */
    fun save(settings: ModelSettings) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(settings.gatewayToken.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("settings", VlmJson.encodeToString(settings.copy(gatewayToken = "")))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存模型设置" }
    }

    /** 获取或生成应用专用 AES 密钥；无参数，密钥不导出到应用文件。 */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
