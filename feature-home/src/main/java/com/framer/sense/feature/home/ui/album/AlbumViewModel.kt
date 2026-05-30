/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.framer.sense.feature.home.ui.album

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.content.pm.PackageManager

/**
 * 相册页面 ViewModel
 *
 * 负责请求权限并加载系统相册中的照片
 */
@HiltViewModel
class AlbumViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlbumUiState>(AlbumUiState.Loading)
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    /**
     * 检查所需权限，通过回调触发权限请求
     */
    fun checkAndRequestPermission(onRequestPermissions: (List<String>) -> Unit) {
        val permissions = requiredPermissions()
        if (hasAlbumPermission(permissions)) {
            loadAlbumPhotos()
        } else {
            onRequestPermissions(permissions)
        }
    }

    fun onAlbumPermissionDenied() {
        _uiState.value = AlbumUiState.PermissionDenied
    }

    /**
     * 加载相册照片
     */
    fun loadAlbumPhotos() {
        viewModelScope.launch {
            _uiState.value = AlbumUiState.Loading
            try {
                val photos = withContext(Dispatchers.IO) {
                    queryAlbumPhotos()
                }
                _uiState.value = AlbumUiState.Success(photos)
            } catch (e: SecurityException) {
                _uiState.value = AlbumUiState.PermissionDenied
            } catch (e: Exception) {
                _uiState.value = AlbumUiState.Error(e.message ?: "加载相册失败")
            }
        }
    }

    /**
     * 通过 ContentResolver 查询 MediaStore 获取所有图片 URI
     */
    private fun queryAlbumPhotos(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                uris.add(uri)
            }
        }
        return uris
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasAlbumPermission(permissions: List<String>): Boolean =
        permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}

sealed interface AlbumUiState {
    object Loading : AlbumUiState
    data class Success(val photos: List<Uri>) : AlbumUiState
    object PermissionDenied : AlbumUiState
    data class Error(val message: String) : AlbumUiState
}
