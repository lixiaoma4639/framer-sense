package com.framer.sense.feature.camera.pytorch.v2.ui

import kotlin.math.abs
import kotlin.math.max

// 虚拟人投影器：把目标框、体型、姿态模板和模型关键点转换成 UI 可绘制的虚拟人数据。
class VirtualHumanProjector {

    /** 无可用人体 pose、分割轮廓或 WholeBody 内轮廓时使用的汉服女性虚线引导模板。 */
    // 生成默认汉服引导图，用于初始状态或没有可靠人体信息时的兜底提示。
    fun projectDefaultHanfuGuide(
        // 汉服引导图应该放置的目标区域。
        targetBounds: V2Rect,
        // 用户体型配置，用于控制虚拟人宽高比例。
        profile: BodyProfile
    ): VirtualHumanFigure =
        // 复用通用 project 逻辑，只是固定使用放松站立模板和更宽的初始汉服比例。
        project(
            // 目标站位区域。
            targetBounds = targetBounds,
            // 体型配置。
            profile = profile,
            // 默认汉服引导使用放松站立姿态。
            template = PoseTemplate.RELAXED_STAND,
            // 初始引导图略宽，让汉服大袖和裙摆更明显。
            hanfuGuideWidthScale = INITIAL_HANFU_GUIDE_WIDTH_SCALE
        )

    // 根据目标区域和模型结果生成虚拟人图形。
    fun project(
        // UI 希望虚拟人放入的目标框，坐标是 0..1 的归一化画面坐标。
        targetBounds: V2Rect,
        // 用户身高/宽度比例配置。
        profile: BodyProfile,
        // 没有真实姿态可用时使用的姿态模板。
        template: PoseTemplate,
        // WholeBody 输出的 133 点姿态，默认为空。
        wholeBodyPose: WholeBodyPoseEstimate = WholeBodyPoseEstimate.Empty,
        // 人体分割轮廓点，默认没有。
        contourPathPoints: List<V2Point> = emptyList(),
        // true 表示虚拟人直接匹配目标框；false 表示根据体型在目标框内重新计算人物框。
        matchTargetBounds: Boolean = false,
        // 汉服引导图相对普通人物框的宽度放大比例。
        hanfuGuideWidthScale: Float = HANFU_GUIDE_WIDTH_SCALE
    ): VirtualHumanFigure {
        // 计算虚拟人的基础边界框。
        val bounds = if (matchTargetBounds) {
            // 有真实人体框或分割框时，直接使用目标框，并确保它不会太小。
            targetBounds.clamped().ensureMinimumSize()
        } else {
            // 没有真实人体匹配时，根据目标框高度和用户体型算一个虚拟人高度。
            val figureHeight = (targetBounds.height * profile.heightScale).coerceIn(
                // 最小不低于目标框高度的 86%，避免虚拟人过矮。
                targetBounds.height * 0.86f,
                // 最大不超过画面 82%，也不超过目标框高度的 108%，避免虚拟人过高。
                minOf(0.82f, targetBounds.height * 1.08f)
            )
            // 让人物下半身贴近目标框底部，计算人物中心 y。
            val centerY = targetBounds.bottom - figureHeight / 2f
            // 根据中心和高度计算顶部，并保证头顶至少留 6% 画面空间。
            val top = (centerY - figureHeight / 2f).coerceAtLeast(0.06f)
            // 根据顶部和高度计算底部，并限制不超过画面 96%。
            val bottom = (top + figureHeight).coerceAtMost(0.96f)
            // 根据目标框宽度和体型宽度比例计算人物宽度。
            val width = (targetBounds.width * profile.widthScale).coerceIn(0.20f, 0.48f)
            // 让人物水平居中到目标框中心，并限制在画面左右边界内。
            val left = (targetBounds.centerX - width / 2f).coerceIn(0.04f, 0.96f - width)
            // 构造最终人物边界框。
            V2Rect(left, top, left + width, bottom)
        }

        // 仅在身体锚点可靠时使用 RTMPose；否则完整退回汉服构图引导。
        val wholeBodyGuidePose = wholeBodyPose.takeIf { it.isReliableForGuide() } ?: WholeBodyPoseEstimate.Empty
        // 构造虚拟人的 3D 姿态点；优先使用 RTMPose 身体点，不可用时使用模板点。
        val posePoints: PosePointSet = buildPosePoints(template, profile, wholeBodyGuidePose, bounds)
        // 把 3D 姿态点投影成 2D 画面点。
        val projected = posePoints.points.mapValues { (_, point) ->
            // 将一个 3D 关节点投影到归一化屏幕坐标。
            projectPoint(bounds, point)
        }
        // 根据骨架连接表生成骨架线。
        val lines: List<VirtualHumanLine> = SKELETON.mapNotNull { bone ->
            // 取骨骼起点的 2D 投影点。
            val start = projected[bone.first]
            // 取骨骼终点的 2D 投影点。
            val end = projected[bone.second]
            // 取骨骼起点的 3D 点，用于计算深度。
            val start3d = posePoints.points[bone.first]
            // 取骨骼终点的 3D 点，用于计算深度。
            val end3d = posePoints.points[bone.second]
            // 如果任意端点缺失，这根骨骼线就不画。
            if (start == null || end == null || start3d == null || end3d == null) {
                // mapNotNull 中返回 null 表示跳过该骨骼。
                null
            } else {
                // 构造一条虚拟人骨架线。
                VirtualHumanLine(
                    // 线段起点。
                    start = start,
                    // 线段终点。
                    end = end,
                    // 深度取两端 3D 深度平均值，供绘制层次使用。
                    depth = (start3d.z + end3d.z) / 2f
                )
            }
        }
        // 判断 WholeBody 模型是否提供了可用的 133 点内轮廓信息。
        val hasWholeBodyContour = wholeBodyGuidePose.confidence >= WHOLE_BODY_CONFIDENCE_THRESHOLD &&
            // 必须有关键点，只有置信度没有点也不能构成轮廓。
            wholeBodyGuidePose.keypoints.isNotEmpty()
        // 判断是否使用汉服引导模板。
        val useHanfuGuide = !posePoints.poseDriven &&
            // 没有分割轮廓时才需要汉服兜底轮廓。
            contourPathPoints.isEmpty() &&
            // 没有 WholeBody 轮廓时才画汉服模板。
            !hasWholeBodyContour
        // 汉服模板需要比普通人体框略宽，用来容纳宽袖和裙摆。
        val visualBounds = if (useHanfuGuide) {
            // 把普通人物框横向放大。
            bounds.widenedForHanfuGuide(hanfuGuideWidthScale)
        } else {
            // 非汉服模板直接使用人物框。
            bounds
        }

        // 返回 UI 绘制所需的完整虚拟人数据。
        return VirtualHumanFigure(
            // 可视边界框。
            bounds = visualBounds,
            // 当前使用的姿态模板。
            template = template,
            // 只有真实姿态驱动时才输出骨架线；模板汉服状态不画骨架。
            lines = if (posePoints.poseDriven) lines else emptyList(),
            // 用 WholeBody 关键点构造内部人体轮廓线。
            innerContourLines = WholeBodyInnerContourBuilder.build(wholeBodyGuidePose),
            // 每个高置信度 WholeBody 点均输出给覆盖层，保证 133 点全量参与引导绘制。
            innerContourPoints = WholeBodyInnerContourBuilder.points(wholeBodyGuidePose),
            // 头部中心优先使用投影出来的 HEAD 点，否则按边界框顶部估算。
            headCenter = projected[Joint.HEAD] ?: V2Point(visualBounds.centerX, visualBounds.top + visualBounds.height * 0.10f),
            // 如果允许画头部，半径按可视宽度比例计算，并设置最小值。
            headRadius = if (posePoints.drawHead) max(visualBounds.width * 0.12f, 0.025f) else 0f,
            // 人体分割轮廓点，交给 overlay 画真实外轮廓。
            contourPathPoints = contourPathPoints,
            // 当前这里固定不单独画圆头，避免和汉服/轮廓样式重复。
            drawHead = false,
            // 标记当前虚拟人是否由真实 pose 驱动。
            poseDriven = posePoints.poseDriven,
            // 决定 overlay 使用汉服路径绘制还是骨架绘制。
            visualStyle = if (useHanfuGuide) {
                // 汉服虚线引导样式。
                VirtualHumanVisualStyle.HANFU_GUIDE
            } else {
                // 普通骨架/轮廓样式。
                VirtualHumanVisualStyle.SKELETON
            },
            // 汉服样式时生成装饰路径，否则没有装饰路径。
            decorativePaths = if (useHanfuGuide) buildHanfuGuidePaths(visualBounds) else emptyList()
        )
    }

