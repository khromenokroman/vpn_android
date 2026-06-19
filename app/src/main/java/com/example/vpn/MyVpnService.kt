package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

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

        if (intent?.action == ACTION_STOP) {
            Log.i("MyVpnService", "Получена команда остановки VPN")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        if (vpnInterface != null) {
            Log.i("MyVpnService", "VPN уже запущен")
            publishState(VpnState.CONNECTED, "VPN подключён")
            return START_STICKY
        }

        val config = loadConfig()
        if (config == null) {
            publishState(VpnState.ERROR, "Некорректные настройки VPN")
            stopSelf()
            return START_NOT_STICKY
        }

        publishState(VpnState.CONNECTING, "VPN подключается...")
        startForeground(NOTIFICATION_ID, buildNotification("VPN подключается..."))

        val builder = Builder()
            .setSession("MyVPN")
            .setMtu(config.vpnMtu)
            .addAddress(config.clientIp, 24)
            .addRoute("0.0.0.0", 0)
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

        nativeStart(
            vpnInterface!!.fd,
            config.serverIp,
            config.serverPort,
            config.vpnKey
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification("VPN подключён"))

        publishState(VpnState.CONNECTED, "VPN подключён")

        return START_STICKY
    }

    fun protectSocket(fd: Int): Boolean {
        Log.i("MyVpnService", "protectSocket fd=$fd")
        return protect(fd)
    }

    fun updateStats(txPackets: Long, txBytes: Long, rxPackets: Long, rxBytes: Long) {
        getSharedPreferences(VpnState.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(VpnState.KEY_TX_PACKETS, txPackets)
            .putLong(VpnState.KEY_TX_BYTES, txBytes)
            .putLong(VpnState.KEY_RX_PACKETS, rxPackets)
            .putLong(VpnState.KEY_RX_BYTES, rxBytes)
            .apply()

        val intent = Intent(VpnState.ACTION_STATS_CHANGED).apply {
            setPackage(packageName)
            putExtra(VpnState.EXTRA_TX_PACKETS, txPackets)
            putExtra(VpnState.EXTRA_TX_BYTES, txBytes)
            putExtra(VpnState.EXTRA_RX_PACKETS, rxPackets)
            putExtra(VpnState.EXTRA_RX_BYTES, rxBytes)
        }

        sendBroadcast(intent)
    }

    override fun onRevoke() {
        Log.i("MyVpnService", "onRevoke")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i("MyVpnService", "onDestroy")
        stopVpn()
        super.onDestroy()
    }

    private fun loadConfig(): Config? {
        val preferences = getSharedPreferences(VpnConfig.PREFERENCES_NAME, Context.MODE_PRIVATE)

        val serverIp = preferences.getString(
            VpnConfig.KEY_SERVER_IP,
            VpnConfig.DEFAULT_SERVER_IP
        )?.trim().orEmpty()

        val serverPort = preferences.getInt(
            VpnConfig.KEY_SERVER_PORT,
            VpnConfig.DEFAULT_SERVER_PORT
        )

        val clientIp = preferences.getString(
            VpnConfig.KEY_CLIENT_IP,
            VpnConfig.DEFAULT_CLIENT_IP
        )?.trim().orEmpty()

        val vpnKey = preferences.getString(
            VpnConfig.KEY_VPN_KEY,
            VpnConfig.DEFAULT_VPN_KEY
        )?.trim().orEmpty()

        val vpnMtu = preferences.getInt(
            VpnConfig.KEY_VPN_MTU,
            VpnConfig.DEFAULT_VPN_MTU
        )

        if (serverIp.isEmpty()) {
            Log.e("MyVpnService", "serverIp пустой")
            return null
        }

        if (serverPort !in 1..65535) {
            Log.e("MyVpnService", "serverPort некорректный: $serverPort")
            return null
        }

        if (clientIp.isEmpty()) {
            Log.e("MyVpnService", "clientIp пустой")
            return null
        }

        if (vpnKey.isEmpty()) {
            Log.e("MyVpnService", "vpnKey пустой")
            return null
        }

        if (vpnMtu !in 576..1500) {
            Log.e("MyVpnService", "MTU некорректный: $vpnMtu")
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

    private fun stopVpn() {
        Log.i("MyVpnService", "stopVpn")

        nativeStop()

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)

        publishState(VpnState.DISCONNECTED, "VPN отключён")
    }

    private fun publishState(state: String, message: String) {
        getSharedPreferences(VpnState.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(VpnState.KEY_STATE, state)
            .putString(VpnState.KEY_MESSAGE, message)
            .apply()

        val intent = Intent(VpnState.ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(VpnState.EXTRA_STATE, state)
            putExtra(VpnState.EXTRA_MESSAGE, message)
        }

        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "VPN service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN connection status"
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VPN")
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
        key: String
    )

    private external fun nativeStop()

    companion object {
        const val ACTION_STOP = "com.example.vpn.STOP"

        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service"

        init {
            System.loadLibrary("vpn")
        }
    }
}