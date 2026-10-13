package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.data.ModelFailure
import com.framer.sense.feature.camera.vlm.model.*

/** 模型使用日志的统一字段，不输出密钥、地址、图片、提示词或上游正文。 */
object ModelUsageLog {
    fun identity(provider: ProviderId?, settings: ModelSettings? = null, actualModel: String? = null, modelOrigin: String = "实际返回"): String {
        val official = (provider == ProviderId.QWEN && settings?.qwenAccess == QwenAccess.OFFICIAL) ||
            (provider == ProviderId.SEED && settings?.seedAccess == SeedAccess.OFFICIAL)
        val source = when (provider) { null -> "尚未调用"; ProviderId.LOCAL -> "离线"; else -> "云端" }
        val access = when { provider == null -> "未知"; provider == ProviderId.LOCAL -> "本机"; official -> "官方直连"; else -> "项目网关" }
        val model = actualModel?.let(::identifier) ?: if (official)
            identifier(if (provider == ProviderId.SEED) CloudModelConfig.seedModel else settings!!.qwenModel) else "待模型返回"
        val modelSource = when { actualModel != null -> modelOrigin; official -> "配置"; else -> "未知" }
        return "source=$source provider=${provider ?: "NONE"} access=$access model=$model modelSource=$modelSource"
    }

    fun failure(error: Throwable): String {
        val failure = error as? ModelFailure
        return "code=${identifier(failure?.code ?: error::class.java.simpleName)} http=${failure?.upstreamHttpStatus ?: "未知"} upstream=${identifier(failure?.upstreamErrorCode ?: "未知")}"
    }

    /** 仅接受短模型/错误标识，拒绝密钥形态、URL 和换行注入。 */
    private fun identifier(value: String): String = value.takeIf {
        it.isNotBlank() && it.length <= 160 && !it.startsWith("sk-", ignoreCase = true) && !it.contains("://") &&
            it.all { char -> char.isLetterOrDigit() || char in "._:/-" }
    } ?: "未识别"
}
