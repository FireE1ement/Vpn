package com.universaltunnel.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.universaltunnel.core.*
import com.universaltunnel.data.ServerRepository
import com.universaltunnel.vpn.ProxyService
import com.universaltunnel.vpn.TunnelVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Main ViewModel for Universal Tunnel app.
 * Manages VPN/Proxy state, server selection, and statistics.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context = application.applicationContext
    private val repository = ServerRepository.getInstance(context)
    private val nativeBridge = NativeBridge()
    
    // Connection state
    private val _connectionState = MutableStateFlow<TunnelState>(TunnelState.DISCONNECTED)
    val connectionState: StateFlow<TunnelState> = _connectionState.asStateFlow()
    
    // Current server
    private val _currentServer = MutableStateFlow<ServerConfig?>(null)
    val currentServer: StateFlow<ServerConfig?> = _currentServer.asStateFlow()
    
    // Statistics
    private val _stats = MutableStateFlow<TunnelStats>(TunnelStats.empty())
    val stats: StateFlow<TunnelStats> = _stats.asStateFlow()
    
    // Speed for display (updated every second)
    private val _currentSpeed = MutableStateFlow<Pair<String, String>>("0 B/s" to "0 B/s")
    val currentSpeed: StateFlow<Pair<String, String>> = _currentSpeed.asStateFlow()
    
    // Mode: VPN or Proxy
    private val _mode = MutableStateFlow<ConnectionMode>(ConnectionMode.VPN)
    val mode: StateFlow<ConnectionMode> = _mode.asStateFlow()
    
    // Health check status
    private val _healthStatus = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val healthStatus: StateFlow<Map<String, ServerHealth>> = _healthStatus.asStateFlow()
    
    // Available servers
    val servers: StateFlow<List<ServerConfig>> = repository.servers
    
    // Subscriptions
    val subscriptions: StateFlow<List<com.universaltunnel.data.Subscription>> = repository.subscriptions
    
    // Error messages
    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()
    
    // Auto-failover enabled
    private val _autoFailover = MutableStateFlow(true)
    val autoFailover: StateFlow<Boolean> = _autoFailover.asStateFlow()
    
    // Site monitoring
    private val _siteStatus = MutableStateFlow<List<SiteCheckResult>>(emptyList())
    val siteStatus: StateFlow<List<SiteCheckResult>> = _siteStatus.asStateFlow()
    
    private var connectionJob: Job? = null
    private var statsJob: Job? = null
    private var healthCheckJob: Job? = null
    private var siteMonitorJob: Job? = null
    
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stateStr = intent?.getStringExtra(TunnelVpnService.EXTRA_STATE)
            stateStr?.let {
                val state = TunnelState.valueOf(it)
                _connectionState.value = state
            }
        }
    }
    
    init {
        // Register state receiver
        context.registerReceiver(
            stateReceiver,
            IntentFilter(TunnelVpnService.ACTION_STATE_CHANGE),
            Context.RECEIVER_NOT_EXPORTED
        )
        
        // Initialize native bridge
        nativeBridge.initialize()
        
        // Start collecting stats when connected
        startStatsCollection()
    }
    
    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(stateReceiver)
        stopAllJobs()
        nativeBridge.destroy()
    }
    
    /**
     * Connect to selected server
     */
    fun connect(server: ServerConfig, mode: ConnectionMode = ConnectionMode.VPN) {
        if (_connectionState.value == TunnelState.CONNECTED || 
            _connectionState.value == TunnelState.CONNECTING) {
            return
        }
        
        _currentServer.value = server
        _mode.value = mode
        
        connectionJob = viewModelScope.launch {
            try {
                _connectionState.value = TunnelState.CONNECTING
                
                val config = buildConfig(server)
                
                if (mode == ConnectionMode.VPN) {
                    // Check VPN permission
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        _errorMessage.emit("VPN permission required")
                        _connectionState.value = TunnelState.DISCONNECTED
                        return@launch
                    }
                    
                    // Start VPN service
                    context.startService(
                        Intent(context, TunnelVpnService::class.java).apply {
                            action = TunnelVpnService.ACTION_CONNECT
                            putExtra(TunnelVpnService.EXTRA_CONFIG, config.toJson())
                        }
                    )
                } else {
                    // Start Proxy service
                    context.startService(
                        Intent(context, ProxyService::class.java).apply {
                            action = ProxyService.ACTION_START
                            putExtra(ProxyService.EXTRA_CONFIG, config.toJson())
                        }
                    )
                }
                
                // Wait for connection
                delay(2000)
                
                // Verify connection
                if (TunnelVpnService.isRunning || ProxyService.isRunning) {
                    _connectionState.value = TunnelState.CONNECTED
                    startHealthCheck(server)
                    startSiteMonitoring()
                } else {
                    _connectionState.value = TunnelState.ERROR
                    _errorMessage.emit("Failed to establish connection")
                }
                
            } catch (e: Exception) {
                _connectionState.value = TunnelState.ERROR
                _errorMessage.emit("Connection error: ${e.message}")
            }
        }
    }
    
    /**
     * Disconnect current connection
     */
    fun disconnect() {
        viewModelScope.launch {
            _connectionState.value = TunnelState.DISCONNECTING
            
            if (_mode.value == ConnectionMode.VPN) {
                context.startService(
                    Intent(context, TunnelVpnService::class.java).apply {
                        action = TunnelVpnService.ACTION_DISCONNECT
                    }
                )
            } else {
                context.startService(
                    Intent(context, ProxyService::class.java).apply {
                        action = ProxyService.ACTION_STOP
                    }
                )
            }
            
            stopAllJobs()
            _connectionState.value = TunnelState.DISCONNECTED
            _currentServer.value = null
        }
    }
    
    /**
     * Quick connect to best server
     */
    fun quickConnect() {
        viewModelScope.launch {
            // Test all servers and pick best
            val allServers = repository.servers.value
            if (allServers.isEmpty()) {
                _errorMessage.emit("No servers configured")
                return@launch
            }
            
            _connectionState.value = TunnelState.CONNECTING
            
            // Test latencies
            val latencies = mutableMapOf<String, Long>()
            
            allServers.take(5).forEach { server -> // Test top 5
                val latency = testServerLatency(server)
                if (latency > 0) {
                    latencies[server.tag] = latency
                }
            }
            
            val bestServer = repository.getBestServer(latencies)
            if (bestServer != null) {
                connect(bestServer, _mode.value)
            } else {
                // Fallback to first server
                connect(allServers.first(), _mode.value)
            }
        }
    }
    
    /**
     * Test latency for specific server
     */
    suspend fun testServerLatency(server: ServerConfig): Long = withContext(Dispatchers.IO) {
        // Create temporary config and test
        val testConfig = buildConfig(server)
        
        // Use native test or simple TCP connect
        try {
            val socket = java.net.Socket()
            socket.connect(
                java.net.InetSocketAddress(server.address, server.port),
                5000
            )
            val start = System.currentTimeMillis()
            // Simple handshake or just measure TCP connect
            val latency = System.currentTimeMillis() - start
            socket.close()
            latency
        } catch (e: Exception) {
            -1L
        }
    }
    
    /**
     * Switch to different server (with reconnection)
     */
    fun switchServer(newServer: ServerConfig) {
        if (_connectionState.value == TunnelState.CONNECTED) {
            disconnect()
            viewModelScope.launch {
                delay(1000) // Wait for disconnect
                connect(newServer, _mode.value)
            }
        } else {
            connect(newServer, _mode.value)
        }
    }
    
    /**
     * Toggle auto-failover
     */
    fun setAutoFailover(enabled: Boolean) {
        _autoFailover.value = enabled
        if (enabled && _connectionState.value == TunnelState.CONNECTED) {
            _currentServer.value?.let { startHealthCheck(it) }
        } else {
            healthCheckJob?.cancel()
        }
    }
    
    /**
     * Import subscription from URL
     */
    fun importSubscription(url: String, name: String) {
        viewModelScope.launch {
            try {
                val result = repository.importSubscription(url, name)
                if (result.isFailure) {
                    _errorMessage.emit("Failed to import: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                _errorMessage.emit("Import error: ${e.message}")
            }
        }
    }
    
    /**
     * Update all subscriptions
     */
    fun updateAllSubscriptions() {
        viewModelScope.launch {
            repository.updateAllSubscriptions()
        }
    }
    
    /**
     * Delete subscription
     */
    fun deleteSubscription(id: String) {
        repository.deleteSubscription(id)
    }
    
    /**
     * Add single server manually
     */
    fun addServer(server: ServerConfig) {
        repository.addServer(server)
    }
    
    /**
     * Remove server
     */
    fun removeServer(tag: String) {
        repository.removeServer(tag)
    }
    
    /**
     * Build complete tunnel configuration
     */
    private fun buildConfig(server: ServerConfig): TunnelConfig {
        return TunnelConfig(
            server = server,
            dns = DNSConfig.default(),
            route = RouteConfig.default(),
            tun = TUNConfig.default(),
            experimental = ExperimentalConfig.default()
        )
    }
    
    /**
     * Start collecting statistics
     */
    private fun startStatsCollection() {
        statsJob = viewModelScope.launch {
            while (isActive) {
                if (_connectionState.value == TunnelState.CONNECTED) {
                    val stats = nativeBridge.getStats()
                    _stats.value = stats
                    
                    // Format speed for display
                    val upSpeed = stats.formatSpeed(stats.uploadSpeed)
                    val downSpeed = stats.formatSpeed(stats.downloadSpeed)
                    _currentSpeed.value = upSpeed to downSpeed
                }
                delay(1000)
            }
        }
    }
    
    /**
     * Start health check monitoring
     */
    private fun startHealthCheck(server: ServerConfig) {
        healthCheckJob?.cancel()
        
        healthCheckJob = viewModelScope.launch {
            while (isActive && _connectionState.value == TunnelState.CONNECTED) {
                try {
                    // Check current server health
                    val latency = testServerLatency(server)
                    
                    val health = ServerHealth(
                        serverId = server.tag,
                        serverName = server.name,
                        latency = latency,
                        available = latency > 0,
                        lastCheck = System.currentTimeMillis(),
                        consecutiveFails = if (latency < 0) 1 else 0
                    )
                    
                    _healthStatus.value = mapOf(server.tag to health)
                    
                    // Auto-failover if needed
                    if (latency < 0 && _autoFailover.value) {
                        performFailover()
                    }
                    
                    delay(30000) // Check every 30 seconds
                    
                } catch (e: Exception) {
                    delay(60000)
                }
            }
        }
    }
    
    /**
     * Start site availability monitoring
     */
    private fun startSiteMonitoring() {
        siteMonitorJob?.cancel()
        
        val sites = listOf(
            "https://www.google.com",
            "https://www.youtube.com",
            "https://github.com"
        )
        
        siteMonitorJob = viewModelScope.launch {
            while (isActive && _connectionState.value == TunnelState.CONNECTED) {
                try {
                    val results = mutableListOf<SiteCheckResult>()
                    
                    sites.forEach { url ->
                        val result = checkSite(url)
                        results.add(result)
                    }
                    
                    _siteStatus.value = results
                    
                    // If all sites fail, trigger failover
                    val allFailed = results.all { !it.available }
                    if (allFailed && _autoFailover.value) {
                        performFailover()
                    }
                    
                    delay(60000) // Check every minute
                    
                } catch (e: Exception) {
                    delay(120000)
                }
            }
        }
    }
    
    /**
     * Check single site availability
     */
    private suspend fun checkSite(url: String): SiteCheckResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            
            val code = connection.responseCode
            val latency = System.currentTimeMillis() - start
            
            SiteCheckResult(
                url = url,
                available = code in 200..299,
                latency = latency,
                statusCode = code,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            SiteCheckResult(
                url = url,
                available = false,
                latency = -1,
                error = e.message,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Perform automatic failover to best available server
     */
    private suspend fun performFailover() {
        _errorMessage.emit("Connection issues detected, switching server...")
        
        val allServers = repository.servers.value
        val currentTag = _currentServer.value?.tag
        
        // Find alternative servers
        val alternatives = allServers.filter { it.tag != currentTag }
        
        if (alternatives.isEmpty()) {
            _errorMessage.emit("No alternative servers available")
            return
        }
        
        // Test alternatives
        val bestAlternative = alternatives
            .map { it to testServerLatency(it) }
            .filter { it.second > 0 }
            .minByOrNull { it.second }
            ?.first
        
        if (bestAlternative != null) {
            _errorMessage.emit("Switching to ${bestAlternative.name}")
            switchServer(bestAlternative)
        } else {
            _errorMessage.emit("No healthy servers found")
        }
    }
    
    private fun stopAllJobs() {
        connectionJob?.cancel()
        healthCheckJob?.cancel()
        siteMonitorJob?.cancel()
    }
}

/**
 * Connection mode
 */
enum class ConnectionMode {
    VPN,    // Full system VPN with TUN
    PROXY   // Local SOCKS5/HTTP proxy only
}

/**
 * Server health data
 */
data class ServerHealth(
    val serverId: String,
    val serverName: String,
    val latency: Long,
    val available: Boolean,
    val lastCheck: Long,
    val consecutiveFails: Int = 0
)

/**
 * Site check result
 */
data class SiteCheckResult(
    val url: String,
    val available: Boolean,
    val latency: Long,
    val statusCode: Int = 0,
    val error: String? = null,
    val timestamp: Long
)