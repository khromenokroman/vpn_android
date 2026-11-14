package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    /**
     * @brief true, если сервер ответил на подключение (EV_TUNNEL_UP/RESTARTED/RESTORED).
     * Таймаут подключения по расписанию проверяет этот флаг.
     */
    private var tunnelEstablished = false

    /** @brief true после fatalStop: onDestroy не должен затирать ERROR состоянием DISCONNECTED. */
    private var fatalErrorPublished = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** @brief Таймаут подключения: не дождались ответа сервера - ERROR и остановка VPN. */
    private val handshakeTimeoutRunnable = Runnable {
        if (tunnelEstablished) {
            return@Runnable
        }
        Log.w("MyVpnService", "Сервер не ответил на подключение за $HANDSHAKE_TIMEOUT_SEC с")
        fatalErrorPublished = true
        stopVpn(publishDisconnected = false)
        publishState(VpnState.ERROR, getString(R.string.error_handshake_timeout, HANDSHAKE_TIMEOUT_SEC))
        stopSelf()
    }

    private data class Config(
        val serverIp: String,
        val serverPort: Int,
        val clientIp: String,
        val vpnKey: String,
        val vpnMtu: Int
    )

    override fun onCreate() {
        super.onCreate()
        Log.i("MyVpnService", "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("MyVpnService", "onStartCommand action=${intent?.action}")
        fatalErrorPublished = false

        if (intent?.action == ACTION_STOP) {
            Log.i("MyVpnService", "Получена команда остановки VPN")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        if (vpnInterface != null) {
            Log.i("MyVpnService", "VPN уже запущен")
            return START_STICKY
        }

        val config = loadConfig()
        if (config == null) {
            publishState(VpnState.ERROR, "Некорректные настройки VPN")
            stopSelf()
            return START_NOT_STICKY
        }

        publishState(VpnState.CONNECTING, getString(R.string.state_connecting))
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notify_connecting)))

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(config.vpnMtu)
            .addAddress(config.clientIp, 24)
            .addRoute("0.0.0.0", 0)
            // IPv6 из ULA: пакеты попадают в TUN и там отбрасываются, а не идут мимо туннеля
            .addAddress(IPV6_TUNNEL_ADDRESS, 64)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e("MyVpnService", "Не удалось создать VPN-интерфейс")
            publishState(VpnState.ERROR, "Не удалось создать VPN-интерфейс")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i("MyVpnService", "VPN-интерфейс создан, fd=${vpnInterface!!.fd} mtu=${config.vpnMtu} ip=${config.clientIp}")

        val started = nativeStart(
            vpnInterface!!.fd,
            config.serverIp,
            config.serverPort,
            config.clientIp,
            config.vpnKey
        )

        if (!started) {
            Log.e("MyVpnService", "nativeStart вернул false (причина выше в журнале)")
            fatalErrorPublished = true
            stopVpn(publishDisconnected = false)
            publishState(VpnState.ERROR, getString(R.string.error_start_failed))
            stopSelf()
            return START_NOT_STICKY
        }

        // CONNECTED публикуется событием EV_TUNNEL_UP из нативной части, когда сервер ответил
        tunnelEstablished = false
        mainHandler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_SEC * 1000L)

        return START_STICKY
    }

    fun protectSocket(fd: Int): Boolean {
        Log.i("MyVpnService", "protectSocket fd=$fd")
        return protect(fd)
    }

    /** @brief События протокола из нативной части (см. EventCode в native-lib.cpp).
     * Вызывается из нативного потока: остановку VPN делаем только через mainHandler,
     * иначе nativeStop будет ждать join того потока, который его вызвал. */
    fun onNativeEvent(code: Int, arg: Long) {
        Log.i("MyVpnService", "onNativeEvent code=$code arg=$arg")
        when (code) {
            EV_TUNNEL_UP -> {
                tunnelEstablished = true
                mainHandler.removeCallbacks(handshakeTimeoutRunnable)
                publishState(VpnState.CONNECTED, getString(R.string.event_tunnel_up))
            }
            EV_TUNNEL_RESTARTED -> {
                tunnelEstablished = true
                mainHandler.removeCallbacks(handshakeTimeoutRunnable)
                publishState(
                    VpnState.CONNECTED,
                    getString(R.string.event_tunnel_restarted)
                )
            }
            EV_TUNNEL_RESTORED -> publishState(
                VpnState.CONNECTED,
                getString(R.string.event_tunnel_restored)
            )
            EV_CLOCK_SKEW -> fatalStop(getString(R.string.event_clock_skew, arg))
            EV_NO_RESPONSE -> publishState(VpnState.NO_RESPONSE, getString(R.string.state_no_response))
            EV_OLD_PROTOCOL -> fatalStop(getString(R.string.event_old_protocol))
            EV_FATAL -> fatalStop(getString(R.string.error_fatal_unknown))
        }
    }

    /** @brief Рабочий поток нативной части упал (см. failAndStop в native-lib.cpp). */
    fun onNativeFatal(reason: String) {
        Log.e("MyVpnService", "onNativeFatal: $reason")
        fatalStop(getString(R.string.error_fatal, reason))
    }

    fun updateStats(txPackets: Long, txBytes: Long, rxPackets: Long, rxBytes: Long, dropped: Long) {
        getSharedPreferences(VpnState.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(VpnState.KEY_TX_PACKETS, txPackets)
            .putLong(VpnState.KEY_TX_BYTES, txBytes)
            .putLong(VpnState.KEY_RX_PACKETS, rxPackets)
            .putLong(VpnState.KEY_RX_BYTES, rxBytes)
            .putLong(VpnState.KEY_DROPPED, dropped)
            .apply()

        val intent = Intent(VpnState.ACTION_STATS_CHANGED).apply {
            setPackage(packageName)
            putExtra(VpnState.EXTRA_TX_PACKETS, txPackets)
            putExtra(VpnState.EXTRA_TX_BYTES, txBytes)
            putExtra(VpnState.EXTRA_RX_PACKETS, rxPackets)
            putExtra(VpnState.EXTRA_RX_BYTES, rxBytes)
            putExtra(VpnState.EXTRA_DROPPED, dropped)
        }

        sendBroadcast(intent)
    }

    override fun onRevoke() {
        Log.i("MyVpnService", "onRevoke")
        stopVpn(publishDisconnected = !fatalErrorPublished)
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i("MyVpnService", "onDestroy")
        stopVpn(publishDisconnected = !fatalErrorPublished)
        super.onDestroy()
    }

    private fun loadConfig(): Config? {
        val preferences = getSharedPreferences(VpnConfig.PREFERENCES_NAME, Context.MODE_PRIVATE)

        val serverIp = preferences.getString(VpnConfig.KEY_SERVER_IP, VpnConfig.DEFAULT_SERVER_IP)
            ?.trim().orEmpty()

        val serverPort = preferences.getInt(
            VpnConfig.KEY_SERVER_PORT,
            VpnConfig.DEFAULT_SERVER_PORT
        )

        val clientIp = preferences.getString(VpnConfig.KEY_CLIENT_IP, VpnConfig.DEFAULT_CLIENT_IP)
            ?.trim().orEmpty()

        val vpnKey = ConfigValidator.normalizeKey(
            preferences.getString(VpnConfig.KEY_VPN_KEY, VpnConfig.DEFAULT_VPN_KEY).orEmpty()
        )

        val vpnMtu = preferences.getInt(VpnConfig.KEY_VPN_MTU, VpnConfig.DEFAULT_VPN_MTU)

        val error = ConfigValidator.validate(serverIp, serverPort, clientIp, vpnKey, vpnMtu)
        if (error != null) {
            Log.e("MyVpnService", "Некорректные настройки: $error")
            return null
        }

        return Config(
            serverIp = serverIp,
            serverPort = serverPort,
            clientIp = clientIp,
            vpnKey = vpnKey,
            vpnMtu = vpnMtu
        )
    }

    private fun stopVpn(publishDisconnected: Boolean = true) {
        Log.i("MyVpnService", "stopVpn")

        mainHandler.removeCallbacks(handshakeTimeoutRunnable)
        tunnelEstablished = false

        nativeStop()

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)

        if (publishDisconnected) {
            publishState(VpnState.DISCONNECTED, getString(R.string.state_disconnected))
        }
    }

    /** @brief Ошибка, после которой туннель недостоверен: VPN останавливается (интернет
     * возвращается в систему), затем публикуется ERROR с причиной. Только mainLooper:
     * метод может дойти сюда из нативного потока через onNativeEvent/onNativeFatal. */
    private fun fatalStop(message: String) {
        mainHandler.post {
            Log.e("MyVpnService", "fatalStop: $message")
            fatalErrorPublished = true
            stopVpn(publishDisconnected = false)
            publishState(VpnState.ERROR, message)
            stopSelf()
        }
    }

    private fun publishState(state: String, message: String) {
        getSharedPreferences(VpnState.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(VpnState.KEY_STATE, state)
            .putString(VpnState.KEY_MESSAGE, message)
            .apply()

        updateNotification(state)

        val intent = Intent(VpnState.ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(VpnState.EXTRA_STATE, state)
            putExtra(VpnState.EXTRA_MESSAGE, message)
        }

        sendBroadcast(intent)
    }

    private fun updateNotification(state: String) {
        val text = when (state) {
            VpnState.CONNECTED -> getString(R.string.notify_connected)
            VpnState.CONNECTING -> getString(R.string.notify_connecting)
            VpnState.NO_RESPONSE -> getString(R.string.notify_no_response)
            else -> null
        }

        if (text == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notify_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notify_channel_description)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private external fun nativeStart(
        tunFd: Int,
        serverIp: String,
        serverPort: Int,
        clientIp: String,
        key: String
    ): Boolean

    private external fun nativeStop()

    companion object {
        const val ACTION_STOP = "com.example.vpn.STOP"

        private const val HANDSHAKE_TIMEOUT_SEC = 45L

        // коды событий, общие с native-lib.cpp (EventCode)
        private const val EV_TUNNEL_UP = 1
        private const val EV_TUNNEL_RESTARTED = 2
        private const val EV_TUNNEL_RESTORED = 3
        private const val EV_CLOCK_SKEW = 4
        private const val EV_NO_RESPONSE = 5
        private const val EV_OLD_PROTOCOL = 6
        private const val EV_FATAL = 7

        private const val IPV6_TUNNEL_ADDRESS = "fd00:706f:6d61::2"

        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service"

        init {
            System.loadLibrary("vpn")
        }
    }
}
