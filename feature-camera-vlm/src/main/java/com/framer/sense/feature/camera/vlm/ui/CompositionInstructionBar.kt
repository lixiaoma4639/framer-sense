package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.model.ModelMode
import com.framer.sense.feature.camera.vlm.model.SeedreamModel
import com.framer.sense.feature.camera.vlm.model.QwenImageModel
import com.framer.sense.feature.camera.vlm.model.ProviderId

/** 原生键盘单独接收输入；相机区域的外部点击只收起编辑区，不穿透到拍照等控件。 */
@Composable
internal fun CompositionInstructionOverlay(state: VlmUiState, onEvent: (VlmIntent) -> Unit, modifier: Modifier = Modifier) {
    // 输入面板与原生键盘保持原固定方向，内部删除图标也随面板保持不动。
    CompositionLocalProvider(LocalCameraIconAngle provides 0f) {
        Box(modifier.imePadding()) {
            if (state.instructionEditorVisible) {
                Box(Modifier.matchParentSize().testTag("vlm_ai_instruction_dismiss")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = stringResource(R.string.vlm_ai_instruction_dismiss)) {
                        onEvent(VlmIntent.InstructionEditorVisible(false))
                    })
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) {
                CompositionInstructionBar(state, onEvent)
            }
        }
    }
}

