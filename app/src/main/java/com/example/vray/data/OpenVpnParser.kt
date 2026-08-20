package com.example.vray.data

import java.util.UUID

object OpenVpnParser {
    /** Accepts raw .ovpn text (must contain a "remote <host> <port>" directive). */
    fun parse(raw: String): ProxyProfile? {
        if (!raw.contains("remote ") || (!raw.contains("dev tun") && !raw.contains("dev-type tun"))) return null

        val match = Regex("remote\\s+(\\S+)\\s+(\\d+)").find(raw) ?: return null
        val host = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: 1194

        return ProxyProfile(
            id = UUID.randomUUID().toString(),
            name = "OpenVPN \u2014 $host",
            protocol = "openvpn",
            address = host,
            port = port,
            ovpnConfigText = raw.trim()
        )
    }
}
