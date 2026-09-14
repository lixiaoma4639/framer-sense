package com.framer.sense.feature.camera.vlm.data

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import com.framer.sense.feature.camera.vlm.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

/** 用户主动启动的数据下载服务；不在开机广播或后台自动恢复联网。 */
@AndroidEntryPoint
class ModelDownloadService : Service() {
    @Inject lateinit var downloads: ModelDownloadRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        /** 网络能力变化时执行计费约束。@param network 当前网络。@param capabilities 网络属性。 */
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { downloads.networkChanged() }
        /** 网络失去连接时暂停。@param network 已断开的网络。 */
        override fun onLost(network: Network) { downloads.networkChanged() }
    }

    /** 创建通知渠道和网络监听；无参数，不自动开始下载。 */
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.vlm_download_channel), NotificationManager.IMPORTANCE_LOW))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        networkRegistered = true
    }

    /** 处理用户启动请求并立即进入前台。
     * @param intent 源地址与计费网络授权；null 表示系统重建，不自动重试。
     * @param flags 系统启动标志。@param startId 本次服务启动编号。
     * @return 不自动重启；恢复下载需要用户点击。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { downloads.pause(); stopSelf(startId); return START_NOT_STICKY }
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY }
        try {
            val initial = notification(downloads.state.value)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, initial)
            downloads.start(intent.getStringExtra(SOURCE) ?: HubDownloadClient.DEFAULT_SOURCE, intent.getBooleanExtra(METERED, false))
            observer?.cancel()
            observer = scope.launch {
                downloads.state.collectLatest { state ->
                    if (state.running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
                    else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                }
            }
        } catch (_: Exception) { downloads.serviceFailed(getString(R.string.vlm_download_service_failed)); stopSelf(startId) }
        return START_NOT_STICKY
    }

    /** 构建下载通知。@param state 当前进度。@return 不含路径或密钥的通知。 */
    private fun notification(state: ModelDownloadState): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val pause = PendingIntent.getService(this, 1, Intent(this, ModelDownloadService::class.java).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = getString(R.string.vlm_download_title)
        val text = getString(R.string.vlm_download_progress, state.downloaded / 1048576, state.total / 1048576)
        builder.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, if (state.total > 0) ((state.downloaded * 100) / state.total).toInt() else 0, state.total == 0L)
            .addAction(Notification.Action.Builder(null, getString(R.string.vlm_download_pause), pause).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(this, 2, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    /** 系统数据同步时间预算耗尽时暂停并保存断点。@param startId 启动编号。@param fgsType 超时服务类型。 */
    override fun onTimeout(startId: Int, fgsType: Int) { downloads.pause(getString(R.string.vlm_download_timeout)); stopSelf(startId) }
    /** 不提供绑定接口。@param intent 绑定请求。@return null，界面通过仓库订阅状态。 */
    override fun onBind(intent: Intent?): IBinder? = null
    /** 停止服务时暂停仍在运行的传输并释放监听；无参数，不卸载原生模型。 */
    override fun onDestroy() {
        if (downloads.state.value.running) downloads.pause()
        if (networkRegistered) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "vlm-model-download"
        private const val NOTIFICATION = 7301
        private const val SOURCE = "source"
        private const val METERED = "metered"
        private const val PAUSE = "pause"
        /** 从可见页面启动下载。@param context 页面上下文。@param source HTTPS 来源。@param allowMetered 用户确认的计费网络选项。 */
        fun start(context: Context, source: String, allowMetered: Boolean) {
            val intent = Intent(context, ModelDownloadService::class.java).putExtra(SOURCE, source).putExtra(METERED, allowMetered)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
