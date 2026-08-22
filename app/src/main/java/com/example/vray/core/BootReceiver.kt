package com.example.vray.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.vray.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val repo = Repository(context)
        val settings = repo.loadSettings()
        if (!settings.autoConnectOnBoot) return

        val profileId = repo.loadSelectedProfileId() ?: return
        val profile = repo.loadProfiles().firstOrNull { it.id == profileId } ?: return

        // Can't prompt for VPN permission from a boot broadcast -- if it wasn't already
        // granted in a previous session, we just silently skip (nothing else to do).
        if (VpnService.prepare(context) != null) return

        when (profile.protocol) {
            "wireguard" -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        WireGuardManager.connect(context, profile.name, profile.wgConfigText)
                    } finally {
                        pending.finish()
                    }
                }
            }
            "openvpn" -> {
                // Not wired up to a real backend yet -- nothing to auto-start.
            }
            else -> {
                val svc = Intent(context, ProxyVpnService::class.java).apply {
                    action = ProxyVpnService.ACTION_CONNECT
                    putExtra(ProxyVpnService.EXTRA_PROFILE_ID, profileId)
                }
                context.startForegroundService(svc)
            }
        }
    }
}
