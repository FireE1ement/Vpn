package com.universaltunnel.data

import android.content.Context
import android.content.SharedPreferences
import com.universaltunnel.core.ServerConfig
import com.universaltunnel.core.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/**
 * Repository for managing VPN/proxy servers and subscriptions
 */
class ServerRepository private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "servers", Context.MODE_PRIVATE
    )
    private val parser = SubscriptionParser()
    
    // In-memory cache
    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers
    
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions
    
    init {
        loadFromStorage()
    }
    
    companion object {
        @Volatile
        private var instance: ServerRepository? = null
        
        fun getInstance(context: Context): ServerRepository {
            return instance ?: synchronized(this) {
                instance ?: ServerRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    /**
     * Add single server
     */
    fun addServer(server: ServerConfig) {
        val current = _servers.value.toMutableList()
        // Check for duplicates
        if (current.none { it.address == server.address && it.port == server.port }) {
            current.add(server.copy(tag = generateTag(server)))
            _servers.value = current
            saveToStorage()
        }
    }
    
    /**
     * Add multiple servers
     */
    fun addServers(servers: List<ServerConfig>) {
        val current = _servers.value.toMutableList()
        servers.forEach { server ->
            if (current.none { it.address == server.address && it.port == server.port }) {
                current.add(server.copy(tag = generateTag(server)))
            }
        }
        _servers.value = current
        saveToStorage()
    }
    
    /**
     * Remove server
     */
    fun removeServer(tag: String) {
        _servers.value = _servers.value.filter { it.tag != tag }
        saveToStorage()
    }
    
    /**
     * Update server
     */
    fun updateServer(tag: String, server: ServerConfig) {
        _servers.value = _servers.value.map { 
            if (it.tag == tag) server else it 
        }
        saveToStorage()
    }
    
    /**
     * Get server by tag
     */
    fun getServer(tag: String): ServerConfig? {
        return _servers.value.find { it.tag == tag }
    }
    
    /**
     * Get best server by latency (from health check)
     */
    fun getBestServer(latencies: Map<String, Long>): ServerConfig? {
        return _servers.value
            .filter { latencies[it.tag] != null && latencies[it.tag]!! > 0 }
            .minByOrNull { latencies[it.tag] ?: Long.MAX_VALUE }
    }
    
    /**
     * Import from subscription URL
     */
    suspend fun importSubscription(url: String, name: String): Result<Subscription> = withContext(Dispatchers.IO) {
        try {
            // Fetch subscription
            val content = fetchSubscription(url)
            
            // Parse
            val result = parser.parse(content, url)
            
            // Add servers
            val servers = result.servers.map { 
                it.copy(group = name, tag = generateTag(it)) 
            }
            addServers(servers)
            
            // Create subscription record
            val sub = Subscription(
                id = UUID.randomUUID().toString(),
                name = name,
                url = url,
                format = result.format,
                serverCount = servers.size,
                lastUpdated = System.currentTimeMillis()
            )
            
            // Add to subscriptions list
            val currentSubs = _subscriptions.value.toMutableList()
            currentSubs.removeAll { it.url == url } // Remove existing
            currentSubs.add(sub)
            _subscriptions.value = currentSubs
            saveSubscriptions()
            
            Result.success(sub)
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Update all subscriptions
     */
    suspend fun updateAllSubscriptions(): Map<String, Result<Subscription>> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Result<Subscription>>()
        
        _subscriptions.value.forEach { sub ->
            results[sub.id] = importSubscription(sub.url, sub.name)
        }
        
        results
    }
    
    /**
     * Delete subscription and its servers
     */
    fun deleteSubscription(id: String) {
        val sub = _subscriptions.value.find { it.id == id } ?: return
        
        // Remove associated servers
        _servers.value = _servers.value.filter { it.group != sub.name }
        
        // Remove subscription
        _subscriptions.value = _subscriptions.value.filter { it.id != id }
        
        saveToStorage()
        saveSubscriptions()
    }
    
    /**
     * Generate unique tag for server
     */
    private fun generateTag(server: ServerConfig): String {
        val base = "${server.protocol}-${server.address}-${server.port}"
        val existing = _servers.value.count { it.tag.startsWith(base) }
        return if (existing == 0) base else "$base-$existing"
    }
    
    /**
     * Fetch subscription content from URL
     */
    private fun fetchSubscription(url: String): String {
        val connection = URL(url).openConnection()
        connection.setRequestProperty("User-Agent", "UniversalTunnel/1.0")
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        
        return connection.getInputStream().use { stream ->
            stream.bufferedReader().readText()
        }
    }
    
    /**
     * Load from SharedPreferences
     */
    private fun loadFromStorage() {
        // Load servers
        val serversJson = prefs.getString("servers_json", "[]")
        val serversArray = JSONArray(serversJson)
        val servers = mutableListOf<ServerConfig>()
        
        for (i in 0 until serversArray.length()) {
            val obj = serversArray.getJSONObject(i)
            servers.add(parseServerConfig(obj))
        }
        _servers.value = servers
        
        // Load subscriptions
        val subsJson = prefs.getString("subscriptions_json", "[]")
        val subsArray = JSONArray(subsJson)
        val subs = mutableListOf<Subscription>()
        
        for (i in 0 until subsArray.length()) {
            val obj = subsArray.getJSONObject(i)
            subs.add(Subscription(
                id = obj.getString("id"),
                name = obj.getString("name"),
                url = obj.getString("url"),
                format = obj.optString("format", "unknown"),
                serverCount = obj.optInt("serverCount", 0),
                lastUpdated = obj.optLong("lastUpdated", 0)
            ))
        }
        _subscriptions.value = subs
    }
    
    /**
     * Save to SharedPreferences
     */
    private fun saveToStorage() {
        val array = JSONArray()
        _servers.value.forEach { server ->
            array.put(serializeServerConfig(server))
        }
        prefs.edit().putString("servers_json", array.toString()).apply()
    }
    
    private fun saveSubscriptions() {
        val array = JSONArray()
        _subscriptions.value.forEach { sub ->
            array.put(JSONObject().apply {
                put("id", sub.id)
                put("name", sub.name)
                put("url", sub.url)
                put("format", sub.format)
                put("serverCount", sub.serverCount)
                put("lastUpdated", sub.lastUpdated)
            })
        }
        prefs.edit().putString("subscriptions_json", array.toString()).apply()
    }
    
    private fun parseServerConfig(obj: JSONObject): ServerConfig {
        return ServerConfig(
            protocol = obj.getString("protocol"),
            address = obj.getString("address"),
            port = obj.getInt("port"),
            uuid = obj.optString("uuid", ""),
            password = obj.optString("password", ""),
            method = obj.optString("method", ""),
            tls = obj.optBoolean("tls", false),
            sni = obj.optString("sni", ""),
            transport = obj.optString("transport", ""),
            path = obj.optString("path", ""),
            host = obj.optString("host", ""),
            name = obj.optString("name", ""),
            group = obj.optString("group", ""),
            tag = obj.getString("tag")
        )
    }
    
    private fun serializeServerConfig(server: ServerConfig): JSONObject {
        return JSONObject().apply {
            put("protocol", server.protocol)
            put("address", server.address)
            put("port", server.port)
            put("uuid", server.uuid)
            put("password", server.password)
            put("method", server.method)
            put("tls", server.tls)
            put("sni", server.sni)
            put("transport", server.transport)
            put("path", server.path)
            put("host", server.host)
            put("name", server.name)
            put("group", server.group)
            put("tag", server.tag)
        }
    }
}

/**
 * Subscription data class
 */
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val format: String,
    val serverCount: Int,
    val lastUpdated: Long
)