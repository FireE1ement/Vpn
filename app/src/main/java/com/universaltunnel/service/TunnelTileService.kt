package com.universaltunnel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.universaltunnel.core.TunnelState
import com.universaltunnel.data.ServerRepository
import com.universaltunnel.vpn.TunnelVpnService

/**
 * Quick Settings Tile for Universal Tunnel.
 * Allows quick connect/disconnect from notification shade.
 */
@RequiresApi(Build.VERSION_CODES.N)
class TunnelTileService : TileService() {
    
    companion object {
        private const val TAG = "TunnelTileService"
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTileState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            stateReceiver,
            IntentFilter(TunnelVpnService.ACTION_STATE_CHANGE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        
        val currentState = qsTile?.state ?: Tile.STATE_INACTIVE
        
        when (currentState) {
            Tile.STATE_INACTIVE -> {
                // Connect
                connect()
            }
            Tile.STATE_ACTIVE -> {
                // Disconnect
                disconnect()
            }
            else -> {
                // Connecting state, do nothing
            }
        }
    }

    private fun connect() {
        // Update tile to show connecting
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            label = "Connecting..."
            updateTile()
        }

        // Start service
        val repository = ServerRepository.getInstance(this)
        val servers = repository.servers.value
        
        if (servers.isEmpty()) {
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "No servers"
                updateTile()
            }
            return
        }

        // Use first server or last used
        val server = servers.first()
        
        startService(
            Intent(this, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_CONNECT
                // Config will be loaded from repository
            }
        )
    }

    private fun disconnect() {
        startService(
            Intent(this, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_DISCONNECT
            }
        )
    }

    private fun updateTileState() {
        val isRunning = TunnelVpnService.isRunning
        
        qsTile?.apply {
            if (isRunning) {
                state = Tile.STATE_ACTIVE
                label = "Universal Tunnel"
                contentDescription = "VPN Connected"
                icon = android.graphics.drawable.Icon.createWithResource(
                    this@TunnelTileService,
                    android.R.drawable.ic_lock_idle_lock // Replace with custom icon
                )
            } else {
                state = Tile.STATE_INACTIVE
                label = "Universal Tunnel"
                contentDescription = "VPN Disconnected"
                icon = android.graphics.drawable.Icon.createWithResource(
                    this@TunnelTileService,
                    android.R.drawable.ic_menu_close_clear_cancel // Replace with custom icon
                )
            }
            updateTile()
        }
    }
}