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
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.serialization.json.*
import com.framer.sense.feature.camera.vlm.R
import com.framer.sense.feature.camera.vlm.ar.SpatialMatrices
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
class FilamentAvatarRenderer(context: Context, private val catalog: PhotoActionCatalog = PhotoActionCatalog()) : AvatarRenderer {
    private val appContext = context.applicationContext
    private val scaleStore = AvatarScaleStore(context)
    override suspend fun scaleMetadata(action: ResolvedPhotoAction) = scaleStore.find(action)
    private val thread = HandlerThread("vlm-filament").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var materialProvider: UbershaderProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private val assets = LinkedHashMap<String, LoadedAvatar>(3, .75f, true)
    private val metadataMutex = Mutex()
    private val metadataCache = mutableMapOf<String, AvatarAssetMetadata>()
    private val resolver = PhotoActionResolver(catalog)
    private val expressionProfiles = mutableMapOf<String, PhotoExpressionProfile>()

    private fun profile(action: ResolvedPhotoAction): PhotoExpressionProfile? = action.expressionProfilePath?.let { path ->
        expressionProfiles.getOrPut("$path:${action.avatarAssetVersion}:${action.version}") {
            appContext.assets.open(path).bufferedReader().use { VlmJson.decodeFromString<PhotoExpressionProfile>(it.readText()) }
        }
    }

    private fun weights(action: ResolvedPhotoAction, expression: ExpressionId, intensity: Float): Map<String, Float> =
        action.expressionProfile?.weights(expression, intensity) ?: AvatarCatalog.expressionWeights(expression, intensity)

    private data class LoadedAvatar(
        val asset: FilamentAsset,
        val rootBaseTransform: FloatArray,
        val boneBaseTransforms: Map<String, FloatArray>,
        val nodeBaseTransforms: Map<Int, FloatArray>,
        val height: Float,
        val width: Float,
        val centerX: Float,
        val minY: Float,
        val centerZ: Float
    )

    private fun metadata(asset: AvatarCatalog.Asset): AvatarAssetMetadata =
        metadataCache.getOrPut("${asset.fileName}:${asset.version}") {
            AvatarAssetMetadata.read(appContext.assets.open(asset.fileName).use { it.readBytes() })
        }

    private fun snapshot(source: PhotoPoseSource.Snapshot): PhotoPoseSnapshot =
        appContext.assets.open(source.path).bufferedReader().use { VlmJson.decodeFromString<PhotoPoseSnapshot>(it.readText()) }

    private fun validateAction(definition: PhotoActionDefinition, role: AvatarId, ethnicity: AvatarEthnicity? = null) {
        val data = metadata(definition.asset(role, ethnicity))
        PhotoActionSupport.validate(definition, role, data, (definition.source as? PhotoPoseSource.Snapshot)?.let(::snapshot), ethnicity)
    }

    override suspend fun availableActions(role: AvatarId, shot: ShotType): List<PhotoActionDefinition> =
        withContext(kotlinx.coroutines.Dispatchers.IO) { metadataMutex.withLock {
            catalog.candidates(role, shot).filter { it.selectable && runCatching { validateAction(it, role) }.isSuccess }
        } }

    override suspend fun availableExpressions(plan: CompositionPlan): List<ExpressionId> =
        withContext(kotlinx.coroutines.Dispatchers.IO) { metadataMutex.withLock {
            val action = resolver.resolve(plan).resolvedSpec!!.action
            val data = metadata(AvatarCatalog.Asset(action.avatarAssetPath, action.avatarAssetVersion, action.rigId))
            val configured = profile(action)
            configured?.validate(data.morphs)
            (configured?.expressions?.keys?.toList() ?: ExpressionId.entries).filter {
                data.morphs.containsAll(configured?.weights(it, 1f)?.keys ?: AvatarCatalog.expressionWeights(it, 1f).keys)
            }
        } }

