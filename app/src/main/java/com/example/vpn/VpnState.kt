package com.example.vpn

/** @brief Состояния VPN и ключи передачи статуса и статистики между сервисом и UI. */
object VpnState {
    const val ACTION_STATE_CHANGED = "com.example.vpn.STATE_CHANGED"
    const val ACTION_STATS_CHANGED = "com.example.vpn.STATS_CHANGED"

    const val EXTRA_STATE = "state"
    const val EXTRA_MESSAGE = "message"

    const val EXTRA_TX_PACKETS = "tx_packets"
    const val EXTRA_TX_BYTES = "tx_bytes"
    const val EXTRA_RX_PACKETS = "rx_packets"
    const val EXTRA_RX_BYTES = "rx_bytes"
    const val EXTRA_DROPPED = "dropped"

    const val DISCONNECTED = "disconnected"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val NO_RESPONSE = "no_response"
    const val ERROR = "error"

    const val PREFERENCES_NAME = "vpn_state"
    const val KEY_STATE = "state"
    const val KEY_MESSAGE = "message"

    const val KEY_TX_PACKETS = "tx_packets"
    const val KEY_TX_BYTES = "tx_bytes"
    const val KEY_RX_PACKETS = "rx_packets"
    const val KEY_RX_BYTES = "rx_bytes"
    const val KEY_DROPPED = "dropped"
}