/** 覆盖在预览上方的编辑区；草稿由 MVI 保存，仅确认后提交给原构图输入链路。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CompositionInstructionBar(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    AnimatedVisibility(state.instructionEditorVisible,
        enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
        modifier = Modifier.fillMaxWidth()) {
        var editorValue by remember(state.instructionDraftRevision) {
            mutableStateOf(TextFieldValue(state.instructionDraft, TextRange(state.instructionDraft.length)))
        }
        LaunchedEffect(state.instructionEditorVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        val submit = {
            if (state.instructionEditorVisible && state.canEditInstruction) {
                focusManager.clearFocus()
                keyboard?.hide()
                onEvent(VlmIntent.SubmitInstruction)
            }
        }
        Surface(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("vlm_ai_instruction_panel")
            // 空白区域也参与命中，阻止同级的外部关闭层接到面板内点击；不消费子控件事件。
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            shape = MaterialTheme.shapes.large,
            color = Color(0xF51C1D22), contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0xFFFFD66A).copy(alpha = .65f)), shadowElevation = 6.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fieldInteraction = remember { MutableInteractionSource() }
                    val fieldEnabled = state.canEditInstruction && state.instructionEditorVisible
                    val hasModelChoice = state.settings.mode != ModelMode.OFFLINE
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFFD66A), focusedBorderColor = Color(0xFFFFD66A),
                        unfocusedBorderColor = Color.White.copy(alpha = .5f),
                        focusedLabelColor = Color(0xFFFFD66A), unfocusedLabelColor = Color.White,
                        focusedPlaceholderColor = Color.White.copy(alpha = .6f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = .6f))
                    BasicTextField(value = editorValue,
                        onValueChange = { value ->
                            editorValue = if (value.text.length > 2000) TextFieldValue(value.text.take(2000), TextRange(2000)) else value
                            if (editorValue.text != state.instructionDraft) onEvent(VlmIntent.InstructionDraftChanged(editorValue.text))
                        },
                        enabled = fieldEnabled, interactionSource = fieldInteraction,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = SolidColor(Color(0xFFFFD66A)),
                        minLines = 2, maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f).testTag("vlm_ai_instruction_input"),
                        decorationBox = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(value = editorValue.text,
                                enabled = fieldEnabled, singleLine = false,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = fieldInteraction, colors = fieldColors,
                                contentPadding = PaddingValues(start = 16.dp, top = 16.dp,
                                    end = if (hasModelChoice) 8.dp else 16.dp,
                                    bottom = if (hasModelChoice) 8.dp else 16.dp),
                                label = { Text(stringResource(R.string.vlm_ai_instruction_label)) },
                                placeholder = { Text(stringResource(R.string.vlm_ai_instruction_label)) },
                                innerTextField = {
                                    // 模型选择与输入共用内容区，不追加占位行或增加输入框高度。
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Box(Modifier.weight(1f).padding(bottom = if (hasModelChoice) 8.dp else 0.dp)) { innerTextField() }
                                        if (hasModelChoice) {
                                            Spacer(Modifier.width(4.dp))
                                            GeneratedModelChoice(state, onEvent) {
                                                focusManager.clearFocus()
                                                keyboard?.hide()
                                            }
                                        }
                                    }
                                })
                        })
                    Button(onClick = submit, enabled = state.canEditInstruction && state.instructionEditorVisible,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD66A), contentColor = Color(0xFF202126)),
                        modifier = Modifier.testTag("vlm_ai_instruction_confirm")) {
                        Text(stringResource(R.string.vlm_ai_instruction_confirm))
                    }
                }
                Text(stringResource(R.string.vlm_ai_instruction_hint), style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = .75f))
                InstructionChoices(state, onEvent)
            }
        }
    }
}

@Composable
private fun GeneratedModelChoice(state: VlmUiState, onEvent: (VlmIntent) -> Unit, beforeOpen: () -> Unit) {
    var expanded by remember(state.settings.preferred, state.settings.mode) { mutableStateOf(false) }
    val supported = state.settings.supportsImageGeneration
    val enabled = state.canEditInstruction && state.instructionEditorVisible && supported
    val color = if (enabled) Color(0xFFFFD66A) else Color.White.copy(alpha = .55f)
    Box {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.testTag("vlm_generated_model_choice")
                .clickable(enabled = enabled, role = Role.Button) { beforeOpen(); expanded = true }) {
            Text(stringResource(when {
                !supported -> R.string.vlm_generated_model_unsupported
                state.settings.preferred == ProviderId.QWEN -> when (state.settings.qwenImageModel) {
                    QwenImageModel.V21_TURBO -> R.string.vlm_generated_model_turbo
                    QwenImageModel.V30 -> R.string.vlm_generated_model_standard
                    else -> R.string.vlm_generated_model_pro
                }
                state.settings.seedreamModel == SeedreamModel.FLASH -> R.string.vlm_generated_model_fast
                else -> R.string.vlm_generated_model_expert
            }), style = MaterialTheme.typography.labelMedium, color = color)
            if (supported) Icon(Icons.Filled.ChevronRight, contentDescription = null,
                tint = color, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            if (state.settings.preferred == ProviderId.QWEN) QwenImageModel.entries.forEach { model ->
                val selected = state.settings.qwenImageModel == model
                DropdownMenuItem(text = { Text(qwenImageModelLabel(model)) },
                    onClick = { expanded = false; onEvent(VlmIntent.QwenImageModelSelected(model)) },
                    modifier = Modifier.testTag("vlm_qwen_image_model_${model.name}")
                        .semantics { this.selected = selected })
            }
            else SeedreamModel.entries.forEach { model ->
                val selected = state.settings.seedreamModel == model
                DropdownMenuItem(text = { Text(seedreamModelLabel(model)) },
                    onClick = { expanded = false; onEvent(VlmIntent.SeedreamModelSelected(model)) },
                    modifier = Modifier.testTag("vlm_seedream_model_${model.name}")
                        .semantics { this.selected = selected })
            }
        }
    }
}

@Composable
private fun InstructionChoices(state: VlmUiState, onEvent: (VlmIntent) -> Unit) {
    val recommendations = listOf(R.string.vlm_ai_recommend_environment, R.string.vlm_ai_recommend_building,
        R.string.vlm_ai_recommend_full_body, R.string.vlm_ai_recommend_portrait,
        R.string.vlm_ai_recommend_symmetry, R.string.vlm_ai_recommend_leading_lines).map { stringResource(it) }
    val enabled = state.canEditInstruction && state.instructionEditorVisible
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("vlm_ai_instruction_choices"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.instructionHistory.isNotEmpty()) {
            item(key = "history-title") { Text(stringResource(R.string.vlm_ai_instruction_history),
                style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFD66A)) }
            itemsIndexed(state.instructionHistory, key = { _, text -> "history:$text" }) { index, text ->
                val selected = !state.instructionDraftFromRecommendation && state.instructionDraft.trim() == text
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .background(if (selected) Color(0x33FFD66A) else Color.White.copy(alpha = .06f))
                    .clickable(enabled = enabled) { onEvent(VlmIntent.InstructionHistorySelected(text)) }
                    .semantics { this.selected = selected }.testTag("vlm_ai_history_$index"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(enabled = enabled, onClick = { onEvent(VlmIntent.DeleteInstructionHistory(text)) },
                        modifier = Modifier.testTag("vlm_ai_history_delete_$index")) {
                        Icon(Icons.Default.DeleteOutline, stringResource(R.string.vlm_ai_instruction_delete_history),
                            tint = Color.White.copy(alpha = .8f), modifier = Modifier.cameraIconRotation())
                    }
                }
            }
        }
        item(key = "recommendation-title") { Text(stringResource(R.string.vlm_ai_instruction_recommendations),
            style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFD66A)) }
        itemsIndexed(recommendations, key = { index, _ -> "recommendation:$index" }) { index, text ->
            val selected = state.instructionDraftFromRecommendation && state.instructionDraft == text
            Text(text, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (selected) Color(0x33FFD66A) else Color.White.copy(alpha = .06f))
                .clickable(enabled = enabled) { onEvent(VlmIntent.InstructionRecommendationSelected(text)) }
                .semantics { this.selected = selected }.padding(10.dp).testTag("vlm_ai_recommendation_$index"),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
