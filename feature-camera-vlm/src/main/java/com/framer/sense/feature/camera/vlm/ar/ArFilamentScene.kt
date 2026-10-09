package com.framer.sense.feature.camera.vlm.ar

import android.content.Context
import android.opengl.Matrix
import android.view.Surface
import android.util.Log
import android.os.SystemClock
import com.google.android.filament.*
import com.google.android.filament.gltfio.*
import com.google.android.filament.filamat.MaterialBuilder
import com.google.ar.core.Frame
import com.google.ar.core.Coordinates2d
import com.google.ar.core.LightEstimate
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 实时 3D：相机纹理、深度遮挡、地面软阴影与实际蒙皮角色共用一个 Filament View。 */
internal class ArFilamentScene(private val context: Context, egl: ArEglContext, textureId: Int) : AutoCloseable {
    private val engine: Engine
    private val scene: Scene
    private val view: View
    private val renderer: Renderer
    private val cameraEntity: Int
    private val camera: Camera
    private val materials = mutableListOf<Material>()
    private val entities = mutableListOf<Int>()
    private val buffers = mutableListOf<VertexBuffer>()
    private val indices = mutableListOf<IndexBuffer>()
    private val importedTexture: Texture
    private var chain: SwapChain? = null
    private val provider: UbershaderProvider
    private val loader: AssetLoader
    private val resources: ResourceLoader
    private var asset: FilamentAsset? = null
    private var assetKey: String? = null
    private var planKey: String? = null
    private var base = emptyMap<Int, FloatArray>()
    private var rootBase = FloatArray(16)
    private var metadata: AvatarAssetMetadata? = null
    private var extent = floatArrayOf(1f, 1f, 1f)
    private var center = FloatArray(3)
    private val backgroundUv: VertexBuffer
    private var backgroundUvValues: FloatArray? = null
    private var depthEntity: Int? = null
    private var depthVertices: VertexBuffer? = null
    private var depthIndices: IndexBuffer? = null
    private val depthMaterial: MaterialInstance
    private var depthSize = 0 to 0
    private val shadowEntity: Int
    private val lightEntity: Int
    private val indirect: IndirectLight
    private val grading: ColorGrading
    private var shown = false
    private var width = 1
    private var height = 1
    internal data class RenderTiming(val totalNs: Long = 0, val depthNs: Long = 0,
        val submitNs: Long = 0, val waitNs: Long = 0, val submitted: Boolean = false)
    var lastRenderTiming = RenderTiming(); private set
    private var firstSubmission = false
    init {
        val initializeStart = SystemClock.elapsedRealtimeNanos()
        Log.i("VlmArRender", "渲染初始化开始 backend=OPENGL externalTexture=$textureId")
        // 属性初始化先于 init 执行，不能在属性声明中调用 EntityManager 的 JNI。
        // 先装载所有原生库，再创建 Engine、实体和其他原生资源。
        com.google.android.filament.Filament.init()
        System.loadLibrary("gltfio-jni")
        MaterialBuilder.init()
        Log.i("VlmArRender", "原生库就绪 elapsedMs=${(SystemClock.elapsedRealtimeNanos()-initializeStart)/1_000_000}")
        engine = Engine.Builder().backend(Engine.Backend.OPENGL).sharedContext(egl.context).build()
        Log.i("VlmArRender", "Engine 就绪 elapsedMs=${(SystemClock.elapsedRealtimeNanos()-initializeStart)/1_000_000}")
        cameraEntity = EntityManager.get().create()
        scene = engine.createScene(); renderer = engine.createRenderer(); view = engine.createView()
        camera = engine.createCamera(cameraEntity)
        camera.setExposure(16f, 1f / 125f, 100f)
        view.scene = scene; view.camera = camera
        view.blendMode = View.BlendMode.OPAQUE
        // 实时画面开启标准显示编码；离屏黑白底专用转换不应用到这里。
        grading = ColorGrading.Builder().toneMapping(ColorGrading.ToneMapping.LINEAR).build(engine)
        view.colorGrading = grading
        view.isPostProcessingEnabled = true
        provider = UbershaderProvider(engine); loader = AssetLoader(engine, provider, EntityManager.get())
        resources = ResourceLoader(engine, true)
        importedTexture = Texture.Builder().sampler(Texture.Sampler.SAMPLER_EXTERNAL)
            .format(Texture.InternalFormat.RGB8).importTexture(textureId.toLong()).build(engine)
        val background = material("ar-camera", true, false, true,
            "void material(inout MaterialInputs m) { prepareMaterial(m); vec3 c = texture(materialParams_camera, getUV0()).rgb; m.baseColor = vec4(mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c)), 1.0); }",
            sampler = "camera").defaultInstance
        background.setParameter("camera", importedTexture, TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
        val bg = quad(background, true, 0); backgroundUv = bg.second; scene.addEntity(bg.first)
        // 用真实深度几何写入同一深度缓冲，避免自定义 gl_FragDepth 与引擎深度预通道冲突。
        depthMaterial = material("ar-depth-mask", false, true, false,
            "void material(inout MaterialInputs m) { prepareMaterial(m); m.baseColor = vec4(0.0); }").defaultInstance
        val shadow = material("ar-contact-shadow", false, false, true,
            "void material(inout MaterialInputs m) { prepareMaterial(m); float a = 0.20 * (1.0 - smoothstep(0.1, 0.5, length(getUV0() - vec2(0.5)))); m.baseColor = vec4(0.0, 0.0, 0.0, a); }", transparent = true).defaultInstance
        shadowEntity = quad(shadow, false, 3).first
        lightEntity = EntityManager.get().create(); entities += lightEntity
        LightManager.Builder(LightManager.Type.SUN).color(1f, .97f, .94f).intensity(65_000f)
            .direction(-.3f, -1f, -.5f).castShadows(false).build(engine, lightEntity)
        scene.addEntity(lightEntity)
        indirect = IndirectLight.Builder().irradiance(1, floatArrayOf(.8f, .8f, .8f)).intensity(24_000f).build(engine)
        scene.indirectLight = indirect
        Log.i("VlmArRender", "渲染初始化完成 elapsedMs=${(SystemClock.elapsedRealtimeNanos()-initializeStart)/1_000_000} backgroundEntity=${bg.first} exposure=16/125/100 postProcessing=true")
    }
    private fun material(name: String, device: Boolean, depthWrite: Boolean, colorWrite: Boolean,
                         source: String, sampler: String? = null, transparent: Boolean = false): Material {
        val started = SystemClock.elapsedRealtimeNanos()
        Log.i("VlmArRender", "材质构建开始 name=$name sampler=$sampler device=$device")
        val builder = MaterialBuilder().name(name).platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.OPENGL).shading(MaterialBuilder.Shading.UNLIT)
            .vertexDomain(if (device) MaterialBuilder.VertexDomain.DEVICE else MaterialBuilder.VertexDomain.OBJECT)
            .culling(MaterialBuilder.CullingMode.NONE)
            .depthWrite(depthWrite).depthCulling(depthWrite || !device).colorWrite(colorWrite).material(source)
        if (name != "ar-depth-mask") builder.require(MaterialBuilder.VertexAttribute.UV0)
        // ARCore 已完成屏幕到相机纹理的转换；Filament 默认翻转 V 会在竖屏造成左右镜像。
        if (sampler == "camera") builder.flipUV(false)
        if (transparent) builder.blending(MaterialBuilder.BlendingMode.TRANSPARENT)
        if (sampler != null) builder.samplerParameter(if (sampler == "camera") MaterialBuilder.SamplerType.SAMPLER_EXTERNAL else MaterialBuilder.SamplerType.SAMPLER_2D,
            MaterialBuilder.SamplerFormat.FLOAT, MaterialBuilder.ParameterPrecision.HIGH, sampler)
        val pack = builder.build(engine)
        Log.i("VlmArRender", "材质构建结束 name=$name valid=${pack.isValid} elapsedMs=${(SystemClock.elapsedRealtimeNanos()-started)/1_000_000}")
        check(pack.isValid) { "AR_MATERIAL_INVALID:$name" }
        return Material.Builder().payload(pack.buffer, pack.buffer.remaining()).build(engine).also { materials += it }
    }
    private fun bytes(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(values) }
    private fun quad(mi: MaterialInstance, device: Boolean, priority: Int): Pair<Int, VertexBuffer> {
        val vertex = VertexBuffer.Builder().vertexCount(4).bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2).build(engine)
        buffers += vertex
        vertex.setBufferAt(engine, 0, bytes(if (device) floatArrayOf(-1f,-1f,1f, 1f,-1f,1f, -1f,1f,1f, 1f,1f,1f)
            else floatArrayOf(-.35f,0f,-.25f, .35f,0f,-.25f, -.35f,0f,.25f, .35f,0f,.25f)))
        vertex.setBufferAt(engine, 1, bytes(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)))
        val index = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        indices += index
        val data = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()); data.asShortBuffer().put(shortArrayOf(0,1,2,2,1,3))
        index.setBuffer(engine, data)
        val entity = EntityManager.get().create(); entities += entity
        engine.transformManager.create(entity)
        RenderableManager.Builder(1).boundingBox(Box(0f,0f,0f, 2f,2f,2f)).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertex, index)
            .material(0, mi).priority(priority).culling(false).castShadows(false).receiveShadows(false).build(engine, entity)
        return entity to vertex
    }
    fun surface(surface: Surface, width: Int, height: Int) {
        chain?.let(engine::destroySwapChain)
        chain = engine.createSwapChain(surface); resize(width, height)
        Log.i("VlmArRender", "SwapChain 就绪 size=${width}x$height surfaceValid=${surface.isValid}")
    }
    fun detachSurface() { chain?.let(engine::destroySwapChain); chain = null }
    fun resize(w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        view.viewport = Viewport(0, 0, width, height)
        backgroundUvValues = null
    }
    fun load(plan: CompositionPlan) {
        val action = requireNotNull(plan.resolvedSpec).action
        val key = action.avatarAssetPath + ":" + action.avatarAssetVersion
        if (key != assetKey) {
            hide(); asset?.let(loader::destroyAsset)
            val payload = context.assets.open(action.avatarAssetPath).use { it.readBytes() }
            val metadata = AvatarAssetMetadata.read(payload); metadata.validateFilamentVertexBuffers(); this.metadata = metadata
            val buffer = ByteBuffer.allocateDirect(payload.size).order(ByteOrder.nativeOrder()).apply { put(payload); flip() }
            val loaded = requireNotNull(loader.createAsset(buffer)); resources.loadResources(loaded)
            asset = loaded; assetKey = key; loaded.releaseSourceData()
            val tm = engine.transformManager
            base = loaded.entities.filter { tm.getInstance(it) != 0 }.associateWith { tm.getTransform(tm.getInstance(it), FloatArray(16)) }
            rootBase = tm.getTransform(tm.getInstance(loaded.root), FloatArray(16))
            extent = loaded.boundingBox.halfExtent.map { it * 2f }.toFloatArray(); center = loaded.boundingBox.center
            planKey = null
        }
        val revision = "${plan.id}:${plan.revision}:${action.cacheIdentity()}:${plan.avatar.expression}:${plan.avatar.expressionIntensity}"
        if (revision == planKey) return
        val loaded = requireNotNull(asset); val tm = engine.transformManager; val rm = engine.renderableManager
        base.forEach { (entity, matrix) -> tm.setTransform(tm.getInstance(entity), matrix) }
        loaded.renderableEntities.forEach { entity ->
            val names = loaded.getMorphTargetNames(entity)
            if (names.isNotEmpty()) rm.setMorphWeights(rm.getInstance(entity), FloatArray(names.size), 0)
        }
        when (val source = action.source) {
            is PhotoPoseSource.Animation -> {
                val animator = loaded.instance.animator
                val index = (0 until animator.animationCount).firstOrNull { animator.getAnimationName(it) == source.name }
                check(index != null && source.sampleSeconds in 0f..animator.getAnimationDuration(index))
                animator.applyAnimation(index, source.sampleSeconds)
            }
            is PhotoPoseSource.Snapshot -> {
                val snapshot = context.assets.open(source.path).bufferedReader().use { VlmJson.decodeFromString<PhotoPoseSnapshot>(it.readText()) }
                snapshot.validate(action.rigId, requireNotNull(metadata).bones)
                snapshot.bones.forEach { (name, matrix) -> tm.setTransform(tm.getInstance(loaded.getFirstEntityByName(name)), matrix.toFloatArray()) }
            }
            is PhotoPoseSource.Controlled -> {
                val rotations = source.directive?.let(AvatarCatalog::directiveRotations) ?: AvatarCatalog.poseRotations(source.pose)
                rotations.forEach { (name, angles) ->
                    val entity = loaded.getFirstEntityByName(name); check(entity != 0)
                    val local = requireNotNull(base[entity]).copyOf()
                    Matrix.rotateM(local, 0, angles[0], 1f, 0f, 0f)
                    Matrix.rotateM(local, 0, angles[1], 0f, 1f, 0f)
                    Matrix.rotateM(local, 0, angles[2], 0f, 0f, 1f)
                    tm.setTransform(tm.getInstance(entity), local)
                }
            }
        }
        val profile = action.expressionProfile ?: action.expressionProfilePath?.let { path -> context.assets.open(path).bufferedReader().use { VlmJson.decodeFromString<PhotoExpressionProfile>(it.readText()) } }
        profile?.validate(requireNotNull(metadata).morphs)
        val weights = profile?.weights(plan.avatar.expression, plan.avatar.expressionIntensity)
            ?: AvatarCatalog.expressionWeights(plan.avatar.expression, plan.avatar.expressionIntensity)
        loaded.renderableEntities.forEach { entity ->
            val names = loaded.getMorphTargetNames(entity)
            if (names.isNotEmpty()) rm.setMorphWeights(rm.getInstance(entity), FloatArray(names.size) { weights[names[it]] ?: 0f }, 0)
        }
        planKey = revision
    }
    fun hide() { if (shown) { asset?.let { scene.removeEntities(it.entities) }; scene.removeEntity(shadowEntity); shown = false } }
    fun render(frame: Frame, model: FloatArray?, depthEnabled: Boolean): Boolean {
        val started = SystemClock.elapsedRealtimeNanos()
        // UV 只随显示几何变化，不逐帧重复上传同一份顶点数据。
        if (backgroundUvValues == null || frame.hasDisplayGeometryChanged()) {
            val coordinates = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f), Coordinates2d.TEXTURE_NORMALIZED, coordinates)
            backgroundUv.setBufferAt(engine, 1, bytes(coordinates))
            backgroundUvValues = coordinates
            Log.i("VlmArRender", "相机纹理映射 size=${width}x$height uv=${coordinates.joinToString()} flipUV=false blend=OPAQUE")
        }
        val uv = requireNotNull(backgroundUvValues)
        val projection = FloatArray(16); frame.camera.getProjectionMatrix(projection, 0, ArGeometry.NEAR, ArGeometry.FAR)
        camera.setCustomProjection(DoubleArray(16) { projection[it].toDouble() }, ArGeometry.NEAR.toDouble(), ArGeometry.FAR.toDouble())
        val cameraModel = FloatArray(16); frame.camera.displayOrientedPose.toMatrix(cameraModel, 0); camera.setModelMatrix(cameraModel)
        val light = frame.lightEstimate
        if (light.state == LightEstimate.State.VALID) {
            val color = FloatArray(4); light.getColorCorrection(color, 0)
            val instance = engine.lightManager.getInstance(lightEntity)
            engine.lightManager.setColor(instance, color[0].coerceIn(.4f, 1.6f), color[1].coerceIn(.4f, 1.6f), color[2].coerceIn(.4f, 1.6f))
            engine.lightManager.setIntensity(instance, 65_000f * color[3].coerceIn(.4f, 1.5f))
            indirect.intensity = 24_000f * color[3].coerceIn(.5f, 1.5f)
        }
        val depthStart = SystemClock.elapsedRealtimeNanos()
        val depthActive = depthEnabled && uploadDepth(frame)
        val depthTime = SystemClock.elapsedRealtimeNanos()-depthStart
        if (!depthActive) depthEntity?.let(scene::removeEntity)
        if (model == null) hide() else asset?.let { loaded ->
            val final = model.copyOf(); Matrix.scaleM(final, 0, ArGeometry.CHARACTER_HEIGHT / extent[1].coerceAtLeast(.1f), ArGeometry.CHARACTER_HEIGHT / extent[1].coerceAtLeast(.1f), ArGeometry.CHARACTER_HEIGHT / extent[1].coerceAtLeast(.1f))
            Matrix.translateM(final, 0, -center[0], -(center[1] - extent[1] / 2f), -center[2])
            val root = FloatArray(16); Matrix.multiplyMM(root, 0, final, 0, rootBase, 0)
            val tm = engine.transformManager; tm.setTransform(tm.getInstance(loaded.root), root)
            loaded.instance.animator.updateBoneMatrices()
            val shadow = model.copyOf(); shadow[13] += .004f
            tm.setTransform(tm.getInstance(shadowEntity), shadow)
            if (!shown) { scene.addEntities(loaded.entities); scene.addEntity(shadowEntity); shown = true }
        }
        val submitStart = SystemClock.elapsedRealtimeNanos()
        var submitted = false
        chain?.let { if (renderer.beginFrame(it, System.nanoTime())) { renderer.render(view); renderer.endFrame(); submitted = true } }
        val submitTime = SystemClock.elapsedRealtimeNanos()-submitStart
        val waitStart = SystemClock.elapsedRealtimeNanos()
        engine.flushAndWait()
        val end = SystemClock.elapsedRealtimeNanos()
        lastRenderTiming = RenderTiming(end-started,depthTime,submitTime,end-waitStart,submitted)
        if (submitted && !firstSubmission) {
            firstSubmission = true
            Log.i("VlmArRender", "首帧已提交 size=${width}x$height timestamp=${frame.timestamp} uv=${uv.joinToString()} projectionScale=${projection[0]}/${projection[5]} modelVisible=$shown depth=$depthActive totalMs=${(end-started)/1_000_000} gpuWaitMs=${(end-waitStart)/1_000_000} presentedPixelsVerified=false")
        }
        return depthActive
    }
    private fun uploadDepth(frame: Frame): Boolean {
        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val step = maxOf(1, (image.width + 159) / 160)
                val columns = (image.width + step - 1) / step
                val rows = (image.height + step - 1) / step
                if (columns < 2 || rows < 2) return false
                if (depthSize != (columns to rows)) {
                    depthEntity?.let { scene.removeEntity(it); engine.destroyEntity(it); EntityManager.get().destroy(it) }
                    depthVertices?.let(engine::destroyVertexBuffer); depthIndices?.let(engine::destroyIndexBuffer)
                    depthVertices = VertexBuffer.Builder().vertexCount(columns * rows).bufferCount(1)
                        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3).build(engine)
                    depthIndices = IndexBuffer.Builder().indexCount((columns - 1) * (rows - 1) * 6)
                        .bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
                    depthEntity = EntityManager.get().create().also { entity ->
                        engine.transformManager.create(entity)
                        RenderableManager.Builder(1).boundingBox(Box(0f,0f,-20f, 40f,40f,40f))
                            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, depthVertices!!, depthIndices!!)
                            .material(0,depthMaterial).priority(1).culling(false).castShadows(false).receiveShadows(false).build(engine,entity)
                    }
                    depthSize = columns to rows
                }
                val intrinsics = frame.camera.imageIntrinsics
                val focal = intrinsics.focalLength; val principal = intrinsics.principalPoint; val dimensions = intrinsics.imageDimensions
                val sx = image.width.toFloat() / dimensions[0]; val sy = image.height.toFloat() / dimensions[1]
                val fx = focal[0] * sx; val fy = focal[1] * sy; val cx = principal[0] * sx; val cy = principal[1] * sy
                if (!fx.isFinite() || !fy.isFinite() || fx <= 0f || fy <= 0f) return false
                val plane = image.planes[0]; val input = plane.buffer.duplicate(); val start = input.position()
                val vertices = FloatArray(columns * rows * 3); val depths = FloatArray(columns * rows)
                for (y in 0 until rows) for (x in 0 until columns) {
                    val px = x * step; val py = y * step
                    val offset = start + py * plane.rowStride + px * plane.pixelStride
                    val z = ((input.get(offset).toInt() and 255) or ((input.get(offset+1).toInt() and 255) shl 8)) * .001f
                    val n = y * columns + x
                    if (z in .15f..ArGeometry.FAR) {
                        depths[n] = z
                        ArGeometry.writeDepthVertex(vertices,n*3,px,py,z,fx,fy,cx,cy)
                    }
                }
                val triangles = ByteBuffer.allocateDirect((columns-1)*(rows-1)*24).order(ByteOrder.nativeOrder())
                fun triangle(a: Int,b: Int,c: Int) {
                    val low = minOf(depths[a],depths[b],depths[c]); val high = maxOf(depths[a],depths[b],depths[c])
                    // 不跨越缺失深度或前后景边缘填面，避免制造虚假的遮挡墙。
                    if (low >= .15f && high-low <= .15f + low*.04f) { triangles.putInt(a); triangles.putInt(b); triangles.putInt(c) }
                }
                for (y in 0 until rows-1) for (x in 0 until columns-1) {
                    val a = y*columns+x; triangle(a,a+1,a+columns); triangle(a+1,a+columns+1,a+columns)
                }
                val count = triangles.position()/4
                if (count == 0) return false
                triangles.flip(); depthVertices!!.setBufferAt(engine,0,bytes(vertices)); depthIndices!!.setBuffer(engine,triangles)
                val entity = requireNotNull(depthEntity)
                engine.renderableManager.setGeometryAt(engine.renderableManager.getInstance(entity),0,
                    RenderableManager.PrimitiveType.TRIANGLES,depthVertices!!,depthIndices!!,0,count)
                val matrix = FloatArray(16); frame.camera.pose.toMatrix(matrix,0)
                engine.transformManager.setTransform(engine.transformManager.getInstance(entity),matrix)
                scene.addEntity(entity)
                true
            }
        } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) { false }
          catch (_: com.google.ar.core.exceptions.NotTrackingException) { false }
    }
    override fun close() {
        hide(); engine.flushAndWait(); asset?.let(loader::destroyAsset)
        resources.destroy(); loader.destroy(); provider.destroyMaterials()
        entities.forEach { scene.removeEntity(it); engine.destroyEntity(it); EntityManager.get().destroy(it) }
        buffers.forEach(engine::destroyVertexBuffer); indices.forEach(engine::destroyIndexBuffer)
        depthEntity?.let { scene.removeEntity(it); engine.destroyEntity(it); EntityManager.get().destroy(it) }
        depthVertices?.let(engine::destroyVertexBuffer); depthIndices?.let(engine::destroyIndexBuffer)
        engine.destroyTexture(importedTexture)
        materials.forEach(engine::destroyMaterial)
        engine.destroyIndirectLight(indirect); engine.destroyColorGrading(grading)
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer)
        chain?.let(engine::destroySwapChain)
        engine.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }
}
