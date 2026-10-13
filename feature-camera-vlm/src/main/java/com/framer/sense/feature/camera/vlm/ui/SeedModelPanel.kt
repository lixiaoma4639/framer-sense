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
import com.framer.sense.feature.camera.vlm.model.*

/** Seed 独立密钥；地址及模型由共享 JSON 配置管理。 */
@Composable
internal fun SeedModelPanel(draft: ModelSettings, update: (ModelSettings) -> Unit, busy: Boolean, onEvent: (VlmIntent) -> Unit) {
    Text(stringResource(R.string.vlm_seed_settings_title), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SeedAccess.entries.forEach { access ->
            FilterChip(selected = draft.seedAccess == access, onClick = { update(draft.copy(seedAccess = access)) },
                enabled = !busy, modifier = Modifier.testTag("vlm_seed_access_${access.name}"),
                label = { Text(stringResource(if (access == SeedAccess.OFFICIAL) R.string.vlm_qwen_official else R.string.vlm_qwen_gateway)) })
        }
    }
    if (draft.seedAccess == SeedAccess.OFFICIAL) {
        Column(Modifier.fillMaxWidth().testTag("vlm_seed_official_configuration"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(CloudModelConfig.seedBaseUrl, {}, readOnly = true, enabled = !busy,
                label = { Text(stringResource(R.string.vlm_seed_base_url)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("vlm_seed_base_url"))
            OutlinedTextField(draft.seedApiKey, { update(draft.copy(seedApiKey = it)) }, enabled = !busy,
                label = { Text(stringResource(R.string.vlm_seed_api_key)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("vlm_seed_api_key"))
            OutlinedTextField(CloudModelConfig.seedModel, {}, readOnly = true, enabled = !busy,
                label = { Text(stringResource(R.string.vlm_seed_model)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("vlm_seed_model"))
            Text(stringResource(R.string.vlm_seed_configuration_hint), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onEvent(VlmIntent.CheckSeed) }, enabled = !busy,
                modifier = Modifier.testTag("vlm_seed_check")) { Text(stringResource(R.string.vlm_seed_check_saved)) }
            Text(stringResource(R.string.vlm_seed_check_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
}
