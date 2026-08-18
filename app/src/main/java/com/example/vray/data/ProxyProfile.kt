package com.example.vray.data

import org.json.JSONObject

/**
 * A single saved server profile, already normalized into the fields Xray needs.
 * Populated either by parsing a share-link (vmess://, vless://, trojan://, ss://),
 * pulled in from a subscription link, or entered manually.
 */
data class ProxyProfile(
    val id: String,
    var name: String,
    var protocol: String,      // "vmess" | "vless" | "trojan" | "shadowsocks"
    var address: String,
    var port: Int,
    var userId: String = "",       // uuid for vmess/vless, password for trojan/ss
    var alterId: Int = 0,          // vmess only (legacy, usually 0)
    var security: String = "auto", // vmess encryption / ss cipher
    var network: String = "tcp",   // tcp, ws, grpc, http
    var path: String = "",         // ws/http path
    var host: String = "",         // ws/http Host header, or SNI hint
    var tls: String = "none",      // "none" | "tls" | "reality"
    var sni: String = "",
    var fingerprint: String = "chrome",
    var flow: String = "",         // vless xtls flow
    var publicKey: String = "",    // reality
    var shortId: String = "",      // reality
    var alpn: String = "",
    var subscriptionId: String? = null // null = added manually / by pasted link or QR
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("protocol", protocol)
        put("address", address); put("port", port); put("userId", userId)
        put("alterId", alterId); put("security", security); put("network", network)
        put("path", path); put("host", host); put("tls", tls); put("sni", sni)
        put("fingerprint", fingerprint); put("flow", flow)
        put("publicKey", publicKey); put("shortId", shortId); put("alpn", alpn)
        subscriptionId?.let { put("subscriptionId", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = ProxyProfile(
            id = o.getString("id"),
            name = o.optString("name", "Server"),
            protocol = o.getString("protocol"),
            address = o.getString("address"),
            port = o.getInt("port"),
            userId = o.optString("userId", ""),
            alterId = o.optInt("alterId", 0),
            security = o.optString("security", "auto"),
            network = o.optString("network", "tcp"),
            path = o.optString("path", ""),
            host = o.optString("host", ""),
            tls = o.optString("tls", "none"),
            sni = o.optString("sni", ""),
            fingerprint = o.optString("fingerprint", "chrome"),
            flow = o.optString("flow", ""),
            publicKey = o.optString("publicKey", ""),
            shortId = o.optString("shortId", ""),
            alpn = o.optString("alpn", ""),
            subscriptionId = if (o.has("subscriptionId")) o.optString("subscriptionId") else null
        )
    }
}
