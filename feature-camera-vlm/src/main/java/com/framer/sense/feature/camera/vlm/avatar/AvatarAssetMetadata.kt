package com.framer.sense.feature.camera.vlm.avatar

import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 对照 gltfio 1.75.1 的非交错加载路径估算上界，不把 GLB bufferViews 总数当成槽数。 */
data class AvatarVertexLayout(val mesh: String, val primitive: Int, val attributes: Set<String>) {
    // NORMAL 占用生成后的四元数槽，TANGENT 在这个阶段跳过；无 NORMAL 的有光材质
    // 最多补一个法线槽。Ubershader 缺省 UV/颜色共享一个 dummy 槽。morph 使用独立缓冲。
    val maxFilamentSlots: Int get() = attributes.count { it != "TANGENT" } +
        (if ("NORMAL" in attributes) 0 else 1) + 1

    fun validate() {
        require("POSITION" in attributes) { "AVATAR_VERTEX_POSITION_MISSING mesh=$mesh primitive=$primitive" }
        require(maxFilamentSlots <= FILAMENT_SLOT_LIMIT) {
            "AVATAR_VERTEX_BUFFER_LIMIT mesh=$mesh primitive=$primitive upperBound=$maxFilamentSlots " +
                "limit=$FILAMENT_SLOT_LIMIT attributes=${attributes.sorted()}"
        }
    }

    companion object { const val FILAMENT_SLOT_LIMIT = 8 }
}

/** 从真实 GLB 读取骨骼、形变与动画时长；不依赖 GPU，供可用性筛选和回归测试使用。 */
data class AvatarAssetMetadata(
    val bones: Set<String>,
    val morphs: Set<String>,
    val animations: Map<String, Float>,
    val vertexLayouts: List<AvatarVertexLayout> = emptyList()
) {
    val maxFilamentSlots: Int get() = vertexLayouts.maxOfOrNull { it.maxFilamentSlots } ?: 0
    fun validateFilamentVertexBuffers() = vertexLayouts.forEach(AvatarVertexLayout::validate)

    companion object {
        fun read(bytes: ByteArray): AvatarAssetMetadata {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 20 && buffer.int == 0x46546c67 && buffer.int == 2 && buffer.int == bytes.size) { "INVALID_GLB" }
            val jsonLength = buffer.int
            require(buffer.int == 0x4e4f534a && jsonLength > 0 && jsonLength <= buffer.remaining()) { "INVALID_GLB_JSON" }
            val json = ByteArray(jsonLength).also(buffer::get)
            val root = VlmJson.parseToJsonElement(json.toString(Charsets.UTF_8).trim()).jsonObject
            var binaryStart = -1
            var binaryLength = 0
            if (buffer.remaining() >= 8) {
                binaryLength = buffer.int
                require(buffer.int == 0x004e4942 && binaryLength >= 0 && binaryLength <= buffer.remaining()) { "INVALID_GLB_BINARY" }
                binaryStart = buffer.position()
            }
            val nodes = root["nodes"]?.jsonArray.orEmpty()
            val jointIndices = root["skins"]?.jsonArray.orEmpty().flatMap { it.jsonObject.getValue("joints").jsonArray.map { n -> n.jsonPrimitive.int } }.toSet()
            val names = jointIndices.map { nodes[it].jsonObject.getValue("name").jsonPrimitive.content }
            require(names.distinct().size == names.size) { "AMBIGUOUS_BONE_NAMES" }
            val morphs = root["meshes"]?.jsonArray.orEmpty().flatMap {
                it.jsonObject["extras"]?.jsonObject?.get("targetNames")?.jsonArray.orEmpty().map { name -> name.jsonPrimitive.content }
            }.toSet()
            fun duration(index: Int): Float {
                val accessor = root.getValue("accessors").jsonArray[index].jsonObject
                require(accessor.getValue("componentType").jsonPrimitive.int == 5126 && accessor.getValue("type").jsonPrimitive.content == "SCALAR") { "INVALID_ANIMATION_TIME" }
                val count = accessor.getValue("count").jsonPrimitive.int
                val view = root.getValue("bufferViews").jsonArray[accessor.getValue("bufferView").jsonPrimitive.int].jsonObject
                require(binaryStart >= 0 && view.getValue("buffer").jsonPrimitive.int == 0 && count > 0) { "ANIMATION_BUFFER_MISSING" }
                val viewOffset = view["byteOffset"]?.jsonPrimitive?.int ?: 0
                val accessorOffset = accessor["byteOffset"]?.jsonPrimitive?.int ?: 0
                val viewLength = view.getValue("byteLength").jsonPrimitive.int
                val stride = view["byteStride"]?.jsonPrimitive?.int ?: 4
                require(stride >= 4 && stride % 4 == 0 && viewOffset >= 0 && accessorOffset >= 0 && viewLength >= 0 &&
                    viewOffset.toLong() + viewLength <= binaryLength &&
                    accessorOffset.toLong() + (count - 1L) * stride + 4 <= viewLength) { "INVALID_ANIMATION_BUFFER" }
                val offset = viewOffset + accessorOffset
                var last = -1f
                repeat(count) { sample ->
                    val value = buffer.getFloat(binaryStart + offset + sample * stride)
                    require(value.isFinite() && value >= 0f && value > last) { "INVALID_ANIMATION_TIME" }
                    last = value
                }
                return last
            }
            val animations = linkedMapOf<String, Float>()
            root["animations"]?.jsonArray.orEmpty().forEach { item ->
                val animation = item.jsonObject
                val name = animation["name"]?.jsonPrimitive?.content ?: return@forEach
                require(name !in animations) { "AMBIGUOUS_ANIMATION_NAME" }
                animations[name] = animation.getValue("samplers").jsonArray.maxOf { duration(it.jsonObject.getValue("input").jsonPrimitive.int) }
            }
            val layouts = root["meshes"]?.jsonArray.orEmpty().flatMapIndexed { index, item ->
                val mesh = item.jsonObject
                val name = mesh["name"]?.jsonPrimitive?.content ?: "mesh[$index]"
                mesh["primitives"]?.jsonArray.orEmpty().mapIndexed { primitive, value ->
                    AvatarVertexLayout(name, primitive, value.jsonObject.getValue("attributes").jsonObject.keys)
                }
            }
            return AvatarAssetMetadata(names.toSet(), morphs, animations, layouts)
        }
    }
}

