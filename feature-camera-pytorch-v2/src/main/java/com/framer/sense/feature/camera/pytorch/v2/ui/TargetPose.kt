package com.framer.sense.feature.camera.pytorch.v2.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class TargetPoseJoint(val index: Int) {
    HEAD(0), LEFT_SHOULDER(5), RIGHT_SHOULDER(6), LEFT_ELBOW(7), RIGHT_ELBOW(8),
    LEFT_HAND(9), RIGHT_HAND(10), LEFT_HIP(11), RIGHT_HIP(12), LEFT_KNEE(13),
    RIGHT_KNEE(14), LEFT_FOOT(15), RIGHT_FOOT(16)
}

data class TargetPosePoint(val x: Float, val y: Float, val z: Float = 0f)
enum class TargetPoseCategory { FULL_BODY, HALF_BODY, CLOSE_UP }
enum class TargetPoseHeadDirection { CAMERA, LEFT, RIGHT, DOWN }

/** 项目自建的完整 COCO-WholeBody 目标 Pose，索引严格对应 RTMPose 0..132 输出。 */
data class TargetPose(
    val id: String,
    val title: String,
    val category: TargetPoseCategory,
    val points: Map<Int, TargetPosePoint>,
    val headDirection: TargetPoseHeadDirection,
    val handGesture: String,
    val instruction: String,
    val compatibleSceneGroups: Set<SceneGroup>,
    val mirroredFromId: String? = null
) {
    init {
        require(points.keys == WHOLE_BODY_INDEXES) { "Target pose must contain every COCO-WholeBody index" }
    }

    val isMirrored: Boolean get() = mirroredFromId != null
    fun point(index: Int): TargetPosePoint = points.getValue(index)

    fun mirrored(): TargetPose = copy(
        id = "${id}_mirror",
        title = "$title（镜像）",
        points = points.map { (index, point) -> mirrorWholeBodyIndex(index) to point.copy(x = -point.x, z = -point.z) }.toMap(),
        headDirection = headDirection.mirror(),
        instruction = instruction.mirrorText(),
        mirroredFromId = id
    )

    companion object { val WHOLE_BODY_INDEXES: Set<Int> = (0..132).toSet() }
}

data class PoseRecommendation(val targetPose: TargetPose, val candidates: List<TargetPose>)
enum class PoseAlignmentArea { TORSO, HEAD, ARM, LEG, HAND }
data class PoseAlignmentFeedback(val area: PoseAlignmentArea, val instruction: String, val confidence: Float)

