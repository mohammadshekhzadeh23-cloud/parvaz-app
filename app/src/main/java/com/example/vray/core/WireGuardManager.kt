package com.example.vray.core

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.StringReader

object WireGuardManager {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var backend: GoBackend? = null
    private var activeTunnel: Tunnel? = null

    private fun getBackend(context: Context): GoBackend =
        backend ?: GoBackend(context.applicationContext).also { backend = it }

    suspend fun connect(context: Context, name: String, configText: String) {
        _state.value = ConnectionState.CONNECTING
        _lastError.value = null
        withContext(Dispatchers.IO) {
            try {
                val config: Config = Config.parse(StringReader(configText).buffered())
                val tunnel = object : Tunnel {
                    override fun getName(): String = name
                    override fun onStateChange(newState: Tunnel.State) {
                        _state.value = if (newState == Tunnel.State.UP) {
                            ConnectionState.CONNECTED
                        } else {
                            ConnectionState.DISCONNECTED
                        }
                    }
                }
                activeTunnel = tunnel
                getBackend(context).setState(tunnel, Tunnel.State.UP, config)
            } catch (e: Exception) {
                _lastError.value = e.message ?: "WireGuard connection failed"
                _state.value = ConnectionState.ERROR
                activeTunnel = null
            }
        }
    }

    suspend fun disconnect(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val tunnel = activeTunnel ?: return@withContext
                getBackend(context).setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
                // already down / nothing to clean up
            }
            activeTunnel = null
            _state.value = ConnectionState.DISCONNECTED
        }
    }
}
