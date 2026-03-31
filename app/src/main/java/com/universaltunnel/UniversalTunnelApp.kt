package com.universaltunnel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.universaltunnel.data.ServerRepository

/**
 * Application class for Universal Tunnel.
 * Initializes core components and notification channels.
 */
class UniversalTunnelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize notification channels
        createNotificationChannels()
        
        // Initialize repository
        ServerRepository.getInstance(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // VPN Service channel
            val vpnChannel = NotificationChannel(
                "universal_tunnel_vpn",
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // Proxy Service channel
            val proxyChannel = NotificationChannel(
                "universal_tunnel_proxy",
                "Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Proxy service status"
                setShowBadge(false)
            }

            // Health check alerts
            val alertChannel = NotificationChannel(
                "universal_tunnel_alerts",
                "Connection Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Connection and failover alerts"
            }

            notificationManager.createNotificationChannels(
                listOf(vpnChannel, proxyChannel, alertChannel)
            )
        }
    }
}