    // 确保真实人体匹配框不会小到影响虚拟人绘制。
    private fun V2Rect.ensureMinimumSize(): V2Rect {
        // 宽度至少达到最小匹配宽度。
        val adjustedWidth = width.coerceAtLeast(MIN_MATCHED_WIDTH)
        // 高度至少达到最小匹配高度。
        val adjustedHeight = height.coerceAtLeast(MIN_MATCHED_HEIGHT)
        // 以原中心为基准重新计算左边界，并限制在画面内。
        val left = (centerX - adjustedWidth / 2f).coerceIn(0f, 1f - adjustedWidth)
        // 以原中心为基准重新计算上边界，并限制在画面内。
        val top = (centerY - adjustedHeight / 2f).coerceIn(0f, 1f - adjustedHeight)
        // 返回扩到最小尺寸后的矩形。
        return V2Rect(left, top, left + adjustedWidth, top + adjustedHeight)
    }

    // 为汉服引导图横向扩展边界框。
    private fun V2Rect.widenedForHanfuGuide(widthScale: Float): V2Rect {
        // 按给定比例放大宽度，同时限制最大宽度，避免汉服占满画面。
        val widenedWidth = (width * widthScale).coerceAtMost(MAX_HANFU_GUIDE_WIDTH)
        // 以原中心为基准计算放大后的左边界，并保留 2% 边距。
        val widenedLeft = (centerX - widenedWidth / 2f).coerceIn(0.02f, 0.98f - widenedWidth)
        // 返回横向放大后的可视边界框。
        return V2Rect(widenedLeft, top, widenedLeft + widenedWidth, bottom)
    }

