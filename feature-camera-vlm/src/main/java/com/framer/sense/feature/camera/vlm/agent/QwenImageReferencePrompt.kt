package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.GeneratedPortraitPreferences

/** 千问独立提示词；不复用或修改 Seedream 的原生图层创作约束。 */
object QwenImageReferencePrompt {
    const val VERSION = "B_RGBA_COMPACT_V3"

    // APP 只提供兜底风格；候选序号不构成模型的历史记忆或姿势去重指令。
    fun build(instruction: String, variation: Int, preferences: GeneratedPortraitPreferences? = null, styleIndex: Int = variation): String {
        val requirement = instruction.trim().take(2000).ifBlank { "未提供特殊要求，请由 AI 自主创作。" }
        val preferenceText = preferences?.let(GeneratedPortraitPreferencePrompt::build).orEmpty()
        val styleText = GeneratedPortraitStylePolicy.prompt(styleIndex)
        return """
            这是一张带有透明度的RGBA图像。

            $preferenceText

            $styleText

            创作目标：参考上传的现场照片，设计能在现场模仿拍摄的艺术人像，仅输出人物素材，由 APP 叠回原环境。利用现场线条、光影、色彩和留白安排人物景别、站位、大小与姿态，使身体有明确主线和视觉重心，手势、视线与表情共同表达情绪。用户未指定时自主创作具有摄影感、影视剧或其他艺术表现力的画面，不固定姿势模板。

            人物：${GeneratedPortraitStylePolicy.SCENE_PRIORITY}原图主角符合上述性别、年龄与种族设置时，保持其身份特征、脸型与体型；不符合或没有主角时，按设置创造真人、动漫感或原创人形角色，不以背景路人替换。服装、发型和配饰遵循上述优先规则，用户未要求保留时不强制沿用原服饰。人物必须是视觉核心，家具和建筑不能代替人物。

            现场约束：原环境由 APP 原样保留，构图应适配原视角、透视、物体位置、天气、时段与光线方向。依据人物所在深度及相近深度的环境物体确定现实尺度，保持合理的受光、重心和人体结构。只使用现场可确认的地面或支撑；无法确认时选择特写、半身或多半身。优先选择无遮挡的位置；已有主角时尽量沿用其位置。画面不能依赖新增道具、虚构光源或修改环境。

            输出：一张 RGBA PNG，尺寸与请求 size 一致，保留原画幅和透明留白。人物位置与比例就是叠回现场后的构图位置与比例，不裁切画布、不居中撑满。完整保留人物及其服装、随身物品，边缘与半透明区域保留合理透明度；其余区域透明，不画白底、棋盘格、环境、地面、背景路人或人物之外的投影。不添加文字、箭头、边框或拼图。

            用户要求：$requirement。在上述人物硬性要求、现场与输出约束内满足；未指定的风格先适配当前环境，无法判断时再参考 APP 本地候选。

            该图像具有alpha通道，背景是透明的。
        """.trimIndent()
    }
}
