package com.tokyoxpa3.androidproxy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EgressAddress] 的迴歸測試。
 *
 * 這裡錯一個分類就會讓出口偵測整條失效：
 *  - 把 CGNAT 位址當成公網 → 探測永遠回傳同一個假值，真正的出口變更看不到。
 *  - 把 IPv6 link-local／ULA 當成公網 → 拿一個不會變的位址當出口，
 *    等於完全失去偵測能力。
 */
class EgressAddressTest {

    @Test
    fun cgnatAddressIsNotPublic() {
        // 實測值：rodin 在台灣大車隊行動網路上的 ccmni3 位址
        assertFalse(EgressAddress.isPublicIpv4("100.96.34.49"))
        assertFalse(EgressAddress.isPublicIpv4("100.64.0.1"))
        assertFalse(EgressAddress.isPublicIpv4("100.127.255.254"))
    }

    @Test
    fun privateRangesAreNotPublic() {
        assertFalse(EgressAddress.isPublicIpv4("10.0.0.1"))
        assertFalse(EgressAddress.isPublicIpv4("172.16.5.4"))
        assertFalse(EgressAddress.isPublicIpv4("172.31.255.255"))
        assertFalse(EgressAddress.isPublicIpv4("192.168.1.1"))
        assertFalse(EgressAddress.isPublicIpv4("169.254.1.1"))
        assertFalse(EgressAddress.isPublicIpv4("127.0.0.1"))
        assertFalse(EgressAddress.isPublicIpv4("0.0.0.0"))
    }

    @Test
    fun publicRangesArePublic() {
        assertTrue(EgressAddress.isPublicIpv4("8.8.8.8"))
        assertTrue(EgressAddress.isPublicIpv4("49.215.1.1"))
        assertTrue(EgressAddress.isPublicIpv4("172.32.0.1"))   // 172.16/12 之外
        assertTrue(EgressAddress.isPublicIpv4("100.128.0.1"))  // 100.64/10 之外
        assertTrue(EgressAddress.isPublicIpv4("1.1.1.1"))
    }

    @Test
    fun malformedIpv4IsNotPublic() {
        assertFalse(EgressAddress.isPublicIpv4(""))
        assertFalse(EgressAddress.isPublicIpv4("8.8.8"))
        assertFalse(EgressAddress.isPublicIpv4("8.8.8.8.8"))
        assertFalse(EgressAddress.isPublicIpv4("256.1.1.1"))
        assertFalse(EgressAddress.isPublicIpv4("8.8.8.a"))
        assertFalse(EgressAddress.isPublicIpv4("8.8.8.+1"))
    }

    @Test
    fun globalIpv6IsGlobal() {
        // 實測值：rodin 的 ccmni2 全域位址
        assertTrue(EgressAddress.isGlobalIpv6("2402:7500:105:ea6f::c279:c005"))
        assertTrue(EgressAddress.isGlobalIpv6("2001:4860:4860::8888"))
    }

    @Test
    fun nonGlobalIpv6IsRejected() {
        assertFalse(EgressAddress.isGlobalIpv6("fe80::1"))            // link-local
        assertFalse(EgressAddress.isGlobalIpv6("fe80::1%wlan0"))      // 帶 zone id
        assertFalse(EgressAddress.isGlobalIpv6("fc00::1"))            // ULA
        assertFalse(EgressAddress.isGlobalIpv6("fd12:3456::1"))       // ULA
        assertFalse(EgressAddress.isGlobalIpv6("::1"))                // loopback
        assertFalse(EgressAddress.isGlobalIpv6("::"))                 // unspecified
        assertFalse(EgressAddress.isGlobalIpv6("::ffff:192.168.1.1")) // IPv4-mapped
        assertFalse(EgressAddress.isGlobalIpv6("2001:db8::1"))        // 文件用
        assertFalse(EgressAddress.isGlobalIpv6("ff02::1"))            // multicast
        assertFalse(EgressAddress.isGlobalIpv6("8.8.8.8"))            // 不是 IPv6
    }

    @Test
    fun pickGlobalIpv6SkipsLinkLocal() {
        val picked = EgressAddress.pickGlobalIpv6(
            listOf("fe80::c279:c005", "2402:7500:105:ea6f::c279:c005")
        )
        assertEquals("2402:7500:105:ea6f::c279:c005", picked)
    }

    @Test
    fun pickGlobalIpv6ReturnsNullWhenOnlyLinkLocal() {
        assertNull(EgressAddress.pickGlobalIpv6(listOf("fe80::1", "::1")))
        assertNull(EgressAddress.pickGlobalIpv6(emptyList()))
    }

    @Test
    fun pickPublicIpv4SkipsCgnat() {
        assertNull(EgressAddress.pickPublicIpv4(listOf("100.96.34.49", "10.0.0.2")))
        assertEquals("49.215.1.1", EgressAddress.pickPublicIpv4(listOf("10.0.0.2", "49.215.1.1")))
    }
}
