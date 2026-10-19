package com.framer.sense.feature.camera.vlm.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.framer.sense.feature.camera.vlm.R

/** 显示状态只保存在当前输入框中，切换明密文不修改密钥或保存配置。 */
@Composable
internal fun SecretKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    fieldTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var visible by remember(fieldTag) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled,
                modifier = Modifier.testTag("${fieldTag}_visibility")) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (visible) R.string.vlm_model_hide_key else R.string.vlm_model_show_key))
            }
        },
        modifier = modifier.testTag(fieldTag)
    )
}
