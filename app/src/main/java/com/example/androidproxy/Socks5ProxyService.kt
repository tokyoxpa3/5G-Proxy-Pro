package com.example.androidproxy

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
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isProxyRunning = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private val networkManager by lazy { 
        com.example.androidproxy.network.CellularNetworkManager(this) 
    }
    private var cellularNetwork: android.net.Network? = null

    // 儲存強引用，Key 為 FD
    private val activeSockets = java.util.concurrent.ConcurrentHashMap<Int, Any>()
    
    companion object {
        const val TAG = "Socks5ProxyService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "socks5_proxy_channel"
        const val ACTION_START_PROXY = "START_PROXY"
        const val ACTION_STOP_PROXY = "STOP_PROXY"
        const val EXTRA_PORT = "PROXY_PORT"
        var isServiceRunning = false
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // --- [新增] 全域異常捕獲器：抓取導致 REASON_CRASH 的元兇 ---
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashDetail = """
                時間: ${java.util.Date()}
                線程: ${thread.name}
                錯誤類型: ${throwable.javaClass.simpleName}
                訊息: ${throwable.message}
                堆疊追蹤:
                ${throwable.stackTraceToString()}
            """.trimIndent()
            
            // 存入 SharedPreferences
            getSharedPreferences("debug_log", MODE_PRIVATE)
                .edit()
                .putString("last_java_crash", crashDetail)
                .commit() // 使用 commit 確保立即寫入磁碟

            // 呼叫原本的處理器（讓系統記錄到 ApplicationExitInfo）
            originalHandler?.uncaughtException(thread, throwable)
        }

        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "5GProxy::WakeLock").apply { acquire() }
        
        // --- 關鍵修復：收到 C 層通知時，真正關閉 Java Socket ---
        NativeEngine.onSocketClosed = { fd -> 
            val socket = activeSockets.remove(fd)
            if (socket is Closeable) {
                try { 
                    socket.close() 
                    // Log.d(TAG, "✅ Socket $fd closed cleanly")
                } catch (e: Exception) {
                    // Log.e(TAG, "❌ Error closing socket $fd: ${e.message}")
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果 intent 為 null，代表是系統因記憶體回收後自動重啟
        val port = intent?.getIntExtra(EXTRA_PORT, 1080) ?: 1080 
        
        if (intent?.action == ACTION_STOP_PROXY) {
            stopProxy()
        } else {
            // 如果是系統重啟且原本就在運行，則重新初始化
            startProxy(port) 
        }
        return START_STICKY
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
        if (isProxyRunning) return
        isServiceRunning = true
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_proxying), getString(R.string.notification_init_network)), 
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        
        serviceScope.launch {
            try {
                isProxyRunning = true
                val network = withTimeoutOrNull(15000) { networkManager.requestCellularNetwork() }
                if (network == null) { stopSelf(); return@launch }
                cellularNetwork = network
                
                NativeEngine.socketProvider = { host, p, isUdp -> 
                    createSocketBoundToNetwork(host, p, isUdp) 
                }
                NativeEngine.registerInstance()
                
                NativeEngine.startSocks5Server(port)

                // 啟動心跳機制：每 30 秒發送一個極小請求，防止 5G 掉線或進入省電模式
                launch {
                    while (isProxyRunning) {
                        delay(30000)
                        cellularNetwork?.let { network ->
                            try {
                                network.openConnection(java.net.URL("http://connectivitycheck.gstatic.com/generate_204")).apply {
                                    connectTimeout = 2000
                                    inputStream.use { it.read() }
                                }
                            } catch (e: Exception) {
                                // 忽略錯誤
                            }
                        }
                    }
                }

                // 監控 C 層線程狀態 (Health Check)
                launch {
                    while (isProxyRunning) {
                        delay(10000) // 每 10 秒檢查一次
                        // 這裡透過嘗試連接 Local Port 來檢查 C 層是否活著
                        if (NativeEngine.isLibraryLoaded() && !isNativeThreadAlive(port)) {
                             Log.e(TAG, "偵測到 Native 引擎異常停止，嘗試重啟...")
                             isProxyRunning = false // 重置狀態
                             try { NativeEngine.stopSocks5Server() } catch (e: Exception) {}
                             startProxy(port) // 重新啟動
                             break
                        }
                    }
                }

                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, createNotification(getString(R.string.status_proxy_running), getString(R.string.notification_locked_format, port)))
            } catch (e: Exception) { stopSelf() }
        }
    }
    
    private fun stopProxy() {
        if (!isProxyRunning) return
        serviceScope.launch {
            try {
                NativeEngine.stopSocks5Server()
                networkManager.releaseCellularNetwork()
                cellularNetwork = null
                
                // 清理所有殘留連線
                activeSockets.values.forEach { 
                    if (it is Closeable) try { it.close() } catch(e: Exception){} 
                }
                activeSockets.clear()
                
                isProxyRunning = false
                isServiceRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) { stopSelf() }
        }
    }
    
    private fun createSocketBoundToNetwork(host: String, port: Int, isUdp: Boolean): Int {
        val network = cellularNetwork ?: return -1
        return try {
            if (isUdp) {
                val ds = java.net.DatagramSocket()
                network.bindSocket(ds)
                val pfd = android.os.ParcelFileDescriptor.fromDatagramSocket(ds)
                val fd = pfd.detachFd()
                activeSockets[fd] = ds
                fd
            }
            else {
                // 過濾無用的本機/鏈路本地地址,並優先嘗試 IPv4
                // (5G 常見 IPv6 黑洞:連 IPv6 會卡到超時,先試 IPv4 可快速連上)
                val addresses = network.getAllByName(host)
                    .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
                    .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                if (addresses.isEmpty()) return -1
                var socket: java.net.Socket? = null
                for (addr in addresses) {
                    val candidate = java.net.Socket()
                    try {
                        candidate.receiveBufferSize = 3 * 1024 * 1024
                        candidate.sendBufferSize = 3 * 1024 * 1024
                        candidate.tcpNoDelay = true
                        network.bindSocket(candidate)
                        // IPv6 用短超時:黑洞時快速失敗,不讓單一連線卡 5 秒
                        val connectTimeout = if (addr is java.net.Inet4Address) 5000 else 1500
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
            // 嘗試連接本地 SOCKS5 端口
            java.net.Socket("127.0.0.1", port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        isProxyRunning = false
        isServiceRunning = false
        super.onDestroy()
    }
}