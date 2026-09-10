package com.framer.sense.feature.camera.vlm.agent

import com.framer.sense.feature.camera.vlm.model.*
import kotlin.math.abs

/** 检查可执行约束；不将未知地面条件误判为可靠空间定位。 */
class PlanValidator {
    /** 校验一批方案及修改范围。
     * @param plans 模型返回的候选方案。
     * @param input 原始画面、设备约束及需要保留的其他方案。
     * @return 所有错误；空列表表示结构与已知几何约束通过。
     */
    fun validate(plans: List<CompositionPlan>, input: DirectorInput): List<String> = buildList {
        if (plans.size != 3) add("必须提供三个方案")
        if (plans.map { it.id }.distinct().size != plans.size) add("方案编号重复")
        if (plans.map { listOf(it.shot, it.avatar.pose, it.crop, it.avatar.foot, it.avatar.height) }.distinct().size < 3) add("三个方案缺少差异")
        plans.forEach { p ->
            val c = p.crop
            val a = p.avatar
            val numbers = listOf(c.left, c.top, c.right, c.bottom, p.zoom, a.foot.x, a.foot.y, a.height, a.yaw, a.expressionIntensity)
            if (numbers.any { !it.isFinite() }) add("${p.id}: 存在非有限数值")
            if (p.revision < 0) add("${p.id}: 方案版本不能为负数")
            if (p.id.isBlank() || p.title.isBlank() || p.guidance.isBlank()) add("${p.id}: 缺少编号、标题或拍摄指导")
            if (!(c.left >= 0f && c.top >= 0f && c.right <= 1f && c.bottom <= 1f && c.right > c.left && c.bottom > c.top)) add("${p.id}: 裁剪框越界或为空")
            if (abs((c.right - c.left) - (c.bottom - c.top)) > 0.015f) add("${p.id}: 首版裁剪必须保持原图画幅")
            if (p.zoom !in input.camera.minZoom..input.camera.maxZoom) add("${p.id}: 倍率超出设备范围")
            if (!p.needsRetake && c.right > c.left) {
                val expected = input.scene.currentZoom / (c.right - c.left)
                if (abs(expected - p.zoom) > 0.06f * expected) add("${p.id}: 裁剪与倍率不一致")
                if (abs((c.left + c.right) / 2f - 0.5f) > 0.02f || abs((c.top + c.bottom) / 2f - 0.5f) > 0.02f) add("${p.id}: 偏心取景需要标记重新取景")
            }
            if (a.height !in 0.08f..4f || a.foot.x !in 0f..1f || a.foot.y !in 0f..4f) add("${p.id}: 人物布局不在允许范围")
            if (a.yaw !in -180f..180f || a.expressionIntensity !in 0f..1f) add("${p.id}: 朝向或表情强度越界")
            if (p.shot in listOf(ShotType.FULL, ShotType.ENVIRONMENT) && (a.foot.y > 1f || a.foot.y - a.height < 0f)) add("${p.id}: 全身/环境人像的头脚被裁掉")
            val cutHeight = (a.foot.y - 1f) / a.height
            if (p.shot == ShotType.HALF && (cutHeight !in 0.20f..0.58f || a.foot.y - a.height < -.02f)) add("${p.id}: 半身景别应在腰到大腿处裁切并保留头部")
            if (p.shot == ShotType.CLOSE_UP && (cutHeight !in 0.58f..0.84f || a.foot.y - a.height < -.02f)) add("${p.id}: 特写应在胸口附近裁切并保留头部")
            if (a.foot.y - a.height > 0.9f || a.foot.y < 0.1f) add("${p.id}: 人物几乎不可见")
        }
        if (input.selectedId != null) {
            if (plans.map { it.id }.toSet() != input.existing.map { it.id }.toSet()) add("修改必须保留原有三个方案编号")
            input.existing.filter { it.id != input.selectedId }.forEach { old ->
                if (plans.find { it.id == old.id } != old) add("只能修改选中的方案 ${input.selectedId}")
            }
        }
    }
}
