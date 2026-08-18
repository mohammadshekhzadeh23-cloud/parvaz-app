package com.example.vray.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Quota/expiry info read from a subscription response, if the provider sends it. */
data class FetchedSubMeta(
    val expireAtEpochSec: Long? = null,
    val dataLimitBytes: Long? = null,
    val dataUsedBytes: Long? = null
)

object SubscriptionManager {

    /** Returns every server found in the link (untagged — caller assigns subscriptionId), plus quota/expiry if sent. */
    suspend fun fetch(url: String): Result<Pair<List<ProxyProfile>, FetchedSubMeta>> =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }

                // Most subscription providers send the link list base64-encoded as a whole;
                // some just send plain newline-separated links. Handle both.
                val decoded = try {
                    String(Base64.decode(body.trim(), Base64.DEFAULT))
                } catch (e: Exception) {
                    body
                }

                val profiles = decoded.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { LinkParser.parse(it) }
                    .toList()

                // Common convention (Shadowsocks/Clash-style):
                // subscription-userinfo: upload=123; download=456; total=789; expire=1700000000
                val infoHeader = conn.getHeaderField("subscription-userinfo")
                val meta = FetchedSubMeta(
                    expireAtEpochSec = infoHeader?.let { extractLong(it, "expire") },
                    dataLimitBytes = infoHeader?.let { extractLong(it, "total") },
                    dataUsedBytes = infoHeader?.let {
                        val up = extractLong(it, "upload") ?: 0L
                        val down = extractLong(it, "download") ?: 0L
                        up + down
                    }
                )
                conn.disconnect()

                if (profiles.isEmpty()) Result.failure(IllegalStateException("No servers found in subscription"))
                else Result.success(profiles to meta)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun extractLong(header: String, key: String): Long? =
        Regex("$key=(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
}