/** 24 套基础姿势；镜像版本使用完整 133 点索引映射自动生成。 */
object TargetPoseLibrary {
    val basePoses: List<TargetPose> = listOf(
        pose("s_curve", "自然 S 曲线", TargetPoseCategory.FULL_BODY, "一脚承重，另一脚轻点地", setOf(SceneGroup.NATURE, SceneGroup.OUTDOOR, SceneGroup.UNKNOWN), legShift = .06f),
        pose("side_weight", "45° 侧身重心", TargetPoseCategory.FULL_BODY, "身体转 45°，肩膀放松", setOf(SceneGroup.URBAN, SceneGroup.OUTDOOR), head = TargetPoseHeadDirection.LEFT, side = .08f, legShift = .05f),
        pose("crossed_ankles", "交叉脚站姿", TargetPoseCategory.FULL_BODY, "双脚轻交叉，重心放后脚", setOf(SceneGroup.NATURE, SceneGroup.OUTDOOR), crossedFeet = true),
        pose("step_forward", "前迈一步", TargetPoseCategory.FULL_BODY, "前脚半步，手臂自然下垂", setOf(SceneGroup.URBAN, SceneGroup.OUTDOOR), walking = true),
        pose("walk_look_away", "轻走望向远处", TargetPoseCategory.FULL_BODY, "慢走一步，视线看向远处", setOf(SceneGroup.URBAN, SceneGroup.NATURE, SceneGroup.OUTDOOR), head = TargetPoseHeadDirection.RIGHT, walking = true, armLift = .04f),
        pose("turn_back", "转身回眸", TargetPoseCategory.FULL_BODY, "身体背向镜头，轻回头", setOf(SceneGroup.NATURE, SceneGroup.URBAN), head = TargetPoseHeadDirection.LEFT, side = .12f, walking = true),
        pose("wall_lean", "靠墙微倾", TargetPoseCategory.FULL_BODY, "肩膀轻靠，膝盖自然放松", setOf(SceneGroup.URBAN, SceneGroup.INDOOR), side = .10f, legShift = .07f, oneHandUp = true),
        pose("pocket_waist", "单手扶腰", TargetPoseCategory.FULL_BODY, "一手扶腰，另一手自然放下", setOf(SceneGroup.URBAN, SceneGroup.OUTDOOR), handOnWaist = true, legShift = .04f),
        pose("adjust_collar_full", "整理衣领", TargetPoseCategory.FULL_BODY, "轻碰衣领，眼神放松", setOf(SceneGroup.INDOOR, SceneGroup.URBAN), handNearFace = true),
        pose("reach_environment", "环境互动伸手", TargetPoseCategory.FULL_BODY, "向身旁景物轻伸手，不要用力", setOf(SceneGroup.NATURE, SceneGroup.URBAN, SceneGroup.OUTDOOR), oneHandUp = true, side = .05f),
        pose("hair_touch", "单手撩发", TargetPoseCategory.HALF_BODY, "手轻靠头发，手肘不要抬太高", setOf(SceneGroup.INDOOR, SceneGroup.NATURE), handNearFace = true),
        pose("adjust_collar_half", "轻扶衣领", TargetPoseCategory.HALF_BODY, "手指轻扶衣领，肩膀下沉", setOf(SceneGroup.INDOOR, SceneGroup.URBAN), handNearFace = true, head = TargetPoseHeadDirection.LEFT),
        pose("relaxed_wrists", "双手自然交叠", TargetPoseCategory.HALF_BODY, "双手轻叠在腰前", setOf(SceneGroup.INDOOR, SceneGroup.UNKNOWN), handsTogether = true),
        pose("hand_near_chin", "手靠近下颌", TargetPoseCategory.HALF_BODY, "手背轻靠下颌，不遮住脸", setOf(SceneGroup.INDOOR, SceneGroup.NATURE), handNearFace = true, head = TargetPoseHeadDirection.RIGHT),
        pose("shoulder_look_away", "侧肩回望", TargetPoseCategory.HALF_BODY, "一侧肩膀靠近镜头，眼神看远处", setOf(SceneGroup.URBAN, SceneGroup.NATURE), side = .10f, head = TargetPoseHeadDirection.LEFT),
        pose("soft_folded_arms", "轻抱手臂", TargetPoseCategory.HALF_BODY, "双臂轻搭，不要夹紧身体", setOf(SceneGroup.INDOOR, SceneGroup.URBAN), handsTogether = true, side = .04f),
        pose("touch_rail", "手扶栏杆或墙面", TargetPoseCategory.HALF_BODY, "一手轻扶身旁环境", setOf(SceneGroup.URBAN, SceneGroup.OUTDOOR), oneHandUp = true),
        pose("lower_then_lift", "低头再抬眼", TargetPoseCategory.HALF_BODY, "下巴微收，眼神轻轻抬起", setOf(SceneGroup.INDOOR, SceneGroup.UNKNOWN), head = TargetPoseHeadDirection.DOWN),
        pose("turn_30", "30° 转头", TargetPoseCategory.CLOSE_UP, "脸转约 30°，保留一侧脸颊", setOf(SceneGroup.INDOOR, SceneGroup.UNKNOWN), head = TargetPoseHeadDirection.LEFT, side = .06f),
        pose("shoulder_to_camera", "肩膀朝镜头", TargetPoseCategory.CLOSE_UP, "一侧肩膀靠近镜头，脖子放松", setOf(SceneGroup.INDOOR, SceneGroup.URBAN), side = .10f),
        pose("chin_down", "微收下巴", TargetPoseCategory.CLOSE_UP, "下巴微收一点，眼神保持自然", setOf(SceneGroup.INDOOR, SceneGroup.UNKNOWN), head = TargetPoseHeadDirection.DOWN),
        pose("hand_face_side", "手靠近脸侧", TargetPoseCategory.CLOSE_UP, "手放脸侧留空，不遮住五官", setOf(SceneGroup.INDOOR, SceneGroup.NATURE), handNearFace = true),
        pose("hands_on_collar", "双手轻扶领口", TargetPoseCategory.CLOSE_UP, "两手轻放领口或背带", setOf(SceneGroup.INDOOR, SceneGroup.UNKNOWN), handsTogether = true, handNearFace = true),
        pose("look_off_camera", "视线望向镜外", TargetPoseCategory.CLOSE_UP, "身体不动，视线看向镜外", setOf(SceneGroup.NATURE, SceneGroup.URBAN, SceneGroup.INDOOR), head = TargetPoseHeadDirection.RIGHT)
    )

