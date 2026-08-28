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

                // 1. SOCKS5 握手（greeting）
                val method = if (authEnabled) 0x02 else 0x00
                output.write(byteArrayOf(0x05, 0x01, method.toByte()))
                output.flush()
                try {
                    val resp = ByteArray(2)
                    readFully(input, resp)
                    if (resp[0].toInt() != 0x05 || (resp[1].toInt() and 0xff) != method) {
                        steps.add(Step(StepKind.GREETING, false,
                            "server chose method ${resp[1].toInt() and 0xff}, expected $method"))
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
                    val userBytes = authUser.toByteArray(Charsets.UTF_8)
                    val passBytes = authPass.toByteArray(Charsets.UTF_8)
                    val req = ByteArray(3 + userBytes.size + passBytes.size)
                    req[0] = 0x01
                    req[1] = userBytes.size.toByte()
                    System.arraycopy(userBytes, 0, req, 2, userBytes.size)
                    req[2 + userBytes.size] = passBytes.size.toByte()
                    System.arraycopy(passBytes, 0, req, 3 + userBytes.size, passBytes.size)
                    output.write(req)
                    output.flush()
                    try {
                        val resp = ByteArray(2)
                        readFully(input, resp)
                        if (resp[0].toInt() != 0x01 || resp[1].toInt() != 0x00) {
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
                val hostBytes = TEST_HOST.toByteArray(Charsets.US_ASCII)
                val req = ByteArray(4 + 1 + hostBytes.size + 2)
                req[0] = 0x05
                req[1] = 0x01            // CMD = CONNECT
                req[2] = 0x00            // RSV
                req[3] = 0x03            // ATYP = domain
                req[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
                req[5 + hostBytes.size] = ((TEST_PORT shr 8) and 0xff).toByte()
                req[6 + hostBytes.size] = (TEST_PORT and 0xff).toByte()
                output.write(req)
                output.flush()
                try {
                    val header = ByteArray(4)
                    readFully(input, header)
                    if (header[0].toInt() != 0x05) {
                        steps.add(Step(StepKind.CONNECT, false, "bad VER ${header[0].toInt() and 0xff}"))
                        return@withContext Result(steps, false)
                    }
                    val rep = header[1].toInt() and 0xff
                    if (rep != 0x00) {
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
