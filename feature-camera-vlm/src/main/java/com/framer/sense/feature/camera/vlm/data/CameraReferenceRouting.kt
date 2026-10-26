package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.framer.sense.feature.camera.vlm.model.*

fun interface ReferenceNetworkState {
    fun isAvailable(): Boolean
}

/** 只读取系统网络状态，不为选择参考方式发起探测或收费模型请求。 */
class AndroidReferenceNetworkState(private val context: Context) : ReferenceNetworkState {
    override fun isAvailable(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}

/** 摄影参考按实际生成链路选择，独立于扫描选景使用的视觉供应商。 */
object CameraReferenceRouting {
    fun source(settings: ModelSettings, networkAvailable: Boolean): ReferenceSource = when (settings.mode) {
        ModelMode.OFFLINE -> ReferenceSource.LEGACY_GLB
        ModelMode.CLOUD -> ReferenceSource.GENERATED_IMAGE
        // 尚未接入的品牌保持云端入口并明确提示，不隐式换品牌或降级。
        ModelMode.AUTO -> if (!settings.supportsImageGeneration ||
            // 千问网关尚无生图能力，保留入口提示，不借用豆包服务或静默降级。
            settings.preferred == ProviderId.QWEN && !settings.officialImageGeneration ||
            networkAvailable && if (settings.officialImageGeneration) settings.imageGenerationKey.isNotBlank()
                else settings.gateway.isNotBlank() && settings.gatewayToken.isNotBlank()
        ) ReferenceSource.GENERATED_IMAGE else ReferenceSource.LEGACY_GLB
    }

    /** 首轮全部因服务问题失败才降级；保留成功参考，不因质量问题或用户取消而换路线。 */
    fun shouldFallback(settings: ModelSettings, generated: GeneratedReferenceState): Boolean =
        settings.mode == ModelMode.AUTO && settings.supportsImageGeneration && generated.candidates.isNotEmpty() &&
            !generated.hasReady && !generated.running && generated.candidates.all {
                it.status == GeneratedCandidateStatus.FAILED && it.errorCode in setOf(
                    "AUTH", "KEY", "GATEWAY", "LIMIT", "NETWORK", "TIMEOUT", "SERVICE", "DOWNLOAD")
            }
}
