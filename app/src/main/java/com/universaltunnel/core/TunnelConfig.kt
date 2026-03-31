package com.universaltunnel.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration for VPN tunnel
 */
data class TunnelConfig(
    val server: ServerConfig,
    val dns: DNSConfig = DNSConfig.default(),
    val route: RouteConfig = RouteConfig.default(),
    val tun: TUNConfig = TUNConfig.default(),
    val experimental: ExperimentalConfig = ExperimentalConfig.default()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("server", server.toJson())
            put("dns", dns.toJson())
            put("route", route.toJson())
            put("tun", tun.toJson())
            put("experimental", experimental.toJson())
        }.toString()
    }

    companion object {
        fun fromJson(json: String): TunnelConfig {
            val obj = JSONObject(json)
            return TunnelConfig(
                server = ServerConfig.fromJson(obj.getJSONObject("server")),
                dns = DNSConfig.fromJson(obj.optJSONObject("dns")),
                route = RouteConfig.fromJson(obj.optJSONObject("route")),
                tun = TUNConfig.fromJson(obj.optJSONObject("tun")),
                experimental = ExperimentalConfig.fromJson(obj.optJSONObject("experimental"))
            )
        }
    }
}

/**
 * Server configuration
 */
data class ServerConfig(
    val protocol: String,  // vmess, vless, trojan, shadowsocks, hysteria2, tuic, wireguard
    val address: String,
    val port: Int,
    val uuid: String = "",
    val password: String = "",
    val method: String = "", // encryption method for SS
    val tls: Boolean = false,
    val alpn: String = "",
    val sni: String = "",
    val allowInsecure: Boolean = false,
    val realityEnabled: Boolean = false,
    val realityPublicKey: String = "",
    val realityShortID: String = "",
    val realitySpiderX: String = "",
    val transport: String = "", // ws, grpc, httpupgrade
    val path: String = "",
    val host: String = "",
    val headers: Map<String, String> = emptyMap(),
    val obfsType: String = "",
    val obfsPassword: String = "",
    val congestionControl: String = "",
    val localAddress: List<String> = emptyList(), // for WireGuard
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val preSharedKey: String = "",
    val reserved: List<Int> = emptyList(),
    val warpEnabled: Boolean = false,
    val name: String = "",
    val group: String = "",
    val tag: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("protocol", protocol)
            put("address", address)
            put("port", port)
            if (uuid.isNotEmpty()) put("uuid", uuid)
            if (password.isNotEmpty()) put("password", password)
            if (method.isNotEmpty()) put("method", method)
            put("tls", tls)
            if (alpn.isNotEmpty()) put("alpn", alpn)
            if (sni.isNotEmpty()) put("sni", sni)
            put("allowInsecure", allowInsecure)
            put("realityEnabled", realityEnabled)
            if (realityPublicKey.isNotEmpty()) put("realityPublicKey", realityPublicKey)
            if (realityShortID.isNotEmpty()) put("realityShortID", realityShortID)
            if (realitySpiderX.isNotEmpty()) put("realitySpiderX", realitySpiderX)
            if (transport.isNotEmpty()) put("transport", transport)
            if (path.isNotEmpty()) put("path", path)
            if (host.isNotEmpty()) put("host", host)
            if (headers.isNotEmpty()) {
                put("headers", JSONObject(headers))
            }
            if (obfsType.isNotEmpty()) put("obfsType", obfsType)
            if (obfsPassword.isNotEmpty()) put("obfsPassword", obfsPassword)
            if (congestionControl.isNotEmpty()) put("congestionControl", congestionControl)
            if (localAddress.isNotEmpty()) put("localAddress", JSONArray(localAddress))
            if (privateKey.isNotEmpty()) put("privateKey", privateKey)
            if (peerPublicKey.isNotEmpty()) put("peerPublicKey", peerPublicKey)
            if (preSharedKey.isNotEmpty()) put("preSharedKey", preSharedKey)
            if (reserved.isNotEmpty()) put("reserved", JSONArray(reserved))
            put("warpEnabled", warpEnabled)
            if (name.isNotEmpty()) put("name", name)
            if (group.isNotEmpty()) put("group", group)
            if (tag.isNotEmpty()) put("tag", tag) else put("tag", "$protocol-$address:$port")
        }
    }

    companion object {
        fun fromJson(json: JSONObject?): ServerConfig {
            if (json == null) return empty()
            return ServerConfig(
                protocol = json.optString("protocol", "vmess"),
                address = json.optString("address", ""),
                port = json.optInt("port", 443),
                uuid = json.optString("uuid", ""),
                password = json.optString("password", ""),
                method = json.optString("method", ""),
                tls = json.optBoolean("tls", false),
                alpn = json.optString("alpn", ""),
                sni = json.optString("sni", ""),
                allowInsecure = json.optBoolean("allowInsecure", false),
                realityEnabled = json.optBoolean("realityEnabled", false),
                realityPublicKey = json.optString("realityPublicKey", ""),
                realityShortID = json.optString("realityShortID", ""),
                realitySpiderX = json.optString("realitySpiderX", ""),
                transport = json.optString("transport", ""),
                path = json.optString("path", ""),
                host = json.optString("host", ""),
                obfsType = json.optString("obfsType", ""),
                obfsPassword = json.optString("obfsPassword", ""),
                congestionControl = json.optString("congestionControl", ""),
                name = json.optString("name", ""),
                group = json.optString("group", ""),
                tag = json.optString("tag", "")
            )
        }

        fun empty(): ServerConfig {
            return ServerConfig("vmess", "", 443)
        }
    }
}