    val allPoses: List<TargetPose> by lazy { basePoses + basePoses.map { it.mirrored() } }
    fun find(id: String): TargetPose? = allPoses.firstOrNull { it.id == id }

    private fun pose(
        id: String, title: String, category: TargetPoseCategory, instruction: String, scenes: Set<SceneGroup>,
        head: TargetPoseHeadDirection = TargetPoseHeadDirection.CAMERA, side: Float = 0f, legShift: Float = 0f,
        walking: Boolean = false, crossedFeet: Boolean = false, handNearFace: Boolean = false,
        handOnWaist: Boolean = false, handsTogether: Boolean = false, oneHandUp: Boolean = false, armLift: Float = 0f
    ) = TargetPose(
        id, title, category,
        wholeBodyPoints(head, side, legShift, walking, crossedFeet, handNearFace, handOnWaist, handsTogether, oneHandUp, armLift),
        head, instruction, instruction, scenes
    )

    private fun wholeBodyPoints(
        headDirection: TargetPoseHeadDirection, side: Float, legShift: Float, walking: Boolean, crossedFeet: Boolean,
        handNearFace: Boolean, handOnWaist: Boolean, handsTogether: Boolean, oneHandUp: Boolean, armLift: Float
    ): Map<Int, TargetPosePoint> {
        val leftFoot = when { crossedFeet -> TargetPosePoint(.10f + side, .98f, .03f); walking -> TargetPosePoint(-.34f + side, .98f, .10f); else -> TargetPosePoint(-.18f + side, .98f, .03f) }
        val rightFoot = when { crossedFeet -> TargetPosePoint(-.06f + side, .98f, -.03f); walking -> TargetPosePoint(.28f + side, .94f, -.08f); else -> TargetPosePoint(.18f + side, .98f, -.03f) }
        val leftHand = when { handNearFace -> TargetPosePoint(-.13f + side, .30f, .10f); handsTogether -> TargetPosePoint(-.05f + side, .54f, .09f); handOnWaist -> TargetPosePoint(-.12f + side, .56f, .08f); oneHandUp -> TargetPosePoint(-.34f + side, .38f - armLift, .10f); else -> TargetPosePoint(-.30f + side, .58f - armLift, .07f) }
        val rightHand = when { handsTogether -> TargetPosePoint(.05f + side, .54f, -.09f); handOnWaist -> TargetPosePoint(.13f + side, .61f, -.07f); else -> TargetPosePoint(.28f + side, .57f, -.07f) }
        val body = mutableMapOf(
            5 to TargetPosePoint(-.23f + side, .28f, -.04f), 6 to TargetPosePoint(.23f + side, .27f, .04f),
            7 to TargetPosePoint((leftHand.x - .23f + side) / 2f, (leftHand.y + .28f) / 2f, leftHand.z * .6f),
            8 to TargetPosePoint((rightHand.x + .23f + side) / 2f, (rightHand.y + .28f) / 2f, rightHand.z * .6f),
            9 to leftHand, 10 to rightHand, 11 to TargetPosePoint(-.16f + side - legShift, .57f, -.02f),
            12 to TargetPosePoint(.16f + side + legShift, .57f, .02f), 13 to TargetPosePoint(if (walking) -.26f + side else -.16f + side - legShift, .76f, if (walking) .07f else 0f),
            14 to TargetPosePoint(if (walking) .25f + side else .16f + side + legShift, .77f, if (walking) -.06f else 0f), 15 to leftFoot, 16 to rightFoot
        )
        val face = facePoints(TargetPosePoint(side * .55f, .10f, .03f), headDirection)
        body[0] = face.getValue(53); body[1] = face.getValue(62); body[2] = face.getValue(65)
        body[3] = TargetPosePoint(face.getValue(23).x - .015f, face.getValue(23).y, -.02f)
        body[4] = TargetPosePoint(face.getValue(39).x + .015f, face.getValue(39).y, .02f)
        return (body + footPoints(leftFoot, rightFoot) + face + handPoints(leftHand, -1f, handNearFace || oneHandUp || handsTogether || handOnWaist).mapKeys { it.key + 91 } + handPoints(rightHand, 1f, handsTogether || handOnWaist).mapKeys { it.key + 112 }).also {
            check(it.keys == TargetPose.WHOLE_BODY_INDEXES)
        }
    }

