package com.framer.sense.feature.camera.vlm.agent

import kotlin.random.Random

/** 现场判断不明确时的随机候选；由 APP 本流程历史去重，不依赖模型记忆。 */
internal object GeneratedPortraitStylePolicy {
    data class Style(val wardrobe: String, val art: String)

    const val SCENE_PRIORITY = "人物风格优先规则（高优先级）：遵守性别、年龄类别与种族设置；用户明确指定的服饰、艺术风格或保留原服饰要求优先。用户未指定的维度，先根据当前环境的建筑、空间、光线、色彩和氛围判断并创作对应风格，不局限于 APP 风格列表；只有无法判断环境适合什么风格时，才参考 APP 本地候选，不让本地候选覆盖明确的用户要求或现场判断。"

    // 长期流行风格与当代文化并列，属于创作分类而非热度排名；不固定性别或种族。
    private val wardrobes = listOf(
        "现代简约服饰", "都市商务与剪裁服饰", "街头潮流服饰", "运动休闲服饰", "户外机能服饰",
        "工装服饰", "牛仔服饰", "学院与常春藤服饰", "日系都市休闲服饰", "韩系都市时尚服饰",
        "美式休闲服饰", "法式日常服饰", "北欧极简服饰", "地中海度假服饰", "波西米亚服饰",
        "摇滚朋克服饰", "哥特暗黑服饰", "Y2K 千禧服饰", "复古迪斯科服饰", "复古港风服饰",
        "汉服", "新中式服饰", "日式传统服饰", "韩式传统服饰", "南亚传统与现代融合服饰",
        "非洲印花现代服饰", "拉美热带与都市服饰", "中东现代服饰", "波普多巴胺穿搭", "现代正式礼服",
        "未来机能服饰", "流行音乐与 K-pop 舞台服饰", "幻想冒险角色服饰"
    )
    private val arts = listOf(
        "现实摄影", "电影叙事", "胶片摄影", "时尚杂志摄影", "纪实街头摄影",
        "复古港片美学", "复古好莱坞美学", "复古未来主义", "科幻", "赛博朋克", "蒸汽朋克",
        "太阳朋克", "奇幻史诗", "魔幻现实主义", "日系动漫", "欧美漫画", "韩系网络漫画",
        "三维动画角色", "平涂插画", "厚涂数字绘画", "波普艺术", "街头涂鸦插画",
        "艺术装饰 Art Deco", "新艺术 Art Nouveau", "油画", "印象派绘画", "水彩", "水墨",
        "粉彩绘画", "版画", "超现实主义"
    )
    val wardrobeCount: Int get() = wardrobes.size
    val artCount: Int get() = arts.size
    val combinationCount: Int get() = wardrobeCount * artCount

    /** 每个 ID 对应一个笛卡尔积组合，不要求两池数量互质。 */
    fun select(index: Int): Style {
        val normalized = Math.floorMod(index, combinationCount)
        return Style(wardrobes[normalized / artCount], arts[normalized % artCount])
    }

    /** 无放回抽样；耗尽时返回空，由入口停止提交，不能悄悄重复旧组合。 */
    fun nextIndex(usedIndices: Set<Int>, random: Random = Random.Default): Int? {
        val available = (0 until combinationCount).filterNot { it in usedIndices }
        return if (available.isEmpty()) null else available[random.nextInt(available.size)]
    }

    fun prompt(index: Int): String {
        val style = select(index)
        return "APP 本地兜底候选（仅在无法判断现场适合的风格时参考）：服饰为${style.wardrobe}，艺术表现为${style.art}。这不是本轮必选风格；两项可按现场与人物设置协调选用，不机械拼接不相容的元素。服装、发型与配饰适合所选性别、年龄和种族。艺术表现只作用于人物，保持合理人体结构与现场尺度，不重绘原环境或虚构光源。"
    }
}
