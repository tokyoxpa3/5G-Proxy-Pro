package com.tokyoxpa3.androidproxy.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StunMessage] 的位元組級迴歸測試。
 *
 * STUN 的 XOR 編碼與 4-byte 屬性對齊一旦寫錯，症狀是「偶爾解析失敗」——
 * 看起來像網路問題，實際上永遠查不到。所以這裡用手算好的位元組鎖住行為。
 */
class StunMessageTest {

    private val tid = ByteArray(12) { it.toByte() }   // 00 01 02 ... 0B

    @Test
    fun bindingRequestHasCorrectHeader() {
        val request = StunMessage.buildBindingRequest(tid)
        assertEquals(20, request.size)
        assertEquals(0x00, request[0].toInt() and 0xFF)
        assertEquals(0x01, request[1].toInt() and 0xFF)      // Binding Request
        assertEquals(0x00, request[2].toInt() and 0xFF)      // length 0
        assertEquals(0x00, request[3].toInt() and 0xFF)
        assertEquals(0x21, request[4].toInt() and 0xFF)      // magic cookie
        assertEquals(0x12, request[5].toInt() and 0xFF)
        assertEquals(0xA4, request[6].toInt() and 0xFF)
        assertEquals(0x42, request[7].toInt() and 0xFF)
        assertArrayEquals(tid, request.copyOfRange(8, 20))
    }

    @Test
    fun parsesXorMappedIpv4() {
        // 203.0.113.5:54321，XOR 後為 EA 12 D5 47 / F5 23
        val response = success(xorMappedIpv4(byteArrayOf(203.toByte(), 0, 113, 5), 54321))
        assertEquals("203.0.113.5", StunMessage.parseMappedAddress(response, tid))
    }

    @Test
    fun parsesLegacyMappedIpv4() {
        val response = success(mappedIpv4(byteArrayOf(49, 215.toByte(), 1, 1), 1080))
        assertEquals("49.215.1.1", StunMessage.parseMappedAddress(response, tid))
    }

    @Test
    fun skipsUnknownAttributeWithPadding() {
        // SOFTWARE 長度 5 → 佔 4+5 但要對齊到 8，解析器必須正確跳過 padding
        val software = ByteArray(4 + 8)
        software[0] = 0x80.toByte()
        software[1] = 0x22
        software[2] = 0x00
        software[3] = 0x05
        "hello".toByteArray().copyInto(software, 4)

        val response = success(software, xorMappedIpv4(byteArrayOf(8, 8, 8, 8), 1234))
        assertEquals("8.8.8.8", StunMessage.parseMappedAddress(response, tid))
    }

    @Test
    fun rejectsMismatchedTransactionId() {
        val response = success(xorMappedIpv4(byteArrayOf(8, 8, 8, 8), 1234))
        val otherTid = ByteArray(12) { (it + 1).toByte() }
        // UDP 沒有連線概念，遲到的舊回應必須被丟棄
        assertNull(StunMessage.parseMappedAddress(response, otherTid))
    }

    @Test
    fun rejectsNonSuccessType() {
        val response = success(xorMappedIpv4(byteArrayOf(8, 8, 8, 8), 1234))
        response[1] = 0x11.toByte()   // Binding Error
        assertNull(StunMessage.parseMappedAddress(response, tid))
    }

    @Test
    fun rejectsTruncatedResponse() {
        assertNull(StunMessage.parseMappedAddress(ByteArray(10), tid))
        val response = success(xorMappedIpv4(byteArrayOf(8, 8, 8, 8), 1234))
        assertNull(StunMessage.parseMappedAddress(response.copyOf(24), tid))
    }

    @Test
    fun rejectsResponseWithoutAddressAttribute() {
        val empty = success(ByteArray(0))
        assertNull(StunMessage.parseMappedAddress(empty, tid))
    }

    private fun success(vararg attrs: ByteArray): ByteArray {
        val body = attrs.fold(ByteArray(0)) { acc, a -> acc + a }
        val out = ByteArray(20 + body.size)
        out[0] = 0x01
        out[1] = 0x01                                        // Binding Success
        out[2] = ((body.size shr 8) and 0xFF).toByte()
        out[3] = (body.size and 0xFF).toByte()
        out[4] = 0x21
        out[5] = 0x12
        out[6] = 0xA4.toByte()
        out[7] = 0x42
        System.arraycopy(tid, 0, out, 8, 12)
        System.arraycopy(body, 0, out, 20, body.size)
        return out
    }

    private fun xorMappedIpv4(ip: ByteArray, port: Int): ByteArray {
        val attr = ByteArray(12)
        attr[0] = 0x00
        attr[1] = 0x20                                        // XOR-MAPPED-ADDRESS
        attr[2] = 0x00
        attr[3] = 0x08
        attr[4] = 0x00                                        // reserved
        attr[5] = 0x01                                        // family IPv4
        val xPort = port xor 0x2112
        attr[6] = ((xPort shr 8) and 0xFF).toByte()
        attr[7] = (xPort and 0xFF).toByte()
        attr[8] = (ip[0].toInt() xor 0x21).toByte()
        attr[9] = (ip[1].toInt() xor 0x12).toByte()
        attr[10] = (ip[2].toInt() xor 0xA4).toByte()
        attr[11] = (ip[3].toInt() xor 0x42).toByte()
        return attr
    }

    private fun mappedIpv4(ip: ByteArray, port: Int): ByteArray {
        val attr = ByteArray(12)
        attr[0] = 0x00
        attr[1] = 0x01                                        // MAPPED-ADDRESS
        attr[2] = 0x00
        attr[3] = 0x08
        attr[4] = 0x00
        attr[5] = 0x01
        attr[6] = ((port shr 8) and 0xFF).toByte()
        attr[7] = (port and 0xFF).toByte()
        System.arraycopy(ip, 0, attr, 8, 4)
        return attr
    }
}
