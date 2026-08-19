package com.example.vray.data

import java.util.UUID

object WireGuardParser {
    /** Accepts a raw wg-quick .conf text (must contain [Interface] and [Peer]). */
    fun parse(raw: String): ProxyProfile? {
        if (!raw.contains("[Interface]") || !raw.contains("[Peer]")) return null

        val endpoint = Regex("Endpoint\\s*=\\s*(\\S+)").find(raw)?.groupValues?.get(1) ?: ""
        val host = endpoint.substringBeforeLast(":", endpoint)
        val port = endpoint.substringAfterLast(":", "51820").toIntOrNull() ?: 51820

        if (host.isEmpty()) return null

        return ProxyProfile(
            id = UUID.randomUUID().toString(),
            name = "WireGuard \u2014 $host",
            protocol = "wireguard",
            address = host,
            port = port,
            wgConfigText = raw.trim()
        )
    }
}
