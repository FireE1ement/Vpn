package com.universaltunnel.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.universaltunnel.data.ServerRepository
import com.universaltunnel.vpn.TunnelVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receiver for boot completed event.
 * Auto-starts VPN if it was active before reboot.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "boot_prefs"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_LAST_SERVER = "last_server"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Boot completed received")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if auto-start is enabled
        if (!prefs.getBoolean(KEY_AUTO_START, false)) {
            Log.d(TAG, "Auto-start disabled")
            return
        }

        val lastServerTag = prefs.getString(KEY_LAST_SERVER, null)
        if (lastServerTag == null) {
            Log.d(TAG, "No last server found")
            return
        }

        // Get server from repository and start
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = ServerRepository.getInstance(context)
                val server = repository.getServer(lastServerTag)
                
                if (server != null) {
                    Log.i(TAG, "Auto-starting VPN with server: ${server.name}")
                    
                    // Start VPN service
                    val serviceIntent = Intent(context, TunnelVpnService::class.java).apply {
                        action = TunnelVpnService.ACTION_CONNECT
                        // Server config will be loaded from repository in service
                    }
                    context.startForegroundService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start VPN", e)
            }
        }
    }

    companion object {
        /**
         * Enable/disable auto-start
         */
        fun setAutoStart(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_START, enabled)
                .apply()
        }

        /**
         * Save last connected server for auto-start
         */
        fun saveLastServer(context: Context, serverTag: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SERVER, serverTag)
                .apply()
        }

        /**
         * Check if auto-start is enabled
         */
        fun isAutoStartEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false)
        }
    }
}