package com.framer.sense.feature.camera.vlm.avatar

import android.content.Context
import android.util.Log
import com.framer.sense.feature.camera.vlm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只读取与已解析动作的资源版本完全匹配的标尺，缺失时保留近似预览。 */
class AvatarScaleStore(context: Context) {
    private val app = context.applicationContext
    private val entries by lazy {
        runCatching { app.assets.open("avatar-scales.json").bufferedReader().use {
            VlmJson.decodeFromString<List<AvatarScaleMetadata>>(it.readText())
        }.filter(AvatarScaleMetadata::valid) }.getOrElse {
            Log.w("VlmArScale", "素材尺度目录不可用", it); emptyList()
        }
    }
    suspend fun find(action: ResolvedPhotoAction): AvatarScaleMetadata? = withContext(Dispatchers.IO) {
        entries.firstOrNull { it.assetPath == action.avatarAssetPath && it.assetVersion == action.avatarAssetVersion }
    }
}
