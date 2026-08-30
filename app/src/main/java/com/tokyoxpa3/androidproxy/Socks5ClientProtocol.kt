package com.tokyoxpa3.androidproxy

/**
 * SelfTest 用的 SOCKS5 客戶端協定編碼與回覆驗證（純邏輯，零 Android 依賴，可單元測試）。
 *
 * [SelfTest] 原本手刻了 greeting（方法協商）、RFC1929 帳密、CONNECT 三段的位元組
 * 編碼與回覆判斷，這類 byte-offset 邏輯最易出錯（例如帳密的 ULEN/PLEN 偏移、CONNECT
 * 的 port 高低位元組）卻完全沒有測試。這裡把「組封包」與「驗回覆」收斂成純函式，
 * [SelfTest] 只保留 socket I/O 接線。
 */
object Socks5ClientProtocol {
    const val VER = 0x05
    const val METHOD_NO_AUTH = 0x00
    const val METHOD_USER_PASS = 0x02
    const val CMD_CONNECT = 0x01
    const val ATYP_IPV4 = 0x01
    const val ATYP_DOMAIN = 0x03
    const val ATYP_IPV6 = 0x04
    const val REP_SUCCESS = 0x00

    /** greeting（方法協商）請求：VER + nmethods(1) + method。 */
    fun greetingRequest(authEnabled: Boolean): ByteArray = byteArrayOf(
        VER.toByte(),
        0x01,
        (if (authEnabled) METHOD_USER_PASS else METHOD_NO_AUTH).toByte(),
    )

    /** 驗證 greeting 回覆（2 bytes）：VER 正確且選定的 method 等於預期。 */
    fun validMethodReply(resp: ByteArray, expectedMethod: Int): Boolean =
        resp.size >= 2 && (resp[0].toInt() and 0xff) == VER &&
            (resp[1].toInt() and 0xff) == expectedMethod

    /** RFC1929 帳密請求：VER(0x01) + ULEN + user + PLEN + pass。 */
    fun authRequest(user: String, pass: String): ByteArray {
        val u = user.toByteArray(Charsets.UTF_8)
        val p = pass.toByteArray(Charsets.UTF_8)
        val req = ByteArray(3 + u.size + p.size)
        req[0] = 0x01
        req[1] = u.size.toByte()
        System.arraycopy(u, 0, req, 2, u.size)
        req[2 + u.size] = p.size.toByte()
        System.arraycopy(p, 0, req, 3 + u.size, p.size)
        return req
    }

    /** 驗證 RFC1929 帳密回覆（2 bytes）：0x01 0x00 = 成功。 */
    fun validAuthReply(resp: ByteArray): Boolean =
        resp.size >= 2 && (resp[0].toInt() and 0xff) == 0x01 && (resp[1].toInt() and 0xff) == 0x00

    /** CONNECT 請求（ATYP=domain）：VER + CMD + RSV(0) + ATYP + len + host + port(2)。 */
    fun connectRequest(host: String, port: Int): ByteArray {
        val h = host.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(4 + 1 + h.size + 2)
        req[0] = VER.toByte()
        req[1] = CMD_CONNECT.toByte()
        req[2] = 0x00
        req[3] = ATYP_DOMAIN.toByte()
        req[4] = h.size.toByte()
        System.arraycopy(h, 0, req, 5, h.size)
        req[5 + h.size] = ((port shr 8) and 0xff).toByte()
        req[6 + h.size] = (port and 0xff).toByte()
        return req
    }

    /** 驗證 CONNECT 回覆表頭（前 4 bytes）：VER 正確回傳 REP code，否則回傳 -1。 */
    fun connectReplyRep(header: ByteArray): Int =
        if (header.size >= 4 && (header[0].toInt() and 0xff) == VER)
            header[1].toInt() and 0xff
        else -1
}
