package com.tokyoxpa3.androidproxy.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 tailnet 位址上的 TCP 連線原封不動轉送到 127.0.0.1:port（主程式 SOCKS5 的 loopback 監聽）。
 *
 * 為什麼要另一個 App：Android per-app VPN 以 UID 為單位決定路由。
 *  - 主程式被 Tailscale 排除 → 蜂巢式 bindSocket 可用，但回 tun0 的封包被路由規則擋下（連線逾時）。
 *  - 主程式被 Tailscale 包含 → tun0 回包正常，但 Tailscale 不允許 bypass，蜂巢式 bindSocket 得到 EPERM。
 * 本 App 留在 Tailscale 內負責「進」，主程式排除在外負責「出」，中間走 loopback（不受 VPN 影響）。
 *
 * 不解析 SOCKS5：純位元組管線，因此 SOCKS5 的 UDP ASSOCIATE（另開 UDP 埠）不會經過這裡；
 * 主程式的 UDP-in-TCP（0x04）走同一條 TCP，照常可用。
 */
class RelayService : Service() {

    companion object {
        private const val TAG = "TailnetRelay"
        private const val CHANNEL_ID = "relay"
        private const val NOTIFICATION_ID = 1
        private const val REFRESH_MS = 5000L
        private const val UPSTREAM_CONNECT_TIMEOUT_MS = 5000
        private const val BUF_SIZE = 64 * 1024
        private const val HALF_CLOSE_LINGER_SEC = 60L

        const val ACTION_START = "com.tokyoxpa3.androidproxy.relay.START"
        const val ACTION_STOP = "com.tokyoxpa3.androidproxy.relay.STOP"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 1080
        const val DEFAULT_CONTROL_PORT = 8081

        @Volatile var rotator: IpRotator? = null

        @Volatile var isRunning = false
        @Volatile var listenAddrs: List<String> = emptyList()
        val activeConnections = AtomicInteger(0)
    }

