package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.CloudModelConfig
import com.framer.sense.feature.camera.vlm.model.ModelSettings
import com.framer.sense.feature.camera.vlm.model.QwenAccess

/** 官方 Key 只由用户在设备填写，保存时由 SettingsStore 加密。 */
@Composable
internal fun QwenModelPanel(draft: ModelSettings, update: (ModelSettings) -> Unit, busy: Boolean, onEvent: (VlmIntent) -> Unit) {
    Text(stringResource(R.string.vlm_qwen_settings_title), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QwenAccess.entries.forEach { access ->
            FilterChip(selected = draft.qwenAccess == access, onClick = { update(draft.copy(qwenAccess = access)) },
                enabled = !busy, modifier = Modifier.testTag("vlm_qwen_access_${access.name}"),
                label = { Text(stringResource(if (access == QwenAccess.OFFICIAL) R.string.vlm_qwen_official else R.string.vlm_qwen_gateway)) })
        }
    }
    if (draft.qwenAccess == QwenAccess.OFFICIAL) {
        Column(Modifier.fillMaxWidth().testTag("vlm_qwen_official_configuration"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(CloudModelConfig.qwenBaseUrl, {}, readOnly = true, enabled = !busy,
                label = { Text(stringResource(R.string.vlm_qwen_base_url)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("vlm_qwen_base_url"))
            OutlinedTextField(draft.qwenApiKey, { update(draft.copy(qwenApiKey = it)) }, enabled = !busy,
                label = { Text(stringResource(R.string.vlm_qwen_api_key)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("vlm_qwen_api_key"))
            OutlinedTextField(CloudModelConfig.qwenModel, {}, readOnly = true, enabled = !busy,
                label = { Text(stringResource(R.string.vlm_qwen_model)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("vlm_qwen_model"))
            Text(stringResource(R.string.vlm_qwen_configuration_hint, CloudModelConfig.qwenModel, CloudModelConfig.qwenBaseUrl), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onEvent(VlmIntent.CheckQwen) }, enabled = !busy,
                modifier = Modifier.testTag("vlm_qwen_check")) { Text(stringResource(R.string.vlm_qwen_check_saved)) }
            Text(stringResource(R.string.vlm_qwen_check_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
}