    // 构建汉服女性虚线引导模板的所有矢量路径。
    private fun buildHanfuGuidePaths(bounds: V2Rect): List<VirtualHumanStrokePath> {
        // 将模板内部 0..1 的相对点映射到实际可视边界框 bounds 内。
        fun point(x: Float, y: Float): V2Point =
            // 返回归一化画面坐标。
            V2Point(
                // x = bounds 左边 + bounds 宽度 * 模板内部 x 比例。
                x = (bounds.left + bounds.width * x).coerceIn(0f, 1f),
                // y = bounds 顶部 + bounds 高度 * 模板内部 y 比例。
                y = (bounds.top + bounds.height * y).coerceIn(0f, 1f)
            )

        // 把一组模板坐标转换成一条可绘制路径。
        fun path(
            // 路径角色，例如外轮廓、头发、脸、袖子、腰饰、裙褶。
            role: VirtualHumanPathRole,
            // 路径中的模板坐标点。
            vararg coordinates: Pair<Float, Float>,
            // 是否闭合路径；闭合路径适合脸、袖子、整体外形等区域。
            closed: Boolean = false,
            // 视觉深度，用于 overlay 中排序和调整线条粗细。
            depth: Float = 0f,
            // 是否平滑连接路径点。
            smooth: Boolean = true
        ): VirtualHumanStrokePath =
            // 构造虚拟人描边路径。
            VirtualHumanStrokePath(
                // 保存路径角色。
                role = role,
                // 把模板相对坐标逐个映射到画面归一化坐标。
                points = coordinates.map { (x, y) -> point(x, y) },
                // 保存是否闭合。
                closed = closed,
                // 保存深度。
                depth = depth,
                // 保存是否平滑。
                smooth = smooth
            )

        // 返回汉服模板所有组成部分。
        return listOf(
            // 整体外轮廓：从头肩开始，经过大袖、裙摆，再闭合回身体另一侧。
            path(
                // OUTLINE 表示整个人像外形轮廓。
                VirtualHumanPathRole.OUTLINE,
                // 上半身左侧到左袖轮廓点。
                0.49f to 0.13f, 0.39f to 0.18f, 0.25f to 0.27f, 0.16f to 0.40f,
                // 左袖下摆和左侧裙摆轮廓点。
                0.11f to 0.54f, 0.17f to 0.65f, 0.10f to 0.79f, 0.02f to 0.93f,
                // 裙摆底部到右下摆轮廓点。
                0.14f to 0.98f, 0.36f to 0.96f, 0.48f to 0.93f, 0.61f to 0.98f,
                // 右侧裙摆和右袖外轮廓点。
                0.91f to 0.96f, 0.98f to 0.90f, 0.88f to 0.79f, 0.89f to 0.60f,
                // 右上袖、肩颈和头部附近轮廓点。
                0.83f to 0.44f, 0.74f to 0.29f, 0.60f to 0.20f, 0.53f to 0.14f,
                // 闭合外轮廓，让整体汉服形成封闭形。
                closed = true
            ),
            // 头发外形：形成顶部发髻和头发包裹区域。
            path(
                // HAIR 表示头发路径。
                VirtualHumanPathRole.HAIR,
                // 头发左侧、顶部发髻、右侧发束的闭合轮廓。
                0.38f to 0.16f, 0.29f to 0.12f, 0.31f to 0.05f, 0.41f to 0.01f,
                // 头顶到右侧发髻轮廓。
                0.54f to 0.02f, 0.63f to 0.08f, 0.60f to 0.15f, 0.53f to 0.18f,
                // 回到脸侧的头发边缘。
                0.44f to 0.18f, closed = true, depth = 0.03f
            ),
            // 头发内侧线：补充额前和侧发层次。
            path(
                // HAIR 表示头发装饰线。
                VirtualHumanPathRole.HAIR,
                // 从左侧发际到右侧发际的弧线。
                0.34f to 0.14f, 0.28f to 0.18f, 0.34f to 0.21f, 0.43f to 0.19f,
                // 继续连接到右侧发束。
                0.54f to 0.18f, 0.61f to 0.15f, depth = 0.04f
            ),
            // 顶部发髻线：强化汉服人物的盘发轮廓。
            path(
                // HAIR 表示头发装饰线。
                VirtualHumanPathRole.HAIR,
                // 头顶发髻和发冠位置的曲线点。
                0.35f to 0.10f, 0.40f to 0.06f, 0.49f to 0.06f, 0.57f to 0.10f,
                // 发髻回落到脸部上沿。
                0.56f to 0.14f, 0.49f to 0.12f, 0.42f to 0.14f, depth = 0.05f
            ),
            // 左侧发束线：补充左边头发的垂落形态。
            path(
                // HAIR 表示头发装饰线。
                VirtualHumanPathRole.HAIR,
                // 左侧头发从外向内收的路径点。
                0.29f to 0.15f, 0.22f to 0.16f, 0.20f to 0.19f, 0.29f to 0.20f,
                // 收回到脸侧。
                0.35f to 0.18f, depth = 0.02f
            ),
            // 脸部外形：闭合的面部轮廓。
            path(
                // FACE 表示脸部路径。
                VirtualHumanPathRole.FACE,
                // 脸部左侧、下巴、右侧脸颊的轮廓点。
                0.43f to 0.18f, 0.39f to 0.23f, 0.40f to 0.31f, 0.46f to 0.35f,
                // 右脸和额头回到起点。
                0.52f to 0.34f, 0.55f to 0.28f, 0.54f to 0.21f, 0.50f to 0.18f,
                // 闭合脸部轮廓，并让它位于较前的视觉深度。
                closed = true, depth = 0.06f
            ),
            // 眉眼线：用短线表现面部上半部分。
            path(
                // FACE 表示脸部细节。
                VirtualHumanPathRole.FACE,
                // 左眼到右眼附近的轻微弧线。
                0.45f to 0.25f, 0.48f to 0.25f, 0.51f to 0.27f, depth = 0.08f
            ),
            // 鼻口附近短线：补充脸部中心细节。
            path(
                // FACE 表示脸部细节。
                VirtualHumanPathRole.FACE,
                // 面部中下方短线。
                0.49f to 0.30f, 0.52f to 0.30f, depth = 0.08f
            ),
            // 下巴/嘴部曲线：让脸部更像人像而不是单纯椭圆。
            path(
                // FACE 表示脸部细节。
                VirtualHumanPathRole.FACE,
                // 嘴部或下巴附近弧线。
                0.45f to 0.33f, 0.48f to 0.34f, 0.51f to 0.33f, depth = 0.08f
            ),
            // 领口和上身袖口连接线。
            path(
                // SLEEVE 表示衣袖或上衣线条。
                VirtualHumanPathRole.SLEEVE,
                // 从左肩经过胸前到右肩的衣领/袖口线。
                0.43f to 0.34f, 0.38f to 0.39f, 0.45f to 0.45f, 0.50f to 0.40f,
                // 回到右肩附近。
                0.54f to 0.34f, depth = 0.04f
            ),
            // 左侧大袖封闭区域。
            path(
                // SLEEVE 表示左袖外形。
                VirtualHumanPathRole.SLEEVE,
                // 左肩向外展开形成宽袖。
                0.36f to 0.32f, 0.23f to 0.43f, 0.20f to 0.56f, 0.32f to 0.64f,
                // 袖口回收到身体中心附近。
                0.47f to 0.54f, 0.42f to 0.42f, closed = true, depth = -0.03f
            ),
            // 右侧大袖封闭区域。
            path(
                // SLEEVE 表示右袖外形。
                VirtualHumanPathRole.SLEEVE,
                // 右肩向外展开形成宽袖。
                0.58f to 0.33f, 0.76f to 0.43f, 0.81f to 0.57f, 0.68f to 0.65f,
                // 袖口回收到身体中心附近。
                0.52f to 0.54f, 0.56f to 0.42f, closed = true, depth = 0.03f
            ),
            // 左袖内部褶线。
            path(
                // SLEEVE 表示袖子内部线条。
                VirtualHumanPathRole.SLEEVE,
                // 左袖内部从上到下的折线/弧线。
                0.25f to 0.46f, 0.34f to 0.51f, 0.41f to 0.54f, 0.34f to 0.60f,
                // 回到左袖下边缘。
                0.22f to 0.57f, depth = -0.04f
            ),
            // 右袖内部褶线。
            path(
                // SLEEVE 表示袖子内部线条。
                VirtualHumanPathRole.SLEEVE,
                // 右袖内部从上到下的折线/弧线。
                0.75f to 0.46f, 0.66f to 0.51f, 0.59f to 0.54f, 0.66f to 0.60f,
                // 回到右袖下边缘。
                0.78f to 0.57f, depth = 0.04f
            ),
            // 双手区域：两手在胸前交叠的封闭轮廓。
            path(
                // HANDS 表示手部路径。
                VirtualHumanPathRole.HANDS,
                // 手部交叠区域的轮廓点。
                0.43f to 0.46f, 0.49f to 0.49f, 0.55f to 0.44f, 0.58f to 0.48f,
                // 手部下侧回到左手区域。
                0.51f to 0.55f, 0.44f to 0.52f, closed = true, depth = 0.09f
            ),
            // 腰部横向装饰，模拟腰带或上衣交叠。
            path(
                // WAIST_ORNAMENT 表示腰饰路径。
                VirtualHumanPathRole.WAIST_ORNAMENT,
                // 横向腰带外形点。
                0.37f to 0.40f, 0.50f to 0.38f, 0.64f to 0.41f, 0.54f to 0.44f,
                // 腰带下边缘回到左侧。
                0.45f to 0.44f, closed = true, depth = 0.02f
            ),
            // 腰间垂饰或衣襟。
            path(
                // WAIST_ORNAMENT 表示腰间垂饰。
                VirtualHumanPathRole.WAIST_ORNAMENT,
                // 从腰部向下的小闭合装饰路径。
                0.50f to 0.42f, 0.47f to 0.50f, 0.51f to 0.57f, 0.55f to 0.50f,
                // 回到腰部上方。
                0.52f to 0.43f, closed = true, depth = 0.05f
            ),
            // 左侧裙褶线，从腰部垂向左下摆。
            path(VirtualHumanPathRole.SKIRT_FOLD, 0.35f to 0.54f, 0.26f to 0.82f, 0.18f to 0.94f, depth = -0.02f),
            // 中左裙褶线，从腰部垂向下摆。
            path(VirtualHumanPathRole.SKIRT_FOLD, 0.43f to 0.54f, 0.39f to 0.82f, 0.36f to 0.95f, depth = 0f),
            // 中间裙褶线，形成长裙竖向结构。
            path(VirtualHumanPathRole.SKIRT_FOLD, 0.52f to 0.54f, 0.53f to 0.82f, 0.50f to 0.95f, depth = 0.03f),
            // 右侧裙褶线，从腰部垂向右下摆。
            path(VirtualHumanPathRole.SKIRT_FOLD, 0.60f to 0.54f, 0.68f to 0.81f, 0.77f to 0.94f, depth = 0.01f),
            // 横向裙褶线，表现衣料层叠和裙面起伏。
            path(VirtualHumanPathRole.SKIRT_FOLD, 0.31f to 0.67f, 0.46f to 0.71f, 0.64f to 0.69f, 0.75f to 0.63f, depth = 0.04f),
            // 下摆裙褶线，表现裙摆底部波动。
            path(VirtualHumanPathRole.SKIRT_FOLD, 0.21f to 0.85f, 0.37f to 0.89f, 0.52f to 0.88f, 0.70f to 0.91f, 0.85f to 0.86f, depth = -0.01f)
        )
    }

