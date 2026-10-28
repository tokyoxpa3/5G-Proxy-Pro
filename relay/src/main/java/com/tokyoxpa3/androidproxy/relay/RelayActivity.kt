package com.tokyoxpa3.androidproxy.relay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class RelayActivity : Activity() {

    private lateinit var portInput: EditText
    private lateinit var controlPortInput: EditText
    private lateinit var socksUserInput: EditText
    private lateinit var socksPassInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var batteryButton: Button
    private lateinit var assistantButton: Button
    private lateinit var usageButton: Button
    private lateinit var rotateButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)

        portInput = numberInput(getString(R.string.hint_port), prefs.getInt("port", RelayService.DEFAULT_PORT))
        controlPortInput = numberInput(getString(R.string.hint_control_port),
            prefs.getInt("control_port", RelayService.DEFAULT_CONTROL_PORT))
        socksUserInput = EditText(this).apply {
            hint = getString(R.string.hint_socks_user)
            setText(prefs.getString("socks_user", ""))
        }
        socksPassInput = EditText(this).apply {
            hint = getString(R.string.hint_socks_pass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("socks_pass", ""))
        }
        toggleButton = Button(this).apply { setOnClickListener { toggle() } }
        batteryButton = Button(this).apply {
            text = getString(R.string.btn_battery)
            setOnClickListener { requestBatteryExemption() }
        }
        assistantButton = Button(this).apply {
            text = getString(R.string.btn_assistant)
            setOnClickListener { openAssistantSettings() }
        }
        usageButton = Button(this).apply {
            text = getString(R.string.btn_usage_access)
            setOnClickListener { requestUsageAccess() }
        }
        rotateButton = Button(this).apply {
            text = getString(R.string.btn_rotate)
            setOnClickListener {
                saveCredentials()
                val started = RelayService.rotator?.start() ?: false
                if (!started) Toast.makeText(this@RelayActivity, R.string.toast_rotate_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        statusText = TextView(this).apply { setPadding(0, pad, 0, pad) }
        val hint = TextView(this).apply { text = getString(R.string.setup_hint) }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@RelayActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(portInput)
                addView(controlPortInput)
                addView(socksUserInput)
                addView(socksPassInput)
                addView(toggleButton)
                addView(batteryButton)
                addView(assistantButton)
                addView(usageButton)
                addView(rotateButton)
                addView(statusText)
                addView(hint)
            })
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Android 9 以下：/status 的數據用量需要 subscriberId
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 2)
        }
    }

    private fun numberInput(hintText: String, value: Int) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun saveCredentials() {
        getSharedPreferences("relay", Context.MODE_PRIVATE).edit()
            .putString("socks_user", socksUserInput.text.toString().trim())
            .putString("socks_pass", socksPassInput.text.toString())
            .apply()
    }

    private fun toggle() {
        if (RelayService.isRunning) {
            getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putBoolean("relay_enabled", false).apply()
            startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
        } else {
            val port = portInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: RelayService.DEFAULT_PORT
            val controlPort = controlPortInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
                ?: RelayService.DEFAULT_CONTROL_PORT
            if (port == controlPort) {
                Toast.makeText(this, R.string.toast_port_conflict, Toast.LENGTH_SHORT).show()
                return
            }
            saveCredentials()
            getSharedPreferences("relay", Context.MODE_PRIVATE).edit()
                .putInt("port", port)
                .putInt("control_port", controlPort)
                .putBoolean("relay_enabled", true)
                .apply()
            startForegroundService(
                Intent(this, RelayService::class.java)
                    .setAction(RelayService.ACTION_START)
                    .putExtra(RelayService.EXTRA_PORT, port)
            )
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun render() {
        val running = RelayService.isRunning
        toggleButton.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
        portInput.isEnabled = !running
        controlPortInput.isEnabled = !running
        val pm = getSystemService(PowerManager::class.java)
        batteryButton.isEnabled = !pm.isIgnoringBatteryOptimizations(packageName)
        assistantButton.text = getString(
            if (AirplaneAssistant.isActive) R.string.btn_assistant_active else R.string.btn_assistant
        )
        rotateButton.isEnabled = running && AirplaneAssistant.isActive
        usageButton.text = getString(
            if (DeviceStatus.hasUsageAccess(this)) R.string.btn_usage_access_granted else R.string.btn_usage_access
        )
        val addrs = RelayService.listenAddrs
        val relayLine = when {
            !running -> getString(R.string.status_stopped)
            addrs.isEmpty() -> getString(R.string.status_waiting)
            else -> getString(
                R.string.status_running,
                addrs.joinToString(", "),
                portInput.text.toString().toIntOrNull() ?: RelayService.DEFAULT_PORT,
                RelayService.activeConnections.get(),
                controlPortInput.text.toString().toIntOrNull() ?: RelayService.DEFAULT_CONTROL_PORT
            )
        }
        val r = RelayService.rotator?.status
        val rotateLine = if (r == null || r.state == "idle") "" else getString(
            R.string.status_rotation, r.state, r.oldIp ?: "?", r.newIp ?: "?", r.attempts, r.error ?: ""
        )
        statusText.text = relayLine + rotateLine
    }

    // Doze 下未加入白名單的 App 會被切斷網路（包含 tun0 進來的連線）
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // /status 的 data_usage 需要「使用情形存取權」（特殊權限，只能在設定頁開）
    private fun requestUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openAssistantSettings() {
        for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { startActivity(Intent(action)); return } catch (e: Exception) {}
        }
    }
}