/**
 * DNS configuration
 */
data class DNSConfig(
    val servers: List<DNSServer>,
    val rules: List<DNSRule>,
    val final: String,
    val strategy: String,
    val disableCache: Boolean,
    val disableExpire: Boolean,
    val fakeip: Boolean,
    val fakeipRange: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                servers.forEach { put(it.toJson()) }
            })
            put("rules", JSONArray().apply {
                rules.forEach { put(it.toJson()) }
            })
            put("final", final)
            put("strategy", strategy)
            put("disableCache", disableCache)
            put("disableExpire", disableExpire)
            put("fakeip", fakeip)
            put("fakeipRange", fakeipRange)
        }
    }

    companion object {
        fun default(): DNSConfig {
            return DNSConfig(
                servers = listOf(
                    DNSServer("remote", "https://1.1.1.1/dns-query", "", ""),
                    DNSServer("local", "223.5.5.5", "", "prefer_ipv4")
                ),
                rules = listOf(
                    DNSRule("geosite", emptyList(), emptyList(), listOf("cn"), emptyList(), "local", false),
                    DNSRule("geoip", emptyList(), emptyList(), emptyList(), listOf("cn"), "local", false)
                ),
                final = "remote",
                strategy = "prefer_ipv4",
                disableCache = false,
                disableExpire = false,
                fakeip = false,
                fakeipRange = "198.18.0.0/15"
            )
        }

        fun fromJson(json: JSONObject?): DNSConfig {
            if (json == null) return default()
            // Parse implementation...
            return default()
        }
    }
}

data class DNSServer(
    val tag: String,
    val address: String,
    val addressResolver: String,
    val strategy: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("tag", tag)
            put("address", address)
            if (addressResolver.isNotEmpty()) put("addressResolver", addressResolver)
            if (strategy.isNotEmpty()) put("strategy", strategy)
        }
    }
}

data class DNSRule(
    val type: String,
    val domain: List<String>,
    val domainSuffix: List<String>,
    val geoSite: List<String>,
    val geoIP: List<String>,
    val server: String,
    val disableCache: Boolean
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("type", type)
            if (domain.isNotEmpty()) put("domain", JSONArray(domain))
            if (domainSuffix.isNotEmpty()) put("domainSuffix", JSONArray(domainSuffix))
            if (geoSite.isNotEmpty()) put("geosite", JSONArray(geoSite))
            if (geoIP.isNotEmpty()) put("geoip", JSONArray(geoIP))
            put("server", server)
            put("disableCache", disableCache)
        }
    }
}

/**
 * Route configuration
 */
