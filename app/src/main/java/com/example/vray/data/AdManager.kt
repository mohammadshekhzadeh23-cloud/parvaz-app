package com.example.vray.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fill these in once you've deployed velox-ad-panel (see its README) to your VPS.
 * Until BASE_URL is set to something real, ad fetching is a silent no-op — the app
 * behaves exactly as before, no ad UI shows up anywhere.
 */
object AdConfig {
    const val BASE_URL = "https://YOUR_DOMAIN_HERE"
    const val API_KEY = "YOUR_APP_API_KEY_HERE"

    val isConfigured: Boolean get() = !BASE_URL.contains("YOUR_DOMAIN_HERE")
}

data class AdItem(
    val id: Int,
    val title: String,
    val body: String,
    val link: String,
    val imageUrl: String?,
    val placement: String = "all" // "all" | "main_card" | "first_launch" | "info_screen"
)

/** Shows in a given spot if the ad is targeted there specifically, or set to show everywhere. */
fun List<AdItem>.forPlacement(placement: String): List<AdItem> =
    filter { it.placement == placement || it.placement == "all" }

object AdManager {

    suspend fun fetchAds(): List<AdItem> = withContext(Dispatchers.IO) {
        if (!AdConfig.isConfigured) return@withContext emptyList()
        try {
            val conn = (URL("${AdConfig.BASE_URL}/api/ads").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-Api-Key", AdConfig.API_KEY)
                connectTimeout = 8000
                readTimeout = 8000
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val arr = JSONObject(text).getJSONArray("ads")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AdItem(
                    id = o.getInt("id"),
                    title = o.getString("title"),
                    body = o.getString("body"),
                    link = o.getString("link"),
                    imageUrl = if (o.isNull("image_url")) null else o.optString("image_url").ifEmpty { null },
                    placement = o.optString("placement", "all")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun reportImpression(id: Int, placement: String) = fireAndForget("/api/ads/$id/impression", placement)
    fun reportClick(id: Int, placement: String) = fireAndForget("/api/ads/$id/click", placement)

    private fun fireAndForget(path: String, placement: String) {
        if (!AdConfig.isConfigured) return
        Thread {
            try {
                val conn = (URL("${AdConfig.BASE_URL}$path?placement=$placement").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("X-Api-Key", AdConfig.API_KEY)
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                conn.responseCode // triggers the request
                conn.disconnect()
            } catch (_: Exception) {
                // best-effort only, never worth surfacing to the user
            }
        }.start()
    }
}
