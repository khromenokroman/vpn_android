package com.example.vpn

/** @brief Настройки подключения: значения по умолчанию и ключи хранилища. */
object VpnConfig {
    const val PREFERENCES_NAME = "vpn_config"

    const val KEY_SERVER_IP = "server_ip"
    const val KEY_SERVER_PORT = "server_port"
    const val KEY_CLIENT_IP = "client_ip"
    const val KEY_VPN_KEY = "vpn_key"
    const val KEY_VPN_MTU = "vpn_mtu"

    // Репозиторий публичный: реальных адресов и ключей здесь быть не должно
    const val DEFAULT_SERVER_IP = ""
    const val DEFAULT_SERVER_PORT = 51820
    const val DEFAULT_CLIENT_IP = "192.168.200.2"
    const val DEFAULT_VPN_KEY = ""
    const val DEFAULT_VPN_MTU = 1380
}
