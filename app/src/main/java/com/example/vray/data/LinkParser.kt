package com.example.vray.data

import android.util.Base64
import android.net.Uri
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * Turns a pasted share-link into a ProxyProfile.
 * Supports the common vmess:// (base64 JSON), vless://, trojan:// and ss:// formats
 * that most panels / subscription services already emit.
 */
object LinkParser {

    fun parse(rawLink: String): ProxyProfile? {
        val link = rawLink.trim()
        return try {
            when {
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("vless://") -> parseVlessOrTrojan(link, "vless")
                link.startsWith("trojan://") -> parseVlessOrTrojan(link, "trojan")
                link.startsWith("ss://") -> parseShadowsocks(link)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmess(link: String): ProxyProfile {
        val b64 = link.removePrefix("vmess://")
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        return ProxyProfile(
            id = UUID.randomUUID().toString(),
            name = json.optString("ps", "VMess Server"),
            protocol = "vmess",
            address = json.getString("add"),
            port = json.getString("port").toInt(),
            userId = json.getString("id"),
            alterId = json.optString("aid", "0").toIntOrNull() ?: 0,
            security = json.optString("scy", "auto"),
            network = json.optString("net", "tcp"),
            path = json.optString("path", ""),
            host = json.optString("host", ""),
            tls = if (json.optString("tls", "") == "tls") "tls" else "none",
            sni = json.optString("sni", "")
        )
    }

    /** vless and trojan share the same URI shape: scheme://userinfo@host:port?query#name */
    private fun parseVlessOrTrojan(link: String, protocol: String): ProxyProfile {
        val uri = Uri.parse(link)
        val userInfo = uri.userInfo ?: ""
        val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "$protocol Server"

        fun q(key: String) = uri.getQueryParameter(key) ?: ""

        val security = q("security").ifEmpty { "none" }
        return ProxyProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = protocol,
            address = uri.host ?: "",
            port = uri.port,
            userId = userInfo, // uuid for vless, password for trojan
            network = q("type").ifEmpty { "tcp" },
            path = q("path"),
            host = q("host"),
            tls = if (security == "tls" || security == "reality") security else "none",
            sni = q("sni"),
            fingerprint = q("fp").ifEmpty { "chrome" },
            flow = q("flow"),
            publicKey = q("pbk"),
            shortId = q("sid"),
            alpn = q("alpn")
        )
    }

    private fun parseShadowsocks(link: String): ProxyProfile {
        // ss://BASE64(method:password)@host:port#name  OR fully base64-encoded variant
        var body = link.removePrefix("ss://")
        val name = if (body.contains("#")) {
            URLDecoder.decode(body.substringAfter("#"), "UTF-8")
        } else "Shadowsocks Server"
        body = body.substringBefore("#")

        val (userInfo, hostPart) = if (body.contains("@")) {
            body.substringBefore("@") to body.substringAfter("@")
        } else {
            // legacy fully-encoded form: ss://BASE64(method:password@host:port)
            val decoded = String(Base64.decode(body, Base64.DEFAULT))
            decoded.substringBefore("@") to decoded.substringAfter("@")
        }

        val decodedUserInfo = try {
            String(Base64.decode(userInfo, Base64.URL_SAFE or Base64.NO_PADDING))
        } catch (e: Exception) {
            String(Base64.decode(userInfo, Base64.DEFAULT))
        }
        val method = decodedUserInfo.substringBefore(":")
        val password = decodedUserInfo.substringAfter(":")
        val host = hostPart.substringBefore(":")
        val port = hostPart.substringAfter(":").substringBefore("/").substringBefore("?").toInt()

        return ProxyProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            protocol = "shadowsocks",
            address = host,
            port = port,
            userId = password,
            security = method
        )
    }
}
