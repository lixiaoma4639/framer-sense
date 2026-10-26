package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 用户模型设置；网关令牌和官方 API Key 以 Android Keystore 加密后保存。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("vlm-settings", Context.MODE_PRIVATE)
    private val alias = "framer-vlm-gateway"

    fun readAvatarEthnicity(): AvatarEthnicity = runCatching {
        AvatarEthnicity.valueOf(prefs.getString("avatar-ethnicity", null) ?: AvatarEthnicity.ASIAN.name)
    }.getOrDefault(AvatarEthnicity.ASIAN)

    fun saveAvatarEthnicity(value: AvatarEthnicity) {
        prefs.edit().putString("avatar-ethnicity", value.name).apply()
    }

    fun readPhotoPreferences(): PhotoPreferences = runCatching {
        VlmJson.decodeFromString<PhotoPreferences>(prefs.getString("photo-preferences", null) ?: "{}")
    }.getOrDefault(PhotoPreferences()).copy(ethnicity = readAvatarEthnicity())

    fun savePhotoPreferences(value: PhotoPreferences) {
        prefs.edit().putString("photo-preferences", VlmJson.encodeToString(value))
            .putString("avatar-ethnicity", value.ethnicity.name).apply()
    }

    /** 只在新候选通过入口检查后取一次，重开扫描或重启 APP 继续轮换。 */
    @Synchronized
    fun nextGeneratedStyleIndex(): Int {
        val index = prefs.getInt("generated-style-index", 0).coerceAtLeast(0)
        prefs.edit().putInt("generated-style-index", if (index == Int.MAX_VALUE) 0 else index + 1).apply()
        return index
    }

    fun readInstructionHistory(): List<String> = runCatching {
        InstructionHistory.normalize(VlmJson.decodeFromString<List<String>>(prefs.getString("instruction-history", null) ?: "[]"))
    }.getOrDefault(emptyList())

    fun saveInstructionHistory(values: List<String>) {
        prefs.edit().putString("instruction-history", VlmJson.encodeToString(InstructionHistory.normalize(values))).apply()
    }

    /** 读取设置；无参数，无法解密时清空令牌，提示用户重新配置。 */
    fun read(): ModelSettings {
        val stored = prefs.getString("settings", null)
        val token = readSecret("token")
        val settings = runCatching {
            var decoded = VlmJson.decodeFromString<ModelSettings>(stored ?: "{}")
            // 旧用户显式保存的网关配置继续走网关；首次设置默认官方直连。
            if (stored != null && token.isNotBlank() && "qwenAccess" !in VlmJson.parseToJsonElement(stored).jsonObject)
                decoded = decoded.copy(qwenAccess = QwenAccess.GATEWAY)
            if (stored != null && token.isNotBlank() && "seedAccess" !in VlmJson.parseToJsonElement(stored).jsonObject)
                decoded = decoded.copy(seedAccess = SeedAccess.GATEWAY)
            decoded
        }.getOrDefault(ModelSettings())
        return CloudModelConfig.applyQwenConfiguration(settings.copy(gatewayToken = token, qwenApiKey = readSecret("qwen-api-key"), seedApiKey = readSecret("seed-api-key"))).normalizeReferenceSource()
    }

    private fun readSecret(name: String): String = runCatching {
            val encoded = prefs.getString(name, null) ?: return@runCatching ""
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
        }.getOrDefault("")

    /** 持久化配置，三个密钥分开加密，和公开设置在同一事务内提交。
     * @param settings 路由、网关和两家官方配置。
     */
    fun save(settings: ModelSettings) {
        val token = encrypt(settings.gatewayToken)
        val qwenKey = encrypt(settings.qwenApiKey)
        val seedKey = encrypt(settings.seedApiKey)
        check(prefs.edit().putString("settings", VlmJson.encodeToString(CloudModelConfig.applyQwenConfiguration(settings).normalizeReferenceSource().copy(gatewayToken = "", qwenApiKey = "", seedApiKey = "")))
            .putString("token", token).putString("qwen-api-key", qwenKey).putString("seed-api-key", seedKey).commit()) { "无法保存模型设置" }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
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