    // 构造用于投影的姿态点集合。
    private fun buildPosePoints(
        // 模板姿态，用于没有真实姿态时兜底。
        template: PoseTemplate,
        // 体型配置，用于模板姿态宽度和高度比例。
        profile: BodyProfile,
        // RTMPose 识别到的真实 133 点姿态。
        wholeBodyPose: WholeBodyPoseEstimate,
        // 当前虚拟人边界框，用于把真实 pose 转换到局部坐标。
        bounds: V2Rect
    ): PosePointSet {
        // 尝试从 RTMPose 身体点构造姿态点。
        val posePoints = buildPoseAwarePoints(wholeBodyPose, bounds)
        // 如果真实 pose 不可用，就使用模板姿态点。
        return if (posePoints == null) {
            // 构造模板驱动的姿态点集合。
            PosePointSet(
                // 使用模板关键点。
                points = buildTemplatePosePoints(template, profile),
                // 模板状态允许计算头部参数。
                drawHead = true,
                // 标记不是模型 pose 驱动。
                poseDriven = false
            )
        } else {
            // 真实 pose 可用时直接返回模型驱动的姿态点。
            posePoints
        }
    }

    // 构造没有真实 pose 时使用的模板 3D 关节点。
    private fun buildTemplatePosePoints(template: PoseTemplate, profile: BodyProfile): Map<Joint, Point3> {
        // 肩膀半宽由体型宽度决定。
        val shoulderHalf = 0.24f * profile.widthScale
        // 髋部半宽由体型宽度决定，通常比肩部窄。
        val hipHalf = 0.17f * profile.widthScale
        // 侧身模板整体向右轻微偏移，模拟侧身站姿。
        val sideOffset = if (template == PoseTemplate.SIDE_STANCE) 0.06f else 0f
        // 判断当前模板是否是走姿，走姿会让手脚位置更动态。
        val walking = template == PoseTemplate.WALKING

        // 返回模板关节点到 3D 点的映射。
        return mapOf(
            // 头部在上方中心附近，z 略靠前。
            Joint.HEAD to Point3(0f + sideOffset, 0.10f, 0.03f),
            // 颈部位于头下方。
            Joint.NECK to Point3(0f + sideOffset * 0.6f, 0.22f, 0.02f),
            // 左肩在中心左侧。
            Joint.LEFT_SHOULDER to Point3(-shoulderHalf + sideOffset, 0.27f, -0.04f),
            // 右肩在中心右侧。
            Joint.RIGHT_SHOULDER to Point3(shoulderHalf + sideOffset, 0.27f, 0.04f),
            // 左肘根据走姿或站姿调整高度。
            Joint.LEFT_ELBOW to Point3(-shoulderHalf * 1.18f, if (walking) 0.43f else 0.46f, 0.02f),
            // 右肘根据走姿或站姿调整高度。
            Joint.RIGHT_ELBOW to Point3(shoulderHalf * 1.15f, if (walking) 0.40f else 0.43f, -0.02f),
            // 左手根据走姿或站姿调整高度。
            Joint.LEFT_HAND to Point3(-shoulderHalf * 1.05f, if (walking) 0.56f else 0.60f, 0.08f),
            // 右手根据走姿或站姿调整高度。
            Joint.RIGHT_HAND to Point3(shoulderHalf * 0.95f, if (walking) 0.53f else 0.57f, -0.08f),
            // 左髋在身体中下部左侧。
            Joint.LEFT_HIP to Point3(-hipHalf, 0.57f, -0.02f),
            // 右髋在身体中下部右侧。
            Joint.RIGHT_HIP to Point3(hipHalf, 0.57f, 0.02f),
            // 左膝在走姿时更向外展开，站姿时更接近中心。
            Joint.LEFT_KNEE to Point3(if (walking) -hipHalf * 1.55f else -hipHalf * 0.92f, 0.76f, if (walking) 0.07f else 0f),
            // 右膝在走姿时更向外展开，站姿时更接近中心。
            Joint.RIGHT_KNEE to Point3(if (walking) hipHalf * 1.42f else hipHalf * 0.94f, 0.77f, if (walking) -0.06f else 0f),
            // 左脚在走姿时迈得更远，站姿时靠近身体下方。
            Joint.LEFT_FOOT to Point3(if (walking) -hipHalf * 1.95f else -hipHalf * 1.05f, 0.98f, if (walking) 0.10f else 0.03f),
            // 右脚在走姿时迈得更远，站姿时靠近身体下方。
            Joint.RIGHT_FOOT to Point3(if (walking) hipHalf * 1.80f else hipHalf * 1.05f, 0.98f, if (walking) -0.08f else -0.03f)
        )
    }

