package com.tokyoxpa3.androidproxy.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [LinkSignature] 的迴歸測試。
 *
 * 這個簽章唯一的工作是「只在位址真的變了才說變了」——誤判成變更會切斷正在
 * 連線中的遊戲／語音，漏判則讓舊出口連線卡到 300 秒逾時。因此測試重點是
 * 兩個方向都要守住。
 */
class LinkSignatureTest {

    @Test
    fun sameAddressesDifferentOrderProduceSameSignature() {
        // 系統回報 linkAddresses 的順序不保證穩定，順序不同不算變更。
        val a = LinkSignature.from(listOf("10.20.30.40", "fe80::1"), "rmnet_data0")
        val b = LinkSignature.from(listOf("fe80::1", "10.20.30.40"), "rmnet_data0")
        assertEquals(a, b)
    }

    @Test
    fun addressChangeProducesDifferentSignature() {
        val before = LinkSignature.from(listOf("10.20.30.40"), "rmnet_data0")
        val after = LinkSignature.from(listOf("10.20.30.41"), "rmnet_data0")
        assertNotEquals(before, after)
    }

    @Test
    fun interfaceChangeProducesDifferentSignature() {
        val before = LinkSignature.from(listOf("10.20.30.40"), "rmnet_data0")
        val after = LinkSignature.from(listOf("10.20.30.40"), "rmnet_data1")
        assertNotEquals(before, after)
    }

    @Test
    fun blankEntriesAndPaddingAreIgnored() {
        val clean = LinkSignature.from(listOf("10.20.30.40"), "rmnet_data0")
        val padded = LinkSignature.from(listOf("  10.20.30.40  ", "", "   "), "rmnet_data0")
        assertEquals(clean, padded)
    }

    @Test
    fun nullInterfaceNameIsStable() {
        val a = LinkSignature.from(listOf("10.20.30.40"), null)
        val b = LinkSignature.from(listOf("10.20.30.40"), null)
        assertEquals(a, b)
        assertNotEquals(a, LinkSignature.from(listOf("10.20.30.40"), "rmnet_data0"))
    }

    @Test
    fun emptyAddressListIsStable() {
        val a = LinkSignature.from(emptyList(), "rmnet_data0")
        val b = LinkSignature.from(emptyList(), "rmnet_data0")
        assertEquals(a, b)
        // 從「沒有位址」變成「有位址」必須被視為變更，否則第一次拿到 IP 時不會清理。
        assertNotEquals(a, LinkSignature.from(listOf("10.20.30.40"), "rmnet_data0"))
    }
}
