package com.tokyoxpa3.androidproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.content.SharedPreferences
import kotlinx.coroutines.*
import java.io.Closeable

class Socks5ProxyService : Service() {

    enum class ProxyStatus { STARTING, RUNNING, PAUSED, RESTARTING, STOPPED, FAILED }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isProxyRunning = false
    @Volatile
    private var isRestarting = false
    @Volatile
    private var proxyPaused = false
    @Volatile
    private var stopRequested = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private val networkManager by lazy { 
        com.tokyoxpa3.androidproxy.network.CellularNetworkManager(this) 
    }
    @Volatile
    private var cellularNetwork: android.net.Network? = null

    private val activeSockets = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    private class DnsEntry(val addresses: List<java.net.InetAddress>, val expiresAt: Long)
    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, DnsEntry>()
    private val dnsCacheLock = Any()
    private val dnsExecutor = java.util.concurrent.ThreadPoolExecutor(
            4, 4,
            0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(64),
            { r -> Thread(r, "socks5-dns").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
        )

    private fun resolveWithCache(network: android.net.Network, host: String): List<java.net.InetAddress> {
        val key = host.lowercase()
        val now = System.currentTimeMillis()
        val hit = dnsCache[key]
        if (hit != null && hit.expiresAt > now) return hit.addresses
        if (dnsCache.size >= 256) dnsCache.clear()
        // 5G 網路劣化時 DNS 可能長時間無回應；加上 2 秒 timeout，
        // 避免 handshake 線程被 DNS 卡死（128 線程全滿時新連線會被直接拒絕）。
        // 注意：不能加 @Synchronized —— 否則 128 個 handshake 線程會在鎖上串行排隊，
        // 每次 DNS 超時 2 秒 × 128 = 最後一個線程要等 ~4 分鐘，等於拒絕服務。
        // dnsCache 是 ConcurrentHashMap 已線程安全，重複解析同一個 host 無害。
        val addresses = try {
            val future = dnsExecutor.submit<List<java.net.InetAddress>> {
                network.getAllByName(host)
                    .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
                    .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            }
            try {
                future.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                future.cancel(true)
            }
        } catch (e: Exception) {
            if (hit != null) return hit.addresses
            emptyList()
        }
        if (addresses.isNotEmpty()) dnsCache[key] = DnsEntry(addresses, now + 5 * 60 * 1000L)
        return addresses
    }
    
    companion object {
        const val TAG = "Socks5ProxyService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "socks5_proxy_channel"
        const val ACTION_START_PROXY = "START_PROXY"
        const val ACTION_STOP_PROXY = "STOP_PROXY"
        const val EXTRA_PORT = "PROXY_PORT"
        @Volatile var isServiceRunning = false

        @Volatile var currentStatus = ProxyStatus.STOPPED
        @Volatile var lastErrorMessage: String? = null
        @Volatile var onStatusChanged: ((ProxyStatus) -> Unit)? = null

        // 允許監聽的 LAN 介面前綴（Wi-Fi / 熱點 / USB 分享 / 乙太網路 / 藍牙 PAN）。
        // 刻意排除所有行動網路介面（rmnet/ccmni/wwan 等），避免代理暴露到 5G 網段。
        private val LAN_IFACE_PREFIXES = listOf(
            "wlan", "wl", "ap", "sap", "swlan", "softap",
            "rndis", "usb", "ncm", "eth", "bnep", "p2p", "up"
        )
    }
    
    override fun onCreate() {
        super.onCreate()
        
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashDetail = getString(
                R.string.crash_detail_format,
                java.util.Date(),
                thread.name,
                throwable.javaClass.simpleName,
                throwable.message,
                throwable.stackTraceToString()
            )
            
            getSharedPreferences("debug_log", MODE_PRIVATE)
                .edit()
                .putString("last_java_crash", crashDetail)
                .commit()

            originalHandler?.uncaughtException(thread, throwable)
        }

        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "5GProxy::WakeLock").apply { acquire() }
        
