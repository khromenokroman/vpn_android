package com.example.vpn

/**
 * Проверка настроек VPN до подключения. Чистый JVM-объект без зависимостей от Android,
 * покрыт юнит-тестами (см. ConfigValidatorTest).
 */
object ConfigValidator {

    const val MIN_PORT = 1
    const val MAX_PORT = 65535
    const val MIN_MTU = 576
    const val MAX_MTU = 1500

    /**
     * Проверяет настройки.
     * @return текст ошибки или null, если настройки корректны
     */
    fun validate(serverIp: String, port: Int, clientIp: String, key: String, mtu: Int): String? {
        if (serverIp.isBlank()) {
            return "Укажите адрес сервера"
        }
        if (!isValidIpv4(serverIp.trim())) {
            return "Введите корректный IPv4-адрес сервера, например 203.0.113.10"
        }
        if (port !in MIN_PORT..MAX_PORT) {
            return "Порт должен быть от $MIN_PORT до $MAX_PORT"
        }
        if (clientIp.isBlank()) {
            return "Укажите адрес в туннеле"
        }
        if (!isValidIpv4(clientIp.trim())) {
            return "Введите корректный IPv4-адрес в туннеле, например 192.168.200.2"
        }
        if (normalizeKey(key).isEmpty()) {
            return "Введите ключ VPN"
        }
        if (mtu !in MIN_MTU..MAX_MTU) {
            return "MTU должен быть от $MIN_MTU до $MAX_MTU"
        }
        return null
    }

    /**
     * Строгая проверка IPv4: ровно 4 части, только цифры, 0..255, без ведущих нулей (как inet_pton).
     */
    fun isValidIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) {
            return false
        }
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) {
                return false
            }
            if (part.any { !it.isDigit() }) {
                return false
            }
            if (part.length > 1 && part[0] == '0') {
                return false
            }
            if (part.toInt() > 255) {
                return false
            }
        }
        return true
    }

    /**
     * Отбрасывает хвостовые пробелы и переводы строки, как сервер при чтении файла ключа
     * (см. read_key_file в src/share/key_file.cpp сервера).
     */
    fun normalizeKey(key: String): String = key.trimEnd('\n', '\r', ' ', '\t')
}
