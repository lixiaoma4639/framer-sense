package com.framer.sense.feature.camera.vlm.avatar

import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import com.framer.sense.feature.camera.vlm.model.*
import com.google.android.filament.*
import com.google.android.filament.filamat.MaterialBuilder
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.*

/** 专用线程中的按需离屏渲染器，所有 Filament 对象在同一线程使用。 */
class FilamentAvatarRenderer : AvatarRenderer {
    private val thread = HandlerThread("vlm-filament").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var material: Material? = null
    private var vertices: VertexBuffer? = null
    private var capsuleVertices: VertexBuffer? = null
    private var indices: IndexBuffer? = null
    private var indexCount = 0

    /** 首次创建渲染引擎、着色器和共享球体网格；无参数，只能在渲染线程调用。 */
    private fun initialize() {
        if (engine != null) return
        Filament.init()
        val e = Engine.create(Engine.Backend.OPENGL)
        engine = e
        MaterialBuilder.init()
        try {
            val compiled = MaterialBuilder().name("VlmAvatar")
                .platform(MaterialBuilder.Platform.MOBILE)
                .shading(MaterialBuilder.Shading.LIT)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "tint")
                .material("void material(inout MaterialInputs material) { prepareMaterial(material); material.baseColor = materialParams.tint; material.roughness = 0.85; material.metallic = 0.0; }")
                .build(e)
            check(compiled.isValid) { "人偶材质编译失败" }
            val buffer = compiled.buffer
            material = Material.Builder().payload(buffer, buffer.remaining()).build(e)
            createSphere(e)
        } catch (failure: Throwable) {
            vertices?.let { e.destroyVertexBuffer(it) }; vertices = null
            capsuleVertices?.let { e.destroyVertexBuffer(it) }; capsuleVertices = null
            indices?.let { e.destroyIndexBuffer(it) }; indices = null
            material?.let { e.destroyMaterial(it) }
            material = null
            e.destroy()
            engine = null
            throw failure
        } finally { MaterialBuilder.shutdown() }
    }

    /** 创建球体与胶囊共享拓扑网格。
     * @param e 当前引擎；网格在进程级渲染器生命周期中复用。
     */
    private fun createSphere(e: Engine) {
        val rows = 17
        val cols = 24
        vertices = createVertices(e, rows, cols, false)
        capsuleVertices = createVertices(e, rows, cols, true)
        val ib = ByteBuffer.allocateDirect(rows * cols * 6 * 2).order(ByteOrder.nativeOrder())
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = r * (cols + 1) + c
            val b = a + cols + 1
            listOf(a, a + 1, b, a + 1, b + 1, b).forEach { ib.putShort(it.toShort()) }
        }
        ib.flip()
        indexCount = rows * cols * 6
        indices = IndexBuffer.Builder().indexCount(indexCount).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(e)
        indices!!.setBuffer(e, ib)
    }

    /** 创建单位范围内球体或胶囊顶点，赤道的双环用于连接胶囊柱面。
     * @param e 渲染引擎。
     * @param rows 纬线间隔数，须为奇数。
     * @param cols 经线间隔数。
     * @param capsule 是否在两端椭球之间加入直柱面。
     * @return 持有位置及法线切线四元数的 GPU 缓冲区。
     */
    private fun createVertices(e: Engine, rows: Int, cols: Int, capsule: Boolean): VertexBuffer {
        val count = (rows + 1) * (cols + 1)
        val bytes = ByteBuffer.allocateDirect(count * 28).order(ByteOrder.nativeOrder())
        val half = rows / 2
        for (r in 0..rows) for (c in 0..cols) {
            val theta = PI * (if (r <= half) r else r - 1) / (rows - 1)
            val phi = 2 * PI * c / cols
            val x = (sin(theta) * cos(phi)).toFloat()
            val y = cos(theta).toFloat()
            val z = (sin(theta) * sin(phi)).toFloat()
            bytes.putFloat(x).putFloat(if (capsule) .5f * y + (if (r <= half) .5f else -.5f) else y).putFloat(z)
            val ny = if (capsule) y * 2f else y
            val normalLength = sqrt(x * x + ny * ny + z * z)
            val nx = x / normalLength
            val normalY = ny / normalLength
            val nz = z / normalLength
            val norm = sqrt(normalY * normalY + nx * nx + (1f + nz) * (1f + nz))
            if (norm < .00001f) bytes.putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f)
            else bytes.putFloat(-normalY / norm).putFloat(nx / norm).putFloat(0f).putFloat((1f + nz) / norm)
        }
        bytes.flip()
        return VertexBuffer.Builder().vertexCount(count).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 28)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, 28).build(e).also { it.setBufferAt(e, 0, bytes) }
    }

    /** 渲染一次人偶并读取透明像素。
     * @param plan 人物布局和模板。
     * @param width 输出宽度。
     * @param height 输出高度。
     * @param warmth 暖色偏移。
     * @param brightness 背景平均亮度。
     * @return 透明 Bitmap 与实际几何投影边界。
     */
    override suspend fun render(plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float): AvatarPreview = mutex.withLock {
        withContext(dispatcher) {
            initialize()
            val e = engine!!
            val scene = e.createScene()
            val view = e.createView()
            val renderer = e.createRenderer()
            val cameraEntity = EntityManager.get().create()
            val camera = e.createCamera(cameraEntity)
            val swapChain = e.createSwapChain(width, height, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
            val created = mutableListOf<Int>()
            val instances = mutableListOf<MaterialInstance>()
            var indirect: IndirectLight? = null
            try {
                val aspect = width.toFloat() / height
                camera.setProjection(Camera.Projection.ORTHO, -aspect / 2.0, aspect / 2.0, -.5, .5, .1, 20.0)
                camera.lookAt(aspect / 2.0, .5, 6.0, aspect / 2.0, .5, 0.0, 0.0, 1.0, 0.0)
                camera.setExposure(16f, 1f / 125f, 100f)
                view.scene = scene
                view.camera = camera
                view.viewport = Viewport(0, 0, width, height)
                view.blendMode = View.BlendMode.TRANSLUCENT
                view.isPostProcessingEnabled = true
                renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
                val light = EntityManager.get().create().also { created += it }
                LightManager.Builder(LightManager.Type.SUN).color(1f, 1f - warmth.coerceIn(-1f, 1f) * .08f, 1f - warmth.coerceIn(-1f, 1f) * .16f)
                    .intensity(45000f + brightness.coerceIn(0f, 1f) * 45000f).direction(-.4f, -1f, -1f).castShadows(false).build(e, light)
                scene.addEntity(light)
                indirect = IndirectLight.Builder().irradiance(1, floatArrayOf(.8f, .8f, .8f)).intensity(16000f).build(e)
                scene.indirectLight = indirect
                val bounds = buildRig(e, scene, plan, aspect, created, instances)
                val pixels = ByteBuffer.allocateDirect(width * height * 4)
                readPixels(e, renderer, view, swapChain, pixels, width, height)
                pixels.rewind()
                val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(pixels)
                // Filament/OpenGL 从左下读取，Android Bitmap 从左上显示。
                val flip = android.graphics.Matrix().apply { setScale(1f, -1f) }
                val upright = Bitmap.createBitmap(raw, 0, 0, width, height, flip, false)
                if (upright !== raw) raw.recycle()
                AvatarPreview(upright, bounds)
            } finally {
                // 读回完成或取消后，先排空驱动命令，再释放本次临时资源。
                e.flushAndWait()
                created.reversed().forEach { e.destroyEntity(it); EntityManager.get().destroy(it) }
                instances.forEach { e.destroyMaterialInstance(it) }
                indirect?.let { e.destroyIndirectLight(it) }
                e.destroyView(view)
                e.destroyScene(scene)
                e.destroyRenderer(renderer)
                e.destroyCameraComponent(cameraEntity)
                EntityManager.get().destroy(cameraEntity)
                e.destroySwapChain(swapChain)
            }
        }
    }

    /** 将模板构建为真实父子变换节点并计算球体的投影范围。
     * @param e 渲染引擎。
     * @param scene 本次场景。
     * @param plan 人物方案。
     * @param aspect 输出宽高比。
     * @param entities 收集本次实体供 finally 释放。
     * @param materials 收集本次材质实例供释放。
     * @return 归一化边界，可超出画面。
     */
    private fun buildRig(e: Engine, scene: Scene, plan: CompositionPlan, aspect: Float, entities: MutableList<Int>, materials: MutableList<MaterialInstance>): CropRect {
        val transforms = e.transformManager
        val nodeIds = mutableMapOf<String, Int>()
        val world = mutableMapOf<String, FloatArray>()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (node in AvatarRig.nodes(plan.avatar)) {
            val entity = EntityManager.get().create().also { entities += it }
            val parent = node.parent?.let { nodeIds.getValue(it) } ?: 0
            val local = AvatarRig.localMatrix(node)
            if (node.parent == null) {
                Matrix.translateM(local, 0, plan.avatar.foot.x * aspect, 1f - plan.avatar.foot.y, 0f)
                val yaw = plan.avatar.yaw + when (plan.avatar.pose) { PoseId.SIDE -> 55f; PoseId.LOOK_BACK -> 100f; else -> 0f }
                Matrix.rotateM(local, 0, yaw, 0f, 1f, 0f)
                val scale = plan.avatar.height / AvatarRig.HEIGHT
                Matrix.scaleM(local, 0, scale, scale, scale)
            }
            val instance = transforms.create(entity, parent, local)
            nodeIds[node.name] = instance
            val matrix = if (node.parent == null) local else FloatArray(16).also { Matrix.multiplyMM(it, 0, world.getValue(node.parent), 0, local, 0) }
            world[node.name] = matrix
            if (node.geometryScale != null) {
                val mat = material!!.createInstance().also { materials += it }
                mat.setParameter("tint", node.tint[0], node.tint[1], node.tint[2], node.tint[3])
                RenderableManager.Builder(1).boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, (if (node.capsule) capsuleVertices else vertices)!!, indices!!, 0, indexCount)
                    .material(0, mat).culling(false).castShadows(false).receiveShadows(false).build(e, entity)
                scene.addEntity(entity)
                // 仿射变换球体的投影轴半径为矩阵对应行的长度。
                val capScale = if (node.capsule) .5f else 1f
                val rx = sqrt(matrix[0] * matrix[0] + capScale * capScale * matrix[4] * matrix[4] + matrix[8] * matrix[8]) + (1f - capScale) * abs(matrix[4])
                val ry = sqrt(matrix[1] * matrix[1] + capScale * capScale * matrix[5] * matrix[5] + matrix[9] * matrix[9]) + (1f - capScale) * abs(matrix[5])
                minX = min(minX, (matrix[12] - rx) / aspect)
                maxX = max(maxX, (matrix[12] + rx) / aspect)
                minY = min(minY, 1f - matrix[13] - ry)
                maxY = max(maxY, 1f - matrix[13] + ry)
            }
        }
        return CropRect(minX, minY, maxX, maxY)
    }

    /** 提交一帧并等待 GPU 像素读回，保持缓冲区存活。
     * @param e 渲染引擎。
     * @param renderer 帧渲染器。
     * @param view 相机与场景视图。
     * @param chain 可读离屏交换链。
     * @param pixels 接收 RGBA 数据的直接缓冲区。
     * @param width 读回宽度。
     * @param height 读回高度。
     */
    private suspend fun readPixels(e: Engine, renderer: Renderer, view: View, chain: SwapChain, pixels: ByteBuffer, width: Int, height: Int) = suspendCancellableCoroutine<Unit> { continuation ->
        check(renderer.beginFrame(chain, System.nanoTime())) { "GPU 暂时无法渲染，请重试" }
        try {
            renderer.render(view)
            val descriptor = Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE)
            descriptor.setCallback(handler, Runnable { if (continuation.isActive) continuation.resume(Unit) })
            renderer.readPixels(0, 0, width, height, descriptor)
        } finally { renderer.endFrame() }
        e.flushAndWait()
    }
}
