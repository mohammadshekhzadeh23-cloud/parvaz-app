package com.example.vray.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray

object PingTester {
    /** Returns delay in ms, or -1 if the server is unreachable / config is invalid. */
    suspend fun measure(profile: ProxyProfile, testUrl: String = "https://www.google.com/generate_204"): Long =
        withContext(Dispatchers.IO) {
            try {
                val config = XrayConfigBuilder.build(profile, AppSettings())
                Libv2ray.measureOutboundDelay(config, testUrl)
            } catch (e: Exception) {
                -1L
            }
        }
}
