package com.framer.sense.feature.camera.vlm.avatar

import android.content.Context
import android.graphics.Bitmap
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.framer.sense.feature.camera.vlm.model.AvatarId
import com.framer.sense.feature.camera.vlm.model.CompositionPlan
import com.framer.sense.feature.camera.vlm.model.CropRect
import com.google.android.filament.*
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * 以 GLB 中原始网格、骨骼和 BlendShape 绘制人像的离屏渲染器。
 *
 * Filament 与 gltfio 都限制在同一个 HandlerThread；缓存最多保留两个角色，避免四个
 * 高精度角色同时占用 GPU 内存。每次渲染只把当前角色实体临时加入 Scene。
 */
class FilamentAvatarRenderer(context: Context) : AvatarRenderer {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("vlm-filament").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var materialProvider: UbershaderProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private val assets = LinkedHashMap<AvatarId, LoadedAvatar>(3, .75f, true)

    private data class LoadedAvatar(
        val asset: FilamentAsset,
        val rootBaseTransform: FloatArray,
        val boneBaseTransforms: Map<String, FloatArray>,
        val height: Float,
        val width: Float,
        val centerX: Float,
        val minY: Float,
        val centerZ: Float
    )

    /** 仅能在渲染线程调用，创建共享的 Filament/glTF Runtime。 */
    private fun initialize() {
        if (engine != null) return
        Filament.init()
        // Filament.init() 只会装载 libfilament-jni；gltfio 是独立 AAR，必须显式
        // 装载其 JNI 库，否则 UbershaderProvider 在真机上会找不到 native 方法。
        System.loadLibrary("gltfio-jni")
        val createdEngine = Engine.create(Engine.Backend.OPENGL)
        try {
            val provider = UbershaderProvider(createdEngine)
            engine = createdEngine
            materialProvider = provider
            assetLoader = AssetLoader(createdEngine, provider, EntityManager.get())
            resourceLoader = ResourceLoader(createdEngine, true)
        } catch (failure: Throwable) {
            materialProvider?.destroyMaterials()
            createdEngine.destroy()
            engine = null
            materialProvider = null
            throw failure
        }
    }

