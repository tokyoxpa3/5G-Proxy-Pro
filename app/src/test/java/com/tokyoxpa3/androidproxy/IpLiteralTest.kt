package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address

class IpLiteralTest {
    @Test
    fun validIpv4() {
        assertTrue(IpLiteral.parse("1.2.3.4") is Inet4Address)
    }

    @Test
    fun validIpv6() {
        assertTrue(IpLiteral.parse("::1") is Inet6Address)
        assertTrue(IpLiteral.parse("2001:db8::1") is Inet6Address)
    }

    @Test
    fun hostnameWithoutColonReturnsNull() {
        // 一般網域：無冒號、非 IPv4 形式 → null（交給正常 DNS）
        assertNull(IpLiteral.parse("example.com"))
    }

    @Test
    fun outOfRangeIpv4ReturnsNull() {
        // "999.999.1.1" 符合 regex，但 InetAddress 剖析失敗 → null
        assertNull(IpLiteral.parse("999.999.1.1"))
    }

    @Test
    fun hostnameWithColonReturnsNull() {
        // 含冒號會被當 IPv6 字面值剖析，但 "foo:bar" 不是合法 IPv6 → null
        assertNull(IpLiteral.parse("foo:bar"))
    }

    @Test
    fun emptyOrBlankReturnsNull() {
        assertNull(IpLiteral.parse(""))
        assertNull(IpLiteral.parse("   "))
    }
}
