package com.tokyoxpa3.androidproxy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EgressAddress] 的迴歸測試。
 *
 * 這裡錯一個分類就會讓出口偵測整條失效：把 IPv6 link-local／ULA 當成公網，
 * 等於拿一個不會變的位址當出口，完全失去偵測能力。
 *
 * IPv4 沒有對應的分類測試 —— 本機 IPv4 位址（含 CGNAT）一律不可信，
 * 生產程式碼不看它，出口位址只由 STUN／echo 回報。
 */
class EgressAddressTest {

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
}
