package com.framer.sense.feature.camera.vlm

import com.framer.sense.feature.camera.vlm.agent.ModelUsageLog
import com.framer.sense.feature.camera.vlm.data.ModelFailure
import com.framer.sense.feature.camera.vlm.model.*
import org.junit.Assert.*
import org.junit.Test

class ModelUsageLogTest {
    @Test fun gatewayModelIsUnknownUntilActualReplyAndNeverGuessedFromQwenConfiguration() {
        val settings = ModelSettings(qwenAccess = QwenAccess.GATEWAY, qwenModel = "configured-only", qwenApiKey = "sk-secret")
        val before = ModelUsageLog.identity(ProviderId.QWEN, settings)
        assertTrue(before.contains("source=云端"))
        assertTrue(before.contains("access=项目网关"))
        assertFalse(before.contains("configured-only"))
        val after = ModelUsageLog.identity(ProviderId.SEED, settings, "actual-seed-model")
        assertTrue(after.contains("provider=SEED"))
        assertTrue(after.contains("model=actual-seed-model modelSource=实际返回"))
        assertFalse(after.contains(settings.qwenApiKey))
        assertTrue(ModelUsageLog.identity(ProviderId.LOCAL, actualModel = "local-model").contains("source=离线"))
    }

    @Test fun failuresKeepSafeStatusWithoutLeakingMessagesSecretsOrInjectedModelText() {
        val failure = ModelFailure("QWEN_MODEL_PERMISSION", "sk-secret response body", false).apply {
            upstreamHttpStatus = 403
            upstreamErrorCode = "Model.AccessDenied"
        }
        val log = ModelUsageLog.failure(failure)
        assertTrue(log.contains("http=403 upstream=Model.AccessDenied"))
        assertFalse(log.contains("sk-secret"))
        assertFalse(log.contains("response body"))
        listOf("sk-secret", "model\nresult=成功", "https://host.invalid?key=secret").forEach { model ->
            val identity = ModelUsageLog.identity(ProviderId.QWEN, ModelSettings(), model)
            assertFalse(identity.contains(model))
            assertTrue(identity.contains("model=未识别"))
        }
    }
}
