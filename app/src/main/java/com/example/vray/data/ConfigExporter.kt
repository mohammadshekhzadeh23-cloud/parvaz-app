package com.example.vray.data

import android.util.Base64
import android.net.Uri
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Turns a saved ProxyProfile back into shareable text: the original-style link for
 * vmess/vless/trojan/ss profiles, or the raw config text for WireGuard/OpenVPN ones.
 */
object ConfigExporter {

    fun toShareableText(p: ProxyProfile): String = when (p.protocol) {
        "wireguard" -> p.wgConfigText
        "openvpn" -> p.ovpnConfigText
        "vmess" -> buildVmess(p)
        "vless" -> buildVlessOrTrojan(p, "vless")
        "trojan" -> buildVlessOrTrojan(p, "trojan")
        "shadowsocks" -> buildShadowsocks(p)
        else -> ""
    }

    private fun buildVmess(p: ProxyProfile): String {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", p.name)
            put("add", p.address)
            put("port", p.port.toString())
            put("id", p.userId)
            put("aid", p.alterId.toString())
            put("scy", p.security)
            put("net", p.network)
            put("path", p.path)
            put("host", p.host)
            put("tls", if (p.tls == "tls") "tls" else "")
            put("sni", p.sni)
        }
        val b64 = Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        return "vmess://$b64"
    }

    private fun buildVlessOrTrojan(p: ProxyProfile, scheme: String): String {
        val builder = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority("${p.userId}@${p.address}:${p.port}")

        if (p.network.isNotEmpty() && p.network != "tcp") builder.appendQueryParameter("type", p.network)
        if (p.path.isNotEmpty()) builder.appendQueryParameter("path", p.path)
        if (p.host.isNotEmpty()) builder.appendQueryParameter("host", p.host)
        if (p.tls != "none") builder.appendQueryParameter("security", p.tls)
        if (p.sni.isNotEmpty()) builder.appendQueryParameter("sni", p.sni)
        if (p.fingerprint.isNotEmpty()) builder.appendQueryParameter("fp", p.fingerprint)
        if (p.flow.isNotEmpty()) builder.appendQueryParameter("flow", p.flow)
        if (p.publicKey.isNotEmpty()) builder.appendQueryParameter("pbk", p.publicKey)
        if (p.shortId.isNotEmpty()) builder.appendQueryParameter("sid", p.shortId)
        if (p.alpn.isNotEmpty()) builder.appendQueryParameter("alpn", p.alpn)

        val withoutFragment = builder.build().toString()
        val encodedName = URLEncoder.encode(p.name, "UTF-8").replace("+", "%20")
        return "$withoutFragment#$encodedName"
    }

    private fun buildShadowsocks(p: ProxyProfile): String {
        val userInfo = Base64.encodeToString("${p.security}:${p.userId}".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
        val encodedName = URLEncoder.encode(p.name, "UTF-8").replace("+", "%20")
        return "ss://$userInfo@${p.address}:${p.port}#$encodedName"
    }
}