data class RouteConfig(
    val rules: List<RouteRule>,
    val final: String,
    val autoDetectInterface: Boolean,
    val overrideAndroidVpn: Boolean
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("rules", JSONArray().apply {
                rules.forEach { put(it.toJson()) }
            })
            put("final", final)
            put("autoDetectInterface", autoDetectInterface)
            put("overrideAndroidVpn", overrideAndroidVpn)
        }
    }

    companion object {
        fun default(): RouteConfig {
            return RouteConfig(
                rules = listOf(
                    RouteRule("geosite", emptyList(), emptyList(), emptyList(), emptyList(), listOf("cn"), emptyList(), "direct"),
                    RouteRule("geoip", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf("cn", "private"), "direct")
                ),
                final = "proxy",
                autoDetectInterface = true,
                overrideAndroidVpn = false
            )
        }

        fun fromJson(json: JSONObject?): RouteConfig {
            if (json == null) return default()
            return default()
        }
    }
}

data class RouteRule(
    val type: String,
    val domain: List<String>,
    val domainSuffix: List<String>,
    val domainKeyword: List<String>,
    val domainRegex: List<String>,
    val geoSite: List<String>,
    val geoIP: List<String>,
    val processName: List<String>,
    val outbound: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("type", type)
            if (domain.isNotEmpty()) put("domain", JSONArray(domain))
            if (domainSuffix.isNotEmpty()) put("domainSuffix", JSONArray(domainSuffix))
            if (domainKeyword.isNotEmpty()) put("domainKeyword", JSONArray(domainKeyword))
            if (domainRegex.isNotEmpty()) put("domainRegex", JSONArray(domainRegex))
            if (geoSite.isNotEmpty()) put("geosite", JSONArray(geoSite))
            if (geoIP.isNotEmpty()) put("geoip", JSONArray(geoIP))
            if (processName.isNotEmpty()) put("processName", JSONArray(processName))
            put("outbound", outbound)
        }
    }
}

/**
 * TUN configuration
 */
data class TUNConfig(
    val interfaceName: String,
    val mtu: Int,
    val address: List<String>,
    val gateway: String,
    val dnsHijack: List<String>,
    val autoRoute: Boolean,
    val strictRoute: Boolean,
    val stack: String  // system, gvisor, mixed
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("interfaceName", interfaceName)
            put("mtu", mtu)
            put("address", JSONArray(address))
            put("gateway", gateway)
            put("dnsHijack", JSONArray(dnsHijack))
            put("autoRoute", autoRoute)
            put("strictRoute", strictRoute)
            put("stack", stack)
        }
    }

    companion object {
        fun default(): TUNConfig {
            return TUNConfig(
                interfaceName = "tun0",
                mtu = 9000,
                address = listOf("10.0.0.2/32", "fdfe:dcba:9876::2/128"),
                gateway = "10.0.0.1",
                dnsHijack = listOf("tcp://8.8.8.8:53", "udp://8.8.8.8:53"),
                autoRoute = true,
                strictRoute = false,
                stack = "mixed"
            )
        }

        fun fromJson(json: JSONObject?): TUNConfig {
            if (json == null) return default()
            return default()
        }
    }
}

/**
 * Experimental configuration
 */
data class ExperimentalConfig(
    val cacheFile: String,
    val clashAPI: ClashAPIConfig
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("cacheFile", cacheFile)
            put("clashApi", clashAPI.toJson())
        }
    }

    companion object {
        fun default(): ExperimentalConfig {
            return ExperimentalConfig(
                cacheFile = "/data/data/com.universaltunnel/cache/sing-box.db",
                clashAPI = ClashAPIConfig(false, "127.0.0.1:9090", "", "")
            )
        }

        fun fromJson(json: JSONObject?): ExperimentalConfig {
            if (json == null) return default()
            return default()
        }
    }
}

data class ClashAPIConfig(
    val enabled: Boolean,
    val listen: String,
    val secret: String,
    val externalUI: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("enabled", enabled)
            put("listen", listen)
            put("secret", secret)
            put("externalUi", externalUI)
        }
    }
}