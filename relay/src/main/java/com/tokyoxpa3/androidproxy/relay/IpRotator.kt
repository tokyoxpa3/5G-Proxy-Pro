package com.tokyoxpa3.androidproxy.relay

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [IP 輪替] 飛航模式 ON → 等待 → OFF → 等蜂巢式恢復 → 經主程式 SOCKS5 查公網 IP。
 *
 * 公網 IP 必須透過 127.0.0.1 的主程式代理查：本 App 在 Tailscale 內，自己對外會走
 * Wi-Fi（或被 Tailscale 擋下蜂巢式 bind），查到的不是 5G 出口。
 *
 * 飛航模式期間 Wi-Fi 與 Tailscale 也會斷，呼叫端（遠端）無法同步等結果；
 * 因此整個流程在背景執行緒自行跑完，結果寫進 status 供恢復連線後查詢。
 */
class IpRotator(private val context: Context, private val socksPort: () -> Int) {

    companion object {
        private const val TAG = "IpRotator"
        private const val MAX_ATTEMPTS = 3
        private const val AIRPLANE_HOLD_MS = 5000L
        private const val TOGGLE_CONFIRM_MS = 5000L
        private const val RECOVER_TIMEOUT_MS = 90_000L
        private const val POLL_MS = 3000L
        private const val IP_HOST = "api.ipify.org"
    }

    data class Status(
        val state: String = "idle",        // idle | running | changed | unchanged | failed
        val oldIp: String? = null,
        val newIp: String? = null,
        val attempts: Int = 0,
        val error: String? = null,
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
    )

    @Volatile var status = Status()
        private set
    private val running = AtomicBoolean(false)

    /** 回 false 代表已有輪替在進行中。 */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        status = Status(state = "running", startedAt = System.currentTimeMillis())
        Thread({
            try { status = rotate(status) } catch (e: Exception) {
                Log.w(TAG, "rotation crashed", e)
                status = status.copy(state = "failed", error = e.message ?: e.javaClass.simpleName)
            } finally {
                status = status.copy(finishedAt = System.currentTimeMillis())
                // 任何失敗路徑都不能把手機留在飛航模式 —— 那會讓遠端永遠連不回來
                if (AirplaneAssistant.isAirplaneOn(context)) setAirplane(false)
                running.set(false)
            }
        }, "ip-rotate").apply { isDaemon = true; start() }
        return true
    }

    private fun rotate(initial: Status): Status {
        if (!AirplaneAssistant.isActive) {
            return initial.copy(state = "failed", error = "relay is not the default digital assistant")
        }
        val oldIp = fetchPublicIp()
        var s = initial.copy(oldIp = oldIp)
        status = s
        for (attempt in 1..MAX_ATTEMPTS) {
            s = s.copy(attempts = attempt)
            status = s
            Log.i(TAG, "attempt $attempt: old=$oldIp")
            if (!setAirplane(true)) return s.copy(state = "failed", error = "airplane mode did not turn on")
            Thread.sleep(AIRPLANE_HOLD_MS)
            if (!setAirplane(false)) return s.copy(state = "failed", error = "airplane mode did not turn off")
            val newIp = waitForPublicIp()
                ?: return s.copy(state = "failed", error = "cellular egress did not recover in ${RECOVER_TIMEOUT_MS / 1000}s")
            s = s.copy(newIp = newIp)
            status = s
            if (oldIp == null || newIp != oldIp) return s.copy(state = "changed")
        }
        return s.copy(state = "unchanged")
    }

    /** 送出切換並確認系統設定確實變了（助理 session 可能被系統靜默拒絕）。 */
    private fun setAirplane(on: Boolean): Boolean {
        if (!AirplaneAssistant.requestAirplane(on)) return false
        val deadline = System.currentTimeMillis() + TOGGLE_CONFIRM_MS
        while (System.currentTimeMillis() < deadline) {
            if (AirplaneAssistant.isAirplaneOn(context) == on) return true
            Thread.sleep(250)
        }
        return AirplaneAssistant.isAirplaneOn(context) == on
    }

    private fun waitForPublicIp(): String? {
        val deadline = System.currentTimeMillis() + RECOVER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            fetchPublicIp()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /** 經 127.0.0.1:socksPort（主程式）以 SOCKS5 查 api.ipify.org；失敗回 null。 */
    fun fetchPublicIp(): String? {
        val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
        val user = prefs.getString("socks_user", "") ?: ""
        val pass = prefs.getString("socks_pass", "") ?: ""
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort()), 3000)
                s.soTimeout = 8000
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())
                val useAuth = user.isNotEmpty() && pass.isNotEmpty()
                out.write(if (useAuth) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0))
                if (inp.readByte().toInt() != 5) return null
                when (inp.readByte().toInt()) {
                    0 -> {}
                    2 -> {
                        val u = user.toByteArray(); val p = pass.toByteArray()
                        out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                        inp.readByte()
                        if (inp.readByte().toInt() != 0) return null
                    }
                    else -> return null
                }
                val host = IP_HOST.toByteArray()
                out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80))
                inp.readByte()
                if (inp.readByte().toInt() != 0) return null
                inp.readByte()
                when (inp.readByte().toInt()) {
                    1 -> inp.skipBytes(4)
                    4 -> inp.skipBytes(16)
                    3 -> inp.skipBytes(inp.readUnsignedByte())
                    else -> return null
                }
                inp.skipBytes(2)
                out.write("GET / HTTP/1.1\r\nHost: $IP_HOST\r\nConnection: close\r\n\r\n".toByteArray())
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = inp.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                val resp = buf.toString("ISO-8859-1")
                if (!resp.startsWith("HTTP/1.1 200") && !resp.startsWith("HTTP/1.0 200")) return null
                ControlHttp.parseIpBody(resp.substringAfter("\r\n\r\n"))
            }
        } catch (e: IOException) {
            null
        }
    }
}
