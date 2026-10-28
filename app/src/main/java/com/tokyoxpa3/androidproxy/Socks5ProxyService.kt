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
import com.tokyoxpa3.androidproxy.network.NetworkHealthClassifier
import com.tokyoxpa3.androidproxy.network.NetworkHealthStatus
import com.tokyoxpa3.androidproxy.network.VpnDetector
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
    // 開機自動啟動剩餘的重試次數；使用者手動啟動 / 停止都會歸零
    @Volatile private var autoStartRetriesLeft = 0
    @Volatile private var autoStartPort = 1080
    // [啟動競態] startProxy 的引擎啟動是非同步的；在它完成前擋下任何重建請求，
    // 否則「引擎好像掛了」的偵測會在啟動空窗期再觸發一次重建。
    @Volatile
    private var engineStarting = false
    // [協程生命週期] 每次 startProxy 的三個背景迴圈（健康檢查/watchdog/stats）綁在
    // 此 Job 上；重建或停止前先取消舊的，避免舊迴圈因 isProxyRunning 快速 false→true
    // 而漏看、與新迴圈並存累積。
    @Volatile
    private var loopsJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private val networkManager by lazy { 
        com.tokyoxpa3.androidproxy.network.CellularNetworkManager(this) 
    }
    // 驗證 Network 是否仍有效（避免拿已失效的 Network 去 bindSocket → EPERM）
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    }
    @Volatile
    private var cellularNetwork: android.net.Network? = null
    // [LAN 監聽重建] 最後一次送給 native 引擎的 LAN 綁定位址集合。用來判斷介面位址
    // 是否真的變了 —— 位址沒變時不重送，避免無謂地動 listener（同一位址的介面
    // 「關掉再開」由 native 每秒對帳自行修復，不需要 Java 插手）。
    @Volatile
    private var lanBindAddrs: Set<String>? = null

    private val activeSockets = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    // [FdAudit 降噪] 逐連線的 created/released 日誌只在 debug 版輸出；release 版只保留
    // 異常訊號（released untracked / late winner）。以 FLAG_DEBUGGABLE 判斷，
    // 不依賴 BuildConfig，確保 F-Droid 以自身工具鏈重編時行為一致。
    private val isDebuggable by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

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
    // [DNS 背壓] 並發解析上限：API 29+ 的 DnsResolver 路徑會在呼叫它的握手執行緒上
    // 以 latch.await 阻塞最多 2 秒，且不受 dnsExecutor（4 執行緒）節流。行動網路 DNS
    // 劣化時若不設限，64 個握手執行緒會全數卡在 DNS 等待，accept 進來的新連線只能排隊。
    // 超過上限的解析直接失敗（回空 → DnsCache 負快取 → REP=host unreachable），把
    // 「代理整段卡死」收斂成「超額連線快速失敗」，與 dnsExecutor 的 DiscardPolicy 精神
    // 一致。16 對個人 5G 分享的短暫並發綽綽有餘，又把劣化時卡住的執行緒壓在握手池之下。
    private val dnsConcurrency = java.util.concurrent.Semaphore(16)
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
    // [Happy Eyeballs] RFC 8305 錯開連線的延後排程器：次要家族（IPv4）延後啟動。
    // 單執行緒 + daemon，僅在延後時間點把 IPv4 連線嘗試丟回 connectExecutor。
    private val happyEyeballsScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "socks5-happy-eyeballs").apply { isDaemon = true }
    }
    // [偵測落檔] 生命週期計數器滾動落檔：把異常信號（acquired/released/stale_skip/
    // bad_slot/purged）以緩慢節奏寫進檔案，取代揮發性 logcat——release 版也有、
    // 重開機不消失，平常不用管，出問題時翻檔案即有痕跡。
    private val engineStatsFile by lazy { java.io.File(filesDir, "engine_stats.log") }
    private val statsTimeFormat = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

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
        // [DNS 背壓] 先搶並發額度：額度滿代表 DNS 正在劣化（多個 leader 各自等滿逾時），
        // 此時與其讓這個握手執行緒再卡 2 秒，不如立刻失敗 → 負快取 → REP=host unreachable。
        if (!dnsConcurrency.tryAcquire()) {
            return emptyList()
        }
        try {
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
                    HappyEyeballs.orderIpv6First(
                        network.getAllByName(host)
                            .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
                    ) { it is java.net.Inet6Address }
                }
                try {
                    future.get(1, java.util.concurrent.TimeUnit.SECONDS)
                } finally {
                    future.cancel(true)
                }
            } catch (e: Exception) {
                emptyList()
            }
        } finally {
            dnsConcurrency.release()
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
        return HappyEyeballs.orderIpv6First(
            res.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
        ) { it is java.net.Inet6Address }
    }
    
    companion object {
        const val TAG = "Socks5ProxyService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "socks5_proxy_channel"
        const val ACTION_START_PROXY = "START_PROXY"
        const val ACTION_STOP_PROXY = "STOP_PROXY"
        const val EXTRA_PORT = "PROXY_PORT"
        // [偵測落檔] 生命週期計數器滾動落檔上限（超過則砍半保留最新）
        private const val STATS_LOG_MAX_BYTES = 64L * 1024
        private const val STATS_LOG_KEEP_BYTES = 32L * 1024

        // [socket 緩衝] 出向 socket 的 SO_RCVBUF/SO_SNDBUF 目標值。
        // 實測（小米 Pad Mini / Android 15）：net.core.rmem_max = wmem_max = 8 MB，
        // 但 rmem_default = wmem_default 只有 224 KB —— UDP relay 的 worker 一次只
        // 讀一個 datagram 再寫給 client，QUIC 的突發（例如 Turnstile 載入挑戰頁時
        // 一次數十個 datagram）很容易在 kernel 佇列就被丟掉，接著就是重傳與卡頓。
        // 3 MB 與 TCP 出口一致，且遠低於 8 MB 上限（kernel 會把值加倍後套用）。
        private const val SOCKET_BUFFER_BYTES = 3 * 1024 * 1024

        // [開發者開關] 逐行 UDP session 拆除 log 的偏好設定鍵（存於 "proxy_config"）。
        // 預設 false：一般使用者不需要逐行明細，開了只會把 logcat 沖掉。拆除的
        // 「分類計數」不受此開關影響，永遠落檔 —— 所以預設也不會回到完全靜默。
        const val PREF_UDP_VERBOSE_LOG = "udp_verbose_log"

        // [開機自動啟動] 兩個鍵都存在 "proxy_config"。
        // PREF_AUTO_START：使用者開關（預設開）。PREF_WAS_RUNNING：使用者最後的意圖 ——
        // 收到 START 設 true、收到 STOP 設 false；失敗或被系統殺掉不改它，
        // 所以「使用者按過停止」的代理開機後不會被悄悄拉起來。
        const val PREF_AUTO_START = "auto_start_on_boot"
        const val PREF_WAS_RUNNING = "was_running"
        // 由 BootReceiver 發出的啟動帶此 extra：開機初期蜂巢式 / Wi-Fi 常常還沒就緒，
        // 這種啟動失敗時改為延遲重試，而不是直接 FAILED 收掉。
        const val EXTRA_AUTO_START = "AUTO_START"
        private const val AUTO_START_RETRIES = 5
        private const val AUTO_START_RETRY_DELAY_MS = 15_000L
        @Volatile var isServiceRunning = false

        @Volatile var currentStatus = ProxyStatus.STOPPED
        @Volatile var lastErrorMessage: String? = null
        // [診斷] native 生命週期計數器每次重建都歸零，看不出跨重建的歷史。
        // 這兩個值補上該脈絡，讓使用者複製的報告能直接看出「代理一直在重啟」。
        @Volatile var restartCount = 0
        @Volatile var lastHealthCheck: String? = null
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
            if (socket == null) {
                // 異常訊號：釋放一個不在 map 的 fd（late winner / 雙關的洩漏徵兆），
                // 無論 debug/release 都保留，供現場洩漏追蹤。
                android.util.Log.w("FdAudit", "released untracked fd=$fd map=${activeSockets.size}")
            } else if (isDebuggable) {
                android.util.Log.d("FdAudit", "released fd=$fd map=${activeSockets.size}")
            }
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
        
        val prefs = getSharedPreferences("proxy_config", MODE_PRIVATE)
        if (intent.action == ACTION_STOP_PROXY) {
            autoStartRetriesLeft = 0
            prefs.edit().putBoolean(PREF_WAS_RUNNING, false).apply()
            stopProxy()
        } else {
            prefs.edit().putBoolean(PREF_WAS_RUNNING, true).apply()
            autoStartRetriesLeft = if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) AUTO_START_RETRIES else 0
            autoStartPort = port
            stopRequested = false
            // 使用者主動啟動 = 新的一輪，重建次數與健康檢查結果歸零
            // （restartProxy 走的是內部路徑，不會經過這裡）
            restartCount = 0
            lastHealthCheck = null
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
        // 上一輪的失敗訊息不得殘留，否則診斷報告會同時出現 RUNNING 與舊的 LastError
        lastErrorMessage = null
        isServiceRunning = true
        proxyPaused = false
        engineStarting = true
        updateStatus(ProxyStatus.STARTING)
        // startForeground(id, notification, type) 的三參數重載是 API 29 才加入；
        // minSdk 26 的裝置（Android 8/9）呼叫會 NoSuchMethodError 讓服務崩潰。
        val startNotif = createNotification(getString(R.string.notification_proxying), getString(R.string.notification_init_network))
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, startNotif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, startNotif)
        }
        
        serviceScope.launch {
            try {
                // 啟動協程開始執行時可能已收到停止指令（stopProxy 的旗標先於本協程設定），
                // 立即退出，避免把 UI 已顯示「已停止」的狀態又翻回運行中
                if (stopRequested) { stopSelf(); return@launch }

                isProxyRunning = true
                // [重建迴圈修復] 先開始長期請求並監控：startMonitoring 用 requestNetwork
                // 把蜂巢式 PDN 在服務期間 pin 住，避免「一次性 request 拿到就放掉」造成
                // PDN 被系統收回 → onLost → 重建的自激迴圈。設定後再等目前可用的網路。
                networkManager.startMonitoring(
                    onAvailable = { newNetwork -> handleNetworkChange(port, newNetwork) },
                    onLost = { handleNetworkChange(port, null) }
                )
                networkManager.awaitNetwork(15000)
                if (stopRequested) { stopSelf(); return@launch }
                val network = networkManager.currentNetwork()
                if (network == null) { failStop(getString(R.string.error_cellular_unavailable)); return@launch }
                cellularNetwork = network

                // [VPN 判定改為實測] 舊版只要「系統裡存在任何 VPN 網路」就拒絕啟動。
                // 但 VPN 存在 ≠ 它接管了本 App 的路由：per-app VPN（split tunneling）
                // 未包含本 App 時，蜂巢式出口綁定完全可用，拿「存在」當前提會誤殺可用的設定。
                // 改成直接做那個真正會失敗的動作 —— bindSocket —— 失敗才拒絕；
                // 「有沒有 VPN」只用來決定把原因歸給誰（純措辭，不影響是否啟動）。
                if (!canBindCellularEgress(network)) {
                    failStop(
                        if (VpnDetector.isVpnActive(connectivityManager)) {
                            getString(R.string.error_vpn_running)
                        } else {
                            getString(R.string.error_cellular_bind_denied)
                        }
                    )
                    return@launch
                }
                
                NativeEngine.socketProvider = { host, p, isUdp -> 
                    createSocketBoundToNetwork(host, p, isUdp) 
                }
                // [UDP 網域 frame] UDP relay 收到 ATYP=0x03（5G-Proxy-Client 開 Remote DNS
                // 時送的就是這種）需要「以蜂巢式網路」解析。直接重用 CONNECT 路徑同一套
                // resolveWithCache：IP 字面值快路徑、共用快取、DnsResolver 逾時、併發背壓。
                // 不走系統 getaddrinfo —— 那會用預設網路，在 Pro 手機上可能繞回 VPN。
                // 快取命中是常態（同一網域多半已由 TCP CONNECT 解析過）；回 null 則該 frame 丟棄。
                NativeEngine.hostResolver = { host ->
                    val net = cellularNetwork
                    if (net == null) null else resolveWithCache(net, host).firstOrNull()?.hostAddress
                }
                NativeEngine.registerInstance()

                val prefs = getSharedPreferences("proxy_config", android.content.Context.MODE_PRIVATE)
                val authUser = prefs.getString("auth_user", "") ?: ""
                val authPass = prefs.getString("auth_pass", "") ?: ""
                NativeEngine.setSocks5Auth(authUser, authPass)
                // [開發者開關] 每次啟動都重新套用一次：native 端的旗標是 process 全域，
                // 但服務可能被系統回收後重建，不能假設它還留著。
                NativeEngine.safeSetUdpVerboseLog(prefs.getBoolean(PREF_UDP_VERBOSE_LOG, false))
                
                // 只綁定 LAN 介面（Wi-Fi/熱點/USB 分享），絕不綁到行動網路
                val bindAddrs = collectLanBindAddresses()
                if (bindAddrs.isEmpty()) {
                    failStop(getString(R.string.error_no_lan_interface))
                    return@launch
                }
                Log.i(TAG, "SOCKS5 listener bind addresses: ${bindAddrs.joinToString()}")
                lanBindAddrs = bindAddrs.toSet()
                val startResult = NativeEngine.startSocks5Server(port, bindAddrs)
                if (startResult != "Started") {
                    failStop(getString(R.string.error_native_start, startResult))
                    return@launch
                }
                if (stopRequested) { try { NativeEngine.stopSocks5Server() } catch (e: Exception) {}; stopSelf(); return@launch }

                // [協程生命週期] 三個背景迴圈綁在同一個 Job；每次啟動先取消舊的。
                // 否則 restart 時舊迴圈只檢查 isProxyRunning，而它在 restartProxy 設
                // false 後幾乎立刻被 startProxy 設回 true，舊迴圈看不到 false 就永遠
                // 活著——每重建一次累積一組 watchdog / stats 迴圈。
                loopsJob?.cancel()
                loopsJob = serviceScope.launch {
                    // [暫停] 15 秒一次的健康檢查（閒置時打 generate_204）會走蜂巢式、吃掉流量
                    // （閒置一整天約 7 MB）。先整段停用；網路變動仍由 startMonitoring 的
                    // onAvailable / onLost 回呼處理。之後改成「先看 VALIDATED、失效才探測」再開回來。
                    lastHealthCheck = "DISABLED"
//                    launch {
//                        var consecutiveFailures = 0
//                        var lastRxBytes = NativeEngine.safeGetTrafficBytes()?.getOrNull(1) ?: 0L
//                        while (isProxyRunning) {
//                            delay(15000)
//                            if (!isProxyRunning) break
//                            // [滿載誤判] 只要 target 端仍有位元組在進來，線路顯然是通的。
//                            // 滿載時 generate_204 探測容易逾時；若照舊連續 3 次失敗就重建，
//                            // 會把正在下載的連線整批切斷。改以實際流量為主要健康依據。
//                            val rxBytes = NativeEngine.safeGetTrafficBytes()?.getOrNull(1) ?: lastRxBytes
//                            val trafficFlowing = rxBytes > lastRxBytes
//                            lastRxBytes = rxBytes
//                            if (trafficFlowing) {
//                                consecutiveFailures = 0
//                                lastHealthCheck = "OK (traffic)"
//                                continue
//                            }
//                            val currentNetwork = cellularNetwork
//                            if (currentNetwork == null) {
//                                lastHealthCheck = "WAITING_NETWORK"
//                                continue
//                            }
//                            when (checkNetworkHealth(currentNetwork)) {
//                                NetworkHealthStatus.OK -> {
//                                    consecutiveFailures = 0
//                                    lastHealthCheck = "OK"
//                                }
//                                NetworkHealthStatus.BLOCKED_BY_VPN -> {
//                                    // 被 VPN 阻擋（設定問題：本 App 被納入 VPN 隧道）：切勿觸發 restartProxy！
//                                    // 重啟無法解決設定問題，且會陷入無限重啟迴圈並切斷所有既有連線。
//                                    // 註：能走到這一支，代表 bind 探測失敗且蜂巢式仍被系統驗證可用
//                                    // （VALIDATED）—— 這是 NetworkHealthClassifier 判定「真的被擋」
//                                    // 的完整條件；缺任何一項都會落到 NETWORK_DEAD 走重建。
//                                    consecutiveFailures = 0
//                                    lastHealthCheck = "BLOCKED_BY_VPN"
//                                    Log.w(TAG, "健康檢查判定為被 VPN 阻擋（蜂巢式出口 bind 被拒且網路仍有效）；跳過自動重啟，請將本 App 排除在 VPN 之外")
//                                    val nm = getSystemService(NotificationManager::class.java)
//                                    nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.status_proxy_running), getString(R.string.notification_vpn_warning)))
//                                }
//                                NetworkHealthStatus.NETWORK_DEAD -> {
//                                    consecutiveFailures++
//                                    lastHealthCheck = "FAILED (${consecutiveFailures}/3)"
//                                    Log.w(TAG, "5G 網路健康檢查失敗 (${consecutiveFailures}/3)，準備自動重建...")
//                                    if (consecutiveFailures >= 3) {
//                                        Log.w(TAG, "5G 網路連續異常，自動重建代理連線...")
//                                        restartProxy(port)
//                                        break
//                                    }
//                                }
//                            }
//                        }
//                    }

                    launch {
                        while (isProxyRunning && !stopRequested) {
                            delay(10000)
                            if (stopRequested || !isProxyRunning) break
                            if (NativeEngine.isLibraryLoaded() && !isNativeThreadAlive() && !isRestarting && !engineStarting) {
                                Log.e(TAG, "偵測到 Native 引擎異常停止，嘗試重啟...")
                                restartProxy(port)
                                break
                            }
                        }
                    }

                    // [LAN 監聽重建] Wi-Fi／熱點／USB 分享的介面位址可能在服務運行中變動。
                    //
                    // 舊版 listener 只在啟動時綁一次、之後永不重建，而實測（小米 Pad Mini /
                    // Android 15）：Wi-Fi 關閉時核心會直接把綁在 wlan0 位址上的 listener
                    // 關掉（/proc/net/tcp 只剩 loopback），Wi-Fi 開回來後即使 IPv4 完全沒變
                    // 也依然是空的 —— 代理顯示「運行中」卻連不上，只能手動重啟服務。
                    // 行動網路關/開之所以沒事，是因為那條路徑本來就有 onLost/onAvailable
                    // 處理，LAN 介面則從來沒有人管。
                    //
                    // native 端現在每秒把實際監聽集合對齊 desired 集合，所以「同一位址的
                    // 介面回來」會自行修復；這裡只負責在「位址本身變了」時把新集合送進去。
                    // 刻意用輪詢而非 ConnectivityManager 的 NetworkCallback：熱點分享與
                    // USB 分享介面不是 ConnectivityManager 意義下的 Network，拿不到回呼，
                    // 輪詢才能一體涵蓋三種分享方式。
                    launch {
                        while (isProxyRunning && !stopRequested) {
                            delay(5000)
                            if (stopRequested || !isProxyRunning) break
                            refreshLanBindAddrs("watchdog")
                        }
                    }

                    // [偵測落檔] 每 5 分鐘把生命週期計數器 snapshot 落檔（release 版也有）
                    launch {
                        while (isProxyRunning && !stopRequested) {
                            delay(5 * 60 * 1000L)
                            if (stopRequested || !isProxyRunning) break
                            appendEngineStatsToFile()
                        }
                    }
                }

                updateStatus(ProxyStatus.RUNNING)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.status_proxy_running), getString(R.string.notification_locked_format, port)))
            } catch (e: Exception) { failStop(e.message) } finally { engineStarting = false }
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
        loopsJob?.cancel(); loopsJob = null
        isServiceRunning = false
        updateStatus(ProxyStatus.FAILED)
        stopNativeEngineSafely()
        try { networkManager.releaseCellularNetwork() } catch (e: Exception) {}
        cellularNetwork = null
        lanBindAddrs = null
        if (autoStartRetriesLeft > 0 && !stopRequested) {
            // 開機自動啟動：保留前景服務與 wakelock，稍後重來（網路多半只是還沒起來）
            autoStartRetriesLeft--
            Log.w(TAG, "開機自動啟動失敗，${AUTO_START_RETRY_DELAY_MS / 1000}s 後重試（剩 $autoStartRetriesLeft 次）")
            serviceScope.launch {
                delay(AUTO_START_RETRY_DELAY_MS)
                if (!stopRequested) startProxy(autoStartPort)
            }
            return
        }
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
     * [LAN 監聽重建] 重新收集 LAN 綁定位址；只有「集合真的變了」才送給 native 引擎。
     *
     * 位址集合為空時刻意不動：介面剛斷、或 Wi-Fi 剛連上但 DHCP 還沒配好位址時都可能
     * 收不到任何位址，此時清空 desired 集合只會把還活著的 listener 一起拆掉。保留舊
     * 集合即可 —— 位址不存在時 native 的每秒對帳綁不上，位址一回來就自動綁上。
     *
     * 全程不重建代理：listener 由 native 端就地增減，既有連線不受影響。
     */
    private fun refreshLanBindAddrs(reason: String) {
        if (!isProxyRunning || engineStarting || isRestarting) return
        val addrs = collectLanBindAddresses()
        if (addrs.isEmpty()) return
        val current = addrs.toSet()
        if (current == lanBindAddrs) return
        Log.w(TAG, "LAN 綁定位址變更（$reason）：${lanBindAddrs ?: emptySet()} → $current，就地更新監聽")
        lanBindAddrs = current
        NativeEngine.safeUpdateBindAddrs(addrs)
    }

    /**
     * 電信端行動網路變更（換 5G IP、PDN 重建、暫時失效…）。
     *
     * 只切換「新連線」要綁定的 Network，**不重建代理**：listener 綁的是 Wi-Fi/熱點等
     * LAN 位址，與蜂巢式無關；既有 target socket 綁在舊網路上，會由 worker 自然偵測
     * 錯誤並回收，新連線改用新 Network 即可。舊設計每個 onLost/onAvailable 都整台拆掉
     * 重建（join 192 條握手執行緒 + 關閉所有連線），在電信端換 IP 或高負載 PDN 重建時
     * 形成重建迴圈，把正在下載的連線（實測 163 條）整批切斷。
     */
    private fun handleNetworkChange(port: Int, network: android.net.Network?) {
        if (!isProxyRunning) return
        if (network != null) {
            if (network == cellularNetwork) return
            Log.w(TAG, "行動網路已更新，切換新連線使用的網路（不重建代理）")
            cellularNetwork = network
            if (currentStatus == ProxyStatus.PAUSED) {
                serviceScope.launch {
                    updateStatus(ProxyStatus.RUNNING)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.status_proxy_running), getString(R.string.notification_locked_format, port)))
                }
            }
        } else {
            if (cellularNetwork == null) return
            Log.w(TAG, "行動網路暫時失效，等待恢復（不重建代理）")
            cellularNetwork = null
            serviceScope.launch {
                updateStatus(ProxyStatus.PAUSED)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.notification_waiting_network), getString(R.string.notification_waiting_network)))
            }
        }
    }
    
    /**
     * 等同於使用者手動「停止代理 → 一鍵開啟」：重新取得新的行動網路並重建代理，
     * 但不會停止服務與前台通知。
     */
    private fun restartProxy(port: Int) {
        if (stopRequested || isRestarting || engineStarting || !isProxyRunning) return
        isRestarting = true
        restartCount++
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
                lanBindAddrs = null
                
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.notification_restarting), getString(R.string.notification_restarting)))
                
                startProxy(port)
            } finally {
                isRestarting = false
            }
        }
    }
    
    // 註：行動網路失效不再「停止引擎」。listener 綁的是 LAN 位址，與蜂巢式無關；
    // 由 handleNetworkChange 切換新連線要用的 Network 即可，既有連線讓 worker 自然回收。
    
    // 健康檢查端點：依序嘗試，任一成功即視為網路健康。gstatic 在部分地區可能無法連通
    // （Google 網域被封鎖），因此補上非 Google 的備援端點，避免誤判「5G 斷線」進而
    // 觸發不必要的代理重建。
    private val healthCheckUrls = listOf(
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://captive.apple.com/hotspot-detect.html",
        "http://connect.rom.miui.com/generate_204"
    )

    /**
     * 只做 bindSocket、不做連線：驗證系統肯不肯把這個網路當成本 App 的出口。
     *
     * 刻意不順便做連通性探測 —— 那會把「地區性被封鎖的探測端點」變成啟動的硬條件
     * （gstatic / apple / miui 三個端點在某些網路下全不通，但代理其實可用）。
     * bind 是本地操作、毫秒級，而且正好就是 VPN 接管路由時會失敗的那一步
     * （實機 log: "Binding socket to network 176 failed: EPERM"），
     * 所以它比「掃描有沒有 VPN 網路」更貼近我們真正想知道的事：能不能出去。
     */
    private fun canBindCellularEgress(network: android.net.Network): Boolean {
        val probe = java.net.Socket()
        return try {
            network.bindSocket(probe)
            true
        } catch (e: Exception) {
            Log.w(TAG, "蜂巢式出口綁定探測失敗: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            try { probe.close() } catch (e: Exception) {}
        }
    }

    private fun checkNetworkHealth(network: android.net.Network): NetworkHealthStatus {
        val cm = connectivityManager
        val caps = try { cm.getNetworkCapabilities(network) } catch (e: Exception) { null }
        val hasInternet = caps != null &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)

        // 先做 VPN 真正會破壞的那一步：把 socket 綁到蜂巢式出口。
        // 舊版把「caps 異常」與「探測端點全失敗」都直接歸咎給 VPN，於是只要系統裡
        // 有任何 VPN 網路（例如同機並存的 5G Proxy Client），5G 真的斷線也會被判成
        // BLOCKED_BY_VPN 而永不自動重建。現在一律以 bind 探測為判據，並且要求蜂巢式
        // 仍被系統驗證可用（VALIDATED）才敢把失敗歸給 VPN —— 詳見 NetworkHealthClassifier。
        val bindOk = hasInternet && canBindCellularEgress(network)
        val probeOk = bindOk && healthProbeSucceeds(network)

        return NetworkHealthClassifier.classify(
            hasInternetCapability = hasInternet,
            cellularValidated = caps?.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true,
            bindSucceeded = bindOk,
            vpnActive = VpnDetector.isVpnActive(cm),
            probeSucceeded = probeOk
        )
    }

    /** 依序嘗試健康檢查端點，任一成功即視為通過。 */
    private fun healthProbeSucceeds(network: android.net.Network): Boolean {
        for (url in healthCheckUrls) {
            val ok = try {
                network.openConnection(java.net.URL(url)).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    inputStream.use { it.read() }
                }
                true
            } catch (e: Exception) {
                false
            }
            if (ok) return true
        }
        return false
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
        loopsJob?.cancel(); loopsJob = null
        if (!isProxyRunning && !proxyPaused) {
            // 服務可能是被本 STOP intent 建立的（先前早已停止）：直接收掉，
            // 否則 onCreate 拿走的 wakelock 與服務本身永遠不會釋放
            isServiceRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        isProxyRunning = false
        proxyPaused = false
        isServiceRunning = false
        lastErrorMessage = null
        updateStatus(ProxyStatus.STOPPED)
        serviceScope.launch {
            try {
                appendEngineStatsToFile() // 停止前記錄最後一筆 stats
                stopNativeEngineSafely()
                networkManager.releaseCellularNetwork()
                cellularNetwork = null
                lanBindAddrs = null
                
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
    
    /**
     * [偵測落檔] 把 native 引擎的生命週期計數器 snapshot 寫進滾動檔案。
     * 每 5 分鐘與停止前各寫一次；檔案超過上限時砍半保留最新，避免長期使用無限長大。
     */
    @Synchronized
    private fun appendEngineStatsToFile() {
        if (!NativeEngine.isLibraryLoaded()) return
        val stats = NativeEngine.safeGetStats()
        if (stats == "not running" || stats == "native library not loaded" || stats == "stats unavailable") return
        // 附上 UDP 網域解析計數：drop/qfull/jobfull/lost 非 0 就代表有 frame 沒送出去，
        // 而這種事過去完全不留痕跡。字串為純 ASCII，不影響下方的 char/byte 等長假設。
        val dns = NativeEngine.safeGetDnsStats()
        // [拆除診斷] 再附上 UDP session 拆除的分類計數。short（存活不到 1 秒就拆）
        // 持續增加就代表客戶端在反覆重建會話 —— 這正是逐行 log 想抓的東西，
        // 但這裡不必開 log、不必接 adb 就有，且計數是永遠開啟的。
        val udpClose = NativeEngine.safeGetUdpCloseStats()
        val line = "${statsTimeFormat.format(java.util.Date())} $stats | dns: $dns | udp_close: $udpClose\n"
        try {
            engineStatsFile.appendText(line)
            val content = engineStatsFile.readText()
            // 統計行皆為 ASCII，char 數 == byte 數；統一以 content 長度判斷與截斷，
            // 避免 file.length()（bytes）與 substring（chars）混用。
            if (content.length > STATS_LOG_MAX_BYTES) {
                engineStatsFile.writeText(content.takeLast(STATS_LOG_KEEP_BYTES.toInt()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "engine stats 落檔失敗", e)
        }
    }

    private fun createSocketBoundToNetwork(host: String, port: Int, isUdp: Boolean): Int {
        val network = cellularNetwork ?: return -1
        // 換 IP / PDN 重建後舊 Network 物件會失效，直接 bindSocket 會 EPERM
        // （實機 log: "Binding socket to network 176 failed: EPERM"）。先驗證仍具
        // INTERNET 能力，失效就快速失敗讓 client 重試，而不是每次拋例外、連帶讓
        // PublicIPChecker 也失敗（UI 因此顯示不出行動網路 IP）。
        val caps = try { connectivityManager.getNetworkCapabilities(network) } catch (e: Exception) { null }
        if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return -1
        }
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
                try {
                    // [UDP 緩衝] 與 TCP 出口同等對待。DatagramSocket 在建構時就已 bind，
                    // 但 Linux 的 SO_RCVBUF/SO_SNDBUF 不受 bind 限制（與 TCP connect 後
                    // 鎖死不同）—— 已在 Debian 6.12 實測：bind 後設定仍會生效，只受
                    // net.core.rmem_max 夾限。設定失敗不該讓整條 UDP session 建不起來，
                    // 但也不能靜默：降級要出聲。
                    try {
                        ds.receiveBufferSize = SOCKET_BUFFER_BYTES
                        ds.sendBufferSize = SOCKET_BUFFER_BYTES
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP socket 緩衝設定失敗，沿用系統預設", e)
                    }
                    network.bindSocket(ds)
                    val pfd = android.os.ParcelFileDescriptor.fromDatagramSocket(ds)
                    val fd = pfd.detachFd()
                    activeSockets[fd] = ds
                    fd
                } catch (e: Exception) {
                    // bindSocket / fromDatagramSocket 失敗時 ds 尚未交給 activeSockets，
                    // 必須在此關閉，否則外層 catch 只記錄、fd 就洩漏了。
                    try { ds.close() } catch (e2: Exception) {}
                    throw e
                }
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
                // resolveWithCache 已 IPv6 優先排序；依家族分割：IPv6 先行、IPv4 延後
                val ipv6 = addresses.filter { it is java.net.Inet6Address }
                val ipv4 = addresses.filter { it is java.net.Inet4Address }

                val winner = java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
                val latch = java.util.concurrent.CountDownLatch(1)
                // [根因修復 2026-08-23] abandoned：主執行緒逾時放棄後，稍晚才連上的
                // 「遲到勝者」必須自行關閉，否則成為無人持有的洩漏 socket
                // （電信商 IPv4 黑洞期間每次逾時都會製造一個，長時間運行下
                //   FDSize 衝上 16384 的元兇之一）
                val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
                val connectTimeout = 2000L
                val overallDeadline = connectTimeout + 500L

                HappyEyeballs.race(
                    first = ipv6,
                    deferred = ipv4,
                    staggerMs = HappyEyeballs.DEFAULT_CONNECTION_ATTEMPT_DELAY_MS,
                    winner = winner,
                    abandoned = abandoned,
                    latch = latch,
                    dispatch = { run -> connectExecutor.execute(run) },
                    scheduler = { delay, run -> happyEyeballsScheduler.schedule(run, delay, java.util.concurrent.TimeUnit.MILLISECONDS) },
                    connect = { addr ->
                        val candidate = java.net.Socket()
                        try {
                            candidate.receiveBufferSize = SOCKET_BUFFER_BYTES
                            candidate.sendBufferSize = SOCKET_BUFFER_BYTES
                            candidate.tcpNoDelay = true
                            network.bindSocket(candidate)
                            candidate.connect(java.net.InetSocketAddress(addr, port), connectTimeout.toInt())
                            candidate
                        } catch (e: Exception) {
                            try { candidate.close() } catch (e2: Exception) {}
                            null
                        }
                    },
                    close = { s -> try { s.close() } catch (e: Exception) {} }
                )

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
                val pfd = try {
                    android.os.ParcelFileDescriptor.fromSocket(socket)
                } catch (e: Exception) {
                    // 勝出的 socket 尚未交給 activeSockets，失敗時必須自行關閉
                    try { socket.close() } catch (e2: Exception) {}
                    return -1
                }
                val fd = pfd.detachFd()
                activeSockets[fd] = socket
                if (isDebuggable) android.util.Log.d("FdAudit", "created fd=$fd map=${activeSockets.size}")
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
        loopsJob?.cancel(); loopsJob = null
        isServiceRunning = false
        // 兜底清理：服務可能未經 ACTION_STOP_PROXY 就被銷毀（系統回收等），
        // 之後 serviceScope 已取消、stopProxy 的清理協程不會再執行，
        // 必須在此同步釋放 native 引擎與所有資源，否則殭屍 listener 會持續佔用埠號。
        // 引擎停止後才可清空 onSocketClosed —— 排空期間 native 執行緒仍需靠它關 fd。
        appendEngineStatsToFile() // 停止前記錄最後一筆 stats
        stopNativeEngineSafely()
        NativeEngine.socketProvider = null
        NativeEngine.onSocketClosed = null
        NativeEngine.hostResolver = null
        activeSockets.values.forEach {
            if (it is Closeable) try { it.close() } catch (e: Exception) {}
        }
        activeSockets.clear()
        try { networkManager.releaseCellularNetwork() } catch (e: Exception) {}
        cellularNetwork = null
        lanBindAddrs = null
        // [失敗訊息可見性] failStop() 剛把 FAILED + lastErrorMessage 推給 UI，緊接著
        // stopSelf() 就會走到這裡。若無條件改回 STOPPED，UI 上的失敗文字只存活一個
        // frame（使用者實測「只閃過幾毫秒，只看到服務已停止」），錯誤原因等於被吞掉。
        // FAILED 是終態：保留它，讓錯誤訊息留在畫面上；下一次 startProxy()/stopProxy()
        // 會清掉 lastErrorMessage 並重新設定狀態，屆時自然復位。
        if (currentStatus != ProxyStatus.FAILED) {
            updateStatus(ProxyStatus.STOPPED)
        }
        serviceScope.cancel()
        happyEyeballsScheduler.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}