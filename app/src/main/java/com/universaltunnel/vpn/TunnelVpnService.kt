package com.universaltunnel.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.universaltunnel.R
import com.universaltunnel.core.NativeBridge
import com.universaltunnel.core.TunnelConfig
import com.universaltunnel.core.TunnelState
import com.universaltunnel.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * VPN Service implementation for Universal Tunnel.
 * Manages the TUN interface and integrates with native Go core.
 */
class TunnelVpnService : VpnService() {
    companion object {
        private const val TAG = "TunnelVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "universal_tunnel_vpn"
        
        const val ACTION_CONNECT = "com.universaltunnel.action.CONNECT"
        const val ACTION_DISCONNECT = "com.universaltunnel.action.DISCONNECT"
        const val EXTRA_CONFIG = "config_json"
        
        @Volatile
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var nativeBridge: NativeBridge? = null
    private var monitorJob: Job? = null
    private var statsJob: Job? = null
    
    // Statistics callback
    var onStatsUpdate: ((upload: Long, download: Long, uploadSpeed: Long, downloadSpeed: Long) -> Unit)? = null
    var onStateChange: ((state: TunnelState) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson != null) {
                    startVpn(configJson)
                } else {
                    Log.e(TAG, "No config provided for connect action")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                stopSelf()
            }
            else -> {
                // Service restarted or unknown action
                if (!isRunning) {
                    stopSelf()
                }
            }
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed")
        stopVpn()
        serviceScope.cancel()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by user or another app")
        stopVpn()
        stopSelf()
    }

    /**
     * Start VPN with configuration
     */
    private fun startVpn(configJson: String) {
        serviceScope.launch {
            try {
                isRunning = true
                updateState(TunnelState.CONNECTING)
                
                // Parse configuration
                val config = TunnelConfig.fromJson(configJson)
                
                // Initialize native bridge
                nativeBridge = NativeBridge().apply {
                    if (!initialize()) {
                        throw IllegalStateException("Failed to initialize native bridge")
                    }
                }
                
                // Build VPN interface
                val builder = Builder().apply {
                    configureInterface(config)
                }
                
                // Establish VPN interface
                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("Failed to establish VPN interface")
                
                val fd = vpnInterface!!.fd
                Log.i(TAG, "VPN interface established with fd: $fd")
                
                // Pass TUN fd to native core
                nativeBridge?.setTunFd(fd)
                
                // Start tunnel
                val result = nativeBridge?.start(config)
                if (result?.isFailure == true) {
                    throw result.exceptionOrNull() ?: IllegalStateException("Failed to start tunnel")
                }
                
                updateState(TunnelState.CONNECTED)
                
                // Start foreground service with notification
                startForeground(NOTIFICATION_ID, createNotification("Connected", config.server.name))
                
                // Start monitoring
                startMonitoring()
                startStatsCollection()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                updateState(TunnelState.ERROR)
                stopVpn()
                stopSelf()
            }
        }
    }

    /**
     * Stop VPN and cleanup
     */
    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        
        monitorJob?.cancel()
        statsJob?.cancel()
        
        serviceScope.launch {
            try {
                updateState(TunnelState.DISCONNECTING)
                nativeBridge?.stop()
                nativeBridge?.destroy()
                nativeBridge = null
                
                vpnInterface?.close()
                vpnInterface = null
                
                isRunning = false
                updateState(TunnelState.DISCONNECTED)
                
                stopForeground(STOP_FOREGROUND_REMOVE)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during VPN stop", e)
            }
        }
    }

    /**
     * Configure VPN interface builder
     */
    private fun Builder.configureInterface(config: TunnelConfig) {
        // Set session name
        setSession("Universal Tunnel")
        
        // Configure MTU
        setMtu(config.tun.mtu)
        
        // Add addresses
        config.tun.address.forEach { addr ->
            val parts = addr.split("/")
            if (parts.size == 2) {
                addAddress(parts[0], parts[1].toInt())
            }
        }
        
        // Add routes - full tunnel or split tunnel
        if (config.tun.autoRoute) {
            // Full tunnel
            addRoute("0.0.0.0", 0)  // IPv4
            addRoute("::", 0)        // IPv6
        }
        
        // Add DNS servers
        config.dns.servers.forEach { server ->
            // Extract IP from URL if needed
            val dnsAddr = when {
                server.address.startsWith("https://") -> {
                    // Extract from HTTPS URL
                    server.address.removePrefix("https://").removeSuffix("/dns-query")
                }
                server.address.startsWith("http://") -> {
                    server.address.removePrefix("http://").removeSuffix("/dns-query")
                }
                else -> server.address
            }
            
            // Only add if it's an IP address
            if (dnsAddr.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                addDnsServer(dnsAddr)
            }
        }
        
        // Allow bypass (some apps can bypass VPN)
        if (!config.tun.strictRoute) {
            allowBypass()
        }
        
        // Allow family (for IPv6)
        allowFamily(android.system.OsConstants.AF_INET)
        allowFamily(android.system.OsConstants.AF_INET6)
        
        // Set underlying networks (for battery optimization)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setMetered(false)
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
                        Log.w(TAG, "Tunnel state changed: $state")
                        if (state == TunnelState.ERROR || state == TunnelState.DISCONNECTED) {
                            // Attempt reconnect or notify
                            onStateChange?.invoke(state ?: TunnelState.ERROR)
                        }
                    }
                    delay(5000) // Check every 5 seconds
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
                        
                        // Update notification with stats
                        updateNotification(
                            upload = stats.formatSpeed(uploadSpeed),
                            download = stats.formatSpeed(downloadSpeed)
                        )
                    }
                    delay(1000) // Update every second
                } catch (e: Exception) {
                    Log.e(TAG, "Stats collection error", e)
                    delay(5000)
                }
            }
        }
    }

    /**
     * Update tunnel state
     */
    private fun updateState(state: TunnelState) {
        onStateChange?.invoke(state)
        
        // Broadcast state change
        sendBroadcast(Intent(ACTION_STATE_CHANGE).apply {
            putExtra(EXTRA_STATE, state.name)
        })
    }

    /**
     * Create notification channel (Android O+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Universal Tunnel VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create service notification
     */
    private fun createNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val disconnectIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TunnelVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Disconnect", disconnectIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Update notification with current stats
     */
    private fun updateNotification(upload: String, download: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Universal Tunnel")
            .setContentText("↑ $upload  ↓ $download")
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                R.drawable.ic_stop,
                "Disconnect",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, TunnelVpnService::class.java).apply {
                        action = ACTION_DISCONNECT
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_STATE_CHANGE = "com.universaltunnel.action.STATE_CHANGE"
        const val EXTRA_STATE = "state"
    }
}