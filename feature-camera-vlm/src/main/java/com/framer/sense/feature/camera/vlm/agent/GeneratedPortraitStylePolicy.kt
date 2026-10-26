package com.framer.sense.feature.camera.vlm.agent

/** APP 为未指定的风格提供轮换建议；不依赖模型记忆、不解析或改写用户文案。 */
internal object GeneratedPortraitStylePolicy {
    data class Style(val wardrobe: String, val art: String)

    private val wardrobes = listOf("现代服饰", "汉服", "日式服饰", "韩式服饰", "美式服饰")
    private val arts = listOf("现实摄影", "科幻", "赛博朋克", "油画")

    fun select(index: Int): Style = Style(
        wardrobes[Math.floorMod(index, wardrobes.size)], arts[Math.floorMod(index, arts.size)]
    )

    fun prompt(index: Int): String {
        val style = select(index)
        return "本轮风格安排：用户未指定服饰风格时采用${style.wardrobe}，未指定艺术风格时采用${style.art}。用户明确指定的服饰、艺术风格或保留原服饰要求优先于本轮安排；只对未指定的维度采用安排，不要求用户额外选择。服装、发型和配饰应适合所选性别与年龄，不固定女性汉服。艺术表现作用于人物，仍适配原环境的光线、透视和现实尺度，不依赖重绘现场。"
    }
}
