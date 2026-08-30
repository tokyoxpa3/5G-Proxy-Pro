package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5ClientProtocolTest {

    // ===== greeting =====
    @Test
    fun greetingWithoutAuthOffersNoAuth() {
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x00),
            Socks5ClientProtocol.greetingRequest(authEnabled = false)
        )
    }

    @Test
    fun greetingWithAuthOffersUserPass() {
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x02),
            Socks5ClientProtocol.greetingRequest(authEnabled = true)
        )
    }

    @Test
    fun validMethodReplyAcceptsMatchingMethod() {
        assertTrue(Socks5ClientProtocol.validMethodReply(byteArrayOf(0x05, 0x00), 0x00))
        assertTrue(Socks5ClientProtocol.validMethodReply(byteArrayOf(0x05, 0x02), 0x02))
    }

    @Test
    fun validMethodReplyRejectsMismatchBadVerOrShort() {
        assertFalse(Socks5ClientProtocol.validMethodReply(byteArrayOf(0x05, 0x02), 0x00)) // method 不符
        assertFalse(Socks5ClientProtocol.validMethodReply(byteArrayOf(0x04, 0x00), 0x00)) // VER 錯誤
        assertFalse(Socks5ClientProtocol.validMethodReply(byteArrayOf(0x05), 0x00))       // 太短
        assertFalse(Socks5ClientProtocol.validMethodReply(ByteArray(0), 0x00))
    }

    // ===== RFC1929 auth =====
    @Test
    fun authRequestEncodesUserAndPass() {
        assertArrayEquals(
            byteArrayOf(0x01, 5, 'a'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(),
                6, 's'.code.toByte(), 'e'.code.toByte(), 'c'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte()),
            Socks5ClientProtocol.authRequest("alice", "secret")
        )
    }

    @Test
    fun authRequestAllowsEmptyPass() {
        // 設定的密碼可為空時，客戶端仍可送 PLEN=0
        assertArrayEquals(
            byteArrayOf(0x01, 5, 'a'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte(), 0),
            Socks5ClientProtocol.authRequest("alice", "")
        )
    }

    @Test
    fun validAuthReplyAcceptsOnlySuccess() {
        assertTrue(Socks5ClientProtocol.validAuthReply(byteArrayOf(0x01, 0x00)))
        assertFalse(Socks5ClientProtocol.validAuthReply(byteArrayOf(0x01, 0x01))) // 拒絕
        assertFalse(Socks5ClientProtocol.validAuthReply(byteArrayOf(0x01)))       // 太短
    }

    // ===== CONNECT =====
    @Test
    fun connectRequestEncodesDomainTarget() {
        // example.com:80 → VER(0x05) CMD(0x01) RSV(0) ATYP(0x03) LEN(11) "example.com" PORT(0x00,0x50)
        val expect = byteArrayOf(
            0x05, 0x01, 0x00, 0x03, 11,
            'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(),
            'l'.code.toByte(), 'e'.code.toByte(), '.'.code.toByte(), 'c'.code.toByte(), 'o'.code.toByte(),
            'm'.code.toByte(),
            0x00, 0x50
        )
        assertArrayEquals(expect, Socks5ClientProtocol.connectRequest("example.com", 80))
    }

    @Test
    fun connectRequestEncodesPortAcrossByteBoundary() {
        // port 443 = 0x01BB，驗證高低位元組正確
        val req = Socks5ClientProtocol.connectRequest("h", 443)
        assertEquals(0x01, req[req.size - 2].toInt() and 0xff)
        assertEquals(0xBB, req[req.size - 1].toInt() and 0xff)
    }

    @Test
    fun connectReplyRepReturnsRepCodeOnValidVer() {
        assertEquals(0x00, Socks5ClientProtocol.connectReplyRep(byteArrayOf(0x05, 0x00, 0x00, 0x01)))
        assertEquals(0x04, Socks5ClientProtocol.connectReplyRep(byteArrayOf(0x05, 0x04, 0x00, 0x01))) // host unreachable
    }

    @Test
    fun connectReplyRepReturnsMinusOneOnBadVerOrShort() {
        assertEquals(-1, Socks5ClientProtocol.connectReplyRep(byteArrayOf(0x04, 0x00, 0x00, 0x01)))
        assertEquals(-1, Socks5ClientProtocol.connectReplyRep(byteArrayOf(0x05, 0x00)))
    }
}