    private fun footPoints(left: TargetPosePoint, right: TargetPosePoint) = mapOf(
        17 to left.copy(x = left.x - .045f, y = left.y - .008f), 18 to left.copy(x = left.x + .028f, y = left.y - .006f), 19 to left.copy(x = left.x - .008f, y = left.y + .015f),
        20 to right.copy(x = right.x + .045f, y = right.y - .008f), 21 to right.copy(x = right.x - .028f, y = right.y - .006f), 22 to right.copy(x = right.x + .008f, y = right.y + .015f)
    )

    private fun facePoints(head: TargetPosePoint, direction: TargetPoseHeadDirection): Map<Int, TargetPosePoint> {
        val turn = when (direction) { TargetPoseHeadDirection.LEFT -> -.025f; TargetPoseHeadDirection.RIGHT -> .025f; else -> 0f }
        val cx = head.x + turn; val cy = head.y + if (direction == TargetPoseHeadDirection.DOWN) .018f else 0f; val width = if (turn == 0f) .115f else .100f
        val result = mutableMapOf<Int, TargetPosePoint>(); fun add(local: Int, x: Float, y: Float, z: Float = head.z) { result[23 + local] = TargetPosePoint(x, y, z) }
        (0..16).forEach { i -> val t = i / 16f; add(i, cx + (t - .5f) * width * 2f, cy + .045f + .075f * (1f - 4f * (t - .5f) * (t - .5f))) }
        (0..4).forEach { i -> add(17 + i, cx - .075f + i * .027f, cy - .026f - abs(2 - i) * .003f); add(22 + i, cx - .033f + i * .027f, cy - .026f - abs(2 - i) * .003f) }
        (0..3).forEach { i -> add(27 + i, cx + turn * .18f, cy - .004f + i * .018f, head.z + .01f) }
        add(31, cx - .028f, cy + .052f); add(32, cx - .014f, cy + .059f); add(33, cx, cy + .061f); add(34, cx + .014f, cy + .059f); add(35, cx + .028f, cy + .052f)
        eyePoints(cx - .043f, cy + .008f).forEachIndexed { i, p -> add(36 + i, p.first, p.second, head.z + .02f) }
        eyePoints(cx + .043f, cy + .008f).forEachIndexed { i, p -> add(42 + i, p.first, p.second, head.z + .02f) }
        (0..11).forEach { i -> val angle = PI + PI * i / 11f; add(48 + i, cx + cos(angle).toFloat() * .052f, cy + .093f + sin(angle).toFloat() * .020f) }
        (0..7).forEach { i -> val angle = PI + PI * i / 7f; add(60 + i, cx + cos(angle).toFloat() * .028f, cy + .093f + sin(angle).toFloat() * .010f) }
        check(result.size == 68); return result
    }

    private fun eyePoints(cx: Float, cy: Float) = listOf(cx - .020f to cy, cx - .010f to cy - .009f, cx + .010f to cy - .009f, cx + .020f to cy, cx + .010f to cy + .009f, cx - .010f to cy + .009f)

