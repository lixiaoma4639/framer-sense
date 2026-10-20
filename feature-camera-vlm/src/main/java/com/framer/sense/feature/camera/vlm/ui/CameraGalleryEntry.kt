package com.framer.sense.feature.camera.vlm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framer.sense.feature.camera.vlm.R

@Composable
internal fun CameraGalleryEntry(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.vlm_open_gallery)
    Box(modifier.size(52.dp).testTag("vlm_gallery_entry")
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Icon(Icons.Default.PhotoLibrary, null,
            tint = Color.White.copy(alpha = if (enabled) 1f else .4f),
            modifier = Modifier.offset(x = 2.dp).size(40.dp).testTag("vlm_gallery_icon").cameraIconRotation())
    }
}
