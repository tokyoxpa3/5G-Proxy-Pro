package com.tokyoxpa3.androidproxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 內建「一鍵自我檢測」：在本機以真實 SOCKS5 客戶端連到 loopback（127.0.0.1:port），
 * 走完整 relay 路徑——TCP 連線 → SOCKS5 握手 →（可選）RFC1929 認證 → CONNECT 建立隧道
 * → 透過 5G 代理送出 HTTP GET 並讀回回應。每一步獨立標記 pass/fail，讓
 * 「SOCKS5 回傳空白」這類模糊描述能被精確定位到「哪一步失敗」。
 */
object SelfTest {

    // 固定的測試目標：example.com:80 穩定回傳 HTTP 200，足以驗證 DNS + TCP + 資料回流
    private const val TEST_HOST = "example.com"
    private const val TEST_PORT = 80
    private const val TIMEOUT_MS = 8000

    enum class StepKind { CONNECT_SOCKET, GREETING, AUTH, CONNECT, DATA }

    data class Step(val kind: StepKind, val pass: Boolean, val detail: String)

    data class Result(val steps: List<Step>, val overallPass: Boolean)

    suspend fun run(port: Int, authUser: String, authPass: String): Result =
        withContext(Dispatchers.IO) {
            val steps = mutableListOf<Step>()
            var socket: Socket? = null
            try {
                // 0. TCP 連線到 loopback（偵測「proxy 沒在聽」）
                socket = Socket()
                socket.soTimeout = TIMEOUT_MS
                try {
                    socket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
                    steps.add(Step(StepKind.CONNECT_SOCKET, true, "127.0.0.1:$port"))
                } catch (e: Exception) {
                    steps.add(Step(StepKind.CONNECT_SOCKET, false, "${e.javaClass.simpleName}: ${e.message}"))
                    return@withContext Result(steps, false)
                }

                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val authEnabled = authUser.isNotEmpty() && authPass.isNotEmpty()

                // 1. SOCKS5 握手（greeting）；組封包與驗回覆抽至 Socks5ClientProtocol
                val method = if (authEnabled) Socks5ClientProtocol.METHOD_USER_PASS else Socks5ClientProtocol.METHOD_NO_AUTH
                output.write(Socks5ClientProtocol.greetingRequest(authEnabled))
                output.flush()
                try {
                    val resp = ByteArray(2)
                    readFully(input, resp)
                    if (!Socks5ClientProtocol.validMethodReply(resp, method)) {
                        val chosen = if (resp.size >= 2) resp[1].toInt() and 0xff else -1
                        steps.add(Step(StepKind.GREETING, false,
                            "server chose method $chosen, expected $method"))
                        return@withContext Result(steps, false)
                    }
                    steps.add(Step(StepKind.GREETING, true,
                        if (authEnabled) "auth required" else "no-auth accepted"))
                } catch (e: Exception) {
                    steps.add(Step(StepKind.GREETING, false, "${e.javaClass.simpleName}: ${e.message}"))
                    return@withContext Result(steps, false)
                }

                // 2. RFC1929 帳密認證（僅在啟用認證時）
                if (authEnabled) {
                    output.write(Socks5ClientProtocol.authRequest(authUser, authPass))
                    output.flush()
                    try {
                        val resp = ByteArray(2)
                        readFully(input, resp)
                        if (!Socks5ClientProtocol.validAuthReply(resp)) {
                            steps.add(Step(StepKind.AUTH, false, "auth rejected (01 ${resp[1].toInt() and 0xff})"))
                            return@withContext Result(steps, false)
                        }
                        steps.add(Step(StepKind.AUTH, true, "credentials accepted"))
                    } catch (e: Exception) {
                        steps.add(Step(StepKind.AUTH, false, "${e.javaClass.simpleName}: ${e.message}"))
                        return@withContext Result(steps, false)
                    }
                }

                // 3. CONNECT 建立隧道（ATYP=0x03 網域，順帶測 DNS）
                output.write(Socks5ClientProtocol.connectRequest(TEST_HOST, TEST_PORT))
                output.flush()
                try {
                    val header = ByteArray(4)
                    readFully(input, header)
                    val rep = Socks5ClientProtocol.connectReplyRep(header)
                    if (rep < 0) {
                        steps.add(Step(StepKind.CONNECT, false, "bad VER ${header[0].toInt() and 0xff}"))
                        return@withContext Result(steps, false)
                    }
                    if (rep != Socks5ClientProtocol.REP_SUCCESS) {
                        steps.add(Step(StepKind.CONNECT, false, "REP=$rep (${repName(rep)})"))
                        return@withContext Result(steps, false)
                    }
                    // 依 ATYP 讀掉剩餘的 bind 位址 + port，保持串流對齊
                    val atyp = header[3].toInt() and 0xff
                    val addrLen = when (atyp) {
                        0x01 -> 4
                        0x04 -> 16
                        0x03 -> {
                            val l = ByteArray(1); readFully(input, l); l[0].toInt() and 0xff
                        }
                        else -> 0
                    }
                    if (addrLen > 0) readFully(input, ByteArray(addrLen))
                    readFully(input, ByteArray(2))
                    steps.add(Step(StepKind.CONNECT, true, "$TEST_HOST:$TEST_PORT"))
                } catch (e: Exception) {
                    steps.add(Step(StepKind.CONNECT, false, "${e.javaClass.simpleName}: ${e.message}"))
                    return@withContext Result(steps, false)
                }

                // 4. 資料回流：透過隧道送出 HTTP GET，讀回並驗證收到 HTTP 回應
                val http = "GET / HTTP/1.0\r\nHost: $TEST_HOST\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
                output.write(http)
                output.flush()
                try {
                    val buf = ByteArray(4096)
                    val n = input.read(buf)
                    if (n <= 0) {
                        steps.add(Step(StepKind.DATA, false, "0 bytes received (connection closed with no data)"))
                        return@withContext Result(steps, false)
                    }
                    val head = String(buf, 0, n, Charsets.US_ASCII)
                    if (!head.startsWith("HTTP/")) {
                        steps.add(Step(StepKind.DATA, false, "$n bytes, unexpected: ${head.take(60)}"))
                        return@withContext Result(steps, false)
                    }
                    steps.add(Step(StepKind.DATA, true, "$n bytes received"))
                } catch (e: Exception) {
                    steps.add(Step(StepKind.DATA, false, "${e.javaClass.simpleName}: ${e.message}"))
                    return@withContext Result(steps, false)
                }

                Result(steps, true)
            } catch (e: Exception) {
                steps.add(Step(StepKind.DATA, false, "${e.javaClass.simpleName}: ${e.message}"))
                Result(steps, false)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("connection closed")
            off += n
        }
    }

    private fun repName(rep: Int): String = when (rep) {
        0x01 -> "general failure"
        0x02 -> "connection not allowed"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "unknown"
    }
}