    // 根据模型识别到的真实 pose 构造虚拟人 3D 关节点。
    private fun buildPoseAwarePoints(pose: WholeBodyPoseEstimate, bounds: V2Rect): PosePointSet? {
        // 姿态整体置信度太低时不使用真实 pose。
        if (pose.confidence < POSE_CONFIDENCE_THRESHOLD) return null

        // 从 WholeBody 的前 17 个身体点读取基础骨架；其余点仍由内轮廓和节点绘制直接使用。
        val namedPoints: Map<PoseKeypointName, V2Point?> = PoseKeypointName.entries.associateWith { pose.point(it.ordinal) }
        // 左肩是构造身体中心和骨架的必要点，缺失则放弃真实 pose。
        val leftShoulder = namedPoints[PoseKeypointName.LEFT_SHOULDER] ?: return null
        // 右肩也是必要点，缺失则放弃真实 pose。
        val rightShoulder = namedPoints[PoseKeypointName.RIGHT_SHOULDER] ?: return null
        // 左髋可选，用于下半身和深度估计。
        val leftHip = namedPoints[PoseKeypointName.LEFT_HIP]
        // 右髋可选，用于下半身和深度估计。
        val rightHip = namedPoints[PoseKeypointName.RIGHT_HIP]
        // 统计可见关键点数量。
        val visibleKeypointCount = namedPoints.values.count { it != null }
        // 判断是否至少有头、手臂或髋部等有用人体点。
        val hasUsefulBodyPoint = listOf(
            // 鼻子可作为头部锚点。
            PoseKeypointName.NOSE,
            // 左眼可作为头部锚点。
            PoseKeypointName.LEFT_EYE,
            // 右眼可作为头部锚点。
            PoseKeypointName.RIGHT_EYE,
            // 左肘用于手臂姿态。
            PoseKeypointName.LEFT_ELBOW,
            // 右肘用于手臂姿态。
            PoseKeypointName.RIGHT_ELBOW,
            // 左腕用于手部位置。
            PoseKeypointName.LEFT_WRIST,
            // 右腕用于手部位置。
            PoseKeypointName.RIGHT_WRIST,
            // 左髋用于身体和腿部。
            PoseKeypointName.LEFT_HIP,
            // 右髋用于身体和腿部。
            PoseKeypointName.RIGHT_HIP
        // 只要这些关键点里有一个存在，就说明 pose 不只是肩膀两个点。
        ).any { namedPoints[it] != null }
        // 关键点太少或没有有用身体点时，放弃真实 pose。
        if (visibleKeypointCount < MIN_KEYPOINTS_FOR_POSE || !hasUsefulBodyPoint) return null

        // 选择第一个可用的头部锚点，用于虚拟人 HEAD。
        val headAnchor: V2Point? = firstAvailable(
            // 所有命名关键点。
            namedPoints,
            // 优先使用鼻子。
            PoseKeypointName.NOSE,
            // 其次使用左眼。
            PoseKeypointName.LEFT_EYE,
            // 再使用右眼。
            PoseKeypointName.RIGHT_EYE,
            // 再使用左耳。
            PoseKeypointName.LEFT_EAR,
            // 最后使用右耳。
            PoseKeypointName.RIGHT_EAR
        )
        // 把 RTMPose 的身体关键点映射到内部 Joint。
        val mappedPoints: Map<Joint, V2Point?> = mapOf(
            // 头部使用前面找到的头部锚点。
            Joint.HEAD to headAnchor,
            // 颈部没有直接关键点，用左右肩中点估算。
            Joint.NECK to midpoint(leftShoulder, rightShoulder),
            // 左肩映射到内部左肩。
            Joint.LEFT_SHOULDER to leftShoulder,
            // 右肩映射到内部右肩。
            Joint.RIGHT_SHOULDER to rightShoulder,
            // 左肘映射到内部左肘。
            Joint.LEFT_ELBOW to namedPoints[PoseKeypointName.LEFT_ELBOW],
            // 右肘映射到内部右肘。
            Joint.RIGHT_ELBOW to namedPoints[PoseKeypointName.RIGHT_ELBOW],
            // 左手使用 YOLO 的左手腕。
            Joint.LEFT_HAND to namedPoints[PoseKeypointName.LEFT_WRIST],
            // 右手使用 YOLO 的右手腕。
            Joint.RIGHT_HAND to namedPoints[PoseKeypointName.RIGHT_WRIST],
            // 左髋映射到内部左髋。
            Joint.LEFT_HIP to leftHip,
            // 右髋映射到内部右髋。
            Joint.RIGHT_HIP to rightHip,
            // 左膝映射到内部左膝。
            Joint.LEFT_KNEE to namedPoints[PoseKeypointName.LEFT_KNEE],
            // 右膝映射到内部右膝。
            Joint.RIGHT_KNEE to namedPoints[PoseKeypointName.RIGHT_KNEE],
            // 左脚使用 YOLO 的左脚踝。
            Joint.LEFT_FOOT to namedPoints[PoseKeypointName.LEFT_ANKLE],
            // 右脚使用 YOLO 的右脚踝。
            Joint.RIGHT_FOOT to namedPoints[PoseKeypointName.RIGHT_ANKLE]
        )

        // 根据左右肩、髋、脚的相对位置估算左侧身体的前后深度。
        val leftDepth = estimateSideDepth(
            // 左肩。
            shoulder = leftShoulder,
            // 左髋。
            hip = leftHip,
            // 左脚踝。
            foot = namedPoints[PoseKeypointName.LEFT_ANKLE],
            // 右肩作为对侧肩。
            oppositeShoulder = rightShoulder,
            // 右髋作为对侧髋。
            oppositeHip = rightHip,
            // 右脚踝作为对侧脚。
            oppositeFoot = namedPoints[PoseKeypointName.RIGHT_ANKLE]
        )
        // 右侧深度取左侧相反值，形成一前一后的透视感。
        val rightDepth = -leftDepth

        // 把真实 2D 关键点转换成内部 3D 点。
        val points = mappedPoints.mapValues { (joint, point) ->
            // 缺失点保持 null；存在点按关节所属左右侧附加深度。
            point?.toPoint3(bounds, joint.depthForSide(leftDepth, rightDepth))
        }.toMutableMap()
        // 左肘缺失时，用左肩到左手之间的插值点估算。
        points[Joint.LEFT_ELBOW] = points[Joint.LEFT_ELBOW] ?: interpolate(points[Joint.LEFT_SHOULDER], points[Joint.LEFT_HAND], 0.55f)
        // 右肘缺失时，用右肩到右手之间的插值点估算。
        points[Joint.RIGHT_ELBOW] = points[Joint.RIGHT_ELBOW] ?: interpolate(points[Joint.RIGHT_SHOULDER], points[Joint.RIGHT_HAND], 0.55f)
        // 左手缺失时，沿左肩到左肘方向继续延长估算。
        points[Joint.LEFT_HAND] = points[Joint.LEFT_HAND] ?: extend(points[Joint.LEFT_SHOULDER], points[Joint.LEFT_ELBOW], 0.72f)
        // 右手缺失时，沿右肩到右肘方向继续延长估算。
        points[Joint.RIGHT_HAND] = points[Joint.RIGHT_HAND] ?: extend(points[Joint.RIGHT_SHOULDER], points[Joint.RIGHT_ELBOW], 0.72f)
        // 左膝缺失时，用左髋到左脚之间的插值点估算。
        points[Joint.LEFT_KNEE] = points[Joint.LEFT_KNEE] ?: interpolate(points[Joint.LEFT_HIP], points[Joint.LEFT_FOOT], 0.54f)
        // 右膝缺失时，用右髋到右脚之间的插值点估算。
        points[Joint.RIGHT_KNEE] = points[Joint.RIGHT_KNEE] ?: interpolate(points[Joint.RIGHT_HIP], points[Joint.RIGHT_FOOT], 0.54f)
        // 左脚缺失时，沿左髋到左膝方向继续延长估算。
        points[Joint.LEFT_FOOT] = points[Joint.LEFT_FOOT] ?: extend(points[Joint.LEFT_HIP], points[Joint.LEFT_KNEE], 0.78f)
        // 右脚缺失时，沿右髋到右膝方向继续延长估算。
        points[Joint.RIGHT_FOOT] = points[Joint.RIGHT_FOOT] ?: extend(points[Joint.RIGHT_HIP], points[Joint.RIGHT_KNEE], 0.78f)

        // 返回真实 pose 驱动的姿态点集合。
        return PosePointSet(
            // 去掉仍然缺失的点，并把非空点取出来。
            points = points.filterValues { it != null }.mapValues { it.value ?: error("Unexpected null pose point") },
            // 有头部锚点才允许画头。
            drawHead = headAnchor != null,
            // 标记为真实 pose 驱动。
            poseDriven = true
        )
    }

