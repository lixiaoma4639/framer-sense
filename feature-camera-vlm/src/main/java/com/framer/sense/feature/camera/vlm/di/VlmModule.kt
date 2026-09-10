package com.framer.sense.feature.camera.vlm.di

import android.content.Context
import com.framer.sense.feature.camera.vlm.agent.CompositionRepository
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
    /** 提供安全设置仓库。@param context 应用上下文。 */
    @Provides @Singleton fun settings(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)
    /** 提供冻结图片存储。@param context 应用上下文。 */
    @Provides @Singleton fun snapshots(@ApplicationContext context: Context): SnapshotStore = SnapshotStore(context)
    /** 提供串行离线模型。@param store 已验证的私有模型包管理器。 */
    @Provides @Singleton fun local(store: LocalModelStore): MnnProvider = MnnProvider(store)
    /** 提供构图业务入口。@param local 本地真实 VLM 适配器。 */
    @Provides @Singleton fun repository(local: MnnProvider): CompositionRepository = CompositionRepository(local)
    /** 提供共享的按需预览服务；无参数，内部 GPU 线程串行执行。 */
    @Provides @Singleton fun previews(): PreviewStore = PreviewStore(FilamentAvatarRenderer())
}
