package com.framer.sense.feature.camera.vlm.di

import android.content.Context
import android.util.Log
import com.framer.sense.feature.camera.vlm.BuildConfig
import com.framer.sense.feature.camera.vlm.agent.*
import com.framer.sense.feature.camera.vlm.avatar.*
import com.framer.sense.feature.camera.vlm.camera.SnapshotStore
import com.framer.sense.feature.camera.vlm.data.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VlmModule {
    /** 提供模型文件仓库。@param context 应用上下文，避免持有 Activity。 */
    @Provides @Singleton fun modelStore(@ApplicationContext context: Context): LocalModelStore = LocalModelStore(context)
    /** 提供进程共享下载状态。@param context 应用上下文。@param store 私有模型目录仓库。 */
    @Provides @Singleton fun downloads(@ApplicationContext context: Context, store: LocalModelStore): ModelDownloadRepository = ModelDownloadRepository(context, store)
    /** 提供安全设置仓库。@param context 应用上下文。 */
    @Provides @Singleton fun settings(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)
    /** 提供冻结图片存储。@param context 应用上下文。 */
    @Provides @Singleton fun snapshots(@ApplicationContext context: Context): SnapshotStore = SnapshotStore(context)
    /** 提供串行离线模型。@param store 已验证的私有模型包管理器。 */
    @Provides @Singleton fun local(store: LocalModelStore): MnnProvider = MnnProvider(store)
    /** 提供构图业务入口。@param local 本地真实 VLM 适配器。 */
    @Provides @Singleton fun repository(local: MnnProvider): CompositionRepository = CompositionRepository(
        local,
        DirectorAgent(logger = object : DirectorLogger {
            override fun debug(message: String) { Log.d("VlmDirector", message) }
            override fun info(message: String) { Log.i("VlmDirector", message) }
            override fun warn(message: String, error: Throwable?) { Log.w("VlmDirector", message, error) }
            override fun error(message: String, error: Throwable?) { Log.e("VlmDirector", message, error) }
            override fun diagnostic(message: () -> String) {
                if (BuildConfig.DEBUG) {
                    val parts = message().chunked(800)
                    parts.forEachIndexed { index, part -> Log.d("VlmDirector", "解析诊断 part=${index + 1}/${parts.size} $part") }
                }
            }
        })
    )
    /** 提供共享的按需预览服务；GLB 资源和 GPU 工作均由内部专用线程串行管理。 */
    @Provides @Singleton fun previews(@ApplicationContext context: Context): PreviewStore = PreviewStore(FilamentAvatarRenderer(context))
}
