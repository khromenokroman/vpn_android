package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.vpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vpnRequestCode = 100

    private val preferences by lazy {
        getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isRunning = preferences.getBoolean(KEY_VPN_RUNNING, false)
        updateStatus(
            text = if (isRunning) "VPN подключён" else "VPN отключён",
            isRunning = isRunning
        )

        binding.startVpnButton.setOnClickListener {
            requestVpnPermissionAndStart()
        }

        binding.stopVpnButton.setOnClickListener {
            stopVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            startVpnService()
        } else if (requestCode == vpnRequestCode) {
            setVpnRunning(false)
            updateStatus("VPN-разрешение не выдано", isRunning = false)
        }
    }

    private fun requestVpnPermissionAndStart() {
        updateStatus("Запрос VPN-разрешения...", isRunning = false)

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        setVpnRunning(true)
        updateStatus("VPN запускается...", isRunning = true)
        startService(Intent(this, MyVpnService::class.java))
        updateStatus("VPN подключён", isRunning = true)
    }

    private fun stopVpnService() {
        updateStatus("VPN отключается...", isRunning = true)

        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }

        startService(intent)

        setVpnRunning(false)
        updateStatus("VPN отключён", isRunning = false)
    }

    private fun setVpnRunning(isRunning: Boolean) {
        preferences.edit()
            .putBoolean(KEY_VPN_RUNNING, isRunning)
            .apply()
    }

    private fun updateStatus(text: String, isRunning: Boolean) {
        binding.sampleText.text = text
        binding.startVpnButton.isEnabled = !isRunning
        binding.stopVpnButton.isEnabled = isRunning
    }

    companion object {
        private const val KEY_VPN_RUNNING = "vpn_running"
    }
}