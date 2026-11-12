package com.tokyoxpa3.androidproxy.network

import java.net.InetAddress

/**
 * 最小 STUN（RFC 5389）Binding 訊息實作。
 *
 * 只做一件事：問伺服器「你看到的來源位址是什麼」。
 *
 * 為什麼需要：HTTPS echo 端點要 DNS + TCP + TLS 三段握手，一次幾百 ms 起跳，
 * 失敗時還要吃滿 timeout；STUN 只要一個 UDP 往返、不經 TLS，是「只想知道
 * 有沒有變」時最便宜的手段。實作抽成純函式是為了能離線鎖住位元組解析 ——
 * XOR 編碼與 4-byte 對齊很容易寫錯，而且錯了只會表現成「偶爾解析失敗」。
 */
object StunMessage {

    const val BINDING_REQUEST = 0x0001
    const val BINDING_SUCCESS = 0x0101
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    private const val MAGIC = 0x2112A442

    /** 20 bytes 的 Binding Request；[transactionId] 必須是 12 bytes 隨機值。 */
    fun buildBindingRequest(transactionId: ByteArray): ByteArray {
        require(transactionId.size == 12) { "transaction id must be 12 bytes" }
        val out = ByteArray(20)
        out[0] = 0x00
        out[1] = 0x01                                     // Binding Request
        out[2] = 0x00
        out[3] = 0x00                                     // 無屬性
        out[4] = 0x21
        out[5] = 0x12
        out[6] = 0xA4.toByte()
        out[7] = 0x42                                     // magic cookie
        System.arraycopy(transactionId, 0, out, 8, 12)
        return out
    }

    /**
     * 從 Binding Success 回應取出映射位址。
     *
     * 優先 `XOR-MAPPED-ADDRESS`（RFC 5389），退回舊式 `MAPPED-ADDRESS`（RFC 3489）。
     * 會驗證 magic cookie 與 transaction id：UDP 沒有連線概念，若不驗證，
     * 遲到的舊回應或同連接埠的偽造封包都可能被當成目前出口位址。
     */
    fun parseMappedAddress(response: ByteArray, transactionId: ByteArray): String? {
        if (response.size < 20) return null
        if (u16(response, 0) != BINDING_SUCCESS) return null
        if (u32(response, 4) != MAGIC) return null
        for (i in 0 until 12) {
            if (response[8 + i] != transactionId[i]) return null
        }

        val length = u16(response, 2)
        val end = minOf(response.size, 20 + length)
        var offset = 20
        var xor: String? = null
        var mapped: String? = null

        while (offset + 4 <= end) {
            val type = u16(response, offset)
            val len = u16(response, offset + 2)
            val valueStart = offset + 4
            if (valueStart + len > end) break
            when (type) {
                ATTR_XOR_MAPPED_ADDRESS ->
                    xor = decodeAddress(response, valueStart, len, transactionId, xored = true)
                ATTR_MAPPED_ADDRESS ->
                    mapped = decodeAddress(response, valueStart, len, transactionId, xored = false)
            }
            // 屬性長度是 4 的倍數對齊（padding 不計入 len）
            offset = valueStart + ((len + 3) / 4) * 4
        }
        return xor ?: mapped
    }

    private fun decodeAddress(
        buf: ByteArray,
        start: Int,
        len: Int,
        transactionId: ByteArray,
        xored: Boolean
    ): String? {
        if (len < 4) return null
        return when (buf[start + 1].toInt() and 0xFF) {
            0x01 -> {
                if (len < 8) return null
                val raw = ByteArray(4) { buf[start + 4 + it] }
                if (xored) xorMask(raw, 0, magicBytes())
                ipString(raw)
            }
            0x02 -> {
                if (len < 20) return null
                val raw = ByteArray(16) { buf[start + 4 + it] }
                if (xored) xorMask(raw, 0, magicBytes() + transactionId)
                ipString(raw)
            }
            else -> null
        }
    }

    private fun magicBytes(): ByteArray =
        byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)

    private fun xorMask(target: ByteArray, offset: Int, mask: ByteArray) {
        for (i in mask.indices) {
            if (offset + i >= target.size) break
            target[offset + i] = (target[offset + i].toInt() xor mask[i].toInt()).toByte()
        }
    }

    private fun ipString(raw: ByteArray): String? = try {
        // InetAddress 只做位元組轉字串，不觸發 DNS
        InetAddress.getByAddress(raw).hostAddress
    } catch (e: Exception) {
        null
    }

    private fun u16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun u32(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or
            ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or
            (buf[at + 3].toInt() and 0xFF)
}
