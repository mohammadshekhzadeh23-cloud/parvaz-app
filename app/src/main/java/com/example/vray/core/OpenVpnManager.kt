package com.example.vray.core

import android.content.Context
import android.content.Intent
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.StringReader

object OpenVpnManager {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun connect(context: Context, name: String, ovpnText: String) {
        _state.value = ConnectionState.CONNECTING
        _lastError.value = null
        withContext(Dispatchers.IO) {
            try {
                val parser = ConfigParser()
                parser.parseConfig(StringReader(ovpnText))
                val profile: VpnProfile = parser.convertProfile()
                profile.mName = name
                profile.mProfileCreator = context.packageName
                ProfileManager.setTemporaryProfile(profile)
                withContext(Dispatchers.Main) {
                    VPNLaunchHelper.startOpenVpn(profile, context.applicationContext)
                }
                _state.value = ConnectionState.CONNECTED
            } catch (e: Exception) {
                _lastError.value = e.message ?: "OpenVPN connection failed"
                _state.value = ConnectionState.ERROR
            }
        }
    }

    fun disconnect(context: Context) {
        try {
            context.stopService(Intent(context, OpenVPNService::class.java))
        } catch (_: Exception) {
            // already stopped
        }
        _state.value = ConnectionState.DISCONNECTED
    }
}
