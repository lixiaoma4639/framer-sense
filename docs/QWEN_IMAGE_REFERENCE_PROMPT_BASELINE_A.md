# Qwen-Image 提示词 A 版对照

2026-10-26 优化前源码快照，仅供对照，不参与运行。当前 APP 使用 B 版，不同时生成两份结果。

```kotlin
package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.GeneratedPortraitPreferences

/** 千问独立提示词；不复用或修改 Seedream 的原生图层创作约束。 */
object QwenImageReferencePrompt {
    fun build(instruction: String, variation: Int, preferences: GeneratedPortraitPreferences? = null): String {
        val requirement = instruction.trim().take(2000).ifBlank { "未提供特殊要求，请由 AI 自主创作。" }
        val preferenceText = preferences?.let(GeneratedPortraitPreferencePrompt::build).orEmpty()
        return """
            参考上传的真实现场照片，设计一张以人物为核心、具有优秀摄影作品或影视画面艺术表现力的构图。最终仅输出用于叠回该现场的透明背景人物 PNG 素材，而非现场背景图或白底人物照。

            一、结合现场完成构图设计
            请结合现场已有光影、色彩、空间层次、线条与留白，综合设计人物景别、画面位置、身体线条、姿势、手势、头部朝向、视线、表情和情绪，使人物叠回参考照片原位置后形成有主次、有艺术取舍和故事表达的画面。没有特殊要求时自主选择适合环境的艺术表现，不固定自然、克制或某种姿势，不仅仅生成居中站立的素材。

            二、人物主体
            参考照片中有明确的主角时，以同一人物为主体，保留身份特征、脸型、发型、体型、服装、颜色、花纹和配饰；用户未要求时不替换外观，不将背景路人作为主角。没有明确主角时，结合现场与用户要求创造真人形象、动漫感人物或原创人形角色。必须输出人物，不能把家具、建筑或其他物体作为主体。

            三、现场约束与现实尺度
            原照片环境由 APP 原样保留；不得依赖改变建筑、背景结构、物体位置、透视、天气、时段、光线方向或新增道具来成立构图。人物受光、色温和阴影应与现场协调。保持原拍摄视角、完整画幅比例和画布边界，不裁切、扩展或重新取景。
            根据人物所在位置的透视和空间深度，参考相近深度的环境物体确定合理身高、体量、位置及遮挡关系，比例符合现实、近大远小，不缩成微型，不不合理放大，不用固定画面占比替代尺度判断。动漫或原创角色同样遵守现场尺度。
            只使用照片中可确认的地面、座位与支撑，不虚构地面或支撑。缺少依据时选择特写、半身或多半身，不补出不存在的地面。动作应可在现场模仿，肢体结构、手指和重心合理。优先选择无遮挡、便于叠回原图的人物位置，不依赖新增环境或重新绘制原环境。若原图已有主角，其位置应尽量与原人物重合，减少叠加后残留原人物的部分。

            四、透明图片输出
            只输出一张真正带 Alpha 通道的 RGBA PNG，画布尺寸与请求 size 完全一致。人物在画布中的位置、比例就是最终摄影构图中的位置、比例，保留周围透明留白，不自动裁切人物边界，不将人物重新居中放大。
            保留人物的头发、服装、飘带、配饰和原有手持物，人物之外全部透明；不绘制现场背景、白底、纯色底、棋盘格背景、背景路人、地面或人物之外的投影。半透明发丝、薄纱保留合理透明度。不添加文字、标签、箭头、边框或拼图，不输出多张图片，不请求图层拆分或另一次抠图。

            五、用户要求
            在现场与真实比例约束内优先满足用户明确的人物、风格、动作、表情、情绪和景别要求，未指定部分自主创作。本轮创作序号为 ${variation + 1}，此序号不代表你已见过之前的图片，不套用固定姿势模板。
            $preferenceText
            用户要求：$requirement
        """.trimIndent()
    }
}
```

## 当时的拍照设置语义映射

```kotlin
package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.*

/** 两家只共用拍照设置的语义映射，各自创作与图片输出模板独立。 */
internal object GeneratedPortraitPreferencePrompt {
    fun build(preferences: GeneratedPortraitPreferences): String {
        val avatar = when (preferences.avatar) {
            AvatarId.ADULT_FEMALE -> "成年女性"
            AvatarId.ADULT_MALE -> "成年男性"
            AvatarId.CHILD_GIRL -> "儿童女"
            AvatarId.CHILD_BOY -> "儿童男"
        }
        val ethnicity = when (preferences.ethnicity) {
            AvatarEthnicity.WHITE -> "白种人"
            AvatarEthnicity.ASIAN -> "亚洲人"
            AvatarEthnicity.BLACK -> "黑人"
        }
        return "拍照设置：指导人像为$avatar，人像种族为$ethnicity。没有明确主角时，将这些设置作为创造人物的默认要求。原照片已有明确主角时保留其身份、年龄、外观和种族，不因默认设置替换主角；用户文字明确指定的人物要求优先于这些默认设置。"
    }
}
```