    private fun WholeBodyPoseEstimate.isReliableForGuide(): Boolean {
        if (confidence < WHOLE_BODY_CONFIDENCE_THRESHOLD) return false
        val bodyPoints = body
        return point(WholeBodyKeypointIndex.LEFT_SHOULDER) != null &&
            point(WholeBodyKeypointIndex.RIGHT_SHOULDER) != null &&
            bodyPoints.size >= MIN_KEYPOINTS_FOR_POSE
    }

    // 把内部 3D 点投影到 2D 画面坐标。
    private fun projectPoint(bounds: V2Rect, point: Point3): V2Point {
        // 根据 z 深度计算透视缩放；z 越大，x 方向缩放越明显。
        val perspective = 1f / (1f + point.z * 0.45f)
        // 返回归一化画面点。
        return V2Point(
            // x 以边界框中心为原点，把局部 x 乘以宽度和透视系数。
            x = (bounds.centerX + point.x * bounds.width * perspective).coerceIn(0f, 1f),
            // y 从边界框顶部向下按高度比例映射。
            y = (bounds.top + point.y * bounds.height).coerceIn(0f, 1f)
        )
    }

    // 把真实 pose 的归一化画面点转换成虚拟人边界框内的 3D 局部点。
    private fun V2Point.toPoint3(bounds: V2Rect, depth: Float): Point3 {
        // 取边界框中心 x 作为局部坐标原点。
        val centerX = bounds.centerX
        // 防止宽度为 0 导致除法异常。
        val width = bounds.width.coerceAtLeast(0.001f)
        // 防止高度为 0 导致除法异常。
        val height = bounds.height.coerceAtLeast(0.001f)
        // 将画面 x 转成相对人物框中心的局部 x，并用增益稍微放大姿态横向表现。
        val x = ((x - centerX) / width * POSE_X_GAIN).coerceIn(-0.68f, 0.68f)
        // 将画面 y 转成人物框内自上而下的局部 y，并用增益压缩竖向表现。
        val y = ((y - bounds.top) / height * POSE_Y_GAIN).coerceIn(0.02f, 0.99f)
        // 返回带深度的 3D 点。
        return Point3(x = x, y = y, z = depth)
    }

