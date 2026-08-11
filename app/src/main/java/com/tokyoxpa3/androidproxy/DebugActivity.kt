package com.tokyoxpa3.androidproxy

import android.os.Bundle
import android.util.Log
import android.widget.*
import android.app.Activity
import android.net.Network
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlinx.coroutines.*
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import com.tokyoxpa3.androidproxy.network.CellularNetworkManager
import com.tokyoxpa3.androidproxy.network.HotspotManager
import com.tokyoxpa3.androidproxy.network.PublicIPChecker

class DebugActivity : Activity() {
    
    private lateinit var statusText: TextView
    private lateinit var wifiIPText: TextView
    private lateinit var hotspotIPText: TextView
    private lateinit var cellularIPText: TextView
    private lateinit var portInput: EditText
    private lateinit var authUserInput: EditText
    private lateinit var authPassInput: EditText
    private lateinit var mainButton: Button
    
    private var isRunning = false
    private var isSpeedTestRunning = false
    private val speedTestUrls = listOf(
        "https://speedtest.singapore.linode.com/100MB-singapore.bin",
        "https://speedtest.tokyo2.linode.com/100MB-tokyo2.bin",
        "https://proof.ovh.net/files/100Mb.dat",
        "https://speed.hetzner.de/100MB.bin",
        "https://speedtest.tele2.net/100MB.zip",
        "https://ipv4.downloader.thinkbroad.com/100MB.zip"
    )
    private val networkManager by lazy { CellularNetworkManager(this) }
    private val ipChecker by lazy { PublicIPChecker() }
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 同步 Service 運行狀態
        isRunning = Socks5ProxyService.isServiceRunning

        setupUI()
        
        // 檢查上次退出原因
        checkLastExitReason()
        
        // 檢查是否有崩潰日誌紀錄
        val sp = getSharedPreferences("debug_log", Context.MODE_PRIVATE)
        val detailedLog = sp.getString("last_java_crash", null)

        if (detailedLog != null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Java 崩潰詳細日誌")
                .setMessage(detailedLog)
                .setPositiveButton("複製並關閉") { _, _ ->
                    // 複製到剪貼簿方便你傳給我
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Crash Log", detailedLog)
                    clipboard.setPrimaryClip(clip)
                    
                    // 清除紀錄避免重複顯示
                    sp.edit().remove("last_java_crash").apply()
                    Toast.makeText(this, "日誌已複製到剪貼簿", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // 2. 根據狀態更新 UI
        if (isRunning) {
            mainButton.text = getString(R.string.btn_stop_proxy)
            mainButton.background = createButtonDrawable(0xFFDC3545.toInt())
            statusText.text = getString(R.string.status_proxy_running)
        }
        
        updateNetworkStatus()
    }

    private fun checkLastExitReason() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val exitReasons = activityManager.getHistoricalProcessExitReasons(packageName, 0, 1)
            
            if (exitReasons.isNotEmpty()) {
                val reason = exitReasons[0]
                val reasonDescription = when (reason.reason) {
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> getString(R.string.exit_reason_low_mem)
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> getString(R.string.exit_reason_user_requested)
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> getString(R.string.exit_reason_user_stopped)
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> getString(R.string.exit_reason_signaled, reason.status)
                    android.app.ApplicationExitInfo.REASON_CRASH -> getString(R.string.exit_reason_crash)
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> getString(R.string.exit_reason_resource)
                    else -> getString(R.string.exit_reason_other, reason.reason, reason.description ?: "")
                }
                
                statusText.text = getString(R.string.last_exit_prefix) + reasonDescription
                if (reason.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED) {
                    statusText.setTextColor(android.graphics.Color.RED)
                }
            }
        }
    }

