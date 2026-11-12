package com.tokyoxpa3.androidproxy.network

import android.net.ConnectivityManager
import android.net.Network
import com.tokyoxpa3.androidproxy.AppLog
import com.tokyoxpa3.androidproxy.IpLiteral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.random.Random

/**
 * 出口公網位址探測。
 *
 * 三層，由便宜到貴：
 *  1. **本機全域 IPv6**（零流量）：IPv6 通常沒有 NAT，介面位址就是出口位址。
 *  2. **STUN**（一個 UDP 往返）：RFC 5389 Binding，問伺服器看到什麼來源位址。
 *  3. **HTTPS echo**（DNS + TCP + TLS）：最貴但最通用，能穿透只放行 443 的網路。
 *
 * 為什麼不能只靠「有連線就知道 IP」：CGNAT 在電信閘道改寫源位址，改寫後的位址
 * 只存在於閘道的 NAT 表，不會回送給 socket —— `getsockname()` 永遠是私有位址
 * （實測 `100.96.34.49`）。所以「問對端」是唯一非 root 的取得方式，[Source] 就是
 * 用來標記這個值到底是誰說的，避免把不同來源的值混在一起比較。
 */
class PublicIPChecker(
    private val connectivityManager: ConnectivityManager? = null
) {

    /**
     * STUN 冷卻截止時間（0 = 沒有冷卻）。
     *
     * 某些網路（企業／校園／部分 Wi-Fi）整個封 UDP，STUN 每次都要吃滿兩個 timeout。
     * 失敗一次就冷卻一段時間，讓探測週期回到 echo 的正常速度。
     */
    @Volatile private var stunCooldownUntil = 0L

    enum class Source { ECHO, STUN, LOCAL_IPV6 }

    data class Result(
        val ip: String?,
        val source: Source?,
        val detail: String?,
        val elapsedMs: Long = 0L
    )

    /**
     * 依序嘗試探測層。
     *
     * 預設 **STUN 優先**：一個 UDP 往返、約 100 bytes，比 HTTPS echo 少 2 個 RTT，
     * 流量少兩個數量級（每 15 秒一次，一天差約 40 MB 行動數據）。
     *
     * [forceEcho] 為 true 時改成 echo 優先，用於定期校正 —— STUN 走 UDP，
     * 部分電信商的 UDP 映射與 TCP 出口落在不同 NAT 池，而代理流量是 TCP，
     * 所以還是要週期性地用 echo 確認真正的 TCP 出口。
     */
    suspend fun probe(network: Network?, forceEcho: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val failures = StringBuilder()
            val startedAt = System.currentTimeMillis()

            val order = if (forceEcho) {
                listOf(Source.ECHO, Source.STUN)
            } else {
                listOf(Source.STUN, Source.ECHO)
            }

            for (layer in order) {
                when (layer) {
                    Source.STUN -> {
                        if (System.currentTimeMillis() < stunCooldownUntil) {
                            failures.append("stun=cooldown; ")
                            continue
                        }
                        for (server in stunServers) {
                            val ip = tryStun(network, server, failures)
                            if (ip != null) {
                                stunCooldownUntil = 0L
                                return@withContext Result(
                                    ip, Source.STUN, server, elapsed(startedAt)
                                )
                            }
                        }
                        // 兩個 STUN 伺服器都不通：這個網路很可能封 UDP（實測某些
                        // 企業／校園網路連 UDP/53 都出不去）。冷卻一段時間再試，
                        // 否則每一輪探測都要白等兩個 timeout。
                        stunCooldownUntil = System.currentTimeMillis() + STUN_COOLDOWN_MS
                        AppLog.w(
                            TAG,
                            "STUN 不可用，${STUN_COOLDOWN_MS / 60_000} 分鐘內跳過: $failures"
                        )
                    }
                    Source.ECHO -> {
                        for (endpoint in echoEndpoints) {
                            val ip = tryEcho(network, endpoint, failures)
                            if (ip != null) {
                                return@withContext Result(
                                    ip, Source.ECHO, endpoint, elapsed(startedAt)
                                )
                            }
                        }
                    }
                    Source.LOCAL_IPV6 -> Unit
                }
            }

            val local = tryLocalIpv6(network)
            if (local != null) {
                // STUN 與 echo 都掛了，至少還能說「這台手機的 IPv6 出口是這個」。
                // issue #8 的痛點就是這種時候 UI 只剩 Failed，什麼都判斷不了。
                AppLog.w(TAG, "probe: STUN/echo 全失敗，退回本機 IPv6 出口 $local（$failures）")
                return@withContext Result(
                    local, Source.LOCAL_IPV6, "local-ipv6", elapsed(startedAt)
                )
            }

            AppLog.w(TAG, "probe failed (${elapsed(startedAt)}ms): $failures")
            Result(null, null, failures.toString().trim(), elapsed(startedAt))
        }

    private fun elapsed(startedAt: Long): Long = System.currentTimeMillis() - startedAt

    /** 相容舊介面：只要位址，不要來源與原因。 */
    suspend fun getPublicIP(network: android.net.Network? = null): String? = probe(network).ip

    suspend fun checkConnectivity(network: android.net.Network? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://www.google.com")
            val connection = if (network != null) {
                network.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }

            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            AppLog.w(TAG, "connectivity check failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun tryEcho(network: Network?, endpoint: String, failures: StringBuilder): String? {
        return try {
            val url = URL(endpoint)
            val connection = if (network != null) {
                network.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }

            connection.requestMethod = "GET"
            connection.connectTimeout = ECHO_CONNECT_TIMEOUT_MS
            connection.readTimeout = ECHO_READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "AndroidProxy/1.0")
            // 中間可能有電信商的透明代理：不讓它回快取，否則出口變了也讀到舊值
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.useCaches = false

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                failures.append("$endpoint=HTTP$code; ")
                return null
            }
            val body = connection.inputStream.use { input ->
                input.bufferedReader().use { it.readText() }
            }
            val candidate = body.trim().lineSequence().firstOrNull()?.trim()
            // 必須是 IP 字面值：端點被劫持或改寫時可能回 HTML／廣告頁，
            // 那種值拿去比對只會製造假變更。
            if (candidate == null || IpLiteral.parse(candidate) == null) {
                failures.append("$endpoint=not-an-ip; ")
                null
            } else {
                candidate
            }
        } catch (e: Exception) {
            failures.append("$endpoint=${e.javaClass.simpleName}; ")
            null
        }
    }

    /**
     * 一個 UDP 往返問出映射位址。
     *
     * 注意 STUN 走的是 UDP，電信商對 UDP 與 TCP 可能使用不同的 NAT 池，
     * 所以這個值**只作為 echo 失敗時的備援**；上層會依 [Source] 分開比對，
     * 不會拿 STUN 值去和先前的 echo 值相減。
     */
    private fun tryStun(network: Network?, server: String, failures: StringBuilder): String? {
        val host = server.substringBeforeLast(':')
        val port = server.substringAfterLast(':').toIntOrNull()
        if (host.isEmpty() || port == null) {
            failures.append("$server=bad-server; ")
            return null
        }
        var socket: DatagramSocket? = null
        return try {
            val addresses = if (network != null) {
                network.getAllByName(host)          // 用該 Network 的 DNS，不是預設網路
            } else {
                InetAddress.getAllByName(host)
            }
            if (addresses.isEmpty()) {
                failures.append("$server=no-dns; ")
                return null
            }
            // 優先走 IPv4：IPv6 沒有 NAT，STUN 只會回報介面自己的位址，
            // 完全看不到 CGNAT 對 IPv4 出口的重綁 —— 而那正是我們最在意的事。
            // 只有在沒有 IPv4 位址時（IPv6-only 網路）才退回 IPv6。
            val target = addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses[0]

            val transactionId = ByteArray(12).also { Random.nextBytes(it) }
            val request = StunMessage.buildBindingRequest(transactionId)

            val ds = DatagramSocket()
            socket = ds
            network?.bindSocket(ds)                 // 把 UDP socket 也釘到蜂巢出口
            ds.soTimeout = STUN_TIMEOUT_MS
            ds.connect(target, port)
            ds.send(DatagramPacket(request, request.size))

            val buffer = ByteArray(512)
            val response = DatagramPacket(buffer, buffer.size)
            ds.receive(response)

            val ip = StunMessage.parseMappedAddress(buffer.copyOf(response.length), transactionId)
            if (ip == null) {
                failures.append("$server=bad-response; ")
                null
            } else {
                ip
            }
        } catch (e: Exception) {
            failures.append("$server=${e.javaClass.simpleName}; ")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 本機介面上的全域 IPv6 —— 沒有 NAT 時它就是出口位址，零流量。
     *
     * 刻意排除隱私臨時位址（flags bit0）：它會週期性輪換，拿它當出口值
     * 會製造「每幾小時換一次 IP」的假變更。
     */
    private fun tryLocalIpv6(network: Network?): String? {
        val cm = connectivityManager ?: return null
        if (network == null) return null
        return try {
            val lp = cm.getLinkProperties(network) ?: return null
            val addresses = lp.linkAddresses.mapNotNull { la ->
                val addr = la.address ?: return@mapNotNull null
                if (addr is java.net.Inet6Address && (la.flags and 0x01) != 0) null
                else addr.hostAddress
            }
            EgressAddress.pickGlobalIpv6(addresses)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "PublicIPChecker"

        /**
         * 兩個 echo 端點就夠：多一個端點就多一段最壞情況的等待，
         * 而這兩個分屬不同營運商，同時被擋的機率已經很低。
         */
        private val echoEndpoints = listOf(
            "https://api.ipify.org?format=text",
            "https://ipv4.icanhazip.com"
        )

        private val stunServers = listOf(
            "stun.l.google.com:19302",
            "stun.cloudflare.com:3478"
        )

        // 探測只需要「有沒有變」，不需要等到慢速鏈路極限；縮短 timeout 讓
        // 全失敗的情況不會把 15 秒的探測週期拖成一分鐘以上。
        private const val ECHO_CONNECT_TIMEOUT_MS = 3000
        private const val ECHO_READ_TIMEOUT_MS = 3000
        private const val STUN_TIMEOUT_MS = 2000

        /** STUN 連續失敗後的冷卻時間：期間直接走 echo，不再白等 UDP timeout。 */
        private const val STUN_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
