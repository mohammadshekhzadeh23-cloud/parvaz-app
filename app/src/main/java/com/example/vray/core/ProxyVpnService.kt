package com.example.vray.core

import android.app.*
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.vray.BuildConfig
import com.example.vray.MainActivity
import com.example.vray.R
import com.example.vray.data.AppSettings
import com.example.vray.data.PerAppMode
import com.example.vray.data.ProxyProfile
import com.example.vray.data.Repository
import com.example.vray.data.XrayConfigBuilder
import com.example.vray.widget.VpnWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class ProxyVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_CONNECT = "com.example.vray.CONNECT"
        const val ACTION_DISCONNECT = "com.example.vray.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val NOTIF_CHANNEL = "vray_vpn"
        const val NOTIF_ID = 1
        private const val STATS_TAG = "proxy"
        private const val MAX_RETRIES = 5

        private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val state: StateFlow<ConnectionState> = _state.asStateFlow()

        private val _stats = MutableStateFlow(TrafficStats())
        val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val _activeProfileName = MutableStateFlow<String?>(null)
        val activeProfileName: StateFlow<String?> = _activeProfileName.asStateFlow()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyVpnService = this@ProxyVpnService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var statsJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private lateinit var controller: CoreController
    private lateinit var repo: Repository

    private var currentProfileId: String? = null
    private var triedProfileIds = mutableSetOf<String>()
    private var userRequestedDisconnect = false
    private var retryCount = 0

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkHandle: Long? = null

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
        controller = Libv2ray.newCoreController(this)
        registerNetworkCallback()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                userRequestedDisconnect = true
                stopProxy()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                val profile = repo.loadProfiles().firstOrNull { it.id == profileId }
                val settings = repo.loadSettings()
                if (profile != null) {
                    userRequestedDisconnect = false
                    retryCount = 0
                    triedProfileIds.clear()
                    startProxy(profile, settings)
                } else {
                    _lastError.value = "Selected server was not found"
                    _state.value = ConnectionState.ERROR
                }
            }
        }
        return START_STICKY
    }

    private fun startProxy(profile: ProxyProfile, settings: AppSettings) {
        currentProfileId = profile.id
        triedProfileIds.add(profile.id)

        _state.value = ConnectionState.CONNECTING
        _lastError.value = null
        startForeground(NOTIF_ID, buildNotification("در حال اتصال…"))
        VpnWidgetProvider.updateAll(this)

        scope.launch {
            try {
                // Reuse an already-open TUN (kill-switch path) so no clear traffic
                // leaks while we're mid-retry; only open a fresh one if needed.
                val tun = tunFd ?: withContext(Dispatchers.Main) { establishTun(settings) }
                    ?: throw IllegalStateException("Could not create VPN interface (permission revoked?)")
                tunFd = tun

                val configJson = XrayConfigBuilder.build(profile, settings)
                try { controller.stopLoop() } catch (_: Exception) {}
                controller.startLoop(configJson, tun.fd)

                _state.value = ConnectionState.CONNECTED
                _activeProfileName.value = profile.name
                retryCount = 0
                updateNotification("متصل \u00b7 ${profile.name}")
                VpnWidgetProvider.updateAll(this@ProxyVpnService)
                startStatsPolling()
            } catch (e: Exception) {
                handleConnectFailure(e, settings)
            }
        }
    }

    private suspend fun handleConnectFailure(e: Exception, settings: AppSettings) {
        _lastError.value = e.message ?: "Failed to start tunnel"
        // Full trace (with the real underlying JNI/config error) so the UI can offer
        // a "details" view instead of just the short message above.
        getSharedPreferences("vray_crash", MODE_PRIVATE).edit()
            .putString("last_connect_error", Log.getStackTraceString(e))
            .apply()
        _state.value = ConnectionState.ERROR
        VpnWidgetProvider.updateAll(this@ProxyVpnService)

        if (userRequestedDisconnect) {
            finalizeStop()
            return
        }

        // Failover: try another saved server we haven't tried yet in this attempt cycle.
        if (settings.autoFailover) {
            val next = repo.loadProfiles().firstOrNull { it.id !in triedProfileIds }
            if (next != null) {
                delay(800)
                startProxy(next, settings)
                return
            }
        }

        // Auto-reconnect: retry the original selection with backoff.
        if (settings.autoReconnect && retryCount < MAX_RETRIES) {
            retryCount++
            val backoffMs = (1000L * retryCount).coerceAtMost(10_000L)
            updateNotification("تلاش مجدد برای اتصال… ($retryCount/$MAX_RETRIES)")
            delay(backoffMs)
            val profile = repo.loadProfiles().firstOrNull { it.id == currentProfileId }
            if (profile != null) {
                triedProfileIds.clear()
                startProxy(profile, settings)
                return
            }
        }

        // Out of retries / no fallback available.
        if (settings.killSwitch) {
            // Keep the TUN interface up (blackholed, no working core loop) so the
            // device stays offline rather than silently falling back to clear traffic.
            updateNotification("اتصال قطع شد \u2014 Kill Switch فعاله، اینترنت مسدوده")
        } else {
            finalizeStop()
        }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                if (lastNetworkHandle != null && lastNetworkHandle != handle &&
                    _state.value == ConnectionState.CONNECTED
                ) {
                    // Underlying network actually changed (e.g. WiFi -> mobile data):
                    // re-establish the tunnel over the new network.
                    val settings = repo.loadSettings()
                    if (settings.autoReconnect) {
                        val profile = repo.loadProfiles().firstOrNull { it.id == currentProfileId }
                        if (profile != null) {
                            retryCount = 0
                            triedProfileIds.clear()
                            startProxy(profile, settings)
                        }
                    }
                }
                lastNetworkHandle = handle
            }
        }
        networkCallback = callback
        try {
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            // Some OEM ROMs restrict this; auto-reconnect on network switch just won't fire.
        }
    }

    private fun startStatsPolling() {
        // Legacy (older/weaker) devices poll less often to save CPU and battery.
        val intervalMs = if (BuildConfig.IS_LEGACY) 3000L else 1500L
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val up = controller.queryStats(STATS_TAG, "uplink")
                    val down = controller.queryStats(STATS_TAG, "downlink")
                    val prev = _stats.value
                    _stats.value = TrafficStats(
                        uplinkBytes = prev.uplinkBytes + up,
                        downlinkBytes = prev.downlinkBytes + down
                    )
                } catch (_: Exception) {
                    // core not ready yet / already stopped -- ignore this tick
                }
                delay(intervalMs)
            }
        }
    }

    private fun establishTun(settings: AppSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("VRay")
            .addAddress("10.10.14.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(settings.customDns.firstOrNull() ?: "1.1.1.1")
            .setMtu(1500)

        when (settings.perAppMode) {
            PerAppMode.ONLY_SELECTED -> settings.selectedApps.forEach {
                try { builder.addAllowedApplication(it) } catch (_: Exception) {}
            }
            PerAppMode.EXCEPT_SELECTED -> settings.selectedApps.forEach {
                try { builder.addDisallowedApplication(it) } catch (_: Exception) {}
            }
            PerAppMode.ALL -> { /* tunnel everything */ }
        }
        if (settings.perAppMode != PerAppMode.ONLY_SELECTED) {
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }

        return builder.establish()
    }

    fun stopProxy() {
        finalizeStop()
    }

    private fun finalizeStop() {
        statsJob?.cancel()
        try { controller.stopLoop() } catch (_: Exception) {}
        tunFd?.close()
        tunFd = null
        _stats.value = TrafficStats()
        _activeProfileName.value = null
        if (userRequestedDisconnect || _state.value != ConnectionState.ERROR) {
            _state.value = ConnectionState.DISCONNECTED
        }
        VpnWidgetProvider.updateAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("VELOX VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onRevoke() {
        userRequestedDisconnect = true
        stopProxy()
        super.onRevoke()
    }

    override fun onDestroy() {
        val cb = networkCallback
        if (cb != null) {
            try { connectivityManager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        scope.cancel()
        super.onDestroy()
    }

    // --- CoreCallbackHandler: called back from the Go core ---
    override fun startup(): Long = 0
    override fun shutdown(): Long {
        if (userRequestedDisconnect) _state.value = ConnectionState.DISCONNECTED
        return 0
    }
    override fun onEmitStatus(p0: Long, p1: String?): Long = 0
}