    override suspend fun resolve(plan: CompositionPlan, selectedActionId: String?): CompositionPlan =
        withContext(kotlinx.coroutines.Dispatchers.IO) { metadataMutex.withLock {
            try {
                val automatic = if (selectedActionId == null && plan.resolvedSpec == null) {
                    val usable = catalog.candidates(plan.avatar.avatarId, plan.shot).filter {
                        it.readiness == ActionReadiness.REVIEWED && runCatching {
                            validateAction(it, plan.avatar.avatarId, plan.avatar.ethnicity)
                            val action = resolver.resolve(plan, it.id, ActionOrigin.MODEL).resolvedSpec!!.action
                            val configured = profile(action)
                            val data = metadata(it.asset(plan.avatar.avatarId, plan.avatar.ethnicity))
                            configured?.validate(data.morphs)
                            val required = (configured?.weights(plan.avatar.expression, plan.avatar.expressionIntensity)
                                ?: AvatarCatalog.expressionWeights(plan.avatar.expression, plan.avatar.expressionIntensity)).filterValues { weight -> weight > 0f }.keys
                            require(data.morphs.containsAll(required))
                        }.isSuccess
                    }
                    resolver.automaticActionId(plan, usable)
                } else null
                var resolved = resolver.resolve(plan, selectedActionId ?: automatic,
                    if (automatic != null) ActionOrigin.MODEL else null)
                val rawSpec = resolved.resolvedSpec!!
                val configured = profile(rawSpec.action)
                if (configured != null) {
                    require(configured.status == rawSpec.action.readiness) { "PHOTO_EXPRESSION_REVIEW_MISMATCH" }
                    configured.validate(metadata(AvatarCatalog.Asset(rawSpec.action.avatarAssetPath,
                        rawSpec.action.avatarAssetVersion, rawSpec.action.rigId)).morphs)
                    if (resolved.avatar.expression !in configured.expressions && selectedActionId != null) {
                        resolved = resolved.withActualAvatar(resolved.avatar.copy(expression = ExpressionId.SMILE,
                            expressionIntensity = configured.defaultIntensity))
                    }
                    val currentSpec = requireNotNull(resolved.resolvedSpec)
                    resolved = resolved.copy(resolvedSpec = currentSpec.copy(
                        action = currentSpec.action.copy(expressionProfile = configured)))
                }
                val spec = resolved.resolvedSpec!!
                validateAction(catalog.find(spec.action.id), spec.avatar.avatarId, spec.avatar.ethnicity)
                val data = metadata(AvatarCatalog.Asset(spec.action.avatarAssetPath, spec.action.avatarAssetVersion, spec.action.rigId))
                val required = weights(spec.action, spec.avatar.expression, spec.avatar.expressionIntensity).filterValues { it > 0f }.keys
                require(data.morphs.containsAll(required)) { "PHOTO_EXPRESSION_MISSING" }
                val controlled = spec.action.source as? PhotoPoseSource.Controlled
                if (controlled != null) {
                    val bones = controlled.directive?.let(AvatarCatalog::directiveRotations)?.keys ?: AvatarCatalog.poseRotations(controlled.pose).keys
                    require(data.bones.containsAll(bones)) { "PHOTO_ACTION_BONES_MISSING" }
                }
                resolved
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) {
                Log.w(LOG_TAG, "动作解析失败 action=${selectedActionId ?: plan.resolvedSpec?.action?.id} code=${failure.message}")
                throw IllegalStateException(appContext.getString(R.string.vlm_photo_action_unavailable), failure)
            }
        } }

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
    private fun avatar(id: AvatarId, action: ResolvedPhotoAction): LoadedAvatar {
        val key = "${action.avatarAssetPath}:${action.avatarAssetVersion}:${action.rigId}:${action.boundsExtentScale}"
        assets[key]?.let { return it }
        val bytes = appContext.assets.open(action.avatarAssetPath).use { it.readBytes() }
        val metadata = AvatarAssetMetadata.read(bytes)
        Log.i(LOG_TAG, "GLB 顶点布局检查 asset=${action.avatarAssetPath} assetVersion=${action.avatarAssetVersion} " +
            "primitives=${metadata.vertexLayouts.size} maxSlotsUpperBound=${metadata.maxFilamentSlots} " +
            "limit=${AvatarVertexLayout.FILAMENT_SLOT_LIMIT}")
        // PreconditionPanic 会在 JNI 触发 SIGABRT，不能靠 Kotlin try/catch 捕获。
        // 必须先校验再调用 createAsset；也在动作能力筛选阶段执行同一校验。
        metadata.validateFilamentVertexBuffers()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            flip()
        }
        val loaded = requireNotNull(assetLoader!!.createAsset(buffer)) { "avatar_asset_load_failed" }
        try {
            resourceLoader!!.loadResources(loaded)
            loaded.releaseSourceData()
            loaded.instance.animator.updateBoneMatrices()
            val transforms = engine!!.transformManager
            val rootBaseTransform = FloatArray(16)
            // Rocketbox 将网格和骨骼放在场景根节点的两个分支中。instance.root 只指向
            // 骨骼分支，方案变换必须以 asset.root 为目标，才能同时影响身体网格与骨骼。
            transforms.getTransform(transforms.getInstance(loaded.root), rootBaseTransform)
            val boneBase = metadata.bones
                .mapNotNull { name ->
                    val entity = loaded.getFirstEntityByName(name)
                    if (entity == 0) null else {
                        val matrix = FloatArray(16)
                        transforms.getTransform(transforms.getInstance(entity), matrix)
                        name to matrix
                    }
                }
                .toMap()
            check(boneBase.keys == metadata.bones) { "PHOTO_ACTION_BONES_MISSING" }
            val nodeBase = loaded.entities.asSequence().mapNotNull { entity ->
                val instance = transforms.getInstance(entity)
                if (instance == 0) null else entity to FloatArray(16).also { transforms.getTransform(instance, it) }
            }.toMap()
            val bounds = loaded.boundingBox
            val center = bounds.center
            val half = bounds.halfExtent
            val worldHalfX = half[0] * action.boundsExtentScale
            val worldHalfY = half[1] * action.boundsExtentScale
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
                nodeBaseTransforms = nodeBase,
                // gltfio 对这批 Rocketbox GLB 的 boundingBox extent 少报了 100 倍，
                // 而 center 保持正确的场景坐标。仅校正 extent，脚底仍以原场景中心定位。
                height = max(.01f, worldHalfY * 2f),
                width = max(.01f, worldHalfX * 2f),
                centerX = center[0],
                minY = center[1] - worldHalfY,
                centerZ = center[2]
            )
            assets[key] = ready
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
    ): AvatarPreview = renderResolved(resolve(plan), width, height, warmth, brightness, AvatarRenderPurpose.COMPOSITION)

    override suspend fun renderFace(plan: CompositionPlan, size: Int): AvatarPreview =
        renderResolved(resolve(plan), size, size, 0f, .6f, AvatarRenderPurpose.FACE)

    private suspend fun renderResolved(
        plan: CompositionPlan, width: Int, height: Int, warmth: Float, brightness: Float, purpose: AvatarRenderPurpose
    ): AvatarPreview = mutex.withLock {
        val screenMetadata=if(plan.photography != null) scaleStore.find(requireNotNull(plan.resolvedSpec).action) else null
        withContext(dispatcher) {
            require(width > 0 && height > 0)
            initialize()
            val e = engine!!
            val loaded = avatar(plan.avatar.avatarId, requireNotNull(plan.resolvedSpec).action)
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
                if (purpose == AvatarRenderPurpose.COMPOSITION) plan.spatialPlacement?.let { placement ->
                    val projection = SpatialMatrices.cropProjection(placement.camera.projection, plan.crop)
                    camera.setCustomProjection(projection.map(Float::toDouble).toDoubleArray(), .1, 40.0)
                    camera.setModelMatrix(requireNotNull(SpatialMatrices.inverse(placement.camera.view)).toFloatArray())
                }
                camera.setExposure(16f, 1f / 125f, 100f)
                view.scene = scene
                view.camera = camera
                view.viewport = Viewport(0, 0, width, height)
                view.blendMode = View.BlendMode.TRANSLUCENT
                // 黑白底必须保持线性才能恢复透明度。这里关闭后处理且不启用 SRGB swapchain，
                // 读回后由 AvatarDisplayColor 恢复 alpha 并编码 sRGB，不能直接当屏幕颜色使用。
                view.isPostProcessingEnabled = false
                Log.i(LOG_TAG, "GLB 显示颜色 action=${plan.resolvedSpec?.action?.id} " +
                    "ethnicity=${plan.avatar.ethnicity} purpose=$purpose " +
                    "pipeline=${AvatarDisplayColor.VERSION} readback=LINEAR bitmap=SRGB " +
                    "warmth=$warmth brightness=$brightness")
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
                var expectedBounds = applyPlan(loaded, renderedPlan, aspect,screenMetadata)
                if (purpose == AvatarRenderPurpose.FACE) focusFace(loaded, renderedPlan, camera)
                scene.addEntities(loaded.asset.entities)
                val softenColors = requireNotNull(plan.resolvedSpec).action.softenColors
                var bitmap = renderTransparent(e, renderer, view, swapChain, width, height, softenColors)
                var actualBounds = alphaCropRect(bitmap)
                // 个别 GLB 导出会报告不准确包围盒。按真实透明像素高度只做一次向下校准，
                // 正常资产不会触发；绝不为了填满画面而放大人像。
                val shrink = if (purpose == AvatarRenderPurpose.FACE || plan.spatialPlacement != null || plan.avatar.projectionCalibrated) 1f else calibrationShrink(expectedBounds, actualBounds)
                if (shrink < .96f) {
                    val originalHeight = renderedPlan.avatar.height
                    renderedPlan = renderedPlan.shrinkProjection(shrink)
                    expectedBounds = applyPlan(loaded, renderedPlan, aspect,screenMetadata)
                    bitmap.recycle()
                    bitmap = renderTransparent(e, renderer, view, swapChain, width, height, softenColors)
                    actualBounds = alphaCropRect(bitmap)
                    Log.w(LOG_TAG, "GLB 比例校准 role=${plan.avatar.avatarId} targetHeight=$originalHeight shrink=$shrink")
                }
                val bounds = actualBounds ?: expectedBounds
                Log.i(
                    LOG_TAG,
                    "GLB 诊断像素 role=${plan.avatar.avatarId} pose=${renderedPlan.avatar.pose} " +
                        "alphaBounds=${alphaBounds(bitmap)} expectedBounds=$expectedBounds actualBounds=$actualBounds"
                )
                check(actualBounds != null) { bitmap.recycle(); appContext.getString(R.string.vlm_avatar_empty_projection) }
                val applied = if (purpose == AvatarRenderPurpose.FACE) renderedPlan else renderedPlan.withActualAvatar(renderedPlan.avatar.copy(projectionCalibrated = true))
                AvatarPreview(bitmap, bounds, applied.avatar, applied.resolvedSpec)
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

    private fun focusFace(loaded: LoadedAvatar, plan: CompositionPlan, camera: Camera) {
        val anchor = requireNotNull(plan.resolvedSpec).action.faceAnchorNode
        val head = loaded.asset.getFirstEntityByName(anchor)
        check(head != 0) { appContext.getString(R.string.vlm_photo_face_unavailable) }
        val transforms = engine!!.transformManager
        val world = transforms.getWorldTransform(transforms.getInstance(head), FloatArray(16))
        val displayHeight = plan.spatialPlacement?.heightMeters ?: plan.avatar.height
        val half = displayHeight * .13
        camera.setProjection(Camera.Projection.ORTHO, -half, half, -half, half, .1, 20.0)
        val x = world[12].toDouble()
        val y = world[13].toDouble() + displayHeight * .02
        val z = world[14].toDouble()
        Log.i(LOG_TAG, "脸部示范定位 action=${plan.resolvedSpec.action.id} anchor=$anchor expression=${plan.avatar.expression}")
        camera.lookAt(x, y, z + 6.0, x, y, z, 0.0, 1.0, 0.0)
    }

    /** 把受限姿势、表情和方案坐标应用到 GLB，返回画面归一化投影边界。 */
    private fun applyPlan(loaded: LoadedAvatar, plan: CompositionPlan, aspect: Float, screenMetadata: AvatarScaleMetadata? = null): CropRect {
        val transforms = engine!!.transformManager
        val avatar = plan.avatar
        val visibleHeight = avatar.height
        val spatial = plan.spatialPlacement
        val scale = spatial?.let { it.heightMeters / it.metadata.referenceHeight }
            ?: (visibleHeight / (screenMetadata?.referenceHeight ?: loaded.height))
        // 新方案使用细分动作；旧方案没有 poseDirective 时严格沿用 PoseId，避免历史卡片
        // 在升级后突然改变姿势。每次均从绑定姿势恢复，杜绝上一张方案的骨骼残留。
        for ((entity, base) in loaded.nodeBaseTransforms) transforms.setTransform(transforms.getInstance(entity), base)
        val renderables = engine!!.renderableManager
        for (entity in loaded.asset.renderableEntities) {
            val names = loaded.asset.getMorphTargetNames(entity)
            if (names.isNotEmpty()) renderables.setMorphWeights(renderables.getInstance(entity), FloatArray(names.size), 0)
        }
        when (val source = requireNotNull(plan.resolvedSpec).action.source) {
            is PhotoPoseSource.Controlled -> {
                val rotations = source.directive?.let(AvatarCatalog::directiveRotations) ?: AvatarCatalog.poseRotations(source.pose)
                for ((name, angles) in rotations) {
                    val entity = loaded.asset.getFirstEntityByName(name)
                    val rotated = loaded.boneBaseTransforms.getValue(name).copyOf()
                    Matrix.rotateM(rotated, 0, angles[0], 1f, 0f, 0f)
                    Matrix.rotateM(rotated, 0, angles[1], 0f, 1f, 0f)
                    Matrix.rotateM(rotated, 0, angles[2], 0f, 0f, 1f)
                    transforms.setTransform(transforms.getInstance(entity), rotated)
                }
            }
            is PhotoPoseSource.Snapshot -> {
                val pose = snapshot(source)
                pose.validate(plan.resolvedSpec.action.rigId, loaded.boneBaseTransforms.keys)
                pose.bones.forEach { (name, matrix) ->
                    transforms.setTransform(transforms.getInstance(loaded.asset.getFirstEntityByName(name)), matrix.toFloatArray())
                }
            }
            is PhotoPoseSource.Animation -> {
                val animator = loaded.asset.instance.animator
                val index = (0 until animator.animationCount).firstOrNull { animator.getAnimationName(it) == source.name }
                check(index != null && source.sampleSeconds.isFinite() && source.sampleSeconds in 0f..animator.getAnimationDuration(index)) { appContext.getString(R.string.vlm_photo_action_unavailable) }
                animator.applyAnimation(index, source.sampleSeconds)
            }
        }
        // 保持 GLB 原生实体层级不变，并在场景根节点同时缩放网格和骨骼。
        val root = FloatArray(16)
        if (spatial != null) {
            SpatialMatrices.model(spatial.position, spatial.yaw).toFloatArray().copyInto(root)
            Matrix.scaleM(root, 0, scale, scale, scale)
            val foot = spatial.metadata.foot
            Matrix.translateM(root, 0, -foot.x, -foot.y, -foot.z)
        } else {
            Matrix.setIdentityM(root, 0)
            Matrix.translateM(root, 0, avatar.foot.x * aspect, 1f - avatar.foot.y, 0f)
            Matrix.rotateM(root, 0, avatar.yaw, 0f, 1f, 0f)
            Matrix.scaleM(root, 0, scale, scale, scale)
            val foot=screenMetadata?.foot
            Matrix.translateM(root, 0, -(foot?.x ?: loaded.centerX), -(foot?.y ?: loaded.minY), -(foot?.z ?: loaded.centerZ))
        }
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

        val desiredWeights = weights(plan.resolvedSpec.action, avatar.expression, avatar.expressionIntensity)
        val executedNames = mutableSetOf<String>()
        for (entity in loaded.asset.renderableEntities) {
            val instance = renderables.getInstance(entity)
            if (instance == 0) continue
            val names = loaded.asset.getMorphTargetNames(entity)
            if (names.isEmpty()) continue
            val weights = FloatArray(names.size) { index -> desiredWeights[names[index]] ?: 0f }
            executedNames += names.toList()
            renderables.setMorphWeights(instance, weights, 0)
            Log.d(LOG_TAG, "表情执行 action=${plan.resolvedSpec.action.id} entity=$entity targets=${names.size} " +
                "expression=${avatar.expression} intensity=${avatar.expressionIntensity} nonZero=${weights.count { it > 0f }}")
        }
        check(executedNames.containsAll(desiredWeights.filterValues { it > 0f }.keys)) {
            appContext.getString(R.string.vlm_photo_expression_binding_failed)
        }
        loaded.asset.instance.animator.updateBoneMatrices()

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
        height: Int,
        softenColors: Boolean
    ): Bitmap {
        renderer.clearOptions = opaqueClear(0.0)
        val blackMatte = readBitmap(e, renderer, view, swapChain, width, height)
        try {
            renderer.clearOptions = opaqueClear(1.0)
            val whiteMatte = readBitmap(e, renderer, view, swapChain, width, height)
            try {
                return restoreTransparency(blackMatte, whiteMatte, softenColors)
            } finally { whiteMatte.recycle() }
        } finally { blackMatte.recycle() }
    }

    private fun opaqueClear(value: Double) = Renderer.ClearOptions().apply {
        clear = true
        clearColor = doubleArrayOf(value, value, value, 1.0)
    }

    /**
     * 黑底像素为 alpha × 前景色，白底像素多出的部分为 (1 - alpha)。
     * 这样即使驱动把 readPixels 的 alpha 强制写成 255，也能恢复抗锯齿边缘的透明度。
     */
    private fun restoreTransparency(black: Bitmap, white: Bitmap, softenColors: Boolean): Bitmap {
        check(black.width == white.width && black.height == white.height) { "avatar_matte_size_mismatch" }
        val size = black.width * black.height
        val blackPixels = IntArray(size)
        val whitePixels = IntArray(size)
        val output = IntArray(size)
        black.getPixels(blackPixels, 0, black.width, 0, 0, black.width, black.height)
        white.getPixels(whitePixels, 0, white.width, 0, 0, white.width, white.height)
        for (index in 0 until size)
            output[index] = AvatarDisplayColor.restorePixel(blackPixels[index], whitePixels[index], softenColors)
        return Bitmap.createBitmap(output, black.width, black.height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val LOG_TAG = "VlmAvatar"
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
