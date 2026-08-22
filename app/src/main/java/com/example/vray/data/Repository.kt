package com.example.vray.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Repository(context: Context) {
    private val prefs = context.getSharedPreferences("vray_store", Context.MODE_PRIVATE)

    fun loadProfiles(): MutableList<ProxyProfile> {
        val raw = prefs.getString("profiles", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { ProxyProfile.fromJson(arr.getJSONObject(it)) }
    }

    fun saveProfiles(profiles: List<ProxyProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun loadSelectedProfileId(): String? = prefs.getString("selected_id", null)

    fun saveSelectedProfileId(id: String) {
        prefs.edit().putString("selected_id", id).apply()
    }

    fun loadSettings(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return AppSettings()
        val o = JSONObject(raw)
        val dnsArr = o.optJSONArray("customDns")
        val dns = if (dnsArr != null) List(dnsArr.length()) { dnsArr.getString(it) } else listOf("1.1.1.1", "8.8.8.8")
        return AppSettings(
            socksPort = o.optInt("socksPort", 10808),
            httpPort = o.optInt("httpPort", 10809),
            enableUdp = o.optBoolean("enableUdp", true),
            muxEnabled = o.optBoolean("muxEnabled", false),
            muxConcurrency = o.optInt("muxConcurrency", 8),
            routingMode = RoutingMode.valueOf(o.optString("routingMode", RoutingMode.GLOBAL.name)),
            bypassLan = o.optBoolean("bypassLan", true),
            customDns = dns,
            perAppMode = PerAppMode.valueOf(o.optString("perAppMode", PerAppMode.ALL.name)),
            selectedApps = o.optJSONArray("selectedApps")?.let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            } ?: emptySet(),
            autoReconnect = o.optBoolean("autoReconnect", true),
            killSwitch = o.optBoolean("killSwitch", false),
            autoFailover = o.optBoolean("autoFailover", false),
            mtu = o.optInt("mtu", 1500),
            themeMode = ThemeMode.valueOf(o.optString("themeMode", ThemeMode.SYSTEM.name)),
            autoConnectOnLaunch = o.optBoolean("autoConnectOnLaunch", false),
            autoConnectOnBoot = o.optBoolean("autoConnectOnBoot", false)
        )
    }

    fun saveSettings(s: AppSettings) {
        val o = JSONObject().apply {
            put("socksPort", s.socksPort); put("httpPort", s.httpPort)
            put("enableUdp", s.enableUdp); put("muxEnabled", s.muxEnabled)
            put("muxConcurrency", s.muxConcurrency); put("routingMode", s.routingMode.name)
            put("bypassLan", s.bypassLan)
            put("customDns", JSONArray(s.customDns))
            put("perAppMode", s.perAppMode.name)
            put("selectedApps", JSONArray(s.selectedApps.toList()))
            put("autoReconnect", s.autoReconnect)
            put("killSwitch", s.killSwitch)
            put("autoFailover", s.autoFailover)
            put("mtu", s.mtu)
            put("themeMode", s.themeMode.name)
            put("autoConnectOnLaunch", s.autoConnectOnLaunch)
            put("autoConnectOnBoot", s.autoConnectOnBoot)
        }
        prefs.edit().putString("settings", o.toString()).apply()
    }

    fun loadSubscriptions(): MutableList<Subscription> {
        val raw = prefs.getString("subscriptions", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Subscription(
                id = o.getString("id"),
                label = o.optString("label", "اشتراک"),
                url = o.getString("url"),
                lastUpdatedEpochMs = o.optLong("lastUpdatedEpochMs", 0L),
                expireAtEpochSec = if (o.has("expireAtEpochSec")) o.optLong("expireAtEpochSec") else null,
                dataLimitBytes = if (o.has("dataLimitBytes")) o.optLong("dataLimitBytes") else null,
                dataUsedBytes = if (o.has("dataUsedBytes")) o.optLong("dataUsedBytes") else null
            )
        }
    }

    fun saveSubscriptions(subs: List<Subscription>) {
        val arr = JSONArray()
        subs.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("label", s.label); put("url", s.url)
                put("lastUpdatedEpochMs", s.lastUpdatedEpochMs)
                s.expireAtEpochSec?.let { put("expireAtEpochSec", it) }
                s.dataLimitBytes?.let { put("dataLimitBytes", it) }
                s.dataUsedBytes?.let { put("dataUsedBytes", it) }
            })
        }
        prefs.edit().putString("subscriptions", arr.toString()).apply()
    }

    fun isOnboardingDone(): Boolean = prefs.getBoolean("onboarding_done", false)

    /** Crash log is stored in its own SharedPreferences file by VRayApplication's handler. */
    fun getLastCrash(context: Context): String? =
        context.getSharedPreferences("vray_crash", Context.MODE_PRIVATE).getString("last_crash", null)

    fun clearLastCrash(context: Context) {
        context.getSharedPreferences("vray_crash", Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun getLastConnectError(context: Context): String? =
        context.getSharedPreferences("vray_crash", Context.MODE_PRIVATE).getString("last_connect_error", null)

    fun setOnboardingDone() {
        prefs.edit().putBoolean("onboarding_done", true).apply()
    }

    /** Full backup: every saved server + subscription, as one JSON blob the user can share/store. */
    fun exportBackupJson(): String {
        val root = JSONObject()
        val profilesArr = JSONArray()
        loadProfiles().forEach { profilesArr.put(it.toJson()) }
        root.put("profiles", profilesArr)

        val subsArr = JSONArray()
        loadSubscriptions().forEach { s ->
            subsArr.put(JSONObject().apply {
                put("id", s.id); put("label", s.label); put("url", s.url)
                put("lastUpdatedEpochMs", s.lastUpdatedEpochMs)
                s.expireAtEpochSec?.let { put("expireAtEpochSec", it) }
                s.dataLimitBytes?.let { put("dataLimitBytes", it) }
                s.dataUsedBytes?.let { put("dataUsedBytes", it) }
            })
        }
        root.put("subscriptions", subsArr)
        root.put("exportedAt", System.currentTimeMillis())
        return root.toString(2)
    }

    /** Replaces current servers/subscriptions with the contents of a previously exported backup. */
    fun importBackupJson(json: String): Boolean = try {
        val root = JSONObject(json)

        root.optJSONArray("profiles")?.let { arr ->
            saveProfiles(MutableList(arr.length()) { ProxyProfile.fromJson(arr.getJSONObject(it)) })
        }

        root.optJSONArray("subscriptions")?.let { arr ->
            val list = MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Subscription(
                    id = o.getString("id"),
                    label = o.optString("label", "اشتراک"),
                    url = o.getString("url"),
                    lastUpdatedEpochMs = o.optLong("lastUpdatedEpochMs", 0L),
                    expireAtEpochSec = if (o.has("expireAtEpochSec")) o.optLong("expireAtEpochSec") else null,
                    dataLimitBytes = if (o.has("dataLimitBytes")) o.optLong("dataLimitBytes") else null,
                    dataUsedBytes = if (o.has("dataUsedBytes")) o.optLong("dataUsedBytes") else null
                )
            }
            saveSubscriptions(list)
        }
        true
    } catch (e: Exception) {
        false
    }
}