    // 根据关节属于左侧、右侧还是中轴，返回对应深度。
    private fun Joint.depthForSide(leftDepth: Float, rightDepth: Float): Float =
        // 左右身体使用相反深度，头颈使用较靠前一侧的弱化深度。
        when (this) {
            // 左侧身体关节使用左侧深度。
            Joint.LEFT_SHOULDER, Joint.LEFT_ELBOW, Joint.LEFT_HAND, Joint.LEFT_HIP, Joint.LEFT_KNEE, Joint.LEFT_FOOT -> leftDepth
            // 右侧身体关节使用右侧深度。
            Joint.RIGHT_SHOULDER, Joint.RIGHT_ELBOW, Joint.RIGHT_HAND, Joint.RIGHT_HIP, Joint.RIGHT_KNEE, Joint.RIGHT_FOOT -> rightDepth
            // 头和颈在身体中轴，使用较大深度的 45%，避免前后感过强。
            Joint.HEAD, Joint.NECK -> max(leftDepth, rightDepth) * 0.45f
        }

    // 估算身体一侧相对另一侧是靠前还是靠后。
    private fun estimateSideDepth(
        // 当前侧肩膀点。
        shoulder: V2Point,
        // 当前侧髋点，可为空。
        hip: V2Point?,
        // 当前侧脚点，可为空。
        foot: V2Point?,
        // 对侧肩膀点。
        oppositeShoulder: V2Point,
        // 对侧髋点，可为空。
        oppositeHip: V2Point?,
        // 对侧脚点，可为空。
        oppositeFoot: V2Point?
    ): Float {
        // 当前侧下半身参考 y，优先脚，其次髋，最后肩。
        val ownLower = foot?.y ?: hip?.y ?: shoulder.y
        // 对侧下半身参考 y，优先脚，其次髋，最后肩。
        val oppositeLower = oppositeFoot?.y ?: oppositeHip?.y ?: oppositeShoulder.y
        // 下半身 y 差值用于估算哪一侧更靠前，并限制幅度。
        val lowerBias = (ownLower - oppositeLower).coerceIn(-0.08f, 0.08f)
        // 肩髋横向宽度差也可暗示身体旋转方向。
        val shoulderBias = if (hip != null && oppositeHip != null) {
            // 肩距和髋距差越明显，身体侧向感越强。
            (abs(oppositeShoulder.x - shoulder.x) - abs(oppositeHip.x - hip.x)).coerceIn(-0.06f, 0.06f)
        } else {
            // 髋部缺失时不使用肩髋差估算。
            0f
        }
        // 综合下半身偏差和肩髋偏差，得到最终深度，并限制范围。
        return (lowerBias * 1.2f + shoulderBias * 0.7f).coerceIn(-0.12f, 0.12f)
    }

    // 计算两个点的中点。
    private fun midpoint(first: V2Point, second: V2Point): V2Point =
        // x/y 分别取平均。
        V2Point((first.x + second.x) / 2f, (first.y + second.y) / 2f)

