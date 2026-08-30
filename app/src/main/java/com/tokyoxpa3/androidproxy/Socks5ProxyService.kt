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

    // DNS 快取 + single-flight 的純協調邏輯抽離到 DnsCache（可單元測試）；
    // 服務層只注入實際的網路查詢。
    private val dnsCache = DnsCache()
    private val dnsExecutor = java.util.concurrent.ThreadPoolExecutor(
            4, 4,
            0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(32),
            { r -> Thread(r, "socks5-dns").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
        )
    // [Happy Eyeballs] 連線競速的並行連線改用共享有界執行緒池，取代每次 CONNECT
    // 對每個解析位址 spawn 一條裸 Thread。大量並發連線下裸 Thread 會無上限產生
    // 短命執行緒（執行緒 churn）；此池上限 16 工作緒 + 128 佇列，池滿時由握手
    // 執行緒自行執行（CallerRunsPolicy），天然背壓且不丟失連線嘗試。
    private val connectExecutor = java.util.concurrent.ThreadPoolExecutor(
            8, 16,
            30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(128),
            { r -> Thread(r, "socks5-connect").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        )

    private fun resolveWithCache(network: android.net.Network, host: String): List<java.net.InetAddress> {
        // [關鍵修復] IP 字面值最優先處理（NetRedirector 等 redirector 只送 IP），
        // 不查 DNS、不進快取 —— 與 v1.5 getAllByName 的字面值行為一致
        IpLiteral.parse(host)?.let { return listOf(it) }
        val key = host.lowercase()
        return dnsCache.lookup(key, System.currentTimeMillis()) {
            doResolve(network, host)
        }
    }

    /** 實際 DNS 查詢（僅在快取未命中時由 single-flight 的 leader 呼叫） */
    private fun doResolve(network: android.net.Network, host: String): List<java.net.InetAddress> {
        // [穩定性修復] DNS 查詢優先走 DnsResolver API（API 29+）：
        // 舊路徑 network.getAllByName() 是不可中斷的阻塞呼叫，行動網路 DNS 劣化時
        // 4 個 socks5-dns 執行緒會全部卡死（future.cancel 無法中斷底層解析），
        // 新查詢只能靠 DiscardPolicy 快速失敗 —— 表現即「代理突然連不上」。
        // DnsResolver 內建逾時 + CancellationSignal 可真正取消，不留卡死執行緒。
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // 逾時/失敗 → 回空清單，交由 DnsCache 負快取 3 秒
            return resolveWithDnsResolver(network, host) ?: emptyList()
        }
        // 5G 網路劣化時 DNS 可能長時間無回應；加上 1 秒 timeout，
        // 避免 handshake 線程被 DNS 卡死（線程池全滿時新連線會被直接拒絕）。
        // 失敗（超時/無結果）也回空清單，交由 DnsCache 負快取 3 秒。
        return try {
            val future = dnsExecutor.submit<List<java.net.InetAddress>> {
                network.getAllByName(host)
                    .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
                    .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            }
            try {
                future.get(1, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                future.cancel(true)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 以 DnsResolver API 解析（API 29+）：系統內建重試/逾時，CancellationSignal
     * 可真正取消底層查詢。回傳 null 代表不支援或查詢逾時（呼叫端自行決定 fallback）。
     */
    private fun resolveWithDnsResolver(network: android.net.Network, host: String): List<java.net.InetAddress>? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val answer = java.util.concurrent.atomic.AtomicReference<List<java.net.InetAddress>>()
        val signal = android.os.CancellationSignal()
        try {
            val callback = object : android.net.DnsResolver.Callback<List<java.net.InetAddress>> {
                override fun onAnswer(res: List<java.net.InetAddress>, rcode: Int) {
                    answer.set(res)
                    latch.countDown()
                }

                override fun onError(error: android.net.DnsResolver.DnsException) {
                    latch.countDown()
                }
            }
            android.net.DnsResolver.getInstance().query(
                network,
                host,
                android.net.DnsResolver.FLAG_NO_RETRY,
                java.util.concurrent.Executor { r -> r.run() }, // callback 只做原子寫入 + countDown，可直接在原執行緒執行
                signal,
                callback
            )
        } catch (e: Exception) {
            return null
        }
        // 最多等 2 秒；逾時就取消底層查詢（與 handshake 可接受延遲相符）
        val done = try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!done) {
            signal.cancel()
            return null
        }
        val res = answer.get() ?: return emptyList()
        return res.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
            .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
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
        
        // [根因修復 v2 2026-08-23] fromSocket()+detachFd() 是「dup」語意：同一個
        // socket 描述存在兩個 fd —— 原生（Socket 內部持有）與交給 C 的副本。
        // 實測只關任一邊都會讓描述存活成 CLOSE_WAIT 幽靈（留在 epoll 永久就緒，
        // level-triggered 事件風暴 = 過去 SIGSEGV 的源頭）。
        // 因此 C 端 finalize 會 close 自己的副本後呼叫本 callback，
        // 這裡負責收掉原生引用（socket.close()）並清理 map。
        NativeEngine.onSocketClosed = { fd ->
            val socket = activeSockets.remove(fd)
            android.util.Log.d("FdAudit", "released fd=$fd hadEntry=${socket != null} map=${activeSockets.size}")
            if (socket is Closeable) {
                try { socket.close() } catch (e: Exception) {}
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
                // 啟動協程開始執行時可能已收到停止指令（stopProxy 的旗標先於本協程設定），
                // 立即退出，避免把 UI 已顯示「已停止」的狀態又翻回運行中
                if (stopRequested) { stopSelf(); return@launch }
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
                        if (NativeEngine.isLibraryLoaded() && !isNativeThreadAlive() && !isRestarting) {
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
        stopNativeEngineSafely()
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
                stopNativeEngineSafely()
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
        stopNativeEngineSafely()
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
    
    /**
     * 序列化 native 引擎的停止：stopProxy 的協程（IO）、pauseProxy/restartProxy
     * 與 onDestroy（主執行緒）可能同時呼叫，而 native_stop_socks5_server 本身
     * 非執行緒安全（兩個執行緒同時通過 running 檢查會對同一 pthread double join）。
     */
    @Synchronized
    private fun stopNativeEngineSafely() {
        try {
            NativeEngine.stopSocks5Server()
        } catch (e: Exception) {
            Log.e(TAG, "stopSocks5Server failed", e)
        }
    }

    private fun stopProxy() {
        // 停止旗標必須在所有 guard 之前設定：isProxyRunning 要等啟動協程實際
        // 開始執行才會為 true，若停止指令落在這個視窗內，早退 guard 會吞掉
        // 停止意圖，代理在使用者按下停止後照常啟動。startProxy 的各個檢查點
        // 會讀取此旗標自行退出。
        stopRequested = true
        if (!isProxyRunning && !proxyPaused) {
            // 服務可能是被本 STOP intent 建立的（先前早已停止）：直接收掉，
            // 否則 onCreate 拿走的 wakelock 與服務本身永遠不會釋放
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        isProxyRunning = false
        proxyPaused = false
        isServiceRunning = false
        updateStatus(ProxyStatus.STOPPED)
        serviceScope.launch {
            try {
                stopNativeEngineSafely()
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
                // [穩定性修復] 並行競速連線（Happy Eyeballs）：
                // 行動網路不穩時，舊版「逐個位址序列嘗試」會把逾時疊加
                // （例：2×3000ms IPv4 + 2×1500ms IPv6 = 9 秒，最終全部失敗 → REP=4），
                // 且弱訊號下單一位址的 SYN 容易丟失。改為同時對所有位址發起連線，
                // 最先成功者勝出、其餘立即關閉 —— 最壞延遲從「總和」變成「單次逾時」，
                // 成功率大幅提升。
                val winner = java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
                val loserSockets = java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket>()
                val latch = java.util.concurrent.CountDownLatch(1)
                // [根因修復 2026-08-23] abandoned：主執行緒逾時放棄後，稍晚才連上的
                // 「遲到勝者」必須自行關閉，否則成為無人持有的洩漏 socket
                // （電信商 IPv4 黑洞期間每次逾時都會製造一個，長時間運行下
                //   FDSize 衝上 16384 的元兇之一）
                val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
                val connectTimeout = 5000L
                val overallDeadline = connectTimeout + 500L

                for (addr in addresses) {
                    connectExecutor.execute {
                        HappyEyeballs.attempt(winner, abandoned, latch,
                            connect = {
                                val candidate = java.net.Socket()
                                try {
                                    candidate.receiveBufferSize = 3 * 1024 * 1024
                                    candidate.sendBufferSize = 3 * 1024 * 1024
                                    candidate.tcpNoDelay = true
                                    network.bindSocket(candidate)
                                    candidate.connect(java.net.InetSocketAddress(addr, port), connectTimeout.toInt())
                                    candidate
                                } catch (e: Exception) {
                                    try { candidate.close() } catch (e2: Exception) {}
                                    loserSockets.add(candidate)
                                    null
                                }
                            },
                            close = { s -> try { s.close() } catch (e: Exception) {} }
                        )
                    }
                }

                val got = try {
                    latch.await(overallDeadline, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    false
                }
                val socket = winner.get()
                if (!got || socket == null) {
                    // 全部失敗/逾時：標記放棄後短暫等待，收回「恰在旗標生效前
                    // 贏得 CAS 的遲到勝者」（否則它成為無人持有的洩漏 socket）
                    abandoned.set(true)
                    Thread.sleep(50)
                    winner.getAndSet(null)?.let {
                        try { it.close() } catch (e: Exception) {}
                        android.util.Log.i("FdAudit", "late winner closed")
                    }
                    return -1
                }
                val pfd = android.os.ParcelFileDescriptor.fromSocket(socket)
                val fd = pfd.detachFd()
                activeSockets[fd] = socket
                android.util.Log.d("FdAudit", "created fd=$fd map=${activeSockets.size}")
                fd
            }
        } catch (e: Exception) {
            android.util.Log.e("FdAudit", "createSocket exception: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    private fun isNativeThreadAlive(): Boolean {
        // [item6] 直接讀 C 層 atomic 旗標，取代每 10 秒開一條真實 TCP 連線
        // （舊做法每次都會 spawn 一條 handshake 執行緒，造成無謂開銷）
        return try {
            NativeEngine.isSocks5ServerRunning()
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        stopRequested = true
        isProxyRunning = false
        proxyPaused = false
        isServiceRunning = false
        // 兜底清理：服務可能未經 ACTION_STOP_PROXY 就被銷毀（系統回收等），
        // 之後 serviceScope 已取消、stopProxy 的清理協程不會再執行，
        // 必須在此同步釋放 native 引擎與所有資源，否則殭屍 listener 會持續佔用埠號。
        // 引擎停止後才可清空 onSocketClosed —— 排空期間 native 執行緒仍需靠它關 fd。
        stopNativeEngineSafely()
        NativeEngine.socketProvider = null
        NativeEngine.onSocketClosed = null
        activeSockets.values.forEach {
            if (it is Closeable) try { it.close() } catch (e: Exception) {}
        }
        activeSockets.clear()
        try { networkManager.releaseCellularNetwork() } catch (e: Exception) {}
        cellularNetwork = null
        updateStatus(ProxyStatus.STOPPED)
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}