    @Volatile private var port = DEFAULT_PORT
    @Volatile private var controlPort = DEFAULT_CONTROL_PORT
    @Volatile private var refreshThread: Thread? = null
    private val listeners = ConcurrentHashMap<String, ServerSocket>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "relay-io").apply { isDaemon = true } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRelay()
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)
        val requested = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        val newPort = if (requested > 0) requested else prefs.getInt("port", DEFAULT_PORT)
        val newControlPort = prefs.getInt("control_port", DEFAULT_CONTROL_PORT)
        startInForeground(newPort)
        if (isRunning && newPort == port && newControlPort == controlPort) return START_STICKY
        stopRelay()
        port = newPort
        controlPort = newControlPort
        // 輪替可能跨越服務重建，埠號一律從設定讀，不抓本實例的欄位
        if (rotator == null) rotator = IpRotator(applicationContext) {
            applicationContext.getSharedPreferences("relay", Context.MODE_PRIVATE).getInt("port", DEFAULT_PORT)
        }
        startRelay()
        return START_STICKY
    }

    override fun onDestroy() {
        stopRelay()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startInForeground(p: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RelayActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, p))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun startRelay() {
        isRunning = true
        // Tailscale 可能晚於本服務連上、重連後換位址；與主程式的 LAN 監聽一樣以輪詢對帳
        refreshThread = Thread({
            while (isRunning) {
                try { reconcileListeners() } catch (e: Exception) { Log.w(TAG, "reconcile failed", e) }
                try { Thread.sleep(REFRESH_MS) } catch (e: InterruptedException) { break }
            }
        }, "relay-refresh").apply { isDaemon = true; start() }
    }

    private fun stopRelay() {
        isRunning = false
        refreshThread?.interrupt()
        refreshThread = null
        listeners.values.forEach { closeQuietly(it) }
        listeners.clear()
        listenAddrs = emptyList()
    }

    private fun collectTailscaleAddrs(): Set<String> {
        val result = linkedSetOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        for (nif in interfaces) {
            if (!nif.isUp) continue
            for (addr in nif.inetAddresses) {
                if (!TailscaleAddress.shouldBind(nif.name, addr)) continue
                var ip = addr.hostAddress ?: continue
                val zone = ip.indexOf('%')
                if (zone >= 0) ip = ip.substring(0, zone)
                result.add(ip)
            }
        }
        return result
    }

    // listener 以 "ip|port" 為鍵：每個 Tailscale 位址各綁 SOCKS 轉送埠與控制 API 埠
    private fun reconcileListeners() {
        val addrs = collectTailscaleAddrs()
        val desired = addrs.flatMap { listOf("$it|$port", "$it|$controlPort") }.toSet()
        // 已消失或已被核心關掉的 listener 先拆掉
        for ((key, ss) in listeners) {
            if (key !in desired || ss.isClosed) {
                closeQuietly(ss)
                listeners.remove(key)
                Log.i(TAG, "listener removed: $key")
            }
        }
        for (key in desired) {
            if (listeners.containsKey(key)) continue
            val ip = key.substringBefore('|')
            val p = key.substringAfter('|').toInt()
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName(ip), p), 128)
                listeners[key] = ss
                val isControl = p == controlPort
                pool.execute { acceptLoop(key, ss, isControl) }
                Log.i(TAG, "listener added: $key")
            } catch (e: IOException) {
                Log.w(TAG, "bind $key failed: ${e.message}")
            }
        }
        listenAddrs = listeners.keys.filter { it.endsWith("|$port") }.map { it.substringBefore('|') }.sorted()
    }

    private fun acceptLoop(key: String, ss: ServerSocket, isControl: Boolean) {
        while (isRunning && !ss.isClosed) {
            val client = try { ss.accept() } catch (e: IOException) { break }
            pool.execute { if (isControl) handleControl(client) else handle(client) }
        }
        // accept 失敗（例如介面消失）：交由下一輪 reconcile 重綁
        closeQuietly(ss)
        listeners.remove(key, ss)
    }

    /**
     * 控制 API（僅綁在 Tailscale 位址；不做認證，tailnet 內任何裝置都可呼叫）：
     *   POST /rotate  → 202，背景執行飛航模式輪替（期間 Tailscale 會斷，結果用 /status 查）
     *   GET  /status  → 最近一次輪替的狀態 + 系統網路判定（network）+ 整機行動數據用量（data_usage）
     *   GET  /ip      → 目前 5G 出口公網 IP
     */
    private fun handleControl(client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val lines = ArrayList<String>()
            while (lines.size < 100) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                lines.add(line)
            }
            val req = ControlHttp.parseHead(lines)
            val rot = rotator
            val (code, body) = when {
                req == null -> 400 to json("error" to "bad request")
                rot == null -> 503 to json("error" to "not ready")
                req.method == "POST" && req.path == "/rotate" ->
                    if (rot.start()) 202 to statusJson(rot.status) else 409 to statusJson(rot.status)
                req.method == "GET" && req.path == "/status" -> 200 to org.json.JSONObject(statusJson(rot.status))
                    .put("network", DeviceStatus.networkJson(applicationContext))
                    .put("data_usage", DeviceStatus.dataUsageJson(applicationContext))
                    .toString()
                req.method == "GET" && req.path == "/ip" -> {
                    val ip = rot.fetchPublicIp()
                    if (ip != null) 200 to json("ip" to ip) else 502 to json("error" to "cellular egress unavailable")
                }
                else -> 404 to json("error" to "not found")
            }
            val reason = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request",
                404 to "Not Found", 409 to "Conflict", 502 to "Bad Gateway", 503 to "Service Unavailable")[code] ?: ""
            val bytes = (body + "\n").toByteArray()
            val out = client.getOutputStream()
            out.write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        } catch (e: IOException) {
            Log.d(TAG, "control connection ended: ${e.message}")
        } finally {
            closeQuietly(client)
        }
    }

    private fun json(vararg pairs: Pair<String, Any?>): String =
        org.json.JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: org.json.JSONObject.NULL) } }.toString()

    private fun statusJson(s: IpRotator.Status): String = json(
        "state" to s.state, "old_ip" to s.oldIp, "new_ip" to s.newIp, "attempts" to s.attempts,
        "error" to s.error, "started_at" to s.startedAt, "finished_at" to s.finishedAt,
        "assistant_active" to AirplaneAssistant.isActive
    )

    private fun handle(client: Socket) {
        val upstream = Socket()
        activeConnections.incrementAndGet()
        try {
            client.tcpNoDelay = true
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), UPSTREAM_CONNECT_TIMEOUT_MS)
            // 上行另開一條執行緒，下行在本執行緒；任一方向 EOF 只 half-close 對端，
            // 讓另一方向把剩下的資料送完（SOCKS 上的 HTTP/1.0 等會依賴 half-close）
            val up = pool.submit { pump(client.getInputStream(), upstream.getOutputStream()); shutdownOutput(upstream) }
            pump(upstream.getInputStream(), client.getOutputStream())
            shutdownOutput(client)
            // 對端遲遲不關上行時不無限等待（避免殘留執行緒），逾時即整條關閉
            try { up.get(HALF_CLOSE_LINGER_SEC, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        } catch (e: IOException) {
            Log.d(TAG, "relay connection ended: ${e.message}")
        } finally {
            closeQuietly(client)
            closeQuietly(upstream)
            activeConnections.decrementAndGet()
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUF_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: IOException) {
            // 對端重置或已關閉：結束這個方向即可
        }
    }

    private fun shutdownOutput(s: Socket) {
        try { if (!s.isClosed) s.shutdownOutput() } catch (e: IOException) {}
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try { c.close() } catch (e: IOException) {}
    }
}
