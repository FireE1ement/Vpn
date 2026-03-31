package com.universaltunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.universaltunnel.R
import com.universaltunnel.core.NativeBridge
import com.universaltunnel.core.TunnelConfig
import com.universaltunnel.core.TunnelState
import com.universaltunnel.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Proxy Service for non-VPN mode.
 * Runs local SOCKS5/HTTP proxy without system VPN.
 */
class ProxyService : Service() {
    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "universal_tunnel_proxy"
        
        const val ACTION_START = "com.universaltunnel.action.START_PROXY"
        const val ACTION_STOP = "com.universaltunnel.action.STOP_PROXY"
        const val EXTRA_CONFIG = "config_json"
        
        @Volatile
        var isRunning = false
            private set
        
        // Proxy ports
        const val SOCKS5_PORT = 10808
        const val HTTP_PORT = 10809
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nativeBridge: NativeBridge? = null
    private var monitorJob: Job? = null
    private var statsJob: Job? = null
    
    var onStatsUpdate: ((upload: Long, download: Long, uploadSpeed: Long, downloadSpeed: Long) -> Unit)? = null
    var onStateChange: ((state: TunnelState) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Proxy Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson != null) {
                    startProxy(configJson)
                } else {
                    Log.e(TAG, "No config provided")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
            }
            else -> {
                if (!isRunning) stopSelf()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Proxy Service destroyed")
        stopProxy()
        serviceScope.cancel()
    }

    /**
     * Start proxy service
     */
    private fun startProxy(configJson: String) {
        serviceScope.launch {
            try {
                isRunning = true
                updateState(TunnelState.CONNECTING)
                
                val config = TunnelConfig.fromJson(configJson)
                
                // Modify config for proxy mode (no TUN)
                val proxyConfig = config.copy(
                    tun = config.tun.copy(
                        address = emptyList() // No TUN in proxy mode
                    )
                )
                
                // Initialize native bridge
                nativeBridge = NativeBridge().apply {
                    if (!initialize()) {
                        throw IllegalStateException("Failed to initialize native bridge")
                    }
                }
                
                // Don't set TUN fd - core will use SOCKS/HTTP inbounds
                nativeBridge?.setTunFd(-1)
                
                // Start tunnel (creates SOCKS5 on 10808 and HTTP on 10809)
                val result = nativeBridge?.start(proxyConfig)
                if (result?.isFailure == true) {
                    throw result.exceptionOrNull() ?: IllegalStateException("Failed to start proxy")
                }
                
                updateState(TunnelState.CONNECTED)
                
                startForeground(NOTIFICATION_ID, createNotification("Proxy Active", 
                    "SOCKS5: 127.0.0.1:$SOCKS5_PORT"))
                
                startMonitoring()
                startStatsCollection()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                updateState(TunnelState.ERROR)
                stopProxy()
                stopSelf()
            }
        }
    }

    /**
     * Stop proxy service
     */
    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy")
        
        monitorJob?.cancel()
        statsJob?.cancel()
        
        serviceScope.launch {
            try {
                updateState(TunnelState.DISCONNECTING)
                nativeBridge?.stop()
                nativeBridge?.destroy()
                nativeBridge = null
                
                isRunning = false
                updateState(TunnelState.DISCONNECTED)
                
                stopForeground(STOP_FOREGROUND_REMOVE)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping proxy", e)
            }
        }
    }

    /**
     * Start connection monitoring
     */
    private fun startMonitoring() {
        monitorJob = serviceScope.launch {
            while (isActive && isRunning) {
                try {
                    val state = nativeBridge?.getState()
                    if (state != TunnelState.CONNECTED) {
                        Log.w(TAG, "Proxy state changed: $state")
                        onStateChange?.invoke(state ?: TunnelState.ERROR)
                    }
                    delay(5000)
                } catch (e: Exception) {
                    Log.e(TAG, "Monitoring error", e)
                    delay(10000)
                }
            }
        }
    }

    /**
     * Start statistics collection
     */
    private fun startStatsCollection() {
        statsJob = serviceScope.launch {
            var lastUpload = 0L
            var lastDownload = 0L
            
            while (isActive && isRunning) {
                try {
                    val stats = nativeBridge?.getStats()
                    if (stats != null) {
                        val uploadSpeed = stats.uploadTotal - lastUpload
                        val downloadSpeed = stats.downloadTotal - lastDownload
                        lastUpload = stats.uploadTotal
                        lastDownload = stats.downloadTotal
                        
                        onStatsUpdate?.invoke(
                            stats.uploadTotal,
                            stats.downloadTotal,
                            uploadSpeed,
                            downloadSpeed
                        )
                        
                        updateNotification(
                            up = stats.formatSpeed(uploadSpeed),
                            down = stats.formatSpeed(downloadSpeed)
                        )
                    }
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Stats error", e)
                    delay(5000)
                }
            }
        }
    }

    private fun updateState(state: TunnelState) {
        onStateChange?.invoke(state)
        sendBroadcast(Intent(TunnelVpnService.ACTION_STATE_CHANGE).apply {
            putExtra(TunnelVpnService.EXTRA_STATE, state.name)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Universal Tunnel Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Proxy service status"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(up: String, down: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Universal Tunnel Proxy")
            .setContentText("↑ $up  ↓ $down")
            .setSmallIcon(R.drawable.ic_proxy)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, 
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
            .addAction(R.drawable.ic_stop, "Stop",
                PendingIntent.getService(this, 0,
                    Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
                    PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
        
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}