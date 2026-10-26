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