    private fun handPoints(wrist: TargetPosePoint, side: Float, fingersUp: Boolean): Map<Int, TargetPosePoint> {
        val directionY = if (fingersUp) -1f else 1f; val result = mutableMapOf(0 to wrist)
        val bases = listOf(-.028f, -.012f, .004f, .020f, .034f); val lengths = listOf(.040f, .068f, .078f, .070f, .057f); val starts = listOf(1, 5, 9, 13, 17)
        starts.indices.forEach { finger -> (0..3).forEach { joint -> val progress = (joint + 1) / 4f; result[starts[finger] + joint] = TargetPosePoint(wrist.x + side * bases[finger] + side * (finger - 2) * .003f * progress, wrist.y + directionY * (.012f + lengths[finger] * progress), wrist.z + side * .012f * progress) } }
        check(result.size == 21); return result
    }
}

class PoseRecommendationEngine(private val poseLibrary: TargetPoseLibrary = TargetPoseLibrary) {
    fun recommend(scene: SemanticScene): PoseRecommendation {
        val category = when (scene.group) { SceneGroup.INDOOR -> TargetPoseCategory.HALF_BODY; SceneGroup.UNKNOWN -> TargetPoseCategory.CLOSE_UP; SceneGroup.NATURE, SceneGroup.OUTDOOR, SceneGroup.URBAN -> TargetPoseCategory.FULL_BODY }
        val candidates = poseLibrary.allPoses.filter { it.category == category && scene.group in it.compatibleSceneGroups }.ifEmpty { poseLibrary.allPoses.filter { it.category == category } }
        return PoseRecommendation(candidates.first(), candidates)
    }
}

class PoseAlignmentEngine {
    fun evaluate(pose: WholeBodyPoseEstimate, target: TargetPose): PoseAlignmentFeedback? {
        val leftShoulder = pose.point(5) ?: return null; val rightShoulder = pose.point(6) ?: return null
        val liveWidth = abs(rightShoulder.x - leftShoulder.x).coerceAtLeast(.01f); val liveCenter = V2Point((leftShoulder.x + rightShoulder.x) / 2f, (leftShoulder.y + rightShoulder.y) / 2f)
        val targetWidth = abs(target.point(6).x - target.point(5).x).coerceAtLeast(.01f); val targetCenter = TargetPosePoint((target.point(5).x + target.point(6).x) / 2f, (target.point(5).y + target.point(6).y) / 2f)
        fun liveNormal(point: V2Point) = V2Point((point.x - liveCenter.x) / liveWidth, (point.y - liveCenter.y) / liveWidth)
        fun targetNormal(point: TargetPosePoint) = V2Point((point.x - targetCenter.x) / targetWidth, (point.y - targetCenter.y) / targetWidth)
        fun error(index: Int): Float? = pose.point(index)?.let { abs(liveNormal(it).x - targetNormal(target.point(index)).x) + abs(liveNormal(it).y - targetNormal(target.point(index)).y) }
        val feedback = mutableListOf<Pair<Float, PoseAlignmentFeedback>>()
        feedback += abs((leftShoulder.y - rightShoulder.y) / liveWidth - (target.point(5).y - target.point(6).y) / targetWidth) to PoseAlignmentFeedback(PoseAlignmentArea.TORSO, "肩膀再放松、贴近虚拟人轮廓", pose.confidence)
        error(0)?.let { feedback += it to PoseAlignmentFeedback(PoseAlignmentArea.HEAD, target.headDirection.feedbackText(), pose.confidence) }
        listOf(9, 10).forEach { index -> error(index)?.let { feedback += it to PoseAlignmentFeedback(PoseAlignmentArea.ARM, "手臂向虚拟人对应位置靠一点", pose.confidence) } }
        listOf(15, 16).forEach { index -> error(index)?.let { feedback += it to PoseAlignmentFeedback(PoseAlignmentArea.LEG, "双脚按虚拟人位置调整，步幅再自然一点", pose.confidence) } }
        handCenter(pose, WholeBodyPoseEstimate.LEFT_HAND_RANGE)?.let { center -> val points = target.points.filterKeys { it in WholeBodyPoseEstimate.LEFT_HAND_RANGE }.values; val targetHand = TargetPosePoint(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat()); feedback += (abs(liveNormal(center).x - targetNormal(targetHand).x) + abs(liveNormal(center).y - targetNormal(targetHand).y)) to PoseAlignmentFeedback(PoseAlignmentArea.HAND, "手指保持放松，跟随虚拟人手势", pose.confidence) }
        return feedback.maxByOrNull { it.first }?.takeIf { it.first >= MIN_FEEDBACK_ERROR }?.second
    }

