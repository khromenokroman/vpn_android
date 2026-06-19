package com.example.vpn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.vpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vpnRequestCode = 100
    private val notificationPermissionRequestCode = 101

    private val statePreferences by lazy {
        getSharedPreferences(VpnState.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val configPreferences by lazy {
        getSharedPreferences(VpnConfig.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VpnState.ACTION_STATE_CHANGED) {
                return
            }

            val state = intent.getStringExtra(VpnState.EXTRA_STATE) ?: VpnState.DISCONNECTED
            val message = intent.getStringExtra(VpnState.EXTRA_MESSAGE) ?: state

            applyVpnState(state, message)
        }
    }

    private val vpnStatsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VpnState.ACTION_STATS_CHANGED) {
                return
            }

            val txBytes = intent.getLongExtra(VpnState.EXTRA_TX_BYTES, 0L)
            val rxBytes = intent.getLongExtra(VpnState.EXTRA_RX_BYTES, 0L)

            applyStats(txBytes, rxBytes)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        loadConfigToUi()
        loadLastVpnState()
        loadLastStats()

        binding.saveConfigButton.setOnClickListener {
            saveConfigFromUi(showSuccess = true)
        }

        binding.startVpnButton.setOnClickListener {
            requestVpnPermissionAndStart()
        }

        binding.stopVpnButton.setOnClickListener {
            stopVpnService()
        }

        binding.openNotificationSettingsButton.setOnClickListener {
            openAppNotificationSettings()
        }
    }

    override fun onStart() {
        super.onStart()

        val stateFilter = IntentFilter(VpnState.ACTION_STATE_CHANGED)
        val statsFilter = IntentFilter(VpnState.ACTION_STATS_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, stateFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(vpnStatsReceiver, statsFilter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                this,
                vpnStateReceiver,
                stateFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                this,
                vpnStatsReceiver,
                statsFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        loadLastVpnState()
        loadLastStats()
        updateUiForNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateUiForNotificationPermission()
    }

    override fun onStop() {
        unregisterReceiver(vpnStateReceiver)
        unregisterReceiver(vpnStatsReceiver)
        super.onStop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            startVpnService()
        } else if (requestCode == vpnRequestCode) {
            applyVpnState(VpnState.DISCONNECTED, "VPN-разрешение не выдано")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != notificationPermissionRequestCode) {
            return
        }

        if (hasNotificationPermission()) {
            loadLastVpnState()
        } else {
            applyVpnState(
                VpnState.ERROR,
                "Разрешите уведомления для работы VPN в фоне"
            )
        }

        updateNotificationSettingsButtonVisibility()
    }

    private fun loadConfigToUi() {
        binding.serverIpEditText.setText(
            configPreferences.getString(
                VpnConfig.KEY_SERVER_IP,
                VpnConfig.DEFAULT_SERVER_IP
            )
        )

        binding.serverPortEditText.setText(
            configPreferences.getInt(
                VpnConfig.KEY_SERVER_PORT,
                VpnConfig.DEFAULT_SERVER_PORT
            ).toString()
        )

        binding.clientIpEditText.setText(
            configPreferences.getString(
                VpnConfig.KEY_CLIENT_IP,
                VpnConfig.DEFAULT_CLIENT_IP
            )
        )

        binding.vpnKeyEditText.setText(
            configPreferences.getString(
                VpnConfig.KEY_VPN_KEY,
                VpnConfig.DEFAULT_VPN_KEY
            )
        )
    }

    private fun saveConfigFromUi(showSuccess: Boolean): Boolean {
        val serverIp = binding.serverIpEditText.text.toString().trim()
        val serverPortText = binding.serverPortEditText.text.toString().trim()
        val clientIp = binding.clientIpEditText.text.toString().trim()
        val vpnKey = binding.vpnKeyEditText.text.toString().trim()

        if (serverIp.isEmpty()) {
            applyVpnState(VpnState.ERROR, "Введите IP сервера")
            return false
        }

        val serverPort = serverPortText.toIntOrNull()
        if (serverPort == null || serverPort !in 1..65535) {
            applyVpnState(VpnState.ERROR, "Введите корректный порт сервера")
            return false
        }

        if (clientIp.isEmpty()) {
            applyVpnState(VpnState.ERROR, "Введите IP клиента")
            return false
        }

        if (vpnKey.isEmpty()) {
            applyVpnState(VpnState.ERROR, "Введите ключ VPN")
            return false
        }

        configPreferences.edit()
            .putString(VpnConfig.KEY_SERVER_IP, serverIp)
            .putInt(VpnConfig.KEY_SERVER_PORT, serverPort)
            .putString(VpnConfig.KEY_CLIENT_IP, clientIp)
            .putString(VpnConfig.KEY_VPN_KEY, vpnKey)
            .apply()

        if (showSuccess) {
            applyVpnState(VpnState.DISCONNECTED, "Настройки сохранены")
        }

        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (!hasNotificationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                notificationPermissionRequestCode
            )
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateUiForNotificationPermission() {
        updateNotificationSettingsButtonVisibility()

        if (hasNotificationPermission()) {
            loadLastVpnState()
            return
        }

        applyVpnState(
            VpnState.ERROR,
            "Разрешите уведомления для работы VPN в фоне"
        )
    }

    private fun updateNotificationSettingsButtonVisibility() {
        binding.openNotificationSettingsButton.visibility =
            if (hasNotificationPermission()) View.GONE else View.VISIBLE
    }

    private fun openAppNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }

        startActivity(intent)
    }

    private fun requestVpnPermissionAndStart() {
        if (!hasNotificationPermission()) {
            applyVpnState(
                VpnState.ERROR,
                "Разрешите уведомления для работы VPN в фоне"
            )
            requestNotificationPermissionIfNeeded()
            updateNotificationSettingsButtonVisibility()
            return
        }

        if (!saveConfigFromUi(showSuccess = false)) {
            return
        }

        applyVpnState(VpnState.CONNECTING, "Запрос VPN-разрешения...")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        if (!hasNotificationPermission()) {
            applyVpnState(
                VpnState.ERROR,
                "Разрешите уведомления для работы VPN в фоне"
            )
            updateNotificationSettingsButtonVisibility()
            return
        }

        applyVpnState(VpnState.CONNECTING, "VPN запускается...")
        startService(Intent(this, MyVpnService::class.java))
    }

    private fun stopVpnService() {
        applyVpnState(VpnState.CONNECTING, "VPN отключается...")

        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }

        startService(intent)
    }

    private fun loadLastVpnState() {
        val state = statePreferences.getString(VpnState.KEY_STATE, VpnState.DISCONNECTED)
            ?: VpnState.DISCONNECTED

        val message = statePreferences.getString(VpnState.KEY_MESSAGE, null)
            ?: messageForState(state)

        applyVpnState(state, message)
    }

    private fun loadLastStats() {
        val txBytes = statePreferences.getLong(VpnState.KEY_TX_BYTES, 0L)
        val rxBytes = statePreferences.getLong(VpnState.KEY_RX_BYTES, 0L)

        applyStats(txBytes, rxBytes)
    }

    private fun applyStats(txBytes: Long, rxBytes: Long) {
        val tx = formatBytes(txBytes)
        val rx = formatBytes(rxBytes)

        binding.statsText.text = "Отправлено: $tx • Получено: $rx"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }

        val kb = bytes / 1024.0
        if (kb < 1024) {
            return String.format("%.1f KB", kb)
        }

        val mb = kb / 1024.0
        if (mb < 1024) {
            return String.format("%.1f MB", mb)
        }

        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    private fun applyVpnState(state: String, message: String) {
        binding.sampleText.text = message

        val notificationAllowed = hasNotificationPermission()

        when (state) {
            VpnState.CONNECTING -> {
                binding.startVpnButton.isEnabled = false
                binding.stopVpnButton.isEnabled = true
                binding.saveConfigButton.isEnabled = false
            }

            VpnState.CONNECTED -> {
                binding.startVpnButton.isEnabled = false
                binding.stopVpnButton.isEnabled = true
                binding.saveConfigButton.isEnabled = false
            }

            VpnState.ERROR -> {
                binding.startVpnButton.isEnabled = notificationAllowed
                binding.stopVpnButton.isEnabled = false
                binding.saveConfigButton.isEnabled = true
            }

            VpnState.DISCONNECTED -> {
                binding.startVpnButton.isEnabled = notificationAllowed
                binding.stopVpnButton.isEnabled = false
                binding.saveConfigButton.isEnabled = true
            }

            else -> {
                binding.startVpnButton.isEnabled = notificationAllowed
                binding.stopVpnButton.isEnabled = false
                binding.saveConfigButton.isEnabled = true
            }
        }

        updateNotificationSettingsButtonVisibility()
    }

    private fun messageForState(state: String): String {
        return when (state) {
            VpnState.CONNECTING -> "VPN подключается..."
            VpnState.CONNECTED -> "VPN подключён"
            VpnState.ERROR -> "Ошибка VPN"
            VpnState.DISCONNECTED -> "VPN отключён"
            else -> "VPN отключён"
        }
    }
}