        NativeEngine.onSocketClosed = { fd -> 
            val socket = activeSockets.remove(fd)
            if (socket is Closeable) {
                try { 
                    socket.close() 
                } catch (e: Exception) {
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 不使用 START_STICKY：系統重啟服務時若帶 null intent（無使用者意圖），
        // 直接停止，避免代理在「使用者已停止」後被悄悄重新啟動。
        if (intent?.action == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent.getIntExtra(EXTRA_PORT, 1080)
        
        if (intent.action == ACTION_STOP_PROXY) {
            stopProxy()
        } else {
            stopRequested = false
            startProxy(port)
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
    
    private fun createNotification(title: String, text: String): Notification {
        val intent = Intent(this, DebugActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID).setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setOngoing(true).build()
    }
    
    private fun startProxy(port: Int) {
        if (stopRequested || isProxyRunning) return
        isServiceRunning = true
        proxyPaused = false
        updateStatus(ProxyStatus.STARTING)
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_proxying), getString(R.string.notification_init_network)), 
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        
        serviceScope.launch {
            try {
                isProxyRunning = true
                val network = withTimeoutOrNull(15000) { networkManager.requestCellularNetwork() }
                if (network == null) { failStop(getString(R.string.error_cellular_unavailable)); return@launch }
                if (stopRequested) { stopSelf(); return@launch }
                cellularNetwork = network
                
                // 監控電信端切換 5G IP / 網路重建事件，偵測到變更時自動恢復代理
                networkManager.startMonitoring(
                    onAvailable = { newNetwork -> handleNetworkChange(port, newNetwork) },
                    onLost = { handleNetworkChange(port, null) }
                )
                
                NativeEngine.socketProvider = { host, p, isUdp -> 
                    createSocketBoundToNetwork(host, p, isUdp) 
                }
                NativeEngine.registerInstance()

                val prefs = getSharedPreferences("proxy_config", android.content.Context.MODE_PRIVATE)
                val authUser = prefs.getString("auth_user", "") ?: ""
                val authPass = prefs.getString("auth_pass", "") ?: ""
                NativeEngine.setSocks5Auth(authUser, authPass)
                
                // 只綁定 LAN 介面（Wi-Fi/熱點/USB 分享），絕不綁到行動網路
                val bindAddrs = collectLanBindAddresses()
                if (bindAddrs.isEmpty()) {
                    failStop(getString(R.string.error_no_lan_interface))
                    return@launch
                }
                Log.i(TAG, "SOCKS5 listener bind addresses: ${bindAddrs.joinToString()}")
                val startResult = NativeEngine.startSocks5Server(port, bindAddrs)
                if (startResult != "Started") {
                    failStop(getString(R.string.error_native_start, startResult))
                    return@launch
                }
                if (stopRequested) { try { NativeEngine.stopSocks5Server() } catch (e: Exception) {}; stopSelf(); return@launch }

                launch {
                    var consecutiveFailures = 0
                    while (isProxyRunning) {
                        delay(15000)
                        if (!isProxyRunning) break
                        val currentNetwork = cellularNetwork ?: continue
                        if (isNetworkHealthy(currentNetwork)) {
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures++
                            Log.w(TAG, "5G 網路健康檢查失敗 (${consecutiveFailures}/3)，準備自動重建...")
                            if (consecutiveFailures >= 3) {
                                Log.w(TAG, "5G 網路連續異常，自動重建代理連線...")
                                restartProxy(port)
                                break
                            }
                        }
                    }
                }

                launch {
                    while (isProxyRunning && !stopRequested) {
                        delay(10000)
                        if (stopRequested || !isProxyRunning) break
                        if (NativeEngine.isLibraryLoaded() && !isNativeThreadAlive(port) && !isRestarting) {
                             Log.e(TAG, "偵測到 Native 引擎異常停止，嘗試重啟...")
                             restartProxy(port)
                             break
                        }
                    }
                }

                updateStatus(ProxyStatus.RUNNING)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.status_proxy_running), getString(R.string.notification_locked_format, port)))
            } catch (e: Exception) { failStop(e.message) }
        }
    }

    private fun updateStatus(status: ProxyStatus) {
        currentStatus = status
        try { onStatusChanged?.invoke(status) } catch (e: Exception) {}
    }