object PhotoActionSupport {
    fun validate(definition: PhotoActionDefinition, role: AvatarId, metadata: AvatarAssetMetadata, snapshot: PhotoPoseSnapshot? = null,
                 ethnicity: AvatarEthnicity? = null) {
        metadata.validateFilamentVertexBuffers()
        require(definition.readiness != ActionReadiness.AWAITING_ASSET && role in definition.supportedAvatars &&
            definition.requirement == ActionRequirement.NONE && definition.rigId == definition.asset(role, ethnicity).rigId) { "PHOTO_ACTION_UNAVAILABLE" }
        when (val source = requireNotNull(definition.source)) {
            is PhotoPoseSource.Controlled -> {
                val bones = source.directive?.let(AvatarCatalog::directiveRotations)?.keys ?: AvatarCatalog.poseRotations(source.pose).keys
                require(metadata.bones.containsAll(bones)) { "PHOTO_ACTION_BONES_MISSING" }
            }
            is PhotoPoseSource.Snapshot -> requireNotNull(snapshot).validate(definition.rigId, metadata.bones)
            is PhotoPoseSource.Animation -> {
                val duration = requireNotNull(metadata.animations[source.name]) {
                    "PHOTO_ANIMATION_MISSING expected=${source.name} available=${metadata.animations.keys.sorted()}"
                }
                require(source.sampleSeconds.isFinite() && source.sampleSeconds in 0f..duration) { "PHOTO_ANIMATION_SAMPLE_INVALID" }
            }
        }
    }
}
