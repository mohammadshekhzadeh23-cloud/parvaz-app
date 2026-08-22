package com.example.vray.data

import org.json.JSONArray
import org.json.JSONObject

/** User-adjustable options — this is the "more options than a typical simple client" part. */
data class AppSettings(
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val enableUdp: Boolean = true,
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val routingMode: RoutingMode = RoutingMode.GLOBAL,
    val bypassLan: Boolean = true,
    val customDns: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val selectedApps: Set<String> = emptySet(),
    val autoReconnect: Boolean = true,
    val killSwitch: Boolean = false,
    val autoFailover: Boolean = false,
    val mtu: Int = 1500,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoConnectOnLaunch: Boolean = false,
    val autoConnectOnBoot: Boolean = false
)

enum class RoutingMode { GLOBAL, BYPASS_IRAN, BYPASS_CHINA }
enum class PerAppMode { ALL, ONLY_SELECTED, EXCEPT_SELECTED }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

object XrayConfigBuilder {

    fun build(profile: ProxyProfile, settings: AppSettings): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("dns", buildDns(settings))
        root.put("inbounds", buildInbounds(settings))
        root.put("outbounds", buildOutbounds(profile, settings))
        root.put("routing", buildRouting(settings))
        if (settings.muxEnabled) {
            // mux is attached to the outbound itself, see buildOutbounds
        }
        return root.toString()
    }

    /**
     * Minimal config for latency testing only: just the one outbound, no local
     * inbounds/ports, no routing rules. Testing several servers concurrently with
     * the full app config (which binds fixed local SOCKS/HTTP ports) makes every
     * test collide on the same ports and return bogus results — this avoids that.
     */
    fun buildOutboundOnly(profile: ProxyProfile): String {
        val root = JSONObject()
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("tag", "proxy")
            put("protocol", profile.protocol)
            put("settings", buildProtocolSettings(profile))
            put("streamSettings", buildStreamSettings(profile))
        })
        root.put("outbounds", arr)
        return root.toString()
    }

    private fun buildDns(settings: AppSettings): JSONObject {
        val servers = JSONArray()
        settings.customDns.forEach { servers.put(it) }
        return JSONObject().put("servers", servers)
    }

    private fun buildInbounds(settings: AppSettings): JSONArray {
        val arr = JSONArray()

        val socks = JSONObject().apply {
            put("tag", "socks-in")
            put("port", settings.socksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("udp", settings.enableUdp)
                put("auth", "noauth")
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls")))
            })
        }
        val http = JSONObject().apply {
            put("tag", "http-in")
            put("port", settings.httpPort)
            put("listen", "127.0.0.1")
            put("protocol", "http")
        }
        arr.put(socks)
        arr.put(http)

        // The real bridge between Android's VpnService TUN device and Xray: without this,
        // AndroidLibXrayLite's StartLoop(config, tunFd) has nothing that actually reads
        // packets from the tun fd, so captured traffic just sits there and nothing works.
        // The fd itself comes from the "xray.tun.fd" env var that StartLoop already sets;
        // this inbound is what makes Xray actually consume it.
        val tun = JSONObject().apply {
            put("tag", "tun-in")
            put("port", 0)
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("mtu", settings.mtu)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls")))
            })
        }
        arr.put(tun)

        return arr
    }

    private fun buildOutbounds(profile: ProxyProfile, settings: AppSettings): JSONArray {
        val arr = JSONArray()

        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", profile.protocol)
            put("settings", buildProtocolSettings(profile))
            put("streamSettings", buildStreamSettings(profile))
            if (settings.muxEnabled) {
                put("mux", JSONObject().apply {
                    put("enabled", true)
                    put("concurrency", settings.muxConcurrency)
                })
            }
        }
        arr.put(proxyOutbound)
        arr.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        arr.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        return arr
    }

    private fun buildProtocolSettings(p: ProxyProfile): JSONObject = when (p.protocol) {
        "vmess" -> JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
            put("address", p.address); put("port", p.port)
            put("users", JSONArray().put(JSONObject().apply {
                put("id", p.userId); put("alterId", p.alterId); put("security", p.security)
            }))
        }))
        "vless" -> JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
            put("address", p.address); put("port", p.port)
            put("users", JSONArray().put(JSONObject().apply {
                put("id", p.userId); put("encryption", "none")
                if (p.flow.isNotEmpty()) put("flow", p.flow)
            }))
        }))
        "trojan" -> JSONObject().put("servers", JSONArray().put(JSONObject().apply {
            put("address", p.address); put("port", p.port); put("password", p.userId)
        }))
        "shadowsocks" -> JSONObject().put("servers", JSONArray().put(JSONObject().apply {
            put("address", p.address); put("port", p.port)
            put("method", p.security); put("password", p.userId)
        }))
        else -> JSONObject()
    }

    private fun buildStreamSettings(p: ProxyProfile): JSONObject {
        val stream = JSONObject().put("network", p.network)

        when (p.network) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", p.path)
                if (p.host.isNotEmpty()) put("headers", JSONObject().put("Host", p.host))
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", p.path))
            "http" -> stream.put("httpSettings", JSONObject().apply {
                put("path", p.path)
                if (p.host.isNotEmpty()) put("host", JSONArray().put(p.host))
            })
        }

        when (p.tls) {
            "tls" -> {
                stream.put("security", "tls")
                stream.put("tlsSettings", JSONObject().apply {
                    put("serverName", p.sni.ifEmpty { p.host.ifEmpty { p.address } })
                    put("fingerprint", p.fingerprint)
                    if (p.alpn.isNotEmpty()) put("alpn", JSONArray(p.alpn.split(",")))
                })
            }
            "reality" -> {
                stream.put("security", "reality")
                stream.put("realitySettings", JSONObject().apply {
                    put("serverName", p.sni)
                    put("fingerprint", p.fingerprint)
                    put("publicKey", p.publicKey)
                    put("shortId", p.shortId)
                })
            }
        }
        return stream
    }

    private fun buildRouting(settings: AppSettings): JSONObject {
        val rules = JSONArray()

        if (settings.bypassLan) {
            rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray(listOf("geoip:private")))
                put("outboundTag", "direct")
            })
        }
        when (settings.routingMode) {
            RoutingMode.BYPASS_IRAN -> rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray(listOf("geoip:ir")))
                put("outboundTag", "direct")
            })
            RoutingMode.BYPASS_CHINA -> rules.put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray(listOf("geoip:cn")))
                put("outboundTag", "direct")
            })
            RoutingMode.GLOBAL -> { /* everything through proxy, no extra rule */ }
        }

        return JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", rules)
        }
    }
}