    private fun handCenter(pose: WholeBodyPoseEstimate, range: IntRange): V2Point? = pose.keypoints.filter { it.index in range && it.confidence >= HAND_CONFIDENCE }.takeIf { it.size >= MIN_HAND_POINTS }?.let { points -> V2Point(points.map { it.point.x }.average().toFloat(), points.map { it.point.y }.average().toFloat()) }
    private companion object { const val MIN_FEEDBACK_ERROR = .24f; const val HAND_CONFIDENCE = .35f; const val MIN_HAND_POINTS = 5 }
}

internal fun TargetPose.projectedWholeBodyPose(bounds: V2Rect, profile: BodyProfile): WholeBodyPoseEstimate = WholeBodyPoseEstimate(
    points.map { (index, point) -> val perspective = 1f / (1f + point.z * .45f); WholeBodyKeypoint(index, V2Point((bounds.centerX + point.x * profile.widthScale * bounds.width * perspective).coerceIn(0f, 1f), (bounds.top + point.y * bounds.height).coerceIn(0f, 1f)), 1f) }, 1f
)

private fun mirrorWholeBodyIndex(index: Int): Int = when (index) {
    0 -> 0; 1 -> 2; 2 -> 1; 3 -> 4; 4 -> 3; 5 -> 6; 6 -> 5; 7 -> 8; 8 -> 7; 9 -> 10; 10 -> 9; 11 -> 12; 12 -> 11; 13 -> 14; 14 -> 13; 15 -> 16; 16 -> 15; 17 -> 20; 18 -> 21; 19 -> 22; 20 -> 17; 21 -> 18; 22 -> 19
    in 23..90 -> 23 + mirrorFaceIndex(index - 23); in 91..111 -> index + 21; in 112..132 -> index - 21; else -> error("Unsupported WholeBody index: $index")
}

private fun mirrorFaceIndex(index: Int): Int = when (index) {
    in 0..16 -> 16 - index; in 17..26 -> 43 - index; in 27..30 -> index; in 31..35 -> 66 - index
    in 36..47 -> intArrayOf(45, 44, 43, 42, 47, 46, 39, 38, 37, 36, 41, 40)[index - 36]
    in 48..59 -> intArrayOf(54, 53, 52, 51, 50, 49, 48, 59, 58, 57, 56, 55)[index - 48]
    in 60..67 -> intArrayOf(64, 63, 62, 61, 60, 67, 66, 65)[index - 60]; else -> error("Unsupported face index: $index")
}

private fun TargetPoseHeadDirection.mirror() = when (this) { TargetPoseHeadDirection.LEFT -> TargetPoseHeadDirection.RIGHT; TargetPoseHeadDirection.RIGHT -> TargetPoseHeadDirection.LEFT; else -> this }
private fun String.mirrorText() = replace("左", "临时").replace("右", "左").replace("临时", "右")
private fun TargetPoseHeadDirection.feedbackText() = when (this) { TargetPoseHeadDirection.LEFT -> "头部再向左侧轻转一点"; TargetPoseHeadDirection.RIGHT -> "头部再向右侧轻转一点"; TargetPoseHeadDirection.DOWN -> "下巴再微收一点，保持眼神放松"; TargetPoseHeadDirection.CAMERA -> "脸部朝向虚拟人，保持自然" }

fun CameraV2Guide.withTargetPose(targetPose: TargetPose, profile: BodyProfile, isSelectionLocked: Boolean): CameraV2Guide = copy(
    targetPose = targetPose,
    virtualHuman = VirtualHumanProjector().project(targetBounds, profile, virtualHuman.template, targetPose, wholeBodyPose, virtualHuman.contourPathPoints),
    alignmentFeedback = PoseAlignmentEngine().evaluate(wholeBodyPose, targetPose), isPoseSelectionLocked = isSelectionLocked
)
