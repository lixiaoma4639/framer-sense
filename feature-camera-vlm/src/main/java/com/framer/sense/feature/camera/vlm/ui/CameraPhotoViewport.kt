package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints

internal val CameraPhotoDegrees = SemanticsPropertyKey<Int>("CameraPhotoDegrees")

/** 固定区域中的文字可随设备转正，照片抵消父层角度，继续保持拍摄方向。 */
internal class CameraPhotoParentRotation(val target: Int, val current: () -> Float)
internal val LocalCameraPhotoParentRotation = compositionLocalOf<CameraPhotoParentRotation?> { null }

/**
 * 照片及透明人像均已在同一转正坐标中生成，再作为整体映回拍摄时的固定取景坐标。
 * 照片方向不随握持改变；若父面板正在转正则抵消其角度。拖动仍使用照片的 x/y。
 */
@Composable
internal fun CameraPhotoViewport(degrees: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val parent = LocalCameraPhotoParentRotation.current
    val sideways = (degrees - (parent?.target ?: 0)) % 180 != 0
    Layout(modifier = modifier.clipToBounds().semantics { this[CameraPhotoDegrees] = degrees }, content = {
        Box(Modifier.fillMaxSize(), content = content)
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val child = measurables.single().measure(Constraints.fixed(
            if (sideways) height else width, if (sideways) width else height))
        layout(width, height) {
            child.placeWithLayer((width - child.width) / 2, (height - child.height) / 2) {
                rotationZ = degrees - (parent?.current?.invoke() ?: 0f)
                transformOrigin = TransformOrigin.Center
            }
        }
    }
}
