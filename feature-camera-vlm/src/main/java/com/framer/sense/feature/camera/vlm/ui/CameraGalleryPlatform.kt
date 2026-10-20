package com.framer.sense.feature.camera.vlm.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.MediaStore

/** 显式指定已解析的相册 Activity，避免隐式 Intent 进入应用选择器。 */
internal fun cameraGalleryListIntent(context: Context): Intent? {
    val manager = context.packageManager
    val probes = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
        Intent(Intent.ACTION_VIEW).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "vnd.android.cursor.dir/image")
    )
    for (probe in probes) {
        val candidates = manager.queryIntentActivities(probe, 0).filter { result ->
            val permission = result.activityInfo?.permission
            permission.isNullOrEmpty() || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        val target = directGalleryActivity(candidates, manager.resolveActivity(probe, 0)) ?: continue
        return directGalleryIntent(probe, target)
    }
    return null
}

/** APP_GALLERY 仅用于查询，实际打开相册首页使用标准 MAIN／LAUNCHER。 */
internal fun directGalleryIntent(probe: Intent, target: ComponentName): Intent =
    (if (probe.action == Intent.ACTION_MAIN) Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        else Intent(probe)).setComponent(target)

/** 默认处理程序必须确实在候选中，不能误选系统 ResolverActivity。 */
internal fun directGalleryActivity(candidates: List<ResolveInfo>, preferred: ResolveInfo?): ComponentName? {
    val available = candidates.filter { result ->
        result.activityInfo?.let { it.enabled && it.exported && it.applicationInfo?.enabled != false } == true
    }
    fun component(info: ResolveInfo) = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    val preferredComponent = preferred?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    available.firstOrNull { component(it) == preferredComponent }?.let { return component(it) }
    return available.sortedWith(compareByDescending<ResolveInfo> {
        (it.activityInfo.applicationInfo?.flags ?: 0) and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.thenByDescending { it.priority }.thenByDescending { it.preferredOrder }
        .thenBy { it.activityInfo.packageName }.thenBy { it.activityInfo.name })
        .firstOrNull()?.let(::component)
}
