package com.example.vray.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OpenVPN connectivity is NOT implemented yet. The only maintained Android OpenVPN
 * library (ics-openvpn) isn't set up to be pulled in as a dependency (its JitPack
 * module doesn't resolve as a normal library artifact), and its author explicitly
 * says it's not meant for reuse and enforces GPL on anything built on top of it --
 * which would require this app's full source to be published. Saving/editing an
 * OpenVPN config still works; actually connecting needs a proper library decision
 * first.
 */
object OpenVpnManager {
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun connect(context: Context, name: String, ovpnText: String) {
        _state.value = ConnectionState.ERROR
        _lastError.value = "اتصال OpenVPN هنوز فعال نشده — کانفیگ ذخیره شد ولی اتصال واقعی به‌زودی اضافه می‌شود"
    }

    fun disconnect(context: Context) {
        _state.value = ConnectionState.DISCONNECTED
    }
}