    private fun setupUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(0xFFF8F9FA.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        
        // Header
        val titleText = TextView(this).apply {
            text = getString(R.string.service_title)
            textSize = 28f
            setTextColor(0xFF212529.toInt())
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        
        // Status Card
        val statusCard = createCard().apply {
            statusText = TextView(context).apply {
                text = getString(R.string.service_not_started)
                textSize = 16f
                setTextColor(0xFF6C757D.toInt())
            }
            addView(statusText)
        }
        
        // Port Config
        val portLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 48, 0, 48)
            gravity = Gravity.CENTER_VERTICAL
            
            val label = TextView(context).apply {
                text = getString(R.string.proxy_port_label)
                textSize = 18f
                setTextColor(0xFF212529.toInt()) 
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            portInput = EditText(context).apply {
                setText("1080")
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)
                setTextColor(0xFF212529.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFE9ECEF.toInt()) 
                    cornerRadius = 8f
                    setStroke(2, 0xFFCED4DA.toInt()) 
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            addView(label)
            addView(portInput)
        }

        // Auth Config (可選帳密，留空 = 無認證)
        val authPrefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val authLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
            gravity = Gravity.CENTER_VERTICAL

            val label = TextView(context).apply {
                text = getString(R.string.auth_user_label)
                textSize = 18f
                setTextColor(0xFF212529.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            authUserInput = EditText(context).apply {
                setText(authPrefs.getString("auth_user", "") ?: "")
                hint = getString(R.string.auth_user_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)
                setTextColor(0xFF212529.toInt())
                setHintTextColor(0xFFADB5BD.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFE9ECEF.toInt())
                    cornerRadius = 8f
                    setStroke(2, 0xFFCED4DA.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            addView(label)
            addView(authUserInput)
        }

        val passLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER_VERTICAL

            val label = TextView(context).apply {
                text = getString(R.string.auth_pass_label)
                textSize = 18f
                setTextColor(0xFF212529.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            authPassInput = EditText(context).apply {
                setText(authPrefs.getString("auth_pass", "") ?: "")
                hint = getString(R.string.auth_pass_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)
                setTextColor(0xFF212529.toInt())
                setHintTextColor(0xFFADB5BD.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFE9ECEF.toInt())
                    cornerRadius = 8f
                    setStroke(2, 0xFFCED4DA.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            addView(label)
            addView(authPassInput)
        }

        val authWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyAuthChange()
            }
        }
        authUserInput.addTextChangedListener(authWatcher)
        authPassInput.addTextChangedListener(authWatcher)
        
        // Main Action Button
        mainButton = Button(this).apply {
            text = getString(R.string.btn_start_proxy)
            textSize = 18f
            setTextColor(Color.WHITE)
            background = createButtonDrawable(0xFF6200EE.toInt())
            setPadding(0, 32, 0, 32)
            setOnClickListener { toggleProxy() }
        }
        
        // IP Display Card
        val ipCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
            }
            
            wifiIPText = TextView(context).apply {
                text = getString(R.string.wifi_ip_fetching)
                textSize = 16f
                setPadding(0, 8, 0, 8)
                setTextColor(0xFF495057.toInt())
            }
            hotspotIPText = TextView(context).apply {
                text = getString(R.string.hotspot_ip_fetching)
                textSize = 16f
                setPadding(0, 8, 0, 8)
                setTextColor(0xFF495057.toInt())
            }
            cellularIPText = TextView(context).apply {
                text = getString(R.string.cellular_ip_fetching)
                textSize = 16f
                setPadding(0, 8, 0, 8)
                setTextColor(0xFF495057.toInt())
            }
            addView(wifiIPText)
            addView(hotspotIPText)
            addView(cellularIPText)
        }
        
        // Refresh Status Button
        val refreshButton = Button(this).apply {
            text = getString(R.string.btn_refresh_status)
            textSize = 14f
            setTextColor(0xFF6200EE.toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12f
                setStroke(2, 0xFF6200EE.toInt())
            }
            setPadding(0, 24, 0, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
            setOnClickListener { refreshStatus() }
        }
        
        rootLayout.addView(titleText)
        rootLayout.addView(statusCard)
        rootLayout.addView(portLayout)
        rootLayout.addView(authLayout)
        rootLayout.addView(passLayout)
        rootLayout.addView(mainButton)
        rootLayout.addView(ipCard)
        rootLayout.addView(refreshButton)
        
        val debugTools = Button(this).apply {
            text = getString(R.string.btn_dev_speed_test)
            textSize = 12f
            setOnClickListener { testRawFiveGSpeed() }
        }
        rootLayout.addView(debugTools)
        
        setContentView(rootLayout)
    }
    
    private fun createCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 16f
            setStroke(2, 0xFFDEE2E6.toInt())
        }
    }
    
    private fun createButtonDrawable(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 12f
    }
    
    private fun toggleProxy() {
        if (!isRunning) {
            startProxyFlow()
        } else {
            stopProxyFlow()
        }
    }
    
    private fun applyAuthChange() {
        val user = authUserInput.text.toString().trim()
        val pass = authPassInput.text.toString()
        getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
            .edit()
            .putString("auth_user", user)
            .putString("auth_pass", pass)
            .apply()
        if (Socks5ProxyService.isServiceRunning) {
            NativeEngine.setSocks5Auth(user, pass)
            Toast.makeText(this, getString(R.string.auth_applied_live), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProxyFlow() {
        if (!PowerPermissionHelper.isWhitelisted(this)) {
            PowerPermissionHelper.showOptimizationDialog(this)
            return 
        }

        val port = portInput.text.toString().toIntOrNull() ?: 1080

        getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
            .edit()
            .putString("auth_user", authUserInput.text.toString().trim())
            .putString("auth_pass", authPassInput.text.toString())
            .apply()

        isRunning = true
        mainButton.text = getString(R.string.btn_stop_proxy)
        mainButton.background = createButtonDrawable(0xFFDC3545.toInt())
        statusText.text = getString(R.string.status_starting)
        
        val intent = Intent(this, Socks5ProxyService::class.java).apply {
            action = Socks5ProxyService.ACTION_START_PROXY
            putExtra(Socks5ProxyService.EXTRA_PORT, port)
        }
        startService(intent)
        
        activityScope.launch {
            delay(3000)
            updateNetworkStatus()
        }
    }
    
    private fun stopProxyFlow() {
        isRunning = false
        mainButton.text = getString(R.string.btn_start_proxy)
        mainButton.background = createButtonDrawable(0xFF6200EE.toInt())
        
        val intent = Intent(this, Socks5ProxyService::class.java).apply {
            action = Socks5ProxyService.ACTION_STOP_PROXY
        }
        startService(intent)
        statusText.text = getString(R.string.status_stopped)
        updateNetworkStatus()
    }
    
    private fun refreshStatus() {
        statusText.text = getString(R.string.status_refreshing)
        updateNetworkStatus()
        if (isRunning) {
            statusText.text = getString(R.string.status_proxy_running)
        } else {
            statusText.text = getString(R.string.status_stopped)
        }
    }

    private fun updateNetworkStatus() {
        activityScope.launch {
            try {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val ip = wifiManager.connectionInfo.ipAddress
                if (ip != 0) {
                    val ipString = String.format("%d.%d.%d.%d", (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff))
                    val port = portInput.text.toString().toIntOrNull() ?: 1080
                    wifiIPText.text = getString(R.string.wifi_proxy_format, ipString, port)
                } else {
                    wifiIPText.text = getString(R.string.wifi_not_connected)
                }
            } catch (e: Exception) {
                wifiIPText.text = getString(R.string.wifi_fetch_failed)
            }

            val hotspotIP = withContext(Dispatchers.IO) {
                HotspotManager.getHotspotIP(applicationContext)
            }
            if (hotspotIP != null) {
                val port = portInput.text.toString().toIntOrNull() ?: 1080
                hotspotIPText.text = getString(R.string.hotspot_ip_format, hotspotIP, port)
            } else {
                hotspotIPText.text = getString(R.string.hotspot_ip_disabled)
            }

            cellularIPText.text = getString(R.string.cellular_ip_fetching)
            val cellularNetwork = withContext(Dispatchers.IO) {
                networkManager.requestCellularNetwork(5000)
            }
            
            if (cellularNetwork != null) {
                val publicIP = ipChecker.getPublicIP(cellularNetwork)
                cellularIPText.text = getString(R.string.cellular_ip_format, publicIP ?: getString(R.string.cellular_ip_failed))
            } else {
                cellularIPText.text = getString(R.string.cellular_ip_not_locked)
            }
        }
    }

    private fun testRawFiveGSpeed() {
        if (isSpeedTestRunning) return
        isSpeedTestRunning = true
        activityScope.launch(Dispatchers.Main) {
            val network = withContext(Dispatchers.IO) { networkManager.requestCellularNetwork(15000) }
            if (network == null) {
                statusText.text = getString(R.string.speed_test_failed)
                isSpeedTestRunning = false
                return@launch
            }
            statusText.text = getString(R.string.speed_test_running)
            withContext(Dispatchers.IO) {
                try {
                    var success = false
                    for (testUrl in speedTestUrls) {
                        try {
                            val url = java.net.URL(testUrl)
                            val conn = network.openConnection(url) as java.net.HttpURLConnection
                            conn.connectTimeout = 8000
                            conn.readTimeout = 5000
                            conn.instanceFollowRedirects = true
                            var total = 0L
                            var startTime = 0L
                            conn.inputStream.use { input ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r == -1) break
                                    if (startTime == 0L) startTime = System.currentTimeMillis()
                                    total += r
                                    if (startTime != 0L && System.currentTimeMillis() - startTime > 10000) break
                                }
                            }
                            if (startTime == 0L) continue
                            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                            if (elapsedSec > 0.0 && total > 0L) {
                                val mbps = (total * 8.0 / 1024 / 1024) / elapsedSec
                                val result = String.format("%.2f", mbps)
                                withContext(Dispatchers.Main) {
                                    statusText.text = getString(R.string.speed_test_result, result)
                                }
                                success = true
                                break
                            }
                        } catch (e: Exception) {
                        }
                    }
                    if (!success) {
                        withContext(Dispatchers.Main) { statusText.text = getString(R.string.speed_test_failed) }
                    }
                } finally {
                    isSpeedTestRunning = false
                    networkManager.releaseCellularNetwork()
                }
            }
        }
    }
}