package com.example.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.vpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vpnRequestCode = 100

    private val preferences by lazy {
        getSharedPreferences(VpnState.PREFERENCES_NAME, Context.MODE_PRIVATE)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadLastVpnState()

        binding.startVpnButton.setOnClickListener {
            requestVpnPermissionAndStart()
        }

        binding.stopVpnButton.setOnClickListener {
            stopVpnService()
        }
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(VpnState.ACTION_STATE_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                this,
                vpnStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        loadLastVpnState()
    }

    override fun onStop() {
        unregisterReceiver(vpnStateReceiver)
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

    private fun requestVpnPermissionAndStart() {
        applyVpnState(VpnState.CONNECTING, "Запрос VPN-разрешения...")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
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
        val state = preferences.getString(VpnState.KEY_STATE, VpnState.DISCONNECTED)
            ?: VpnState.DISCONNECTED

        val message = preferences.getString(VpnState.KEY_MESSAGE, null)
            ?: messageForState(state)

        applyVpnState(state, message)
    }

    private fun applyVpnState(state: String, message: String) {
        binding.sampleText.text = message

        when (state) {
            VpnState.CONNECTING -> {
                binding.startVpnButton.isEnabled = false
                binding.stopVpnButton.isEnabled = true
            }

            VpnState.CONNECTED -> {
                binding.startVpnButton.isEnabled = false
                binding.stopVpnButton.isEnabled = true
            }

            VpnState.ERROR -> {
                binding.startVpnButton.isEnabled = true
                binding.stopVpnButton.isEnabled = false
            }

            VpnState.DISCONNECTED -> {
                binding.startVpnButton.isEnabled = true
                binding.stopVpnButton.isEnabled = false
            }

            else -> {
                binding.startVpnButton.isEnabled = true
                binding.stopVpnButton.isEnabled = false
            }
        }
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