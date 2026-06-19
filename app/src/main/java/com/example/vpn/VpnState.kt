package com.example.vpn

object VpnState {
    const val ACTION_STATE_CHANGED = "com.example.vpn.STATE_CHANGED"

    const val EXTRA_STATE = "state"
    const val EXTRA_MESSAGE = "message"

    const val DISCONNECTED = "disconnected"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val ERROR = "error"

    const val PREFERENCES_NAME = "vpn_state"
    const val KEY_STATE = "state"
    const val KEY_MESSAGE = "message"
}