    private fun failStop(message: String?) {
        Log.e(TAG, "代理啟動失敗: ${message ?: "unknown"}")
        lastErrorMessage = message
        isProxyRunning = false
        proxyPaused = false
        isServiceRunning = false
        updateStatus(ProxyStatus.FAILED)
        try { NativeEngine.stopSocks5Server() } catch (e: Exception) {}
        try { networkManager.releaseCellularNetwork() } catch (e: Exception) {}
        cellularNetwork = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 收集可安全綁定的 LAN 位址：僅限 Wi-Fi / 熱點 / USB 分享等本機區域網路介面，
     * 排除 loopback、link-local 與任何行動網路介面，防止代理暴露在 5G 網段。
     */
    private fun collectLanBindAddresses(): Array<String> {
        val result = linkedSetOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyArray()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (!LAN_IFACE_PREFIXES.any { name.startsWith(it) }) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) continue
                    var ip = addr.hostAddress ?: continue
                    val zone = ip.indexOf('%')
                    if (zone >= 0) ip = ip.substring(0, zone)
                    result.add(ip)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "收集 LAN 綁定位址失敗", e)
        }
        return result.toTypedArray()
    }
    
    /**
     * 電信端切換 5G IP / 行動網路變更時被呼叫。
     * 新網路就緒 → 自動重建代理；網路失效 → 暫停代理等待恢復（不停止服務）。
     */
    private fun handleNetworkChange(port: Int, network: android.net.Network?) {
        if (!isProxyRunning && !proxyPaused) return
        serviceScope.launch {
            if (network != null) {
                if (network == cellularNetwork) return@launch
                Log.w(TAG, "偵測到電信端更換 5G IP / 行動網路，自動重建代理連線...")
                restartProxy(port)
            } else {
                Log.w(TAG, "行動網路已失效 (onLost)，暫停代理並等待網路恢復...")
                pauseProxy()
            }
        }
    }
    
    /**
     * 等同於使用者手動「停止代理 → 一鍵開啟」：重新取得新的行動網路並重建代理，
     * 但不會停止服務與前台通知。
     */
    private fun restartProxy(port: Int) {
        if (stopRequested || isRestarting || (!isProxyRunning && !proxyPaused)) return
        isRestarting = true
        updateStatus(ProxyStatus.RESTARTING)
        serviceScope.launch {
            try {
                Log.w(TAG, "開始重建代理：停止舊代理並釋放舊 socket...")
                isProxyRunning = false
                proxyPaused = false
                try { NativeEngine.stopSocks5Server() } catch (e: Exception) {}
                activeSockets.values.forEach { 
                    if (it is Closeable) try { it.close() } catch (e: Exception) {} 
                }
                activeSockets.clear()
                dnsCache.clear()
                cellularNetwork = null
                
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.notification_restarting), getString(R.string.notification_restarting)))
                
                startProxy(port)
            } finally {
                isRestarting = false
            }
        }
    }
    
    /**
     * 行動網路失效時暫停代理（停止 server 與 socket），但保留服務與網路監控，
     * 等網路恢復（onAvailable）時自動重建。
     */
    private fun pauseProxy() {
        if (!isProxyRunning) return
        isProxyRunning = false
        proxyPaused = true
        try { NativeEngine.stopSocks5Server() } catch (e: Exception) {}
        activeSockets.values.forEach { 
            if (it is Closeable) try { it.close() } catch (e: Exception) {} 
        }
        activeSockets.clear()
        dnsCache.clear()
        cellularNetwork = null
        updateStatus(ProxyStatus.PAUSED)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.notification_waiting_network), getString(R.string.notification_waiting_network)))
    }
    
    private fun isNetworkHealthy(network: android.net.Network): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            network.openConnection(java.net.URL("http://connectivitycheck.gstatic.com/generate_204")).apply {
                connectTimeout = 3000
                readTimeout = 3000
                inputStream.use { it.read() }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun stopProxy() {
        if (!isProxyRunning && !proxyPaused) return
        // 同步設定停止旗標：watchdog / restart 邏輯會立即停止動作，
        // 避免「使用者按下停止」與「自動重啟」競態造成殭屍 listener。
        stopRequested = true
        isProxyRunning = false
        proxyPaused = false
        isServiceRunning = false
        updateStatus(ProxyStatus.STOPPED)
        serviceScope.launch {
            try {
                NativeEngine.stopSocks5Server()
                networkManager.releaseCellularNetwork()
                cellularNetwork = null
                
                activeSockets.values.forEach { 
                    if (it is Closeable) try { it.close() } catch(e: Exception){} 
                }
                activeSockets.clear()
                dnsCache.clear()
                
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) { stopSelf() }
        }
    }
    
    private fun createSocketBoundToNetwork(host: String, port: Int, isUdp: Boolean): Int {
        val network = cellularNetwork ?: return -1
        return try {
            if (isUdp) {
                // 雙棧 UDP socket：IPv4-only 的 DatagramSocket 無法對 IPv6 目標 sendto
                // (EAFNOSUPPORT)，會讓「走代理的 IPv6 UDP/DNS」整段靜默失敗。
                // 綁定 IPv6 ANY (::) 在 Android/Linux 預設即為雙棧 (bindv6only=0)
                val ds = try {
                    java.net.DatagramSocket(
                        java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), 0)
                    )
                } catch (e: Exception) {
                    java.net.DatagramSocket()
                }
                network.bindSocket(ds)
                val pfd = android.os.ParcelFileDescriptor.fromDatagramSocket(ds)
                val fd = pfd.detachFd()
                activeSockets[fd] = ds
                fd
            }
            else {
                val addresses = resolveWithCache(network, host)
                if (addresses.isEmpty()) return -1
                var socket: java.net.Socket? = null
                for (addr in addresses) {
                    val candidate = java.net.Socket()
                    try {
                        candidate.receiveBufferSize = 3 * 1024 * 1024
                        candidate.sendBufferSize = 3 * 1024 * 1024
                        candidate.tcpNoDelay = true
                        network.bindSocket(candidate)
                        val connectTimeout = if (addr is java.net.Inet4Address) 3000 else 1500
                        candidate.connect(java.net.InetSocketAddress(addr, port), connectTimeout)
                        socket = candidate
                        break
                    } catch (e: Exception) {
                        try { candidate.close() } catch (e2: Exception) {}
                    }
                }
                if (socket == null) return -1
                val pfd = android.os.ParcelFileDescriptor.fromSocket(socket)
                val fd = pfd.detachFd()
                activeSockets[fd] = socket
                fd
            }
        } catch (e: Exception) { -1 }
    }

    private fun isNativeThreadAlive(port: Int): Boolean {
        return try {
            java.net.Socket("127.0.0.1", port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        stopRequested = true
        isProxyRunning = false
        proxyPaused = false
        isServiceRunning = false
        updateStatus(ProxyStatus.STOPPED)
        super.onDestroy()
    }
}