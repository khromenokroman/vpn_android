package com.example.vpn

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.vpn.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vpnRequestCode = 100
    private val notificationPermissionRequestCode = 101

    private val handler = Handler(Looper.getMainLooper())

    private var currentState = VpnState.DISCONNECTED
    private var connectStartMs = 0L

    private var panelAnimator: ValueAnimator? = null

    private val animationsEnabled: Boolean by lazy {
        try {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        } catch (e: Exception) {
            true
        }
    }

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
            val message = intent.getStringExtra(VpnState.EXTRA_MESSAGE).orEmpty()

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
            val dropped = intent.getLongExtra(VpnState.EXTRA_DROPPED, 0L)

            applyStats(txBytes, rxBytes, dropped)
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

        binding.connectButton.setOnClickListener {
            onConnectClicked()
        }

        binding.settingsButton.setOnClickListener {
            toggleSettingsPanel()
        }

        binding.saveConfigButton.setOnClickListener {
            saveConfigFromUi()
        }

        // Отладочный автозапуск для автотестов. Существует только в debug-сборке:
        // в release BuildConfig.DEBUG == false и этот путь недостижим.
        if (BuildConfig.DEBUG) {
            applyDebugExtras(intent)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) {
            applyDebugExtras(intent)
        }
    }

    override fun onStart() {
        super.onStart()

        val stateFilter = IntentFilter(VpnState.ACTION_STATE_CHANGED)
        val statsFilter = IntentFilter(VpnState.ACTION_STATS_CHANGED)

        ContextCompat.registerReceiver(
            this, vpnStateReceiver, stateFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, vpnStatsReceiver, statsFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        loadLastVpnState()
        loadLastStats()
    }

    override fun onResume() {
        super.onResume()
        binding.connectButton.resumeAnimations()
    }

    override fun onPause() {
        binding.connectButton.pauseAnimations()
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(vpnStateReceiver)
        unregisterReceiver(vpnStatsReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        panelAnimator?.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == vpnRequestCode) {
            if (resultCode == RESULT_OK) {
                startVpnService()
            } else {
                applyVpnState(VpnState.DISCONNECTED, getString(R.string.vpn_permission_missing))
            }
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

        if (!hasNotificationPermission()) {
            showMessage(getString(R.string.notification_permission_hint))
        }
    }

    /** @brief Тап по кнопке подключения: подключение, отключение или отмена подключения. */
    private fun onConnectClicked() {
        vibrate()
        when (currentState) {
            VpnState.DISCONNECTED, VpnState.ERROR -> requestVpnPermissionAndStart()
            VpnState.CONNECTED, VpnState.NO_RESPONSE -> {
                applyVpnState(VpnState.CONNECTING, getString(R.string.state_disconnecting))
                stopVpnService()
            }
            VpnState.CONNECTING -> {
                stopVpnService()
                applyVpnState(VpnState.DISCONNECTED, "")
            }
        }
    }

    private fun requestVpnPermissionAndStart() {
        if (!saveConfigFromUi(silent = true)) {
            applyVpnState(VpnState.DISCONNECTED, "")
            return
        }

        val intent = VpnService.prepare(this)
        if (intent != null) {
            applyVpnState(VpnState.CONNECTING, getString(R.string.state_connecting))
            startActivityForResult(intent, vpnRequestCode)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        applyVpnState(VpnState.CONNECTING, getString(R.string.state_connecting))
        startService(Intent(this, MyVpnService::class.java))
    }

    private fun stopVpnService() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }
        startService(intent)
    }

    // Отладочный вход для автотестов (только debug-сборка): extras настройки + autoconnect
    private fun applyDebugExtras(intent: Intent?) {
        val extras = intent?.extras ?: return
        android.util.Log.i("MainActivity", "applyDebugExtras: action=${extras.getString("action")} autoconnect=${extras.getBoolean("autoconnect", false)} state=$currentState")

        val server = extras.getString("server")
        val port = if (extras.containsKey("port")) extras.getInt("port") else null
        val tunIp = extras.getString("tunIp")
        val key = extras.getString("key")
        val mtu = if (extras.containsKey("mtu")) extras.getInt("mtu") else null

        val editor = configPreferences.edit()
        var changed = false
        if (server != null) {
            editor.putString(VpnConfig.KEY_SERVER_IP, server)
            changed = true
        }
        if (port != null) {
            editor.putInt(VpnConfig.KEY_SERVER_PORT, port)
            changed = true
        }
        if (tunIp != null) {
            editor.putString(VpnConfig.KEY_CLIENT_IP, tunIp)
            changed = true
        }
        if (key != null) {
            editor.putString(VpnConfig.KEY_VPN_KEY, key)
            changed = true
        }
        if (mtu != null) {
            editor.putInt(VpnConfig.KEY_VPN_MTU, mtu)
            changed = true
        }
        if (changed) {
            editor.apply()
            loadConfigToUi()
        }

        when (extras.getString("action")) {
            // отладочные хуки автотеста: работают в уже запущенном процессе (singleTop + onNewIntent)
            "connect" -> requestVpnPermissionAndStart()
            "disconnect" -> stopVpnService()
            else -> if (extras.getBoolean("autoconnect", false)) {
                // подключаемся всегда, текущее состояние не важно
                requestVpnPermissionAndStart()
            }
        }
    }

    private fun loadConfigToUi() {
        binding.serverIpEditText.setText(
            configPreferences.getString(VpnConfig.KEY_SERVER_IP, VpnConfig.DEFAULT_SERVER_IP)
        )

        binding.serverPortEditText.setText(
            configPreferences.getInt(VpnConfig.KEY_SERVER_PORT, VpnConfig.DEFAULT_SERVER_PORT)
                .toString()
        )

        binding.clientIpEditText.setText(
            configPreferences.getString(VpnConfig.KEY_CLIENT_IP, VpnConfig.DEFAULT_CLIENT_IP)
        )

        binding.vpnKeyEditText.setText(
            configPreferences.getString(VpnConfig.KEY_VPN_KEY, VpnConfig.DEFAULT_VPN_KEY)
        )

        binding.vpnMtuEditText.setText(
            configPreferences.getInt(VpnConfig.KEY_VPN_MTU, VpnConfig.DEFAULT_VPN_MTU)
                .toString()
        )
    }

    /** @brief Проверяет и сохраняет настройки; при silent=true ошибки не показываются. */
    private fun saveConfigFromUi(silent: Boolean = false): Boolean {
        val serverIp = binding.serverIpEditText.text.toString().trim()
        val serverPortText = binding.serverPortEditText.text.toString().trim()
        val clientIp = binding.clientIpEditText.text.toString().trim()
        val vpnKey = binding.vpnKeyEditText.text.toString()
        val vpnMtuText = binding.vpnMtuEditText.text.toString().trim()

        val serverPort = serverPortText.toIntOrNull() ?: -1
        val vpnMtu = vpnMtuText.toIntOrNull() ?: -1

        val error = ConfigValidator.validate(serverIp, serverPort, clientIp, vpnKey, vpnMtu)
        if (error != null) {
            if (!silent) {
                showMessage(error)
            }
            return false
        }

        configPreferences.edit()
            .putString(VpnConfig.KEY_SERVER_IP, serverIp)
            .putInt(VpnConfig.KEY_SERVER_PORT, serverPort)
            .putString(VpnConfig.KEY_CLIENT_IP, clientIp)
            .putString(VpnConfig.KEY_VPN_KEY, ConfigValidator.normalizeKey(vpnKey))
            .putInt(VpnConfig.KEY_VPN_MTU, vpnMtu)
            .apply()

        if (!silent) {
            showMessage(getString(R.string.settings_saved))
        }

        return true
    }

    private fun toggleSettingsPanel() {
        val panel = binding.settingsPanel
        panelAnimator?.cancel()

        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            return
        }

        panel.visibility = View.VISIBLE
        if (!animationsEnabled) {
            return
        }

        panel.alpha = 0f
        panelAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            addUpdateListener {
                panel.alpha = it.animatedValue as Float
                panel.translationY = (1f - it.animatedValue as Float) * 60f
            }
            start()
        }
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

    private fun loadLastVpnState() {
        val state = statePreferences.getString(VpnState.KEY_STATE, VpnState.DISCONNECTED)
            ?: VpnState.DISCONNECTED

        val message = statePreferences.getString(VpnState.KEY_MESSAGE, null).orEmpty()

        applyVpnState(state, message)
    }

    private fun loadLastStats() {
        val txBytes = statePreferences.getLong(VpnState.KEY_TX_BYTES, 0L)
        val rxBytes = statePreferences.getLong(VpnState.KEY_RX_BYTES, 0L)
        val dropped = statePreferences.getLong(VpnState.KEY_DROPPED, 0L)

        applyStats(txBytes, rxBytes, dropped)
    }

    private fun applyStats(txBytes: Long, rxBytes: Long, dropped: Long) {
        binding.telemetryText.text = getString(
            R.string.telemetry_fmt,
            getString(R.string.telemetry_line1, formatBytes(txBytes), formatBytes(rxBytes)),
            getString(R.string.telemetry_line2, dropped)
        )

        if (currentState == VpnState.CONNECTED || currentState == VpnState.NO_RESPONSE) {
            updateConnectionTimer()
        }
    }

    private fun updateConnectionTimer() {
        val elapsedSec = ((System.currentTimeMillis() - connectStartMs).coerceAtLeast(0L)) / 1000L
        val hours = elapsedSec / 3600
        val minutes = (elapsedSec % 3600) / 60
        val seconds = elapsedSec % 60

        val text = if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }

        binding.timerText.text = getString(R.string.connection_time_fmt, text)
    }

    /** @brief Форматирует байты в «Б/КБ/МБ/ГБ»; разделитель десятичной части - по локали. */
    private fun formatBytes(bytes: Long): String {
        val unit: Int
        val value: String
        when {
            bytes < 1024 -> {
                unit = R.string.unit_bytes
                value = bytes.toString()
            }
            bytes < 1024 * 1024 -> {
                unit = R.string.unit_kb
                value = formatDecimal(bytes / 1024.0, 1)
            }
            bytes < 1024 * 1024 * 1024 -> {
                unit = R.string.unit_mb
                value = formatDecimal(bytes / 1024.0 / 1024.0, 1)
            }
            else -> {
                unit = R.string.unit_gb
                value = formatDecimal(bytes / 1024.0 / 1024.0 / 1024.0, 2)
            }
        }
        return "$value ${getString(unit)}"
    }

    private fun formatDecimal(value: Double, digits: Int): String =
        String.format(Locale.getDefault(), "%.${digits}f", value)

    private fun applyVpnState(state: String, message: String) {
        val previousState = currentState
        currentState = state

        updateConnectButton(state)

        when (state) {
            VpnState.CONNECTING -> {
                binding.statusText.text = if (message.isEmpty()) {
                    getString(R.string.state_connecting)
                } else {
                    message
                }
                showMessage(null)
                binding.timerText.visibility = View.GONE
            }

            VpnState.CONNECTED -> {
                binding.statusText.text = getString(R.string.state_connected)
                if (previousState != VpnState.CONNECTED) {
                    connectStartMs = System.currentTimeMillis()
                }
                binding.timerText.visibility = View.VISIBLE
                updateConnectionTimer()
                showMessage(null)
            }

            VpnState.NO_RESPONSE -> {
                binding.statusText.text = getString(R.string.state_no_response)
                binding.timerText.visibility = View.VISIBLE
                updateConnectionTimer()
                showMessage(null)
            }

            VpnState.ERROR -> {
                binding.statusText.text = getString(R.string.state_error)
                binding.timerText.visibility = View.GONE
                showMessage(message.ifEmpty { null })
            }

            else -> { // DISCONNECTED
                binding.statusText.text = getString(R.string.state_disconnected)
                binding.timerText.visibility = View.GONE
                showMessage(null)
            }
        }
    }

    /** @brief Внешний вид и описание кнопки подключения по состоянию VPN. */
    private fun updateConnectButton(state: String) {
        val (buttonState, description) = when (state) {
            VpnState.CONNECTED -> ConnectButtonView.ButtonState.CONNECTED to
                getString(R.string.connect_cd_stop)
            VpnState.CONNECTING -> ConnectButtonView.ButtonState.CONNECTING to
                getString(R.string.connect_cd_connecting)
            VpnState.NO_RESPONSE -> ConnectButtonView.ButtonState.NO_RESPONSE to
                getString(R.string.connect_cd_no_response)
            VpnState.ERROR -> ConnectButtonView.ButtonState.ERROR to
                getString(R.string.connect_cd_error)
            else -> ConnectButtonView.ButtonState.DISCONNECTED to
                getString(R.string.connect_cd_start)
        }

        binding.connectButton.setState(buttonState)
        binding.connectButton.contentDescription = description
    }

    private fun showMessage(message: String?) {
        if (message.isNullOrEmpty()) {
            binding.messageText.visibility = View.GONE
        } else {
            binding.messageText.text = message
            binding.messageText.visibility = View.VISIBLE
        }
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }
}
