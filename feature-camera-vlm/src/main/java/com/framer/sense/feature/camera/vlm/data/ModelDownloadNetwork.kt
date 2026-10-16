package com.framer.sense.feature.camera.vlm.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Wi-Fi 默认允许；移动网络（含免费流量）每次开始/续传都需用户确认。 */
object ModelDownloadNetwork {
    fun requiresConfirmation(wifi: Boolean, cellular: Boolean, metered: Boolean): Boolean =
        cellular || (!wifi && metered)

    fun requiresConfirmation(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        return requiresConfirmation(capabilities, connectivity.isActiveNetworkMetered)
    }

    fun requiresConfirmation(capabilities: NetworkCapabilities, metered: Boolean): Boolean = requiresConfirmation(
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), metered)
}
