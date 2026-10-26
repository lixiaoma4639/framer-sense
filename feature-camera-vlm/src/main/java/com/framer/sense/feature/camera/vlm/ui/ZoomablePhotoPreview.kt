package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min

internal val PhotoPreviewScale = SemanticsPropertyKey<Float>("PhotoPreviewScale")
internal val PhotoPreviewOffsetX = SemanticsPropertyKey<Float>("PhotoPreviewOffsetX")
internal val PhotoPreviewOffsetY = SemanticsPropertyKey<Float>("PhotoPreviewOffsetY")

internal data class PhotoPreviewTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun updated(pan: Offset, zoom: Float, centroid: Offset, viewport: IntSize, aspectRatio: Float): PhotoPreviewTransform {
        if (viewport.width <= 0 || viewport.height <= 0 || !zoom.isFinite() || zoom <= 0f ||
            !pan.x.isFinite() || !pan.y.isFinite()) return this
        val nextScale = (scale * zoom).coerceIn(1f, 5f)
        if (nextScale == 1f) return PhotoPreviewTransform()
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val anchor = if (centroid.x.isFinite() && centroid.y.isFinite()) centroid - center else Offset.Zero
        val ratio = nextScale / scale
        val nextOffset = offset * ratio + anchor * (1f - ratio) + pan
        val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (viewport.width.toFloat() / viewport.height)
        val fittedWidth = min(viewport.width.toFloat(), viewport.height * aspect)
        val fittedHeight = fittedWidth / aspect
        val maxX = ((fittedWidth * nextScale - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((fittedHeight * nextScale - viewport.height) / 2).coerceAtLeast(0f)
        return PhotoPreviewTransform(nextScale, Offset(nextOffset.x.coerceIn(-maxX, maxX), nextOffset.y.coerceIn(-maxY, maxY)))
    }
}

/** 大图内的查看手势，仅为临时 UI 状态；回调只保留原倍率下的离线人像移动。 */
@Composable
internal fun ZoomablePhotoPreview(
    imageKey: Any,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    onUnzoomedDrag: ((Offset) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember(imageKey, viewport) { mutableStateOf(PhotoPreviewTransform()) }
    val currentDrag by rememberUpdatedState(onUnzoomedDrag)
    Box(modifier.clipToBounds().onSizeChanged { viewport = it }
        .semantics {
            this[PhotoPreviewScale] = transform.scale
            this[PhotoPreviewOffsetX] = transform.offset.x
            this[PhotoPreviewOffsetY] = transform.offset.y
        }
        .pointerInput(imageKey, viewport, aspectRatio) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var pastSlop = false
                var hadMultiple = false
                var totalZoom = 1f
                var totalPan = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break
                    val fingers = event.changes.count { it.pressed }
                    if (fingers > 1) hadMultiple = true
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (!pastSlop) {
                        totalZoom *= zoom
                        totalPan += pan
                        val zoomMotion = abs(1f - totalZoom) * event.calculateCentroidSize(useCurrent = false)
                        pastSlop = zoomMotion > viewConfiguration.touchSlop || totalPan.getDistance() > viewConfiguration.touchSlop
                    }
                    if (pastSlop) {
                        if (!hadMultiple && transform.scale == 1f && fingers == 1 && currentDrag != null) {
                            val fittedWidth = min(viewport.width.toFloat(), viewport.height * aspectRatio)
                            val fittedHeight = fittedWidth / aspectRatio
                            if (fittedWidth > 0f && fittedHeight > 0f) currentDrag?.invoke(Offset(pan.x / fittedWidth, pan.y / fittedHeight))
                        } else {
                            transform = transform.updated(pan, zoom, event.calculateCentroid(useCurrent = false), viewport, aspectRatio)
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.offset.x
            translationY = transform.offset.y
        }, content = content)
    }
}
