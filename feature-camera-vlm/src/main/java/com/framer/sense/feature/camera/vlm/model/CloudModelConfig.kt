package com.framer.sense.feature.camera.vlm.model

import com.framer.sense.feature.camera.vlm.BuildConfig

/** 只提供配置访问入口；实际值统一维护在仓库 config/cloud-models.json。 */
object CloudModelConfig {
    const val gatewayBaseUrl = BuildConfig.MODEL_GATEWAY_BASE_URL
    const val capabilitiesV1Path = BuildConfig.MODEL_GATEWAY_CAPABILITIES_V1_PATH
    const val capabilitiesV2Path = BuildConfig.MODEL_GATEWAY_CAPABILITIES_V2_PATH
    const val directorV1Path = BuildConfig.MODEL_GATEWAY_DIRECTOR_V1_PATH
    const val directorV2Path = BuildConfig.MODEL_GATEWAY_DIRECTOR_V2_PATH
    const val viewsPath = BuildConfig.MODEL_GATEWAY_VIEWS_PATH
    const val qwenBaseUrl = BuildConfig.MODEL_PROVIDERS_QWEN_BASE_URL
    const val qwenModel = BuildConfig.MODEL_PROVIDERS_QWEN_MODEL
    const val qwenVisionPath = BuildConfig.MODEL_PROVIDERS_QWEN_VISION_PATH
    val qwenAllowedHosts = BuildConfig.MODEL_PROVIDERS_QWEN_ALLOWED_HOSTS.split(',').map { it.trim() }.toSet()
    const val qwenWorkspaceHostSuffix = BuildConfig.MODEL_PROVIDERS_QWEN_WORKSPACE_HOST_SUFFIX
    const val seedBaseUrl = BuildConfig.MODEL_PROVIDERS_SEED_BASE_URL
    const val seedModel = BuildConfig.MODEL_PROVIDERS_SEED_MODEL
    const val seedVisionPath = BuildConfig.MODEL_PROVIDERS_SEED_VISION_PATH
    val seedAllowedHosts = BuildConfig.MODEL_PROVIDERS_SEED_ALLOWED_HOSTS.split(',').map { it.trim() }.toSet()

    /** 地址和模型以当前构建的 JSON 为准；保留用户密钥和其他设置。 */
    fun applyQwenConfiguration(settings: ModelSettings): ModelSettings =
        settings.copy(qwenBaseUrl = qwenBaseUrl, qwenModel = qwenModel)
}
