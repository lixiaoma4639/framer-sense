package com.framer.sense.feature.camera.vlm.agent

import kotlin.random.Random
import com.framer.sense.feature.camera.vlm.model.GeneratedStyleChoice

/** 现场判断不明确时的随机候选；由 APP 本流程历史去重，不依赖模型记忆。 */
internal object GeneratedPortraitStylePolicy {
    data class Style(val wardrobe: String, val art: String)

    const val SCENE_PRIORITY = "创作优先级：人物设置、真实现场条件、现实比例及输出协议是硬性约束；在这些约束内，先满足用户明确提出的要求，再依据照片中实际可见的环境自主决定未指定的创作内容，最后才考虑 APP 提供的服饰、画风、情绪或动作意图候选。环境自主创作的优先级必须高于所有写死的模板和随机内容。根据空间用途、建筑与陈设语言、材质、文化与时代线索、光线、色彩和现场活动语境，设计具有合理出现依据的造型、人物瞬间与摄影表达，不局限于候选列表。用户未要求主题扮演、时代转换或反差创作时，不主动引入缺少现场依据的时代与主题设定；依据不足时选择适合现场活动语境的造型，不照搬随机服饰。只有现场与用户要求仍无法确定的部分才可参考相容候选；候选与现场不符或需要虚构条件时直接舍弃，不修改现场迁就候选，也不将用户未填写的部分视为必须采用随机内容。"

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

    fun choice(index: Int): GeneratedStyleChoice = select(index).let { GeneratedStyleChoice(it.wardrobe, it.art) }

    fun nextUnusedKey(usedKeys: Set<String>, random: Random = Random.Default): Int? {
        val available = (0 until combinationCount).filterNot { choice(it).key in usedKeys }
        return if (available.isEmpty()) null else available[random.nextInt(available.size)]
    }

    fun prompt(index: Int): String = prompt(choice(index))

    fun prompt(style: GeneratedStyleChoice): String {
        return "APP 随机候选（最低优先级，仅作可舍弃的参考）：可参考的服饰是${style.wardrobe}，艺术表现是${style.art}。这些名称不是用户要求，也不是本轮必须执行的创作方向；仅用于现场与用户要求仍未能确定、且与环境相容的部分，可以全部忽略并自主创作。若候选缺少现场依据或与现场活动语境不协调，舍弃候选，优先采用环境支持的造型与表达。服装、发型与配饰适合所选性别、年龄和种族；艺术表现只作用于人物，保持合理人体结构与现场尺度，不重绘原环境或虚构光源。"
    }
}
