// 声明当前文件属于 Camera PyTorch V2 拍照功能的 UI 包。
package com.framer.sense.feature.camera.pytorch.v2.ui

// 构图引擎：把模型分析结果转换成 UI 可展示的取景引导结果。
class CameraV2CompositionEngine(
    // 虚拟人投影器，负责根据目标区域、姿态模板和真实姿态生成屏幕上的虚拟人形。
    private val projector: VirtualHumanProjector = VirtualHumanProjector(),
    // 姿态模板选择器，负责根据真实姿态和场景类型选择站姿、走姿等模板。
    private val poseTemplateSelector: PoseTemplateSelector = PoseTemplateSelector()
) {

    // 根据一帧相机分析结果和用户体型配置，生成当前帧的构图引导。
    fun buildGuide(
        // 当前帧的 ONNX 分析结果，包含人体、物体、姿态、分割、亮度和场景信息。
        analysis: CameraV2Analysis,
        // 用户体型配置，用于虚拟人投影时调整比例。
        profile: BodyProfile
    ): CameraV2Guide {
        // 先从场景和遮挡信息中选择一个推荐站位区域。
        val targetBounds = chooseTargetBounds(analysis)
        // 根据当前人体姿态和语义场景选择虚拟人的姿态模板。
        val template = poseTemplateSelector.select(analysis.wholeBodyPose, analysis.semanticScene)
        // 从检测到的人体框中选出主要人物，面积和置信度乘积越大越可信。
        val primaryPerson = analysis.people.maxByOrNull { it.bounds.area * it.confidence }
        // 从人体分割结果中选出主要人体轮廓，同样优先面积大且置信度高的结果。
        val primarySegment = analysis.personSegments.maxByOrNull { it.bounds.area * it.confidence }
        // 虚拟人叠加区域优先使用分割框，因为分割边界通常比检测框更贴合人体。
        val humanOverlayBounds: V2Rect = primarySegment?.bounds
            // 没有分割时，使用人体检测框并结合姿态关键点向外扩展，避免手脚被框外截断。
            ?: primaryPerson?.bounds?.expandedWithWholeBodyPose(analysis.wholeBodyPose)
            // 没有检测到人时，退回使用推荐站位区域，让虚拟人继续提供构图参考。
            ?: targetBounds
        // 根据人体区域、体型、姿态模板和真实关键点生成虚拟人图形。
        val figure = projector.project(
            // 虚拟人应该落入或贴合的目标区域。
            targetBounds = humanOverlayBounds,
            // 用户体型参数。
            profile = profile,
            // 当前场景下选中的姿态模板。
            template = template,
            // whole-body 模型产生的 133 点姿态。
            wholeBodyPose = analysis.wholeBodyPose,
            // 人体分割轮廓点，用于更贴合真实人体边缘绘制。
            contourPathPoints = primarySegment?.contour.orEmpty(),
            // 有真实人体或分割结果时，让虚拟人尽量匹配真实人体框；没人时则使用引导框。
            matchTargetBounds = primarySegment != null || primaryPerson != null
        )

        // 如果必要模型资源还没准备好，优先提示模型资源缺失，不继续做普通构图判断。
        if (!analysis.modelAvailability.allRequiredReady) {
            // 返回一条需要调整状态的引导，但移动方向为空，因为问题不是用户站位造成的。
            return guide(
                // 保留推荐站位区域，UI 仍可显示参考框。
                targetBounds = targetBounds,
                // 标记当前质量为需要移动/处理，避免显示为可拍摄状态。
                quality = CameraV2Quality.NEEDS_MOVE,
                // 不要求用户移动。
                movement = CameraV2Movement.NONE,
                // 提示模型资源缺失。
                hint = CameraV2Hint.MODEL_ASSETS_MISSING,
                // 传入原始分析结果，用于组装 guide 的场景、模型状态等字段。
                analysis = analysis,
                // 传入前面已经生成的虚拟人图形。
                figure = figure
            )
        }

        // 如果画面平均亮度低于阈值，优先提示低光环境。
        if (analysis.luminance < LOW_LIGHT_THRESHOLD) {
            // 低光会影响检测和成片质量，所以直接返回 poor 状态。
            return guide(
                // 当前推荐站位区域。
                targetBounds = targetBounds,
                // 质量标记为差。
                quality = CameraV2Quality.POOR,
                // 低光不是通过移动解决，所以不建议方向。
                movement = CameraV2Movement.NONE,
                // 提示光线不足。
                hint = CameraV2Hint.LOW_LIGHT,
                // 当前帧分析结果。
                analysis = analysis,
                // 当前帧虚拟人图形。
                figure = figure
            )
        }

        // 计算所有检测物体覆盖推荐站位区域的比例，用于判断目标位置是否被杂物遮挡。
        val blockedRatio = analysis.objects.sumOf {
            // 对每个物体，计算它和目标区域的交集面积。
            it.bounds.intersectionArea(targetBounds).toDouble()
        // 将总遮挡面积除以目标区域面积，得到 0..1 附近的遮挡比例。
        }.toFloat() / targetBounds.area.coerceAtLeast(0.001f)
        // 如果遮挡比例超过限制，就提示用户向物体较少的方向移动。
        if (blockedRatio > BLOCKED_RATIO_LIMIT) {
            // 根据物体在画面上下左右区域的分布，计算远离杂物的移动方向。
            val movement = movementAwayFromObjects(analysis.objects)
            // 返回场景繁忙/移动方向相关的引导。
            return guide(
                // 仍使用当前推荐站位区域。
                targetBounds = targetBounds,
                // 质量标记为需要移动。
                quality = CameraV2Quality.NEEDS_MOVE,
                // 建议移动方向。
                movement = movement,
                // 把移动方向转换成对应 UI 提示文案。
                hint = movement.toBusyHint(),
                // 当前帧分析结果。
                analysis = analysis,
                // 当前帧虚拟人图形。
                figure = figure
            )
        }

        // 如果没有检测到主要人物，提示用户进入推荐站位区域。
        primaryPerson ?: return guide(
            // 推荐站位区域。
            targetBounds = targetBounds,
            // 没有人时还不能拍，标记为需要移动。
            quality = CameraV2Quality.NEEDS_MOVE,
            // 尚无人体位置，无法给出具体方向。
            movement = CameraV2Movement.NONE,
            // 提示进入引导框。
            hint = CameraV2Hint.ENTER_GUIDE,
            // 当前帧分析结果。
            analysis = analysis,
            // 当前帧虚拟人图形。
            figure = figure
        )

        // 计算真实人体高度和推荐目标区域高度的比例，用来判断人离镜头太近还是太远。
        val sizeRatio = primaryPerson.bounds.height / targetBounds.height.coerceAtLeast(0.001f)
        // 人体相对目标区域过高，说明人太近或占画面太满。
        if (sizeRatio > 1.18f) {
            // 提示后退。
            return guide(
                // 当前目标区域。
                targetBounds = targetBounds,
                // 需要调整站位。
                quality = CameraV2Quality.NEEDS_MOVE,
                // 向后移动。
                movement = CameraV2Movement.BACKWARD,
                // UI 展示后退提示。
                hint = CameraV2Hint.STEP_BACK,
                // 当前帧分析结果。
                analysis = analysis,
                // 当前帧虚拟人图形。
                figure = figure
            )
        }
        // 人体相对目标区域过小，说明人太远或占画面不足。
        if (sizeRatio < 0.45f) {
            // 提示向前走。
            return guide(
                // 当前目标区域。
                targetBounds = targetBounds,
                // 需要调整站位。
                quality = CameraV2Quality.NEEDS_MOVE,
                // 向前移动。
                movement = CameraV2Movement.FORWARD,
                // UI 展示向前提示。
                hint = CameraV2Hint.STEP_FORWARD,
                // 当前帧分析结果。
                analysis = analysis,
                // 当前帧虚拟人图形。
                figure = figure
            )
        }

        // 计算人体中心和目标区域中心在水平方向的偏移，负数表示人在目标左侧。
        val horizontalOffset = primaryPerson.bounds.centerX - targetBounds.centerX
        // 如果人体明显偏左，提示继续向左移动以对齐当前目标区域。
        if (horizontalOffset < -CENTER_TOLERANCE_X) {
            // 返回左移引导。
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.LEFT, CameraV2Hint.MOVE_LEFT, analysis, figure)
        }
        // 如果人体明显偏右，提示向右移动。
        if (horizontalOffset > CENTER_TOLERANCE_X) {
            // 返回右移引导。
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.RIGHT, CameraV2Hint.MOVE_RIGHT, analysis, figure)
        }

        // 计算人体中心和目标区域中心在垂直方向的偏移，负数表示人在目标上方。
        val verticalOffset = primaryPerson.bounds.centerY - targetBounds.centerY
        // 如果人体偏上，提示上移或调整画面向上对齐。
        if (verticalOffset < -CENTER_TOLERANCE_Y) {
            // 返回上移引导。
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.UP, CameraV2Hint.MOVE_UP, analysis, figure)
        }
        // 如果人体偏下，提示下移或调整画面向下对齐。
        if (verticalOffset > CENTER_TOLERANCE_Y) {
            // 返回下移引导。
            return guide(targetBounds, CameraV2Quality.NEEDS_MOVE, CameraV2Movement.DOWN, CameraV2Hint.MOVE_DOWN, analysis, figure)
        }

        // 所有质量检查都通过，返回构图良好的状态。
        return guide(
            // 当前目标区域。
            targetBounds = targetBounds,
            // 构图质量良好。
            quality = CameraV2Quality.GOOD,
            // 不需要移动。
            movement = CameraV2Movement.NONE,
            // UI 展示良好提示。
            hint = CameraV2Hint.GOOD,
            // 当前帧分析结果。
            analysis = analysis,
            // 当前帧虚拟人图形。
            figure = figure
        )
    }

    // 从候选构图区域中选择最适合当前场景的目标站位区域。
    private fun chooseTargetBounds(analysis: CameraV2Analysis): V2Rect =
        // 根据场景类型生成左、中、右三个候选区域，并选择评分最高的一个。
        candidatesFor(analysis.semanticScene.group).maxBy { candidate ->
            // 物体惩罚：候选区域和物体重叠越多，分数越低。
            val objectPenalty = analysis.objects.sumOf {
                // 重叠面积乘以物体置信度权重，越可信的物体惩罚越重。
                candidate.intersectionArea(it.bounds).toDouble() * (1.0 + it.confidence)
            }
            // 人体惩罚：候选区域中心离已有人的位置越近，越不适合作为重新站位目标。
            val personPenalty = analysis.people.sumOf {
                // 中心距离乘以权重，避免目标区域和当前人体位置过度冲突。
                candidate.centerDistanceTo(it.bounds).toDouble() * PERSON_REPOSITION_WEIGHT
            }
            // 三分法偏好：不同场景会偏好不同水平中心，比如城市偏右、自然偏左。
            val thirdsPreference = 1.0 - kotlin.math.abs(candidate.centerX - preferredCenterX(analysis.semanticScene.group))
            // 垂直空间偏好：候选区域顶部越接近经验值 0.16，越适合作为人物站位。
            val verticalSpace = 1.0 - kotlin.math.abs(candidate.top - 0.16f)
            // 综合评分：场景偏好和垂直空间加分，物体遮挡和人体冲突扣分。
            thirdsPreference + verticalSpace * 0.25 - objectPenalty * OBJECT_OVERLAP_WEIGHT - personPenalty
        }

    // 根据场景分组生成三个候选站位矩形。
    private fun candidatesFor(group: SceneGroup): List<V2Rect> {
        // 室内顶部留白略多，避免头顶离画面太近。
        val top = if (group == SceneGroup.INDOOR) 0.18f else 0.14f
        // 自然场景底部区域略低，允许人物站得更完整。
        val bottom = if (group == SceneGroup.NATURE) 0.93f else 0.90f
        // 返回左、中、右三个归一化矩形，供 chooseTargetBounds 评分。
        return listOf(
            // 左侧候选人物区域。
            V2Rect(0.10f, top, 0.46f, bottom),
            // 中间候选人物区域。
            V2Rect(0.32f, top, 0.68f, bottom),
            // 右侧候选人物区域。
            V2Rect(0.54f, top, 0.90f, bottom)
        )
    }

    // 根据场景分组给出推荐的水平构图中心。
    private fun preferredCenterX(group: SceneGroup): Float =
        // 城市和自然场景使用轻微偏移，其他场景保持居中。
        when (group) {
            // 城市场景偏右，给环境线条或建筑空间留出构图余量。
            SceneGroup.URBAN -> 0.62f
            // 自然场景偏左，让人物和环境空间形成更自然的平衡。
            SceneGroup.NATURE -> 0.38f
            // 室内、户外未知等场景默认居中。
            else -> 0.50f
        }

    // 根据画面中物体的分布，选择一个远离遮挡物的移动方向。
    private fun movementAwayFromObjects(objects: List<SceneObject>): CameraV2Movement {
        // 统计所有物体落在画面左半区的总面积。
        val left = objects.sumOf { it.bounds.intersectionArea(LEFT_ZONE).toDouble() }
        // 统计所有物体落在画面右半区的总面积。
        val right = objects.sumOf { it.bounds.intersectionArea(RIGHT_ZONE).toDouble() }
        // 统计所有物体落在画面上半区的总面积。
        val top = objects.sumOf { it.bounds.intersectionArea(TOP_ZONE).toDouble() }
        // 统计所有物体落在画面下半区的总面积。
        val bottom = objects.sumOf { it.bounds.intersectionArea(BOTTOM_ZONE).toDouble() }
        // 比较上下左右的遮挡面积，往遮挡较少的相反方向引导。
        return when {
            // 左侧物体明显更多时，提示向右避开。
            left > right * AREA_BIAS -> CameraV2Movement.RIGHT
            // 右侧物体明显更多时，提示向左避开。
            right > left * AREA_BIAS -> CameraV2Movement.LEFT
            // 上方物体明显更多时，提示向下避开。
            top > bottom * AREA_BIAS -> CameraV2Movement.DOWN
            // 下方物体明显更多时，提示向上避开。
            bottom > top * AREA_BIAS -> CameraV2Movement.UP
            // 没有明显方向时默认向右，给 UI 一个稳定兜底方向。
            else -> CameraV2Movement.RIGHT
        }
    }

    // 将移动方向转换成场景拥挤时的 UI 提示。
    private fun CameraV2Movement.toBusyHint(): CameraV2Hint =
        // 只有明确方向时展示方向提示，否则展示场景繁忙。
        when (this) {
            // 左移提示。
            CameraV2Movement.LEFT -> CameraV2Hint.MOVE_LEFT
            // 右移提示。
            CameraV2Movement.RIGHT -> CameraV2Hint.MOVE_RIGHT
            // 上移提示。
            CameraV2Movement.UP -> CameraV2Hint.MOVE_UP
            // 下移提示。
            CameraV2Movement.DOWN -> CameraV2Hint.MOVE_DOWN
            // 前后或无方向时，使用通用场景繁忙提示。
            else -> CameraV2Hint.SCENE_BUSY
        }

    // 根据姿态关键点扩展人体检测框，让框覆盖手脚等可能超出检测框的部位。
    private fun V2Rect.expandedWithWholeBodyPose(pose: WholeBodyPoseEstimate): V2Rect {
        // 取出置信度足够高的姿态关键点，低置信度点不参与框扩展。
        val posePoints = pose.keypoints
            // 过滤低可信关键点，避免异常点把框拉得过大。
            .filter { it.confidence >= POSE_BOX_KEYPOINT_THRESHOLD }
            // 只保留关键点坐标。
            .map { it.point }
        // 没有可靠关键点时，只对原人体框做固定 padding 扩展。
        if (posePoints.isEmpty()) return expand(PERSON_BOX_PADDING)
        // 用原人体框和所有可靠关键点共同计算新的外接矩形。
        val poseBounds = V2Rect(
            // 左边界取原框左边和所有关键点 x 的最小值。
            left = minOf(left, posePoints.minOf { it.x }),
            // 上边界取原框上边和所有关键点 y 的最小值。
            top = minOf(top, posePoints.minOf { it.y }),
            // 右边界取原框右边和所有关键点 x 的最大值。
            right = maxOf(right, posePoints.maxOf { it.x }),
            // 下边界取原框下边和所有关键点 y 的最大值。
            bottom = maxOf(bottom, posePoints.maxOf { it.y })
        )
        // 对融合姿态后的外接矩形再扩展一点，避免虚拟人贴边。
        return poseBounds.expand(PERSON_BOX_PADDING)
    }

    // 将矩形四边按指定 padding 外扩，并限制在 0..1 的归一化画面范围内。
    private fun V2Rect.expand(padding: Float): V2Rect =
        // 构造外扩后的矩形，再调用 clamped 防止越界。
        V2Rect(left - padding, top - padding, right + padding, bottom + padding).clamped()

    // 统一创建 CameraV2Guide，避免多个分支重复填写相同字段。
    private fun guide(
        // 推荐站位或匹配区域。
        targetBounds: V2Rect,
        // 当前构图质量。
        quality: CameraV2Quality,
        // 当前建议移动方向。
        movement: CameraV2Movement,
        // 当前 UI 提示类型。
        hint: CameraV2Hint,
        // 当前帧分析结果。
        analysis: CameraV2Analysis,
        // 当前帧虚拟人图形。
        figure: VirtualHumanFigure
    ): CameraV2Guide =
        // 将分析结果和构图判断汇总成 UI 状态对象。
        CameraV2Guide(
            // 目标区域。
            targetBounds = targetBounds,
            // 构图质量。
            quality = quality,
            // 移动方向。
            movement = movement,
            // 提示类型。
            hint = hint,
            // 语义场景。
            semanticScene = analysis.semanticScene,
            // 虚拟人图形。
            virtualHuman = figure,
            // 模型可用性。
            modelAvailability = analysis.modelAvailability,
            // 当前帧宽高比。
            frameAspectRatio = analysis.frameAspectRatio
        )

    // 构图引擎内部常量。
    private companion object {
        // 平均亮度低于该值时认为环境过暗。
        const val LOW_LIGHT_THRESHOLD = 42.0
        // 目标区域被物体遮挡超过该比例时认为场景繁忙。
        const val BLOCKED_RATIO_LIMIT = 0.26f
        // 物体遮挡在候选区域评分里的惩罚权重。
        const val OBJECT_OVERLAP_WEIGHT = 3.1
        // 当前人体位置对重新选择站位区域的影响权重。
        const val PERSON_REPOSITION_WEIGHT = 0.16
        // 水平方向中心对齐容忍值，超过后提示左右移动。
        const val CENTER_TOLERANCE_X = 0.065f
        // 垂直方向中心对齐容忍值，超过后提示上下移动。
        const val CENTER_TOLERANCE_Y = 0.075f
        // 判断某侧遮挡明显更多的面积倍率。
        const val AREA_BIAS = 1.2
        // 人体框扩展比例，避免虚拟人或轮廓贴边。
        const val PERSON_BOX_PADDING = 0.025f
        // 姿态关键点参与人体框扩展所需的最低置信度。
        const val POSE_BOX_KEYPOINT_THRESHOLD = 0.2f

        // 画面左半区，用于统计物体遮挡方向。
        val LEFT_ZONE = V2Rect(0f, 0f, 0.5f, 1f)
        // 画面右半区，用于统计物体遮挡方向。
        val RIGHT_ZONE = V2Rect(0.5f, 0f, 1f, 1f)
        // 画面上半区，用于统计物体遮挡方向。
        val TOP_ZONE = V2Rect(0f, 0f, 1f, 0.5f)
        // 画面下半区，用于统计物体遮挡方向。
        val BOTTOM_ZONE = V2Rect(0f, 0.5f, 1f, 1f)
    }
}