    /** 加载角色 GLB，并记录绑定姿势，供每次方案渲染前恢复。 */
    private fun avatar(id: AvatarId): LoadedAvatar {
        assets[id]?.let { return it }
        val bytes = appContext.assets.open(AvatarCatalog.asset(id).fileName).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            flip()
        }
        val loaded = requireNotNull(assetLoader!!.createAsset(buffer)) { "avatar_asset_load_failed" }
        try {
            resourceLoader!!.loadResources(loaded)
            loaded.releaseSourceData()
            loaded.instance.animator?.updateBoneMatrices()
            val transforms = engine!!.transformManager
            val rootBaseTransform = FloatArray(16)
            // Rocketbox 将网格和骨骼放在场景根节点的两个分支中。instance.root 只指向
            // 骨骼分支，方案变换必须以 asset.root 为目标，才能同时影响身体网格与骨骼。
            transforms.getTransform(transforms.getInstance(loaded.root), rootBaseTransform)
            val boneBase = AvatarCatalog.controlledBoneNames
                .mapNotNull { name ->
                    val entity = loaded.getFirstEntityByName(name)
                    if (entity == 0) null else {
                        val matrix = FloatArray(16)
                        transforms.getTransform(transforms.getInstance(entity), matrix)
                        name to matrix
                    }
                }
                .toMap()
            val bounds = loaded.boundingBox
            val center = bounds.center
            val half = bounds.halfExtent
            val worldHalfX = half[0] * ROCKETBOX_BOUNDING_BOX_EXTENT_SCALE
            val worldHalfY = half[1] * ROCKETBOX_BOUNDING_BOX_EXTENT_SCALE
            Log.i(
                LOG_TAG,
                "GLB 诊断加载 role=$id assetRoot=${loaded.root} instanceRoot=${loaded.instance.root} " +
                    "boxCenter=${center.contentToString()} boxHalf=${half.contentToString()} " +
                    "assetRootLocal=${matrixText(rootBaseTransform)}"
            )
            val ready = LoadedAvatar(
                asset = loaded,
                rootBaseTransform = rootBaseTransform,
                boneBaseTransforms = boneBase,
                // gltfio 对这批 Rocketbox GLB 的 boundingBox extent 少报了 100 倍，
                // 而 center 保持正确的场景坐标。仅校正 extent，脚底仍以原场景中心定位。
                height = max(.01f, worldHalfY * 2f),
                width = max(.01f, worldHalfX * 2f),
                centerX = center[0],
                minY = center[1] - worldHalfY,
                centerZ = center[2]
            )
            assets[id] = ready
            trimCache()
            return ready
        } catch (failure: Throwable) {
            assetLoader!!.destroyAsset(loaded)
            throw failure
        }
    }

    /** 缓存按最近使用淘汰，绝不销毁正在被 Scene 使用的资源（调用时 Scene 为空）。 */
    private fun trimCache() {
        while (assets.size > 2) {
            val eldest = assets.entries.iterator().next()
            assetLoader!!.destroyAsset(eldest.value.asset)
            assets.remove(eldest.key)
        }
    }

    override suspend fun render(
        plan: CompositionPlan,
        width: Int,
        height: Int,
        warmth: Float,
        brightness: Float
    ): AvatarPreview = mutex.withLock {
        withContext(dispatcher) {
            initialize()
            val e = engine!!
            val loaded = avatar(plan.avatar.avatarId)
            val scene = e.createScene()
            val view = e.createView()
            val renderer = e.createRenderer()
            val cameraEntity = EntityManager.get().create()
            val camera = e.createCamera(cameraEntity)
            val swapChain = e.createSwapChain(width, height, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
            var lightEntity = 0
            var fillLightEntity = 0
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
                // 后处理最终合成会把透明交换链的 alpha 写成 1，导致整个 GLB 离屏层遮住
                // 冻结照片；人像参考图需要保留每个像素的原始透明度，故在该离屏路径关闭它。
                view.isPostProcessingEnabled = false
                lightEntity = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.SUN)
                    .color(1f, 1f - warmth.coerceIn(-1f, 1f) * .08f, 1f - warmth.coerceIn(-1f, 1f) * .16f)
                    .intensity(45000f + brightness.coerceIn(0f, 1f) * 45000f)
                    .direction(-.4f, -1f, -1f)
                    .castShadows(false)
                    .build(e, lightEntity)
                scene.addEntity(lightEntity)
                // 为脸部和衣物补入低强度正面光，避免单一太阳光造成眼眶与面部发黑。
                fillLightEntity = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(1f, .94f, .90f)
                    .intensity(22_000f)
                    .direction(.25f, -.55f, -1f)
                    .castShadows(false)
                    .build(e, fillLightEntity)
                scene.addEntity(fillLightEntity)
                indirect = IndirectLight.Builder().irradiance(1, floatArrayOf(.8f, .8f, .8f)).intensity(24_000f).build(e)
                scene.indirectLight = indirect
                var renderedPlan = plan
                var expectedBounds = applyPlan(loaded, renderedPlan, aspect)
                scene.addEntities(loaded.asset.entities)
                var bitmap = renderTransparent(e, renderer, view, swapChain, width, height)
                var actualBounds = alphaCropRect(bitmap)
                // 个别 GLB 导出会报告不准确包围盒。按真实透明像素高度只做一次向下校准，
                // 正常资产不会触发；绝不为了填满画面而放大人像。
                val shrink = calibrationShrink(expectedBounds, actualBounds)
                if (shrink < .96f) {
                    val originalHeight = renderedPlan.avatar.height
                    renderedPlan = renderedPlan.copy(avatar = renderedPlan.avatar.copy(height = originalHeight * shrink))
                    expectedBounds = applyPlan(loaded, renderedPlan, aspect)
                    bitmap.recycle()
                    bitmap = renderTransparent(e, renderer, view, swapChain, width, height)
                    actualBounds = alphaCropRect(bitmap)
                    Log.w(LOG_TAG, "GLB 比例校准 role=${plan.avatar.avatarId} targetHeight=$originalHeight shrink=$shrink")
                }
                val bounds = actualBounds ?: expectedBounds
                Log.i(
                    LOG_TAG,
                    "GLB 诊断像素 role=${plan.avatar.avatarId} pose=${renderedPlan.avatar.pose} " +
                        "alphaBounds=${alphaBounds(bitmap)} expectedBounds=$expectedBounds actualBounds=$actualBounds"
                )
                AvatarPreview(bitmap, bounds)
            } finally {
                scene.removeEntities(loaded.asset.entities)
                e.flushAndWait()
                if (lightEntity != 0) {
                    e.destroyEntity(lightEntity)
                    EntityManager.get().destroy(lightEntity)
                }
                if (fillLightEntity != 0) {
                    e.destroyEntity(fillLightEntity)
                    EntityManager.get().destroy(fillLightEntity)
                }
                indirect?.let(e::destroyIndirectLight)
                e.destroyView(view)
                e.destroyScene(scene)
                e.destroyRenderer(renderer)
                e.destroyCameraComponent(cameraEntity)
                EntityManager.get().destroy(cameraEntity)
                e.destroySwapChain(swapChain)
            }
        }
    }

    /** 把受限姿势、表情和方案坐标应用到 GLB，返回画面归一化投影边界。 */
    private fun applyPlan(loaded: LoadedAvatar, plan: CompositionPlan, aspect: Float): CropRect {
        val transforms = engine!!.transformManager
        val avatar = plan.avatar
        val visibleHeight = avatar.height
        val scale = visibleHeight / loaded.height
        // 新方案使用细分动作；旧方案没有 poseDirective 时严格沿用 PoseId，避免历史卡片
        // 在升级后突然改变姿势。每次均从绑定姿势恢复，杜绝上一张方案的骨骼残留。
        val rotations = avatar.poseDirective?.let { AvatarCatalog.directiveRotations(it) }
            ?: AvatarCatalog.poseRotations(avatar.pose)
        for ((name, base) in loaded.boneBaseTransforms) {
            val entity = loaded.asset.getFirstEntityByName(name)
            if (entity == 0) continue
            val rotated = base.copyOf()
            rotations[name]?.let { angles ->
                Matrix.rotateM(rotated, 0, angles[0], 1f, 0f, 0f)
                Matrix.rotateM(rotated, 0, angles[1], 0f, 1f, 0f)
                Matrix.rotateM(rotated, 0, angles[2], 0f, 0f, 1f)
            }
            transforms.setTransform(transforms.getInstance(entity), rotated)
        }
        loaded.asset.instance.animator?.updateBoneMatrices()
        // 保持 GLB 原生实体层级不变，并在场景根节点同时缩放网格和骨骼。
        val root = FloatArray(16)
        Matrix.setIdentityM(root, 0)
        Matrix.translateM(root, 0, avatar.foot.x * aspect, 1f - avatar.foot.y, 0f)
        Matrix.rotateM(root, 0, avatar.yaw, 0f, 1f, 0f)
        Matrix.scaleM(root, 0, scale, scale, scale)
        Matrix.translateM(root, 0, -loaded.centerX, -loaded.minY, -loaded.centerZ)
        val finalRoot = FloatArray(16)
        Matrix.multiplyMM(finalRoot, 0, root, 0, loaded.rootBaseTransform, 0)
        transforms.setTransform(transforms.getInstance(loaded.asset.root), finalRoot)
        val firstMesh = loaded.asset.renderableEntities.firstOrNull()
        Log.i(
            LOG_TAG,
            "GLB 诊断变换 role=${avatar.avatarId} targetHeight=${avatar.height} scale=$scale " +
                "foot=${avatar.foot} assetRootLocal=${matrixText(finalRoot)} " +
                "assetRootWorld=${worldMatrixText(loaded.asset.root)} " +
                "mesh=${firstMesh ?: 0} meshWorld=${firstMesh?.let(::worldMatrixText) ?: "none"}"
        )

        val desiredWeights = AvatarCatalog.expressionWeights(avatar.expression, avatar.expressionIntensity)
        val renderables = engine!!.renderableManager
        for (entity in loaded.asset.renderableEntities) {
            val instance = renderables.getInstance(entity)
            if (instance == 0) continue
            val names = loaded.asset.getMorphTargetNames(entity)
            if (names.isEmpty()) continue
            val weights = FloatArray(names.size) { index -> desiredWeights[names[index]] ?: 0f }
            renderables.setMorphWeights(instance, weights, 0)
        }

        val width = loaded.width * scale / aspect
        return CropRect(
            left = avatar.foot.x - width / 2f,
            top = avatar.foot.y - visibleHeight,
            right = avatar.foot.x + width / 2f,
            bottom = avatar.foot.y
        )
    }

    /** 提交一帧并等待读回透明 RGBA 像素。 */
    private suspend fun readPixels(
        e: Engine,
        renderer: Renderer,
        view: View,
        chain: SwapChain,
        pixels: ByteBuffer,
        width: Int,
        height: Int
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        check(renderer.beginFrame(chain, System.nanoTime())) { "avatar_frame_unavailable" }
        try {
            renderer.render(view)
            val descriptor = Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE)
            descriptor.setCallback(handler, Runnable { if (continuation.isActive) continuation.resume(Unit) })
            renderer.readPixels(0, 0, width, height, descriptor)
        } finally {
            renderer.endFrame()
        }
        e.flushAndWait()
    }

    private suspend fun readBitmap(
        e: Engine,
        renderer: Renderer,
        view: View,
        chain: SwapChain,
        width: Int,
        height: Int
    ): Bitmap {
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        readPixels(e, renderer, view, chain, pixels, width, height)
        pixels.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(pixels)
        }
    }

    /** 用黑白底各渲染一次，恢复部分 GPU 会丢失的透明 alpha。 */
    private suspend fun renderTransparent(
        e: Engine,
        renderer: Renderer,
        view: View,
        swapChain: SwapChain,
        width: Int,
        height: Int
    ): Bitmap {
        renderer.clearOptions = opaqueClear(0.0)
        val blackMatte = readBitmap(e, renderer, view, swapChain, width, height)
        renderer.clearOptions = opaqueClear(1.0)
        val whiteMatte = readBitmap(e, renderer, view, swapChain, width, height)
        return restoreTransparency(blackMatte, whiteMatte)
    }

    private fun opaqueClear(value: Double) = Renderer.ClearOptions().apply {
        clear = true
        clearColor = doubleArrayOf(value, value, value, 1.0)
    }

    /**
     * 黑底像素为 alpha × 前景色，白底像素多出的部分为 (1 - alpha)。
     * 这样即使驱动把 readPixels 的 alpha 强制写成 255，也能恢复抗锯齿边缘的透明度。
     */
    private fun restoreTransparency(black: Bitmap, white: Bitmap): Bitmap {
        check(black.width == white.width && black.height == white.height) { "avatar_matte_size_mismatch" }
        val size = black.width * black.height
        val blackPixels = IntArray(size)
        val whitePixels = IntArray(size)
        val output = IntArray(size)
        black.getPixels(blackPixels, 0, black.width, 0, 0, black.width, black.height)
        white.getPixels(whitePixels, 0, white.width, 0, 0, white.width, white.height)
        for (index in 0 until size) {
            val dark = blackPixels[index]
            val light = whitePixels[index]
            val darkRed = dark ushr 16 and 0xff
            val darkGreen = dark ushr 8 and 0xff
            val darkBlue = dark and 0xff
            val difference = (
                ((light ushr 16 and 0xff) - darkRed).coerceIn(0, 255) +
                    ((light ushr 8 and 0xff) - darkGreen).coerceIn(0, 255) +
                    ((light and 0xff) - darkBlue).coerceIn(0, 255)
                ) / 3
            val alpha = (255 - difference).coerceIn(0, 255)
            if (alpha == 0) continue
            val rawRed = (darkRed * 255 / alpha).coerceAtMost(255)
            val rawGreen = (darkGreen * 255 / alpha).coerceAtMost(255)
            val rawBlue = (darkBlue * 255 / alpha).coerceAtMost(255)
            // Rocketbox 原始贴图偏写实且暗部很重。轻度提亮、降低局部对比和饱和度，
            // 使参考人像更接近柔和插画效果，同时保留衣物与表情细节。
            val luminance = rawRed * .2126f + rawGreen * .7152f + rawBlue * .0722f
            val red = softIllustrationChannel(rawRed, luminance)
            val green = softIllustrationChannel(rawGreen, luminance)
            val blue = softIllustrationChannel(rawBlue, luminance)
            output[index] = alpha shl 24 or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(output, black.width, black.height, Bitmap.Config.ARGB_8888)
    }

    private fun softIllustrationChannel(channel: Int, luminance: Float): Int {
        val softened = luminance + (channel - luminance) * .82f
        val lifted = kotlin.math.sqrt((softened / 255f).coerceIn(0f, 1f)) * 255f
        return (lifted * .90f + 255f * .10f).toInt().coerceIn(0, 255)
    }

    private companion object {
        const val LOG_TAG = "VlmAvatar"
        const val ROCKETBOX_BOUNDING_BOX_EXTENT_SCALE = 100f
    }

    private fun worldMatrixText(entity: Int): String {
        val transforms = engine!!.transformManager
        val instance = transforms.getInstance(entity)
        if (instance == 0) return "missing"
        return matrixText(transforms.getWorldTransform(instance, FloatArray(16)))
    }

    private fun matrixText(matrix: FloatArray): String = matrix
        .map { "%.4f".format(java.util.Locale.US, it) }
        .joinToString(prefix = "[", postfix = "]")

    /** 仅用于定位透明读回与网格投影是否一致；不参与最终合成。 */
    private fun alphaBounds(bitmap: Bitmap): String {
        val bounds = alphaCropRect(bitmap) ?: return "empty"
        val left = (bounds.left * bitmap.width).toInt()
        val top = (bounds.top * bitmap.height).toInt()
        val right = (bounds.right * bitmap.width).toInt() - 1
        val bottom = (bounds.bottom * bitmap.height).toInt() - 1
        return "$left,$top,$right,$bottom/${bitmap.width}x${bitmap.height}"
    }

    /** 透明人偶在离屏预览中的实际归一化像素边界。 */
    private fun alphaCropRect(bitmap: Bitmap): CropRect? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, pixel ->
            if ((pixel ushr 24) <= 12) return@forEachIndexed
            val x = index % bitmap.width
            val y = index / bitmap.width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        return if (right < 0) null else CropRect(
            left.toFloat() / bitmap.width,
            top.toFloat() / bitmap.height,
            (right + 1).toFloat() / bitmap.width,
            (bottom + 1).toFloat() / bitmap.height
        )
    }

    /** 仅在实际身高明显超过目标可见高度时向下修正，防止资产异常遮住环境。 */
    private fun calibrationShrink(expected: CropRect, actual: CropRect?): Float {
        actual ?: return 1f
        fun visibleHeight(bounds: CropRect) = (bounds.bottom.coerceAtMost(1f) - bounds.top.coerceAtLeast(0f)).coerceAtLeast(.01f)
        val ratio = visibleHeight(expected) / visibleHeight(actual)
        return if (ratio < .96f) ratio.coerceIn(.45f, .95f) else 1f
    }
}
