package com.tokyoxpa3.androidproxy

import android.os.Bundle
import android.util.Log
import android.widget.*
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Network
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private lateinit var usbTetherIPText: TextView
    private lateinit var cellularIPText: TextView
    private lateinit var portInput: EditText
    private lateinit var authUserInput: EditText
    private lateinit var authPassInput: EditText
    private lateinit var mainButton: Button
    private lateinit var selfTestButton: Button
    private lateinit var copyDiagnosticsButton: Button
    private lateinit var selfTestResultText: TextView
    
    private var isRunning = false
    private var pendingNotificationPermission = false
    private val REQUEST_NOTIFICATION_PERMISSION = 1002
    private val networkManager by lazy { CellularNetworkManager(this) }
    private val ipChecker by lazy { PublicIPChecker() }
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 同步 Service 運行狀態
        isRunning = Socks5ProxyService.isServiceRunning

        setupUI()
        
        // 訂閱 Service 狀態變更，UI 與真實狀態同步
        Socks5ProxyService.onStatusChanged = { status ->
            activityScope.launch { onProxyStatusChanged(status) }
        }
        
        // 檢查上次退出原因
        checkLastExitReason()
        
        // 檢查是否有崩潰日誌紀錄
        val sp = getSharedPreferences("debug_log", Context.MODE_PRIVATE)
        val detailedLog = sp.getString("last_java_crash", null)

        if (detailedLog != null) {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.debug_crash_log_title))
                .setMessage(detailedLog)
                .setPositiveButton(getString(R.string.btn_copy_and_close)) { _, _ ->
                    // 複製到剪貼簿方便你傳給我
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Crash Log", detailedLog)
                    clipboard.setPrimaryClip(clip)
                    
                    // 清除紀錄避免重複顯示
                    sp.edit().remove("last_java_crash").apply()
                    Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // 2. 根據狀態更新 UI（已停止時保留「上次退出原因」的文字，不覆寫）
        if (Socks5ProxyService.currentStatus != Socks5ProxyService.ProxyStatus.STOPPED) {
            onProxyStatusChanged(Socks5ProxyService.currentStatus)
        }
        
        updateNetworkStatus()
    }

    override fun onDestroy() {
        Socks5ProxyService.onStatusChanged = null
        activityScope.cancel()
        super.onDestroy()
    }

    private fun onProxyStatusChanged(status: Socks5ProxyService.ProxyStatus) {
        val stopText = getString(R.string.btn_stop_proxy)
        val startText = getString(R.string.btn_start_proxy)
        when (status) {
            Socks5ProxyService.ProxyStatus.STARTING -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(0xFFDC3545.toInt())
                statusText.text = getString(R.string.status_starting)
                statusText.setTextColor(0xFF6C757D.toInt())
            }
            Socks5ProxyService.ProxyStatus.RUNNING -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(0xFFDC3545.toInt())
                statusText.text = getString(R.string.status_proxy_running)
                statusText.setTextColor(0xFF6C757D.toInt())
            }
            Socks5ProxyService.ProxyStatus.RESTARTING -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(0xFFDC3545.toInt())
                statusText.text = getString(R.string.status_restarting)
                statusText.setTextColor(0xFF6C757D.toInt())
            }
            Socks5ProxyService.ProxyStatus.PAUSED -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(0xFFDC3545.toInt())
                statusText.text = getString(R.string.status_waiting_network)
                statusText.setTextColor(0xFF6C757D.toInt())
            }
            Socks5ProxyService.ProxyStatus.STOPPED -> {
                isRunning = false
                mainButton.text = startText
                mainButton.background = createButtonDrawable(0xFF6200EE.toInt())
                statusText.text = getString(R.string.status_stopped)
                statusText.setTextColor(0xFF6C757D.toInt())
            }
            Socks5ProxyService.ProxyStatus.FAILED -> {
                isRunning = false
                mainButton.text = startText
                mainButton.background = createButtonDrawable(0xFF6200EE.toInt())
                val error = Socks5ProxyService.lastErrorMessage
                statusText.text = if (error != null) {
                    getString(R.string.status_failed, error)
                } else {
                    getString(R.string.status_failed_generic)
                }
                statusText.setTextColor(android.graphics.Color.RED)
            }
        }
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
            // 高度為 WRAP_CONTENT：外層 ScrollView（setContentView 處）負責填滿與捲動
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                // 還原上次使用的埠號（旋轉/重啟 Activity 不會退回預設 1080）
                setText(getSharedPreferences("proxy_config", Context.MODE_PRIVATE).getString("proxy_port", "1080") ?: "1080")
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

        // 埠號即時持久化（比照帳密欄位的 TextWatcher 模式），
        // 旋轉螢幕或 Activity 重建後不會靜默改回 1080
        portInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
                        .edit().putString("proxy_port", value).apply()
                }
            }
        })

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
            usbTetherIPText = TextView(context).apply {
                text = getString(R.string.usb_tether_ip_fetching)
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
            addView(usbTetherIPText)
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

        // Diagnostics Card（自我檢測 + 複製診斷報告）
        val diagCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }

            selfTestButton = Button(context).apply {
                text = getString(R.string.btn_self_test)
                textSize = 16f
                setTextColor(Color.WHITE)
                background = createButtonDrawable(0xFF17A2B8.toInt())
                setPadding(0, 24, 0, 24)
                setOnClickListener { runSelfTest() }
            }
            addView(selfTestButton)

            copyDiagnosticsButton = Button(context).apply {
                text = getString(R.string.btn_copy_diagnostics)
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
                    topMargin = 16
                }
                setOnClickListener { copyDiagnostics() }
            }
            addView(copyDiagnosticsButton)

            selfTestResultText = TextView(context).apply {
                textSize = 14f
                setTextColor(0xFF495057.toInt())
                setPadding(0, 16, 0, 0)
                visibility = View.GONE
            }
            addView(selfTestResultText)
        }
        rootLayout.addView(diagCard)
        rootLayout.addView(refreshButton)

        // 包 ScrollView：橫向或小螢幕時內容高度超過可視區域，
        // 沒有捲動能力時 mainButton 會落在折疊線之下完全無法點擊
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFF8F9FA.toInt())
        }
        scrollView.addView(rootLayout)
        setContentView(scrollView)
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
            // 未加入電池白名單：說明風險，並允許使用者「仍然繼續」
            PowerPermissionHelper.showOptimizationDialog(this) {
                Toast.makeText(this, getString(R.string.toast_battery_warning), Toast.LENGTH_LONG).show()
                continueStartProxyFlow()
            }
            return
        }
        continueStartProxyFlow()
    }

    private fun continueStartProxyFlow() {
        // Android 13+：要求通知權限，否則前景服務通知不會顯示
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationPermission = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            return
        }
        startProxyInternal()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION && pendingNotificationPermission) {
            pendingNotificationPermission = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("DebugActivity", "Notification permission granted")
            } else {
                Toast.makeText(this, getString(R.string.toast_notification_denied), Toast.LENGTH_LONG).show()
            }
            startProxyInternal()
        }
    }

    private fun startProxyInternal() {
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
        // 以 Service 實際回報的狀態更新，不依賴 Activity 本地猜測
        onProxyStatusChanged(Socks5ProxyService.currentStatus)
    }

    private fun runSelfTest() {
        if (!Socks5ProxyService.isServiceRunning) {
            selfTestResultText.text = getString(R.string.self_test_not_running)
            selfTestResultText.setTextColor(0xFFDC3545.toInt())
            selfTestResultText.visibility = View.VISIBLE
            return
        }

        val port = portInput.text.toString().toIntOrNull() ?: 1080
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val authUser = prefs.getString("auth_user", "") ?: ""
        val authPass = prefs.getString("auth_pass", "") ?: ""

        selfTestButton.isEnabled = false
        selfTestButton.text = getString(R.string.self_test_running)
        selfTestResultText.visibility = View.VISIBLE
        selfTestResultText.text = ""

        activityScope.launch {
            val result = SelfTest.run(port, authUser, authPass)
            selfTestButton.isEnabled = true
            selfTestButton.text = getString(R.string.btn_self_test)

            val sb = StringBuilder()
            sb.append(getString(if (result.overallPass) R.string.self_test_pass else R.string.self_test_fail))
                .append("\n\n")
            for (step in result.steps) {
                val label = when (step.kind) {
                    SelfTest.StepKind.CONNECT_SOCKET -> getString(R.string.self_test_step_connect_socket)
                    SelfTest.StepKind.GREETING -> getString(R.string.self_test_step_greeting)
                    SelfTest.StepKind.AUTH -> getString(R.string.self_test_step_auth)
                    SelfTest.StepKind.CONNECT -> getString(R.string.self_test_step_connect)
                    SelfTest.StepKind.DATA -> getString(R.string.self_test_step_data)
                }
                sb.append(if (step.pass) "✅ " else "❌ ").append(label)
                    .append(": ").append(step.detail).append("\n")
            }
            selfTestResultText.text = sb.toString().trimEnd()
            selfTestResultText.setTextColor(if (result.overallPass) 0xFF28A745.toInt() else 0xFFDC3545.toInt())
        }
    }

    private fun copyDiagnostics() {
        val report = buildDiagnosticsReport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Diagnostics", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
    }

    private fun buildDiagnosticsReport(): String {
        val sb = StringBuilder()
        sb.append("===== ").append(getString(R.string.diagnostics_title)).append(" =====\n")
        sb.append("Time: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .append("\n")
        try {
            sb.append("Version: ")
                .append(packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown")
                .append("\n")
        } catch (e: Exception) {
            sb.append("Version: unknown\n")
        }

        val statusName = when (Socks5ProxyService.currentStatus) {
            Socks5ProxyService.ProxyStatus.STARTING -> "STARTING"
            Socks5ProxyService.ProxyStatus.RUNNING -> "RUNNING"
            Socks5ProxyService.ProxyStatus.RESTARTING -> "RESTARTING"
            Socks5ProxyService.ProxyStatus.PAUSED -> "PAUSED"
            Socks5ProxyService.ProxyStatus.STOPPED -> "STOPPED"
            Socks5ProxyService.ProxyStatus.FAILED -> "FAILED"
        }
        sb.append("Status: ").append(statusName).append("\n")
        Socks5ProxyService.lastErrorMessage?.let { sb.append("LastError: ").append(it).append("\n") }

        val port = portInput.text.toString().toIntOrNull() ?: 1080
        sb.append("Port: ").append(port).append("\n")
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val user = prefs.getString("auth_user", "") ?: ""
        val pass = prefs.getString("auth_pass", "") ?: ""
        val authOn = user.isNotEmpty() && pass.isNotEmpty()
        sb.append("Auth: ").append(
            if (authOn) "enabled (user=$user, pass=${mask(pass)})" else "disabled"
        ).append("\n")

        sb.append(wifiIPText.text).append("\n")
        sb.append(hotspotIPText.text).append("\n")
        sb.append(usbTetherIPText.text).append("\n")
        sb.append(cellularIPText.text).append("\n")

        sb.append("NativeStats: ").append(NativeEngine.safeGetStats()).append("\n")

        // 附上滾動落檔的歷史痕跡（release 版可直接看，不需 root / debug 版）
        try {
            val logFile = java.io.File(filesDir, "engine_stats.log")
            if (logFile.exists()) {
                val content = logFile.readText()
                if (content.isNotBlank()) {
                    sb.append("\n----- engine_stats.log (tail) -----\n")
                    sb.append(content.takeLast(4096))
                    sb.append("\n")
                }
            }
        } catch (e: Exception) {
            sb.append("\nengine_stats.log: unreadable (").append(e.message).append(")\n")
        }

        return sb.toString()
    }

    private fun mask(s: String): String =
        if (s.length <= 2) "*" else s.take(1) + "*".repeat(s.length - 2) + s.takeLast(1)

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

            val usbTetherIP = withContext(Dispatchers.IO) {
                HotspotManager.getUsbTetherIP(applicationContext)
            }
            if (usbTetherIP != null) {
                val port = portInput.text.toString().toIntOrNull() ?: 1080
                usbTetherIPText.text = getString(R.string.usb_tether_ip_format, usbTetherIP, port)
            } else {
                usbTetherIPText.text = getString(R.string.usb_tether_ip_disabled)
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


}