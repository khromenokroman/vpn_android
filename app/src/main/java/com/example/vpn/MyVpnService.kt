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

        startForeground(NOTIFICATION_ID, buildNotification("VPN подключается..."))

        if (vpnInterface != null) {
            Log.i("MyVpnService", "VPN уже запущен")
            return START_STICKY
        }

        val builder = Builder()
            .setSession("MyVPN")
            .setMtu(1380)
            .addAddress("192.168.200.3", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e("MyVpnService", "Не удалось создать VPN-интерфейс")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i("MyVpnService", "VPN-интерфейс создан, fd=${vpnInterface!!.fd}")

        nativeStart(
            vpnInterface!!.fd,
            "132.243.124.73",
            51820,
            "CvEE3TRqqNNpmxy+/kk07Qk9912ZyCqUlv0ay5006KY="
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification("VPN подключён"))

        return START_STICKY
    }

    fun protectSocket(fd: Int): Boolean {
        Log.i("MyVpnService", "protectSocket fd=$fd")
        return protect(fd)
    }

    override fun onDestroy() {
        Log.i("MyVpnService", "onDestroy")
        stopVpn()
        super.onDestroy()
    }

    private fun stopVpn() {
        Log.i("MyVpnService", "stopVpn")

        nativeStop()

        vpnInterface?.close()
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
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