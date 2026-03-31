package com.universaltunnel.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * JNI bridge to Go core library.
 * This class provides Kotlin-friendly interface to the Go tunnel implementation.
 */
class NativeBridge {
    companion object {
        private const val TAG = "NativeBridge"
        
        init {
            try {
                System.loadLibrary("tunnel")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                throw RuntimeException("Failed to load native library", e)
            }
        }
    }

    // Native methods from Go
    private external fun nativeCreateTunnel(): Long
    private external fun nativeStartTunnel(handle: Long, configJson: String): Int
    private external fun nativeStopTunnel(handle: Long): Int
    private external fun nativeSetTunFd(handle: Long, fd: Int)
    private external fun nativeGetState(handle: Long): String
    private external fun nativeGetStats(handle: Long): String
    private external fun nativeGetServerInfo(handle: Long): String
    private external fun nativeTestLatency(handle: Long): Long
    private external fun nativeDestroyTunnel(handle: Long)
    private external fun nativeGetVersion(): String

    private var tunnelHandle: Long = 0

    /**
     * Initialize the tunnel instance
     */
    fun initialize(): Boolean {
        return try {
            tunnelHandle = nativeCreateTunnel()
            tunnelHandle != 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create tunnel", e)
            false
        }
    }

    /**
     * Start tunnel with configuration
     */
    suspend fun start(config: TunnelConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val configJson = config.toJson()
            Log.d(TAG, "Starting tunnel with config: ${configJson.take(200)}...")
            
            val result = nativeStartTunnel(tunnelHandle, configJson)
            if (result == 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to start tunnel: $result"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tunnel", e)
            Result.failure(e)
        }
    }

    /**
     * Stop the tunnel
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val result = nativeStopTunnel(tunnelHandle)
            if (result == 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to stop tunnel: $result"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
            Result.failure(e)
        }
    }

    /**
     * Set Android TUN file descriptor
     */
    fun setTunFd(fd: Int) {
        try {
            nativeSetTunFd(tunnelHandle, fd)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting TUN fd", e)
        }
    }

    /**
     * Get current tunnel state
     */
    fun getState(): TunnelState {
        return try {
            val stateStr = nativeGetState(tunnelHandle)
            TunnelState.fromString(stateStr)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting state", e)
            TunnelState.ERROR
        }
    }

    /**
     * Get current statistics
     */
    fun getStats(): TunnelStats {
        return try {
            val statsJson = nativeGetStats(tunnelHandle)
            TunnelStats.fromJson(JSONObject(statsJson))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting stats", e)
            TunnelStats.empty()
        }
    }

    /**
     * Get current server information
     */
    fun getServerInfo(): ServerInfo {
        return try {
            val infoJson = nativeGetServerInfo(tunnelHandle)
            ServerInfo.fromJson(JSONObject(infoJson))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting server info", e)
            ServerInfo.empty()
        }
    }

    /**
     * Test latency to current server
     */
    suspend fun testLatency(): Long = withContext(Dispatchers.IO) {
        try {
            nativeTestLatency(tunnelHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Error testing latency", e)
            -1L
        }
    }

    /**
     * Get core version
     */
    fun getVersion(): String {
        return try {
            nativeGetVersion()
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Cleanup resources
     */
    fun destroy() {
        try {
            if (tunnelHandle != 0L) {
                nativeDestroyTunnel(tunnelHandle)
                tunnelHandle = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying tunnel", e)
        }
    }

    protected fun finalize() {
        destroy()
    }
}

/**
 * Tunnel states
 */
enum class TunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR;

    companion object {
        fun fromString(str: String): TunnelState {
            return when (str.lowercase()) {
                "disconnected" -> DISCONNECTED
                "connecting" -> CONNECTING
                "connected" -> CONNECTED
                "disconnecting" -> DISCONNECTING
                "error" -> ERROR
                else -> ERROR
            }
        }
    }
}

/**
 * Tunnel statistics data class
 */
data class TunnelStats(
    val uploadTotal: Long,
    val downloadTotal: Long,
    val uploadSpeed: Long,
    val downloadSpeed: Long,
    val connections: Int,
    val lastUpdated: Long
) {
    companion object {
        fun fromJson(json: JSONObject): TunnelStats {
            return TunnelStats(
                uploadTotal = json.optLong("uploadTotal", 0),
                downloadTotal = json.optLong("downloadTotal", 0),
                uploadSpeed = json.optLong("uploadSpeed", 0),
                downloadSpeed = json.optLong("downloadSpeed", 0),
                connections = json.optInt("connections", 0),
                lastUpdated = json.optLong("lastUpdated", 0)
            )
        }

        fun empty(): TunnelStats {
            return TunnelStats(0, 0, 0, 0, 0, 0)
        }
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1_000_000_000 -> "%.2f GB/s".format(bytesPerSecond / 1_000_000_000.0)
            bytesPerSecond >= 1_000_000 -> "%.2f MB/s".format(bytesPerSecond / 1_000_000.0)
            bytesPerSecond >= 1_000 -> "%.2f KB/s".format(bytesPerSecond / 1_000.0)
            else -> "$bytesPerSecond B/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000_000 -> "%.2f TB".format(bytes / 1_000_000_000_000.0)
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

/**
 * Server information data class
 */
data class ServerInfo(
    val name: String,
    val address: String,
    val protocol: String,
    val port: Int,
    val country: String,
    val latency: Long,
    val connected: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): ServerInfo {
            return ServerInfo(
                name = json.optString("name", ""),
                address = json.optString("address", ""),
                protocol = json.optString("protocol", ""),
                port = json.optInt("port", 0),
                country = json.optString("country", ""),
                latency = json.optLong("latency", -1),
                connected = json.optBoolean("connected", false)
            )
        }

        fun empty(): ServerInfo {
            return ServerInfo("", "", "", 0, "", -1, false)
        }
    }
}