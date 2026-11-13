package com.example.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверяет ConfigValidator: допустимые и недопустимые наборы настроек,
 * разбор IPv4 и нормализацию ключа.
 */
class ConfigValidatorTest {

    private val validKey = "dGVzdC1rZXktbm90LWEtcmVhbC1zZWNyZXQtMDAwMDA="

    /** Корректные настройки проходят без ошибки. */
    @Test
    fun validConfigAccepted() {
        assertNull(ConfigValidator.validate("203.0.113.10", 51820, "192.168.200.2", validKey, 1380))
    }

    /** Граничные значения порта и MTU принимаются. */
    @Test
    fun boundaryValuesAccepted() {
        assertNull(ConfigValidator.validate("1.2.3.4", 1, "10.0.0.1", validKey, 576))
        assertNull(ConfigValidator.validate("1.2.3.4", 65535, "10.0.0.1", validKey, 1500))
    }

    /** Пустой и некорректный адрес сервера отклоняются с понятным текстом. */
    @Test
    fun invalidServerIpRejected() {
        assertEquals("Укажите адрес сервера", ConfigValidator.validate("  ", 51820, "192.168.200.2", validKey, 1380))
        assertTrue(ConfigValidator.validate("256.1.1.1", 51820, "192.168.200.2", validKey, 1380)!!.contains("сервера"))
        assertTrue(ConfigValidator.validate("10.0.0", 51820, "192.168.200.2", validKey, 1380)!!.contains("сервера"))
        assertTrue(ConfigValidator.validate("10.0.0.1.5", 51820, "192.168.200.2", validKey, 1380)!!.contains("сервера"))
    }

    /** Порт вне 1..65535 отклоняется. */
    @Test
    fun invalidPortRejected() {
        assertTrue(ConfigValidator.validate("203.0.113.10", 0, "192.168.200.2", validKey, 1380)!!.contains("Порт"))
        assertTrue(ConfigValidator.validate("203.0.113.10", 65536, "192.168.200.2", validKey, 1380)!!.contains("Порт"))
        assertTrue(ConfigValidator.validate("203.0.113.10", -1, "192.168.200.2", validKey, 1380)!!.contains("Порт"))
    }

    /** Некорректный адрес в туннеле отклоняется. */
    @Test
    fun invalidClientIpRejected() {
        assertTrue(ConfigValidator.validate("203.0.113.10", 51820, "", validKey, 1380)!!.contains("туннеле"))
        assertTrue(ConfigValidator.validate("203.0.113.10", 51820, "192.168.300.2", validKey, 1380)!!.contains("туннеле"))
    }

    /** Пустой ключ отклоняется. */
    @Test
    fun emptyKeyRejected() {
        assertTrue(ConfigValidator.validate("203.0.113.10", 51820, "192.168.200.2", "", 1380)!!.contains("ключ"))
    }

    /** MTU вне 576..1500 отклоняется. */
    @Test
    fun invalidMtuRejected() {
        assertTrue(ConfigValidator.validate("203.0.113.10", 51820, "192.168.200.2", validKey, 575)!!.contains("MTU"))
        assertTrue(ConfigValidator.validate("203.0.113.10", 51820, "192.168.200.2", validKey, 1501)!!.contains("MTU"))
    }

    /** Разбор IPv4: валидные адреса. */
    @Test
    fun ipv4Valid() {
        assertTrue(ConfigValidator.isValidIpv4("0.0.0.0"))
        assertTrue(ConfigValidator.isValidIpv4("255.255.255.255"))
        assertTrue(ConfigValidator.isValidIpv4("192.168.200.2"))
        assertTrue(ConfigValidator.isValidIpv4("203.0.113.10"))
    }

    /** Разбор IPv4: невалидные адреса, включая ведущие нули. */
    @Test
    fun ipv4Invalid() {
        assertFalse(ConfigValidator.isValidIpv4("01.2.3.4"))
        assertFalse(ConfigValidator.isValidIpv4("1.2.3"))
        assertFalse(ConfigValidator.isValidIpv4("1.2.3.4.5"))
        assertFalse(ConfigValidator.isValidIpv4("a.b.c.d"))
        assertFalse(ConfigValidator.isValidIpv4("1.2.3.-4"))
        assertFalse(ConfigValidator.isValidIpv4("256.0.0.1"))
        assertFalse(ConfigValidator.isValidIpv4(""))
    }

    /** Хвостовые пробелы и переводы строк ключа отбрасываются, как у сервера. */
    @Test
    fun keyNormalizedLikeServer() {
        assertEquals(validKey, ConfigValidator.normalizeKey("$validKey\n"))
        assertEquals(validKey, ConfigValidator.normalizeKey("$validKey\r\n  \t"))
        assertEquals("", ConfigValidator.normalizeKey("\n\r \t"))
    }
}
