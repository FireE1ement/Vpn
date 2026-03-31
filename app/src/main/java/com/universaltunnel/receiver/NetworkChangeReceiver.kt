package com.universaltunnel.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.universaltunnel.core.TunnelState
import com.universaltunnel.vpn.TunnelVpnService
import kotlinx.coroutines.*

/**
 * Receiver for network connectivity changes.
 * Handles reconnection when network switches (WiFi <-> Mobile).
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NetworkChangeReceiver"
        private const val RECONNECT_DELAY = 3000L // 3 seconds
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) {
            return
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)

        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        Log.d(TAG, "Network change: connected=$isConnected, validated=$isValidated")

        if (isConnected && isValidated) {
            // Network is available, check if VPN needs restart
            if (TunnelVpnService.isRunning) {
                handleNetworkSwitch(context)
            }
        }
    }

    private fun handleNetworkSwitch(context: Context) {
        scope.launch {
            try {
                Log.i(TAG, "Network switched, scheduling reconnect...")
                
                // Wait a bit for network to stabilize
                delay(RECONNECT_DELAY)
                
                // Send reconnect broadcast to service
                context.sendBroadcast(
                    Intent(ACTION_NETWORK_SWITCHED)
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling network switch", e)
            }
        }
    }

    companion object {
        const val ACTION_NETWORK_SWITCHED = "com.universaltunnel.action.NETWORK_SWITCHED"
    }
}