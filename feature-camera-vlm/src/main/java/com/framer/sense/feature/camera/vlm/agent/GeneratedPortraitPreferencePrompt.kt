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
        return "人物硬性要求（最高优先级）：指导人像为$avatar，人像种族为$ethnicity。主角的性别、年龄类别和种族以此设置为准，参考图、服饰风格及用户文案中的冲突描述均不得覆盖。原图人物不符合设置时，按设置重新创作人物；符合时可保留其身份特征。种族不绑定服饰、国籍或艺术风格，任何服饰候选均不得改变上述人物设置。"
    }
}