    // 从多个候选关键点名称中返回第一个可用点。
    private fun firstAvailable(
        // 关键点名称到坐标的映射。
        points: Map<PoseKeypointName, V2Point?>,
        // 按优先级排列的关键点名称。
        vararg names: PoseKeypointName
    ): V2Point? =
        // 依次查找第一个非空坐标。
        names.firstNotNullOfOrNull { points[it] }

    // 在两个 3D 点之间做线性插值，用于补齐缺失关节。
    private fun interpolate(start: Point3?, end: Point3?, amount: Float): Point3? {
        // 任一端点缺失时无法插值。
        if (start == null || end == null) return null
        // 返回 start 到 end 之间 amount 比例处的点。
        return Point3(
            // x 线性插值。
            x = start.x + (end.x - start.x) * amount,
            // y 线性插值。
            y = start.y + (end.y - start.y) * amount,
            // z 线性插值。
            z = start.z + (end.z - start.z) * amount
        )
    }

    // 沿 start -> end 方向从 end 继续向外延长，用于估算手脚缺失点。
    private fun extend(start: Point3?, end: Point3?, amount: Float): Point3? {
        // 任一端点缺失时无法延长。
        if (start == null || end == null) return null
        // 返回从 end 出发继续延长 amount 比例后的点。
        return Point3(
            // x 按方向向外延长。
            x = end.x + (end.x - start.x) * amount,
            // y 按方向向外延长。
            y = end.y + (end.y - start.y) * amount,
            // z 按方向向外延长。
            z = end.z + (end.z - start.z) * amount
        )
    }

    // 内部 3D 点结构，x/y 是人物框内局部坐标，z 是前后深度。
    private data class Point3(
        // 横向局部坐标，通常以人物框中心为 0。
        val x: Float,
        // 纵向局部坐标，0 接近头顶，1 接近脚底。
        val y: Float,
        // 前后深度，用于简单透视和绘制层次。
        val z: Float
    )

    // 姿态点集合，描述当前虚拟人是否由真实 pose 驱动。
    private data class PosePointSet(
        // 内部关节到 3D 点的映射。
        val points: Map<Joint, Point3>,
        // 是否有足够信息绘制头部。
        val drawHead: Boolean,
        // true 表示来自真实 pose；false 表示来自模板。
        val poseDriven: Boolean
    )

    // 虚拟人内部使用的关节枚举，数量少于 YOLO/WholeBody 原始关键点。
    private enum class Joint {
        // 头部。
        HEAD,
        // 颈部。
        NECK,
        // 左肩。
        LEFT_SHOULDER,
        // 右肩。
        RIGHT_SHOULDER,
        // 左肘。
        LEFT_ELBOW,
        // 右肘。
        RIGHT_ELBOW,
        // 左手。
        LEFT_HAND,
        // 右手。
        RIGHT_HAND,
        // 左髋。
        LEFT_HIP,
        // 右髋。
        RIGHT_HIP,
        // 左膝。
        LEFT_KNEE,
        // 右膝。
        RIGHT_KNEE,
        // 左脚。
        LEFT_FOOT,
        // 右脚。
        RIGHT_FOOT
    }

    // 投影器内部常量和骨架连接表。
    private companion object {
        // 使用真实 pose 的最低整体置信度。
        const val POSE_CONFIDENCE_THRESHOLD = 0.25f
        // 使用真实 pose 至少需要的可见关键点数量。
        const val MIN_KEYPOINTS_FOR_POSE = 4
        // 真实 pose 转局部坐标时的横向增益。
        const val POSE_X_GAIN = 1.18f
        // 真实 pose 转局部坐标时的纵向增益。
        const val POSE_Y_GAIN = 0.90f
        // WholeBody 轮廓可用的最低置信度。
        const val WHOLE_BODY_CONFIDENCE_THRESHOLD = 0.18f
        // 常规汉服引导宽度放大比例。
        const val HANFU_GUIDE_WIDTH_SCALE = 1.08f
        // 初始汉服引导宽度放大比例。
        const val INITIAL_HANFU_GUIDE_WIDTH_SCALE = 1.28f
        // 汉服引导最大宽度，避免宽袖超出合理画面比例。
        const val MAX_HANFU_GUIDE_WIDTH = 0.52f
        // 匹配真实人体框时允许的最小宽度。
        const val MIN_MATCHED_WIDTH = 0.12f
        // 匹配真实人体框时允许的最小高度。
        const val MIN_MATCHED_HEIGHT = 0.24f

        // 骨架连接表，定义哪些关节之间画线。
        val SKELETON = listOf(
            // 头到颈。
            Joint.HEAD to Joint.NECK,
            // 颈到左肩。
            Joint.NECK to Joint.LEFT_SHOULDER,
            // 颈到右肩。
            Joint.NECK to Joint.RIGHT_SHOULDER,
            // 左肩到左肘。
            Joint.LEFT_SHOULDER to Joint.LEFT_ELBOW,
            // 左肘到左手。
            Joint.LEFT_ELBOW to Joint.LEFT_HAND,
            // 右肩到右肘。
            Joint.RIGHT_SHOULDER to Joint.RIGHT_ELBOW,
            // 右肘到右手。
            Joint.RIGHT_ELBOW to Joint.RIGHT_HAND,
            // 颈到左髋。
            Joint.NECK to Joint.LEFT_HIP,
            // 颈到右髋。
            Joint.NECK to Joint.RIGHT_HIP,
            // 左髋到右髋。
            Joint.LEFT_HIP to Joint.RIGHT_HIP,
            // 左髋到左膝。
            Joint.LEFT_HIP to Joint.LEFT_KNEE,
            // 左膝到左脚。
            Joint.LEFT_KNEE to Joint.LEFT_FOOT,
            // 右髋到右膝。
            Joint.RIGHT_HIP to Joint.RIGHT_KNEE,
            // 右膝到右脚。
            Joint.RIGHT_KNEE to Joint.RIGHT_FOOT
        )
    }
}
