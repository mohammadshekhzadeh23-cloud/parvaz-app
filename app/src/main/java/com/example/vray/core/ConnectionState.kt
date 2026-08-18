package com.example.vray.core

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class TrafficStats(val uplinkBytes: Long = 0, val downlinkBytes: Long = 0)
